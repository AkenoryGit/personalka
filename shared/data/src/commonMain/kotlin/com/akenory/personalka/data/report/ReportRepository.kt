package com.akenory.personalka.data.report

import com.akenory.personalka.data.backup.BackupMade
import com.akenory.personalka.data.backup.BackupPlatform
import com.akenory.personalka.data.groups.member
import com.akenory.personalka.data.groups.sharing
import com.akenory.personalka.domain.backup.Xlsx
import com.akenory.personalka.domain.format.GroupReportFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.report.GroupReport
import com.akenory.personalka.domain.report.GroupReportGroup
import com.akenory.personalka.domain.report.GroupReportPerson
import com.akenory.personalka.domain.report.GroupReportRules
import com.akenory.personalka.domain.report.GroupReportTraining
import kotlinx.coroutines.withContext
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.money.SalaryMonthData
import com.akenory.personalka.data.money.SalaryRepository
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.report.ClientParticipation
import com.akenory.personalka.domain.report.ClientReport
import com.akenory.personalka.domain.report.ClientReportRules
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.money.SalaryYearRules
import com.akenory.personalka.domain.report.ReportKey
import com.akenory.personalka.domain.report.ReportRules
import com.akenory.personalka.domain.report.ReportTable
import com.akenory.personalka.domain.report.ReportYear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

/**
 * Названия для отчёта: типы и места по id (места — в порядке тренера) и «за кого» каждая тренировка — группа или
 * клиенты «Иванов И., Петров П.».
 */
data class ReportNames(
    val types: Map<String, TrainingTypeEntity> = emptyMap(),
    val places: List<LocationEntity> = emptyList(),
    val whom: Map<String, String> = emptyMap(),
) {
    private val placeById = places.associateBy { it.id }

    fun type(id: String): String = types[id]?.let { it.shortName.ifBlank { it.name } }.orEmpty()

    fun place(id: String): String = placeById[id]?.let { it.shortName.ifBlank { it.name } }.orEmpty()

    /** «ПТ · ББ», у групп с коллегой — со случаем. */
    fun row(key: ReportKey): String = ReportFormat.rowTitle(key, type(key.typeId), place(key.locationId))
}

/** Отчёт за период ([table]) или год ([year]) и названия к нему. */
data class ReportView(val table: ReportTable? = null, val year: ReportYear? = null, val names: ReportNames = ReportNames())

/**
 * Вкладка «Группы» (шаг 5.3о): [report] — блоки выбранных групп, [groups] — все группы с тренировками в периоде для окна
 * выбора («id» → название, по названию).
 */
data class GroupReportView(val report: GroupReport, val groups: List<Pair<String, String>>)

/** Клиент в выборе вкладки «Клиент»: «Орлова Ксения», [name] — для поиска, [archived] — пометка «в архиве». */
data class ReportClient(val id: String, val title: String, val name: ClientName, val archived: Boolean)

/** Отчёт по клиенту (5.15, шаг 5.3к): [client] — `null`, если его удалили. */
data class ClientReportView(val report: ClientReport, val client: ReportClient?, val names: ReportNames = ReportNames())

/**
 * «Отчёты» (ТЗ 5.15, шаг 5.3и). Месяцы считает [salary] — тот же код, что вкладки «Зарплаты», поэтому «В зарплату» и
 * «в счёт ступени» совпадают с ней. Здесь только названия и выбор месяцев периода.
 */
class ReportRepository(private val db: PersonalkaDatabase, private val salary: SalaryRepository) {
    private val trainingDao = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()

    /** Отчёт за [from]–[to] на «сейчас» [now]; пересчитывается сам при правках. */
    fun table(from: LocalDate, to: LocalDate, now: LocalDateTime): Flow<ReportView> {
        val months = ReportRules.monthsOf(from, to)
        return view(from, to, salary.monthsData(months, now.date)) { list, places ->
            ReportView(table = ReportRules.table(list.mapNotNull { d -> d.input?.let { it to d.view.pay } }, from, to, now, places))
        }
    }

