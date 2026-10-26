package com.akenory.personalka.data.reconcile

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientAlias1cEntity
import com.akenory.personalka.data.db.entity.ReconciliationEntity
import com.akenory.personalka.data.db.entity.ReconciliationItemEntity
import com.akenory.personalka.data.db.entity.ServiceMapping1cEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.report1c.AppVisit
import com.akenory.personalka.domain.report1c.Client1c
import com.akenory.personalka.domain.report1c.Diff1c
import com.akenory.personalka.domain.report1c.NameMatch
import com.akenory.personalka.domain.report1c.Names1c
import com.akenory.personalka.domain.report1c.Reconcile1c
import com.akenory.personalka.domain.report1c.Reconcile1cRules
import com.akenory.personalka.domain.report1c.Report1c
import com.akenory.personalka.domain.report1c.Report1cParser
import com.akenory.personalka.domain.report1c.ServiceTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Имя из отчёта, которое тренер подтверждает сам (ТЗ 8.3): похожие или тёзки на выбор, или неизвестный клиент. */
data class Name1cQuestion(val name1c: String, val match: NameMatch)

/**
 * Что спросить до сверки (ТЗ 5.14): [services] — названия услуг без типа и места, [names] — имена без единственного
 * клиента. Пусто и там, и там — можно сверять сразу.
 */
data class Report1cQuestions(val services: List<String>, val names: List<Name1cQuestion>) {
    val none: Boolean get() = services.isEmpty() && names.isEmpty()
}

