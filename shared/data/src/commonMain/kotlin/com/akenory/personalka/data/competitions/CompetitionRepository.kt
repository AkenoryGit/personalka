package com.akenory.personalka.data.competitions

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.CompetitionEntity
import com.akenory.personalka.data.db.entity.CompetitionEntryEntity
import com.akenory.personalka.data.db.entity.CompetitionStartEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.results.toItem
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.competition.Competition
import com.akenory.personalka.domain.competition.CompetitionDraft
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.CompetitionStart
import com.akenory.personalka.domain.competition.Participant
import com.akenory.personalka.domain.format.CompetitionFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Соревнование с участниками (по фамилии) и их стартами — для списка и карточки (5.21). */
data class CompetitionRow(val competition: Competition, val participants: List<Participant>)

/** Что вернёт «Отменить»: записи, удалённые в момент [at], оживают. */
class CompetitionUndo internal constructor(internal val id: String, internal val at: Long)

/**
 * Соревнования (ТЗ 5.21, шаг 5.38): чтение для экранов, форма, подбор участников, отметки «сдал», старты и итоги.
 * Результат старта пишется и в результаты клиента (5.11): правка времени там видна и здесь, удаление там стирает
 * его у старта. Удаления — мягкие, «Отменить» оживляет записи, удалённые тем же действием.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class CompetitionRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val dao = db.competitionDao()
    private val trainings = db.trainingDao()

    /** Все соревнования с участниками. Клиент, стёртый полностью, из участников пропадает. */
    val rows: Flow<List<CompetitionRow>> = combine(
        dao.observeCompetitions(),
        dao.observeEntries(),
        dao.observeStarts(),
        db.clientDao().observeAllClients(),
        dao.observeResults(),
    ) { competitions, entries, starts, clients, results ->
        val clientById = clients.associateBy { it.id }
        val resultById = results.associateBy { it.id }
        val startsByEntry = starts.groupBy { it.entryId }
        val entriesByCompetition = entries.groupBy { it.competitionId }
        competitions.map { c ->
            val competition = c.toModel()
            val itemIds = competition.items.map { it.id }.toSet()
            val participants = entriesByCompetition[c.id].orEmpty().mapNotNull { e ->
                val client = clientById[e.clientId] ?: return@mapNotNull null
                Participant(
                    id = e.id,
                    clientId = e.clientId,
                    name = ClientName(client.lastName, client.firstName, client.middleName),
                    birthYear = client.birthDate?.year,
                    handedIn = CompetitionRules.decodeHanded(e.handedIn).intersect(itemIds),
                    starts = startsByEntry[e.id].orEmpty().map { it.toModel(c.poolM, resultById) },
                )
            }.sortedWith(CompetitionRules.ORDER)
            CompetitionRow(competition, participants)
        }
    }

    /** Одно соревнование; удалено — `null`. */
    fun row(id: String): Flow<CompetitionRow?> = rows.map { list -> list.firstOrNull { it.competition.id == id } }

    /** Результаты клиентов по id клиента — для заявочного времени и итогов. */
    val results: Flow<Map<String, List<ResultItem>>> =
        dao.observeResults().map { list -> list.groupBy({ it.clientId }, { it.toItem() }) }

    /** Новое соревнование; возвращает его id. */
    suspend fun create(draft: CompetitionDraft): String {
        val now = clock()
        val entity = CompetitionEntity(
            id = Ids.newId(now),
            sync = SyncFields.new(coachId(), now),
            name = draft.name,
            date = draft.date,
            poolM = draft.poolM,
            place = draft.place,
            rules = CompetitionRules.encodeRules(draft.rules),
            items = CompetitionRules.encodeItems(draft.items),
        )
        dao.upsertCompetition(entity)
        return entity.id
    }

    /**
     * Правка из формы. Отметки по убранным пунктам стираются; результаты, записанные из стартов, получают новые
     * название, дату и бассейн соревнования.
     */
    suspend fun update(id: String, draft: CompetitionDraft) {
        val now = clock()
        db.withWriteTransaction {
            val before = dao.competition(id) ?: return@withWriteTransaction
            dao.upsertCompetition(
                before.copy(
                    name = draft.name,
                    date = draft.date,
                    poolM = draft.poolM,
                    place = draft.place,
                    rules = CompetitionRules.encodeRules(draft.rules),
                    items = CompetitionRules.encodeItems(draft.items),
                    sync = before.sync.touched(now),
                ),
            )
            dao.entryRowsOf(id).filter { it.sync.deletedAt == null }.forEach { e ->
                val kept = CompetitionRules.keepHanded(CompetitionRules.decodeHanded(e.handedIn), draft.items)
                val text = CompetitionRules.encodeHanded(kept)
                if (text != e.handedIn) dao.upsertEntry(e.copy(handedIn = text, sync = e.sync.touched(now)))
            }
            dao.startRowsOfCompetition(id).filter { it.sync.deletedAt == null && it.resultId != null }.forEach { s ->
                val result = trainings.result(s.resultId!!)?.takeIf { it.sync.deletedAt == null } ?: return@forEach
                if (result.date != draft.date || result.poolM != draft.poolM || result.eventName != draft.name) {
                    trainings.upsertResult(result.copy(date = draft.date, poolM = draft.poolM, eventName = draft.name, sync = result.sync.touched(now)))
                }
            }
        }
    }

    /** «Удалить соревнование»: с участниками и стартами; результаты в карточках остаются. */
    suspend fun delete(id: String): CompetitionUndo? {
        val now = clock()
        var done = false
        db.withWriteTransaction {
            val c = dao.competition(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            dao.upsertCompetition(c.copy(sync = c.sync.deleted(now)))
            dao.entryRowsOf(id).filter { it.sync.deletedAt == null }.forEach { dao.upsertEntry(it.copy(sync = it.sync.deleted(now))) }
            dao.startRowsOfCompetition(id).filter { it.sync.deletedAt == null }.forEach { dao.upsertStart(it.copy(sync = it.sync.deleted(now))) }
            done = true
        }
        return if (done) CompetitionUndo(id, now) else null
    }

    /** «Отменить» после удаления соревнования. */
    suspend fun undoDelete(undo: CompetitionUndo) {
        val now = clock()
        db.withWriteTransaction {
            dao.competition(undo.id)?.takeIf { it.sync.deletedAt == undo.at }?.let { dao.upsertCompetition(it.copy(sync = it.sync.restored(now))) }
            dao.entryRowsOf(undo.id).filter { it.sync.deletedAt == undo.at }.forEach { dao.upsertEntry(it.copy(sync = it.sync.restored(now))) }
            dao.startRowsOfCompetition(undo.id).filter { it.sync.deletedAt == undo.at }.forEach { dao.upsertStart(it.copy(sync = it.sync.restored(now))) }
        }
    }

    /**
     * «Добавить N» в подборе: новые участники. Уже участвующие пропускаются, убранный раньше — оживает с прежними
     * отметками. Возвращает, сколько добавлено.
     */
    suspend fun add(competitionId: String, clientIds: Collection<String>): Int {
        val now = clock()
        var added = 0
        val coach = coachId()
        db.withWriteTransaction {
            if (dao.competition(competitionId)?.sync?.deletedAt != null) return@withWriteTransaction
            val rows = dao.entryRowsOf(competitionId).groupBy { it.clientId }
            clientIds.distinct().forEach { clientId ->
                val old = rows[clientId].orEmpty()
                when {
                    old.any { it.sync.deletedAt == null } -> return@forEach
                    old.isNotEmpty() -> {
                        val last = old.maxBy { it.sync.updatedAt }
                        dao.upsertEntry(last.copy(sync = last.sync.restored(now)))
                    }
                    else -> dao.upsertEntry(CompetitionEntryEntity(Ids.newId(now), SyncFields.new(coach, now), competitionId, clientId))
                }
                added++
            }
        }
        return added
    }

    /** Флажок «сдал» пункта [itemId] у участника. */
    suspend fun setHanded(entryId: String, itemId: String, handed: Boolean) {
        val e = dao.entry(entryId)?.takeIf { it.sync.deletedAt == null } ?: return
        val set = CompetitionRules.decodeHanded(e.handedIn).let { if (handed) it + itemId else it - itemId }
        val text = CompetitionRules.encodeHanded(set)
        if (text != e.handedIn) dao.upsertEntry(e.copy(handedIn = text, sync = e.sync.touched(clock())))
    }

    /** «Убрать из соревнования»: участник и его старты; результаты в карточке остаются. */
    suspend fun remove(entryId: String): CompetitionUndo? {
        val now = clock()
        var done = false
        db.withWriteTransaction {
            val e = dao.entry(entryId)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            dao.upsertEntry(e.copy(sync = e.sync.deleted(now)))
            dao.startRowsOf(entryId).filter { it.sync.deletedAt == null }.forEach { dao.upsertStart(it.copy(sync = it.sync.deleted(now))) }
            done = true
        }
        return if (done) CompetitionUndo(entryId, now) else null
    }

    /** «Отменить» после «Убрать из соревнования». */
    suspend fun undoRemove(undo: CompetitionUndo) {
        val now = clock()
        db.withWriteTransaction {
            dao.entry(undo.id)?.takeIf { it.sync.deletedAt == undo.at }?.let { dao.upsertEntry(it.copy(sync = it.sync.restored(now))) }
            dao.startRowsOf(undo.id).filter { it.sync.deletedAt == undo.at }.forEach { dao.upsertStart(it.copy(sync = it.sync.restored(now))) }
        }
    }

    /**
     * Старт участника: новый ([startId] = `null`) или правка. [entryCs] — заявочное время, введённое тренером
     * (`null` — лучшее, `CompetitionRules.storedEntry`). Есть [resultCs] — он же результатом в карточке клиента
     * (соревнования, название, день и бассейн соревнования); стёрт — результат из карточки убирается.
     * Возвращает сообщение: «Личный рекорд, − 1,07», «Результат сохранён» или «Старт сохранён».
     */
    suspend fun saveStart(
        entryId: String,
        startId: String?,
        style: SwimStyle,
        distanceM: Int,
        entryCs: Int?,
        resultCs: Int?,
        place: Int?,
    ): String {
        val now = clock()
        val coach = coachId()
        var message = CompetitionFormat.START_SAVED
        db.withWriteTransaction {
            val entry = dao.entry(entryId) ?: return@withWriteTransaction
            val c = dao.competition(entry.competitionId) ?: return@withWriteTransaction
            val before = startId?.let { dao.start(it) }
            val linked = before?.resultId?.let { trainings.result(it) }
            var resultId: String? = null
            if (resultCs != null) {
                val event = SwimEvent(style, distanceM, c.poolM)
                val others = trainings.resultsOf(entry.clientId).filter { it.id != linked?.id }.map { it.toItem() }
                val gain = CompetitionRules.gain(others, event, resultCs, c.date)
                val result = ResultEntity(
                    id = linked?.id ?: Ids.newId(now),
                    sync = linked?.sync?.let { if (it.deletedAt != null) it.restored(now) else it.touched(now) } ?: SyncFields.new(coach, now),
                    clientId = entry.clientId,
                    date = c.date,
                    style = style,
                    distanceM = distanceM,
                    poolM = c.poolM,
                    timeCs = resultCs,
                    kind = ResultKind.COMPETITION,
                    eventName = c.name,
                    note = linked?.note,
                )
                trainings.upsertResult(result)
                resultId = result.id
                message = gain?.let(ResultFormat::record) ?: ResultFormat.SAVED
            } else if (linked != null && linked.sync.deletedAt == null) {
                trainings.upsertResult(linked.copy(sync = linked.sync.deleted(now)))
            }
            dao.upsertStart(
                CompetitionStartEntity(
                    id = before?.id ?: Ids.newId(now),
                    sync = before?.sync?.touched(now) ?: SyncFields.new(coach, now),
                    competitionId = c.id,
                    entryId = entry.id,
                    clientId = entry.clientId,
                    style = style,
                    distanceM = distanceM,
                    entryCs = entryCs,
                    resultCs = resultCs,
                    place = place,
                    resultId = resultId,
                ),
            )
        }
        return message
    }

    /** «Удалить старт»; результат в карточке клиента остаётся. */
    suspend fun deleteStart(startId: String): CompetitionUndo? {
        val s = dao.start(startId)?.takeIf { it.sync.deletedAt == null } ?: return null
        val now = clock()
        dao.upsertStart(s.copy(sync = s.sync.deleted(now)))
        return CompetitionUndo(startId, now)
    }

    /** «Отменить» после «Удалить старт». */
    suspend fun undoDeleteStart(undo: CompetitionUndo) {
        val s = dao.start(undo.id)?.takeIf { it.sync.deletedAt == undo.at } ?: return
        dao.upsertStart(s.copy(sync = s.sync.restored(clock())))
    }

    private suspend fun coachId(): String = db.coachDao().currentCoach()?.id ?: error("Соревнования появляются после первого запуска")
}

private fun CompetitionEntity.toModel() = Competition(
    id = id,
    name = name,
    date = date,
    poolM = poolM,
    place = place,
    rules = CompetitionRules.decodeRules(rules),
    items = CompetitionRules.decodeItems(items),
)

/** Старт для экранов: результат — из карточки клиента, если он записан оттуда и не удалён. */
private fun CompetitionStartEntity.toModel(poolM: Int, results: Map<String, ResultEntity>) = CompetitionStart(
    id = id,
    event = SwimEvent(style, distanceM, poolM),
    entryCs = entryCs,
    resultCs = if (resultId != null) results[resultId]?.timeCs else resultCs,
    place = place,
    resultId = resultId?.takeIf { it in results },
)