    /**
     * Отчёт по клиенту [clientId] за [from]–[to] (шаг 5.3к): его участия поверх тех же месяцев «Зарплаты», поэтому
     * «В зарплату» у него — только тренировки, которые она считает.
     */
    fun client(clientId: String, from: LocalDate, to: LocalDate, now: LocalDateTime): Flow<ClientReportView> {
        val months = ReportRules.monthsOf(from, to)
        return combine(
            salary.monthsData(months, now.date),
            trainingDao.observeParticipationsOfClient(clientId),
            clients.observeClient(clientId),
            reference.observeLocations(),
            trainingDao.observeTrainingsOfClient(clientId),
        ) { list, participations, client, places, own ->
            // История в группе — с первого «пришёл» (4.4, шаг 5.3н): раньше тренировки группы в отчёт не идут.
            val byTraining = own.associateBy { it.id }
            val starts = GroupRules.historyStarts(
                participations.mapNotNull { p -> byTraining[p.trainingId]?.let { t -> t.groupId?.let { Triple(it, t.start.date, p.status == ParticipationStatus.ATTENDED) } } },
            )
            val mine = participations.filter { p ->
                val t = byTraining[p.trainingId]
                t == null || !GroupRules.hiddenFromHistory(t.groupId, t.start.date, t.start <= now, p.status, starts)
            }.associate { it.trainingId to ClientParticipation(it.trainingId, it.status, it.notifiedAt, it.cancelReason) }
            val report = ClientReportRules.report(
                clientId,
                list.mapNotNull { d -> d.input?.let { it to d.view.pay } },
                mine,
                from,
                to,
                now,
                places.map { it.id },
            )
            ClientReportView(
                report = report,
                client = client?.takeIf { it.sync.deletedAt == null }?.let(::reportClient),
                names = ReportNames(list.lastOrNull()?.types.orEmpty(), places),
            )
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Посещаемость групп за [from]–[to] (шаг 5.3о): [chosen] — выбранные группы, `null` — все. Тренировки и участия —
     * все групповые: начало истории ребёнка считается по всем (4.4, шаг 5.3н).
     */
    fun groups(from: LocalDate, to: LocalDate, now: LocalDateTime, chosen: Set<String>?): Flow<GroupReportView> = combine(
        clients.observeGroups(),
        clients.observeAllMembers(),
        clients.observeAllClients(),
        trainingDao.observeGroupTrainings(),
        trainingDao.observeGroupParticipations(),
    ) { groups, members, people, trainings, participations ->
        val colleagues = db.moneyDao().colleagues().associate { it.id to it.name }
        val reportGroups = groups.filter { it.sync.deletedAt == null }.map { g ->
            val sharing = g.sharing()
            val colleague = if (sharing.conductedBy == ConductedBy.EACH_OWN) sharing.colleague?.let { colleagues[it] } ?: SalaryFormat.COLLEAGUE else null
            GroupReportGroup(g.id, g.name, colleague)
        }
        val byTraining = participations.groupBy { it.trainingId }
        val list = trainings.filter { it.groupId != null && it.sync.deletedAt == null }.map { t ->
            GroupReportTraining(
                id = t.id,
                groupId = t.groupId!!,
                start = t.start,
                status = t.status,
                people = byTraining[t.id].orEmpty().map { GroupReportPerson(it.clientId, it.status, it.notifiedAt != null) },
            )
        }
        val withTrainings = GroupReportRules.withTrainings(list, from, to, now)
        val names = people.associate { it.id to ClientName(it.lastName, it.firstName, it.middleName) }
        val report = GroupReportRules.report(
            reportGroups,
            list,
            members.filter { it.sync.deletedAt == null }.groupBy({ it.groupId }, { it.member() }),
            names,
            from,
            to,
            now,
            chosen,
        )
        GroupReportView(
            report,
            reportGroups.filter { it.id in withTrainings }.sortedBy { ClientRules.key(it.name) }.map { it.id to it.name },
        )
    }.flowOn(Dispatchers.Default)

    /** Файл Excel отчёта по группам (5.15): «Итоги» и журнал по группе — тем же писателем, что выгрузка 7.6. */
    suspend fun groupsExcel(report: GroupReport, today: LocalDate): BackupMade = withContext(Dispatchers.Default) {
        val sheets = GroupReportFormat.sheets(report)
        BackupMade(GroupReportFormat.fileName(today), BackupPlatform.zip(Xlsx.files(sheets).mapValues { it.value.encodeToByteArray() }))
    }

    /** Клиенты для выбора — и в архиве, по фамилии (5.8). */
    fun clients(): Flow<List<ReportClient>> = clients.observeAllClients().map { list ->
        list.sortedBy { ClientRules.sortKey(ClientName(it.lastName, it.firstName, it.middleName)) }.map(::reportClient)
    }

    private fun reportClient(c: ClientEntity) = ReportClient(
        id = c.id,
        title = "${c.lastName} ${c.firstName}",
        name = ClientName(c.lastName, c.firstName, c.middleName),
        archived = c.status == ClientStatus.ARCHIVED,
    )

    /** «Год» [year]: месяцы по текущий, у каждого — отметка «сверен» из «Сверок». */
    fun year(year: Int, now: LocalDateTime): Flow<ReportView> {
        val months = SalaryYearRules.months(year, now.date)
        val from = LocalDate(year, 1, 1)
        val to = LocalDate(year, 12, 31)
        return view(from, to, salary.monthsData(months, now.date)) { list, places ->
            val closed = list.filter { it.view.closed }.map { it.view.month }.toSet()
            ReportView(year = ReportRules.year(year, list.mapNotNull { d -> d.input?.let { it to d.view.pay } }, now, closed, places))
        }
    }

    private fun view(
        from: LocalDate,
        to: LocalDate,
        months: Flow<List<SalaryMonthData>>,
        build: (List<SalaryMonthData>, List<String>) -> ReportView,
    ): Flow<ReportView> {
        val start = from.atTime(LocalTime(0, 0))
        val end = to.plus(1, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        return combine(
            months,
            trainingDao.observeParticipationsBetween(start, end),
            clients.observeAllClients(),
            clients.observeGroups(),
            reference.observeLocations(),
        ) { list, participations, people, groups, places ->
            val view = build(list, places.map { it.id })
            val ids = (view.table?.items?.keys ?: emptySet()) + (view.year?.total?.items?.keys ?: emptySet())
            val trainings = list.flatMap { it.trainings.values }.filter { it.id in ids }
            val names = people.associate { it.id to CalendarFormat.client(ClientName(it.lastName, it.firstName)) }
            val groupNames = groups.associate { it.id to it.name }
            val byTraining = participations.groupBy { it.trainingId }
            val whom = trainings.associate { t ->
                t.id to (t.groupId?.let { groupNames[it] } ?: byTraining[t.id].orEmpty().mapNotNull { names[it.clientId] }.joinToString(", "))
            }
            view.copy(names = ReportNames(list.lastOrNull()?.types.orEmpty(), places, whom))
        }.flowOn(Dispatchers.Default)
    }
}
