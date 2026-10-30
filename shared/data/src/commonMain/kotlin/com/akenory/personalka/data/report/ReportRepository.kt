package com.akenory.personalka.data.report

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
        ) { list, participations, client, places ->
            val mine = participations.associate { it.trainingId to ClientParticipation(it.trainingId, it.status, it.notifiedAt, it.cancelReason) }
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
