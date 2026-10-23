package com.akenory.personalka.data.results

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Что вносит форма результата (5.11). Название старта — только у соревнований. */
data class ResultDraft(
    val clientId: String,
    val date: LocalDate,
    val event: SwimEvent,
    val timeCs: Int,
    val kind: ResultKind,
    val eventName: String? = null,
)

/** Что вернёт «Отменить»: запись до действия; `null` — её не было, новый результат удаляется. */
class ResultUndo internal constructor(internal val id: String, internal val before: ResultEntity?)

/** Итог записи: [message] — «Результат сохранён» или «Личный рекорд, − 7,00» — и «Отменить». */
data class ResultSaved(val message: String, val undo: ResultUndo)

/**
 * Результаты клиента (ТЗ 5.11, 5.9): чтение для карточки, запись из формы, удаление; у каждого действия —
 * «Отменить». Рекорды и график считают правила [ResultRules].
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class ResultRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainings = db.trainingDao()

    /** Результаты клиента по дню. */
    fun results(clientId: String): Flow<List<ResultItem>> =
        trainings.observeResultsOf(clientId).map { list -> list.map { it.toItem() } }

    /**
     * Новый результат ([id] = `null`) или правка. Сообщение — «Личный рекорд, − 7,00», если результат быстрее
     * прежнего лучшего на этой дистанции (5.11), иначе «Результат сохранён».
     */
    suspend fun save(draft: ResultDraft, id: String? = null): ResultSaved {
        val now = clock()
        val others = trainings.resultsOf(draft.clientId).map { it.toItem() }
        val gain = ResultRules.recordGain(others, draft.event, draft.timeCs, exceptId = id)
        val before = id?.let { trainings.result(it) }
        val sync = before?.sync?.touched(now) ?: SyncFields.new(coachId(), now)
        val entity = ResultEntity(
            id = before?.id ?: Ids.newId(now),
            sync = sync,
            clientId = draft.clientId,
            date = draft.date,
            style = draft.event.style,
            distanceM = draft.event.distanceM,
            poolM = draft.event.poolM,
            timeCs = draft.timeCs,
            kind = draft.kind,
            eventName = draft.eventName?.trim()?.takeIf { draft.kind == ResultKind.COMPETITION && it.isNotEmpty() },
            note = before?.note,
        )
        trainings.upsertResult(entity)
        return ResultSaved(gain?.let(ResultFormat::record) ?: ResultFormat.SAVED, ResultUndo(entity.id, before))
    }

    /** «Удалить» в форме результата; «Отменить» возвращает его. Нет такого — `null`. */
    suspend fun delete(id: String): ResultUndo? {
        val result = trainings.result(id)?.takeIf { it.sync.deletedAt == null } ?: return null
        trainings.upsertResult(result.copy(sync = result.sync.deleted(clock())))
        return ResultUndo(id, result)
    }

    /** «Отменить»: запись становится такой, какой была до действия; новая — удаляется. */
    suspend fun undo(undo: ResultUndo) {
        val now = clock()
        val current = trainings.result(undo.id) ?: return
        val before = undo.before
        trainings.upsertResult(
            if (before == null) current.copy(sync = current.sync.deleted(now)) else before.copy(sync = current.sync.touched(now).copy(deletedAt = before.sync.deletedAt)),
        )
    }

    private suspend fun coachId(): String = db.coachDao().currentCoach()?.id ?: error("Результаты появляются после первого запуска")
}

internal fun ResultEntity.toItem() = ResultItem(id, date, SwimEvent(style, distanceM, poolM), timeCs, kind, eventName)