/**
 * Сверка с отчётом 1С из базы (ТЗ 8, шаг 4.2а): услуги и имена, которые тренер указал один раз, участия тренировок
 * периода и сохранённые сверки с их пунктами. Правила сопоставления — [Reconcile1cRules].
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class Report1cRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val dao = db.report1cDao()
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /** Неизвестные услуги и имена отчёта [report]. */
    suspend fun questions(report: Report1c): Report1cQuestions {
        val services = Reconcile1cRules.unknownServices(report, services().keys)
        val names = Reconcile1cRules.names(report, aliases(), clientList()).values
            .filter { (_, match) -> match !is NameMatch.Found }
            .map { (name, match) -> Name1cQuestion(name, match) }
        return Report1cQuestions(services, names)
    }

    /** «Укажите тип и место для «…»» (8.3): запоминается один раз; повтор того же названия правит запись. */
    suspend fun saveService(name1c: String, typeId: String, locationId: String) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val old = dao.services().lastOrNull { it.name1c == name1c }
        dao.upsertService(
            old?.copy(sync = old.sync.touched(now), typeId = typeId, locationId = locationId)
                ?: ServiceMapping1cEntity(Ids.newId(now), SyncFields.new(coach.id, now), name1c, typeId, locationId),
        )
    }

    /** Подтверждённая пара «имя из 1С — клиент» (8.3); заодно у клиента запоминается «ФИО как в 1С», если его не было. */
    suspend fun saveAlias(name1c: String, clientId: String) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val key = Names1c.key(name1c)
        db.withWriteTransaction {
            val old = dao.aliases().lastOrNull { it.nameKey == key }
            dao.upsertAlias(
                old?.copy(sync = old.sync.touched(now), clientId = clientId)
                    ?: ClientAlias1cEntity(Ids.newId(now), SyncFields.new(coach.id, now), key, clientId),
            )
            clients.client(clientId)?.takeIf { it.name1c == null }?.let { clients.upsertClient(it.copy(name1c = name1c, sync = it.sync.touched(now))) }
        }
    }

    /**
     * Сверить отчёт [report] из файла [fileName] и сохранить итог. Сверка того же периода уже была — обновляется она:
     * решения переходят к тем же пунктам, а пропавшие открытые пункты закрываются как исправленные (8.4). Возвращает id
     * сверки и её итог.
     */
    suspend fun check(report: Report1c, fileName: String): Pair<String, Reconcile1c> {
        val coach = requireNotNull(coaches.currentCoach()) { "Нет тренера" }
        val now = clock()
        val generatedAt = Report1cParser.exportedAt(fileName)
        val until = Reconcile1cRules.until(report, generatedAt, ClubTime.now(now))
        val clientOf = Reconcile1cRules.names(report, aliases(), clientList())
            .mapNotNull { (key, v) -> (v.second as? NameMatch.Found)?.let { key to it.clientId } }.toMap()
        val result = Reconcile1cRules.compare(report, Reconcile1cRules.visitsToCheck(visits(report), report, until), clientOf, services())
        var id = ""
        db.withWriteTransaction {
            val old = dao.reconciliationOf(report.from, report.to)
            val rec = ReconciliationEntity(
                id = old?.id ?: Ids.newId(now),
                sync = old?.sync?.touched(now) ?: SyncFields.new(coach.id, now),
                fileName = fileName,
                periodFrom = report.from,
                periodTo = report.to,
                generatedAt = generatedAt,
                coachName1c = report.coach,
                rowsTotal = report.rows.size,
                matched = result.matched,
                checkedUntil = until,
            )
            id = rec.id
            dao.upsertReconciliation(rec)
            val before = if (old == null) emptyList() else dao.items(old.id)
            val byKey = before.associateBy { it.itemKey }
            val carry = Reconcile1cRules.carry(byKey.mapValues { it.value.resolution }, result.diffs)
            val items = result.diffs.mapIndexed { i, diff ->
                val resolution = carry.resolutions.getValue(diff.key)
                val prev = byKey[diff.key]
                val client = diff.visit?.clientId ?: diff.row?.let { clientOf[Names1c.key(it.client)] }
                val fresh = diff.entity(prev?.id ?: Ids.newId(now + i), prev?.sync?.touched(now) ?: SyncFields.new(coach.id, now), rec.id, client)
                fresh.copy(resolution = resolution, resolvedAt = if (prev?.resolution == resolution) prev.resolvedAt else null)
            }
            val closed = before.filter { it.itemKey in carry.closed }
                .map { it.copy(sync = it.sync.touched(now), resolution = ReconcileResolution.FIXED_IN_APP, resolvedAt = now) }
            dao.upsertItems(items + closed)
        }
        return id to result
    }

    /** История для карточки «Отчёт 1С» (5.14). */
    fun history(): Flow<List<ReconciliationEntity>> = dao.observeReconciliations()

    fun reconciliation(id: String): Flow<ReconciliationEntity?> = dao.observeReconciliation(id)

    fun items(reconciliationId: String): Flow<List<ReconciliationItemEntity>> = dao.observeItems(reconciliationId)

    /** Открытые пункты и «Написал администратору» всех сверок — «Нужно сделать» и напоминание (8.4). */
    fun unresolved(): Flow<List<ReconciliationItemEntity>> =
        dao.observeUnresolved(ReconcileResolution.OPEN, ReconcileResolution.ASKED_ADMIN)

    /** Решение по пункту: «Написал администратору», «Игнорировать», «Исправлено» или снова открыт. */
    suspend fun resolve(itemId: String, resolution: ReconcileResolution) {
        val now = clock()
        val item = dao.item(itemId) ?: return
        if (item.resolution == resolution) return
        val at = if (resolution == ReconcileResolution.OPEN) null else now
        dao.upsertItems(listOf(item.copy(sync = item.sync.touched(now), resolution = resolution, resolvedAt = at)))
    }

    /** Услуги: одно название дважды (два телефона) — последняя изменённая. */
    private suspend fun services(): Map<String, ServiceTarget> =
        dao.services().associate { it.name1c to ServiceTarget(it.typeId, it.locationId) }

    private suspend fun aliases(): Map<String, String> = dao.aliases().associate { it.nameKey to it.clientId }

    private suspend fun clientList(): List<Client1c> = clients.observeAllClients().first().map {
        Client1c(it.id, ClientName(it.lastName, it.firstName, it.middleName), it.status == ClientStatus.ARCHIVED)
    }

    /** Участия тренировок периода отчёта с видом и владельцем тренировки. */
    private suspend fun visits(report: Report1c): List<AppVisit> {
        val from = LocalDateTime(report.from, LocalTime(0, 0))
        val to = LocalDateTime(report.to.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        val byId = trainings.trainingsBetween(from, to).associateBy { it.id }
        val kinds = reference.trainingTypes().associate { it.id to it.kind }
        return trainings.observeParticipationsBetween(from, to).first().mapNotNull { p ->
            val t = byId[p.trainingId] ?: return@mapNotNull null
            AppVisit(t.id, p.clientId, t.start, t.typeId, t.locationId, kinds[t.typeId] ?: TrainingKind.OTHER, t.owner, p.status)
        }
    }
}

/** Пункт сверки для базы: строка 1С колонками, участие — id тренировки; [clientId] — и у строки 1С, если клиент найден. */
private fun Diff1c.entity(id: String, sync: SyncFields, reconciliationId: String, clientId: String?) = ReconciliationItemEntity(
    id = id,
    sync = sync,
    reconciliationId = reconciliationId,
    kind = kind,
    itemKey = key,
    at = row?.at ?: visit!!.start,
    trainingId = visit?.trainingId,
    clientId = clientId,
    statusDiffers = statusDiffers,
    serviceDiffers = serviceDiffers,
    rowAt = row?.at,
    rowServiceName = row?.serviceName,
    rowClient = row?.client,
    rowStatus = row?.status,
)
