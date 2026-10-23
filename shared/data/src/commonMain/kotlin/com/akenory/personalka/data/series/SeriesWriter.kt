package com.akenory.personalka.data.series

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.SeriesClientEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.groups.addParticipant
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.restriction.Placement
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionRules
import com.akenory.personalka.domain.series.Series
import com.akenory.personalka.domain.series.SeriesChanges
import com.akenory.personalka.domain.series.SeriesPattern

// Запись тренировок и серий из формы (5.5), переноса (5.3) и продления (4.4) — с тем, что нужно для «Отменить» (5.20).

/**
 * Как было до изменения: «Отменить» возвращает эти записи и убирает созданные. Хранит только то, что изменение
 * тронуло: тренировку, её состав, серию и её клиентов, отложенный план клиента или группы.
 */
class Undo internal constructor(
    /** Тренировка до изменения; `null` — её создали. */
    internal val trainings: Map<String, TrainingEntity?>,
    /** Действующие участия тренировки до изменения. */
    internal val participations: Map<String, List<ParticipationEntity>>,
    internal val series: Map<String, SeriesEntity?>,
    internal val seriesClients: Map<String, List<SeriesClientEntity>>,
    internal val clients: Map<String, ClientEntity>,
    internal val groups: Map<String, GroupEntity>,
)

/**
 * Записывает изменения и запоминает, какими записи были до первой правки, — для [Undo]. Вызывается внутри
 * одной транзакции; [now] — момент записи, миллисекунды UTC.
 */
internal class Recorder(private val db: PersonalkaDatabase, private val now: Long) {
    private val trainingDao = db.trainingDao()
    private val scheduleDao = db.scheduleDao()
    private val clientDao = db.clientDao()

    private val trainings = LinkedHashMap<String, TrainingEntity?>()
    private val participations = LinkedHashMap<String, List<ParticipationEntity>>()
    private val series = LinkedHashMap<String, SeriesEntity?>()
    private val seriesClients = LinkedHashMap<String, List<SeriesClientEntity>>()
    private val clients = LinkedHashMap<String, ClientEntity>()
    private val groups = LinkedHashMap<String, GroupEntity>()

    /** Записать тренировку: новую или изменённую. */
    suspend fun saveTraining(item: TrainingEntity) {
        rememberTraining(item.id)
        trainingDao.upsertTraining(item)
    }

    /** Клиенты тренировки — в этом порядке; убранные удаляются, новые ожидаются (EXPECTED), отметки остающихся целы. */
    suspend fun setParticipants(training: TrainingEntity, clientIds: List<String>) {
        rememberParticipants(training.id)
        val old = trainingDao.participationsOf(training.id)
        old.filter { it.clientId !in clientIds }.forEach { trainingDao.upsertParticipation(it.copy(sync = it.sync.deleted(now))) }
        // Порядок клиентов — по моменту записи: «Петров / Сидоров» в блоке не меняется местами.
        clientIds.filter { id -> old.none { it.clientId == id } }.forEachIndexed { i, clientId ->
            db.addParticipant(training, clientId, now + i)
        }
    }

    /** Удалить тренировку вместе с участиями (мягко). */
    suspend fun deleteTraining(id: String) {
        val item = trainingDao.training(id)?.takeIf { it.sync.deletedAt == null } ?: return
        rememberTraining(id)
        rememberParticipants(id)
        trainingDao.participationsOf(id).forEach { trainingDao.upsertParticipation(it.copy(sync = it.sync.deleted(now))) }
        trainingDao.upsertTraining(item.copy(sync = item.sync.deleted(now)))
    }

