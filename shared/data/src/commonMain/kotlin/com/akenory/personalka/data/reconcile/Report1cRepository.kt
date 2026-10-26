package com.akenory.personalka.data.reconcile

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientAlias1cEntity
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ReconciliationEntity
import com.akenory.personalka.data.db.entity.ReconciliationItemEntity
import com.akenory.personalka.data.db.entity.ServiceMapping1cEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.money.reconcileSpt
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.report1c.AppVisit
import com.akenory.personalka.domain.report1c.Client1c
import com.akenory.personalka.domain.report1c.Diff1c
import com.akenory.personalka.domain.report1c.NameMatch
import com.akenory.personalka.domain.report1c.Names1c
import com.akenory.personalka.domain.report1c.Reconcile1c
import com.akenory.personalka.domain.report1c.Reconcile1cRules
import com.akenory.personalka.domain.report1c.Report1c
import com.akenory.personalka.domain.report1c.Report1cParser
import com.akenory.personalka.domain.report1c.Row1c
import com.akenory.personalka.domain.report1c.Service1c
import com.akenory.personalka.domain.report1c.ServiceTarget
import com.akenory.personalka.domain.report1c.Status1c
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Имя из отчёта, которое тренер подтверждает сам (ТЗ 8.3): похожие или тёзки на выбор. */
data class Name1cQuestion(val name1c: String, val match: NameMatch)

/**
 * Что спросить до сверки (ТЗ 5.14): [services] — названия услуг без типа и места, [names] — имена с похожими клиентами
 * или тёзками на выбор. Совсем неизвестные имена не спрашиваются — их строки идут в «Только в 1С» с «Добавить в
 * приложение». Пусто и там, и там — можно сверять сразу.
 */
data class Report1cQuestions(val services: List<String>, val names: List<Name1cQuestion>) {
    val none: Boolean get() = services.isEmpty() && names.isEmpty()
}

/** Услуга из 1С для списка «Услуги и имена из 1С» (шаг 4.2г): [app] — «ПТ · ББ». */
data class Service1cView(val name1c: String, val typeId: String, val locationId: String, val app: String)

/**
 * Подтверждённая пара «имя из 1С — клиент» (шаг 4.2г): [name1c] — как имя написано в 1С (у клиента запомнено «ФИО как в
 * 1С») или фамилия и имя с заглавных, [client] — «Ганиева Кира» из приложения.
 */
data class Alias1cView(val nameKey: String, val name1c: String, val clientId: String, val client: String)

/** Всё, что тренер указал для 1С; пусто — строки «Услуги и имена из 1С» нет (10.7). */
data class Mappings1c(val services: List<Service1cView>, val names: List<Alias1cView>) {
    val none: Boolean get() = services.isEmpty() && names.isEmpty()
}

/**
 * Пункт сверки для экрана (8.4): [name] — «Пестов Егор» (клиент приложения или фамилия и имя из 1С), [app] — «ПТ · ББ»
 * тренировки приложения, [what] — она же словами для администратора («группа», «ПТ»), [diff] — пункт с нынешним
 * статусом участия, для строки администратору.
 */