    /** Записать серию для правил целиком: поля и клиентов. */
    suspend fun saveSeries(value: Series, coachId: String) {
        val stored = scheduleDao.series(value.id)?.takeIf { it.sync.deletedAt == null }
        if (value.id !in series) series[value.id] = stored
        val p = value.pattern
        val sync = stored?.sync?.touched(now) ?: SyncFields.new(coachId, now)
        scheduleDao.upsertSeries(
            SeriesEntity(
                id = value.id,
                sync = sync,
                typeId = p.typeId,
                locationId = p.locationId,
                groupId = p.groupId,
                weekdays = p.weekdays,
                startTime = p.startTime,
                durationMin = p.durationMin,
                validFrom = value.validFrom,
                validTo = value.validTo,
                generatedUntil = value.generatedUntil,
                skipDates = value.skipDates.sorted(),
            ),
        )
        if (value.id !in seriesClients) seriesClients[value.id] = scheduleDao.seriesClients(value.id)
        val rows = scheduleDao.seriesClientRowsOfSeries(value.id)
        rows.filter { it.sync.deletedAt == null && it.clientId !in p.clientIds }
            .forEach { scheduleDao.upsertSeriesClient(it.copy(sync = it.sync.deleted(now))) }
        // Порядок клиентов серии — по моменту записи, как у участий.
        p.clientIds.forEachIndexed { i, clientId ->
            val row = rows.firstOrNull { it.clientId == clientId }
            when {
                row == null -> scheduleDao.upsertSeriesClient(SeriesClientEntity(Ids.newId(now), SyncFields.new(coachId, now + i), value.id, clientId))
                row.sync.deletedAt != null -> scheduleDao.upsertSeriesClient(row.copy(sync = row.sync.restored(now)))
            }
        }
    }

    /** Дописать план в «отложенный» клиента (4.3): тренировки серии для него не нашлось. */
    suspend fun addPendingPlan(clientId: String, text: String) {
        val client = clientDao.client(clientId) ?: return
        if (clientId !in clients) clients[clientId] = client
        clientDao.upsertClient(client.copy(pendingPlan = joinPlans(client.pendingPlan, text), sync = client.sync.touched(now)))
    }

    /** То же для группы. */
    suspend fun addGroupPendingPlan(groupId: String, text: String) {
        val group = clientDao.group(groupId) ?: return
        if (groupId !in groups) groups[groupId] = group
        clientDao.upsertGroup(group.copy(pendingPlan = joinPlans(group.pendingPlan, text), sync = group.sync.touched(now)))
    }

    fun undo(): Undo = Undo(trainings.toMap(), participations.toMap(), series.toMap(), seriesClients.toMap(), clients.toMap(), groups.toMap())

    private suspend fun rememberTraining(id: String) {
        if (id !in trainings) trainings[id] = trainingDao.training(id)?.takeIf { it.sync.deletedAt == null }
    }

    private suspend fun rememberParticipants(trainingId: String) {
        if (trainingId !in participations) participations[trainingId] = trainingDao.participationsOf(trainingId)
    }

    private fun joinPlans(old: String?, add: String): String = if (old.isNullOrBlank()) add else "$old\n$add"
}

/**
 * Изменения серий (4.4) в базе: серии с клиентами, новые тренировки с ожидаемыми участниками, изменённые
 * и удалённые; планы, которым не нашлось тренировки, — в «отложенный» клиентов или группы [owner].
 * Флаг «поверх запрета» новых и изменённых пересчитывается по [restrictions] (4.5): окно — до записи.
 *
 * @param owner чья серия менялась: ей отложенные планы; у продления их не бывает.
 * @param isDuty дежурство ли тип: дежурство с запретами не сверяется.
 */
internal suspend fun Recorder.write(
    db: PersonalkaDatabase,
    changes: SeriesChanges,
    coachId: String,
    now: Long,
    restrictions: List<Restriction>,
    owner: SeriesPattern?,
    isDuty: (String) -> Boolean,
) {
    val trainingDao = db.trainingDao()
    val clientDao = db.clientDao()
    changes.series.forEach { saveSeries(it, coachId) }
    changes.created.forEachIndexed { i, new ->
        val group = new.groupId?.let { clientDao.group(it) }
        val entity = TrainingEntity(
            id = Ids.newId(now),
            sync = SyncFields.new(coachId, now + i),
            typeId = new.typeId,
            locationId = new.locationId,
            start = new.start,
            durationMin = new.durationMin,
            seriesId = new.seriesId,
            groupId = new.groupId,
            plan = new.plan,
            owner = group?.owner ?: Owner.SELF,
            ownerColleagueId = group?.ownerColleagueId,
            conductedBy = group?.defaultConductedBy ?: ConductedBy.SELF,
            colleagueId = group?.defaultColleagueId,
            overRestriction = RestrictionRules.flag(Placement(new.start, new.durationMin, new.locationId, isDuty(new.typeId)), restrictions),
        )
        saveTraining(entity)
        setParticipants(entity, new.clientIds)
    }
    changes.updated.forEach { t ->
        val stored = trainingDao.training(t.id) ?: return@forEach
        val group = t.groupId?.takeIf { it != stored.groupId }?.let { clientDao.group(it) }
        val entity = stored.copy(
            seriesId = t.seriesId,
            seriesDetached = t.detached,
            start = t.start,
            durationMin = t.durationMin,
            typeId = t.typeId,
            locationId = t.locationId,
            groupId = t.groupId,
            plan = t.plan,
            owner = group?.owner ?: stored.owner,
            ownerColleagueId = if (group != null) group.ownerColleagueId else stored.ownerColleagueId,
            conductedBy = group?.defaultConductedBy ?: stored.conductedBy,
            colleagueId = if (group != null) group.defaultColleagueId else stored.colleagueId,
            overRestriction = RestrictionRules.flag(Placement(t.start, t.durationMin, t.locationId, isDuty(t.typeId)), restrictions),
            sync = stored.sync.touched(now),
        )
        saveTraining(entity)
        setParticipants(entity, t.participants.map { it.clientId })
    }
    changes.deleted.forEach { deleteTraining(it.id) }
    if (owner == null) return
    changes.pendingPlans.forEach { text ->
        val groupId = owner.groupId
        if (groupId != null) addGroupPendingPlan(groupId, text) else owner.clientIds.forEach { addPendingPlan(it, text) }
    }
}

/**
 * «Отменить» (5.20): записи — как были до изменения, созданные — удалены. Каждая запись получает новую версию,
 * а не старую: для синхронизации это ещё одно изменение, а не откат истории.
 */
internal suspend fun PersonalkaDatabase.restore(undo: Undo, now: Long) {
    val trainingDao = trainingDao()
    val scheduleDao = scheduleDao()
    val clientDao = clientDao()
    undo.trainings.forEach { (id, before) ->
        val current = trainingDao.training(id)
        if (before == null) {
            if (current != null && current.sync.deletedAt == null) trainingDao.upsertTraining(current.copy(sync = current.sync.deleted(now)))
            trainingDao.participationsOf(id).forEach { trainingDao.upsertParticipation(it.copy(sync = it.sync.deleted(now))) }
        } else {
            trainingDao.upsertTraining(before.copy(sync = (current?.sync ?: before.sync).restored(now)))
        }
    }
    undo.participations.forEach { (trainingId, before) ->
        if (undo.trainings[trainingId] == null && trainingId in undo.trainings) return@forEach
        val rows = trainingDao.participationRowsOfTraining(trainingId)
        rows.filter { row -> row.sync.deletedAt == null && before.none { it.id == row.id } }
            .forEach { trainingDao.upsertParticipation(it.copy(sync = it.sync.deleted(now))) }
        before.forEach { old ->
            val row = rows.firstOrNull { it.id == old.id }
            trainingDao.upsertParticipation(old.copy(sync = (row?.sync ?: old.sync).restored(now)))
        }
    }
    undo.series.forEach { (id, before) ->
        val current = scheduleDao.series(id)
        if (before == null) {
            if (current != null && current.sync.deletedAt == null) scheduleDao.upsertSeries(current.copy(sync = current.sync.deleted(now)))
        } else {
            scheduleDao.upsertSeries(before.copy(sync = (current?.sync ?: before.sync).restored(now)))
        }
    }
    undo.seriesClients.forEach { (seriesId, before) ->
        val rows = scheduleDao.seriesClientRowsOfSeries(seriesId)
        rows.filter { row -> row.sync.deletedAt == null && before.none { it.id == row.id } }
            .forEach { scheduleDao.upsertSeriesClient(it.copy(sync = it.sync.deleted(now))) }
        before.forEach { old ->
            val row = rows.firstOrNull { it.id == old.id }
            scheduleDao.upsertSeriesClient(old.copy(sync = (row?.sync ?: old.sync).restored(now)))
        }
    }
    undo.clients.values.forEach { old ->
        val current = clientDao.client(old.id) ?: return@forEach
        clientDao.upsertClient(current.copy(pendingPlan = old.pendingPlan, sync = current.sync.touched(now)))
    }
    undo.groups.values.forEach { old ->
        val current = clientDao.group(old.id) ?: return@forEach
        clientDao.upsertGroup(current.copy(pendingPlan = old.pendingPlan, sync = current.sync.touched(now)))
    }
}