data class Report1cItemView(
    val item: ReconciliationItemEntity,
    val name: String,
    val sex: Sex?,
    val app: String?,
    val what: String?,
    val diff: Diff1c,
) {
    val status: ParticipationStatus? get() = diff.visit?.status
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
            .filter { (_, match) -> match is NameMatch.Ask }
            .map { (name, match) -> Name1cQuestion(name, match) }
        return Report1cQuestions(services, names)
    }

    /** «ФИО как в 1С» тренера (3.2): с ним сравнивается шапка отчёта (8.2, п. 5). */
    suspend fun coachName1c(): String? = coaches.currentCoach()?.name1c

    /** Первая сверка запоминает ФИО тренера из шапки отчёта (3.2); уже запомнено — не меняется. */
    suspend fun rememberCoach(name1c: String?) {
        if (name1c.isNullOrBlank()) return
        val coach = coaches.currentCoach() ?: return
        if (coach.name1c == null) coaches.upsertCoach(coach.copy(name1c = name1c, sync = coach.sync.touched(clock())))
    }

    /** Клиенты на выбор в вопросе «Кто это» — в порядке [ids]. */
    suspend fun clients(ids: List<String>): List<ClientEntity> = ids.mapNotNull { clients.client(it) }

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
     * Сохранённые услуги и имена (шаг 4.2г): одно название или имя дважды (два телефона) — последняя запись; пары с
     * удалёнными клиентами не показываются — они и так не действуют.
     */
    fun mappings(): Flow<Mappings1c> = combine(
        dao.observeServices(),
        dao.observeAliases(),
        clients.observeAllClients(),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { services, aliases, people, types, places ->
        val byId = people.associateBy { it.id }
        val typeOf = types.associateBy { it.id }
        val placeOf = places.associateBy { it.id }
        Mappings1c(
            services = services.associateBy { it.name1c }.values.sortedBy { it.name1c }.map {
                Service1cView(it.name1c, it.typeId, it.locationId, listOfNotNull(typeOf[it.typeId]?.shortName, placeOf[it.locationId]?.shortName).joinToString(" · "))
            },
            names = aliases.associateBy { it.nameKey }.values.mapNotNull { a ->
                val c = byId[a.clientId]?.takeIf { it.sync.deletedAt == null } ?: return@mapNotNull null
                val name1c = c.name1c?.takeIf { Names1c.key(it) == a.nameKey } ?: ReconcileFormat.name1c(a.nameKey)
                Alias1cView(a.nameKey, name1c, c.id, ClientName(c.lastName, c.firstName).short)
            }.sortedBy { it.nameKey },
        )
    }

    /** Кого предложить вместо клиента пары [alias]: он сам первым, затем похожие и тёзки, как в вопросе «Кто это». */
    suspend fun aliasChoices(alias: Alias1cView): List<ClientEntity> {
        val others = when (val m = Reconcile1cRules.match(alias.name1c, emptyMap(), clientList())) {
            is NameMatch.Found -> listOf(m.clientId)
            is NameMatch.Ask -> m.clientIds
            NameMatch.Unknown -> emptyList()
        }
        return clients((listOf(alias.clientId) + others).distinct())
    }

    /** «Нет такого клиента» у сохранённой пары: пара удаляется, при следующей загрузке имя сверяется заново. */
    suspend fun deleteAlias(nameKey: String) {
        val now = clock()
        dao.aliases().filter { it.nameKey == nameKey }.forEach { dao.upsertAlias(it.copy(sync = it.sync.deleted(now))) }
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

    /** Пункты сверки [id] для экрана; пересчитываются сами после каждой правки. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun view(id: String): Flow<List<Report1cItemView>> = combine(
        dao.observeItems(id),
        clients.observeAllClients(),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { items, people, types, places -> Sources(items, people.associateBy { it.id }, types.associateBy { it.id }, places.associateBy { it.id }) }
        .mapLatest { src -> src.items.map { view(it, src) } }

    private class Sources(
        val items: List<ReconciliationItemEntity>,
        val clients: Map<String, ClientEntity>,
        val types: Map<String, TrainingTypeEntity>,
        val places: Map<String, LocationEntity>,
    )

    private suspend fun view(item: ReconciliationItemEntity, src: Sources): Report1cItemView {
        val client = item.clientId?.let { src.clients[it] }
        val t = item.trainingId?.let { trainings.training(it) }?.takeIf { it.sync.deletedAt == null }
        val p = if (t != null && item.clientId != null) trainings.participationRow(t.id, item.clientId)?.takeIf { it.sync.deletedAt == null } else null
        val type = t?.let { src.types[it.typeId] }
        val row = item.rowAt?.let { at -> Row1c(at, Service1c.OTHER, item.rowServiceName.orEmpty(), item.rowClient.orEmpty(), item.rowStatus ?: Status1c.CAME, 1, 1, 0) }
        val visit = if (t != null && p != null) AppVisit(t.id, p.clientId, t.start, t.typeId, t.locationId, type?.kind ?: TrainingKind.OTHER, t.owner, p.status) else null
        val name = client?.let { ClientName(it.lastName, it.firstName).short }
            ?: item.rowClient?.let { Reconcile1cRules.nameOf(it)?.short ?: it }.orEmpty()
        return Report1cItemView(
            item = item,
            name = name,
            sex = client?.sex,
            app = t?.let { listOfNotNull(type?.shortName, src.places[it.locationId]?.shortName).joinToString(" · ") },
            what = type?.let { if (it.name.uppercase() == it.name) it.name else it.name.lowercase() },
            diff = Diff1c(item.kind, row, visit, item.statusDiffers, item.serviceDiffers),
        )
    }

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

    /**
     * «Добавить в приложение» (8.4) — строка «Только в 1С» со статусом из 1С: клиент найден — он, нет — заводится по ФИО из
     * 1С (и пара запоминается); тренировка того же типа, места и начала уже есть (группа) — клиент добавляется в неё,
     * иначе — новая тренировка тренера. Прошедшая и «Пришел» — проведена. `false` — услуга не сопоставлена или пункт не тот.
     */
    suspend fun addFromReport(itemId: String): Boolean {
        val coach = coaches.currentCoach() ?: return false
        val now = clock()
        var done = false
        db.withWriteTransaction {
            val item = dao.item(itemId)?.takeIf { it.trainingId == null } ?: return@withWriteTransaction
            val at = item.rowAt ?: return@withWriteTransaction
            val status = item.rowStatus ?: return@withWriteTransaction
            val target = services()[item.rowServiceName] ?: return@withWriteTransaction
            val type = reference.trainingType(target.typeId) ?: return@withWriteTransaction
            val clientId = item.clientId ?: newClient(item.rowClient ?: return@withWriteTransaction, coach.id, now)
            val existing = trainings.trainingsBetween(at, ClubTime.plusMinutes(at, 1))
                .firstOrNull { it.typeId == target.typeId && it.locationId == target.locationId && it.start == at }
            val appStatus = Reconcile1cRules.appStatus(status)
            val training = existing ?: TrainingEntity(
                id = Ids.newId(now),
                sync = SyncFields.new(coach.id, now),
                typeId = target.typeId,
                locationId = target.locationId,
                start = at,
                durationMin = type.defaultDurationMin,
                status = if (appStatus == ParticipationStatus.ATTENDED) TrainingStatus.DONE else TrainingStatus.CANCELLED,
                doneAt = if (appStatus == ParticipationStatus.ATTENDED) now else null,
            )
            if (existing == null) {
                trainings.upsertTraining(training)
            } else if (appStatus == ParticipationStatus.ATTENDED && existing.status != TrainingStatus.DONE) {
                trainings.upsertTraining(existing.copy(status = TrainingStatus.DONE, doneAt = now, cancelReason = null, sync = existing.sync.touched(now)))
            }
            val old = trainings.participationRow(training.id, clientId)
            trainings.upsertParticipation(
                old?.copy(status = appStatus, sync = if (old.sync.deletedAt != null) old.sync.restored(now) else old.sync.touched(now))
                    ?: ParticipationEntity(Ids.newId(now + 1), SyncFields.new(coach.id, now), training.id, clientId, appStatus),
            )
            dao.upsertItems(listOf(item.copy(clientId = clientId, sync = item.sync.touched(now), resolution = ReconcileResolution.FIXED_IN_APP, resolvedAt = now)))
            db.reconcileSpt(now)
            done = true
        }
        return done
    }

    /**
     * «Исправить в приложении» (8.4): расходится статус или услуга — как в 1С (статус участия, тип и место тренировки);
     * «Только в приложении» — участие становится отменой без списания. Пункт закрывается как исправленный.
     */
    suspend fun fixInApp(itemId: String): Boolean {
        val now = clock()
        var done = false
        db.withWriteTransaction {
            val item = dao.item(itemId) ?: return@withWriteTransaction
            val t = item.trainingId?.let { trainings.training(it) }?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val p = item.clientId?.let { trainings.participationRow(t.id, it) } ?: return@withWriteTransaction
            val status = when (item.kind) {
                ReconcileItemKind.ONLY_APP -> ParticipationStatus.CANCELLED
                ReconcileItemKind.STATUS_DIFF -> if (item.statusDiffers) Reconcile1cRules.appStatus(item.rowStatus ?: return@withWriteTransaction) else p.status
                else -> return@withWriteTransaction
            }
            if (status != p.status) trainings.upsertParticipation(p.copy(status = status, chargeOverridden = false, sync = p.sync.touched(now)))
            var changed = t
            if (item.serviceDiffers) {
                services()[item.rowServiceName]?.let { changed = changed.copy(typeId = it.typeId, locationId = it.locationId) }
            }
            if (status == ParticipationStatus.ATTENDED && changed.status != TrainingStatus.DONE) {
                changed = changed.copy(status = TrainingStatus.DONE, doneAt = now, cancelReason = null)
            }
            if (changed != t) trainings.upsertTraining(changed.copy(sync = t.sync.touched(now)))
            dao.upsertItems(listOf(item.copy(sync = item.sync.touched(now), resolution = ReconcileResolution.FIXED_IN_APP, resolvedAt = now)))
            db.reconcileSpt(now)
            done = true
        }
        return done
    }

    /** Клиент по ФИО из 1С — для «Добавить в приложение» неизвестного; пара «имя — клиент» запоминается. */
    private suspend fun newClient(name1c: String, coachId: String, now: Long): String {
        val name = Reconcile1cRules.nameOf(name1c) ?: ClientName(name1c.trim(), "")
        val id = Ids.newId(now + 2)
        clients.upsertClient(ClientEntity(id, SyncFields.new(coachId, now), name.lastName, name.firstName, name.middleName, name1c = name1c))
        dao.upsertAlias(ClientAlias1cEntity(Ids.newId(now + 3), SyncFields.new(coachId, now), Names1c.key(name1c), id))
        return id
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
