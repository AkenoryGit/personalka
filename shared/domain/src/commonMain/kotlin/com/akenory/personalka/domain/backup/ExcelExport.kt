package com.akenory.personalka.domain.backup

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ExcelFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.SalaryYear
import com.akenory.personalka.domain.result.ResultTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// Выгрузка для чтения (ТЗ 7.6, шаг 3.8в): четыре листа Excel. Данные собирает слой данных, здесь — только какие
// столбцы, в каком порядке и как подписаны. Зарплата — те же SalaryYear, что во вкладке «Год», поэтому суммы
// месяцев в файле совпадают с приложением до копейки.

/** Клиент на листе «Клиенты»: [groups] — группы, где он сейчас; [attended] — проведённые тренировки, где он был. */
data class ExcelClient(
    val name: ClientName,
    val birthDate: LocalDate?,
    val contactName: String?,
    val contactPhone: String?,
    val groups: List<String>,
    val archived: Boolean,
    val attended: Int,
    val lastVisit: LocalDate?,
    val mediaUrl: String?,
    val note: String?,
)

/** Тренировка на листе «Тренировки»: [people] — клиенты с отметкой, [colleague] — имя коллеги, если вёл не один. */
data class ExcelTraining(
    val start: LocalDateTime,
    val durationMin: Int,
    val type: String,
    val place: String,
    val group: String?,
    val people: List<Pair<ClientName, ParticipationStatus>>,
    val status: TrainingStatus,
    val conductedBy: ConductedBy,
    val colleague: String?,
    val cancelReason: String?,
    val plan: String?,
    val fact: String?,
)

data class ExcelResult(
    val date: LocalDate,
    val client: ClientName,
    val distanceM: Int,
    val style: SwimStyle,
    val poolM: Int,
    val timeCs: Int,
    val kind: ResultKind,
    val eventName: String?,
    val note: String?,
)

object ExcelExport {

    /** Книга из четырёх листов в порядке ТЗ 7.6. */
    fun sheets(
        clients: List<ExcelClient>,
        trainings: List<ExcelTraining>,
        results: List<ExcelResult>,
        salary: List<SalaryYear>,
    ): List<XlsxSheet> = listOf(clientSheet(clients), trainingSheet(trainings), resultSheet(results), salarySheet(salary))

    fun clientSheet(clients: List<ExcelClient>): XlsxSheet = XlsxSheet(
        name = ExcelFormat.CLIENTS,
        headers = ExcelFormat.CLIENT_HEADERS,
        widths = listOf(16, 14, 16, 13, 14, 16, 22, 12, 10, 13, 24, 30),
        rows = clients
            .sortedWith(compareBy<ExcelClient> { it.archived }.thenBy { it.name.lastName.lowercase() }.thenBy { it.name.firstName.lowercase() })
            .map { c ->
                listOf(
                    text(c.name.lastName), text(c.name.firstName), text(c.name.middleName), c.birthDate?.let { XlsxCell.Day(it) },
                    text(c.contactName), text(c.contactPhone), text(c.groups.sorted().joinToString(", ")),
                    text(if (c.archived) ExcelFormat.ARCHIVED else ExcelFormat.ACTIVE),
                    XlsxCell.Whole(c.attended.toLong()), c.lastVisit?.let { XlsxCell.Day(it) }, text(c.mediaUrl), text(c.note),
                )
            },
    )

    fun trainingSheet(trainings: List<ExcelTraining>): XlsxSheet = XlsxSheet(
        name = ExcelFormat.TRAININGS,
        headers = ExcelFormat.TRAINING_HEADERS,
        widths = listOf(11, 5, 7, 7, 16, 14, 16, 36, 13, 12, 18, 30, 30),
        rows = trainings.sortedBy { it.start }.map { t ->
            listOf(
                XlsxCell.Day(t.start.date), text(DateFormat.weekdayShort(t.start.dayOfWeek)), text(DateFormat.time(t.start.time)),
                XlsxCell.Whole(t.durationMin.toLong()), text(t.type), text(t.place), text(t.group),
                text(t.people.sortedBy { it.first.short.lowercase() }.joinToString(", ") { (name, status) -> ExcelFormat.person(name.short, status) }),
                text(ExcelFormat.status(t.status)), text(ExcelFormat.conducted(t.conductedBy, t.colleague)),
                text(t.cancelReason), text(t.plan), text(t.fact),
            )
        },
    )

    fun resultSheet(results: List<ExcelResult>): XlsxSheet = XlsxSheet(
        name = ExcelFormat.RESULTS,
        headers = ExcelFormat.RESULT_HEADERS,
        widths = listOf(11, 22, 12, 14, 11, 10, 9, 13, 24, 30),
        rows = results.sortedWith(compareBy<ExcelResult> { it.date }.thenBy { it.client.short.lowercase() }).map { r ->
            listOf(
                XlsxCell.Day(r.date), text(r.client.short), XlsxCell.Whole(r.distanceM.toLong()), text(ResultFormat.style(r.style)),
                XlsxCell.Whole(r.poolM.toLong()), text(ResultTime.format(r.timeCs)), XlsxCell.Hundredths(r.timeCs.toLong()),
                text(ExcelFormat.where(r.kind)), text(r.eventName), text(r.note),
            )
        },
    )

    /**
     * Месяцы лет [years] по возрастанию — те же строки, что во вкладке «Год»; после каждого года — строка «Итого» с теми
     * же суммами, что вверху вкладки. Прогноз — только у идущего месяца, план и процент — где план есть.
     */
    fun salarySheet(years: List<SalaryYear>): XlsxSheet {
        val rows = mutableListOf<List<XlsxCell?>>()
        val bold = mutableSetOf<Int>()
        years.filter { it.rows.isNotEmpty() }.sortedBy { it.year }.forEach { y ->
            y.rows.sortedBy { it.month }.forEach { m ->
                rows += listOf(
                    XlsxCell.Whole(m.month.year.toLong()), text(SalaryFormat.monthName(m.month)), XlsxCell.Money(m.earned),
                    m.forecast?.let { XlsxCell.Money(it) }, m.plan?.let { XlsxCell.Money(it) }, m.percent?.let { XlsxCell.Whole(it.toLong()) },
                    XlsxCell.Money(m.ndfl), XlsxCell.Money(m.onHand), XlsxCell.Whole(m.ptCount.toLong()),
                    XlsxCell.Whole(m.groupChildren.toLong()), hours(m.dutyMinutes),
                )
            }
            bold += rows.size
            rows += listOf(
                XlsxCell.Whole(y.year.toLong()), text(ExcelFormat.total(y.year)), XlsxCell.Money(y.earned), null, null, null,
                XlsxCell.Money(y.ndfl), XlsxCell.Money(y.onHand), XlsxCell.Whole(y.ptCount.toLong()),
                XlsxCell.Whole(y.groupChildren.toLong()), hours(y.dutyMinutes),
            )
        }
        return XlsxSheet(
            name = ExcelFormat.SALARY,
            headers = ExcelFormat.SALARY_HEADERS,
            widths = listOf(7, 16, 14, 14, 14, 9, 13, 14, 6, 10, 12),
            rows = rows,
            boldRows = bold,
        )
    }

    /** Часы дежурств с сотыми: 90 минут — 1,50. */
    private fun hours(minutes: Int): XlsxCell = XlsxCell.Hundredths((minutes * 100L + 30) / 60)

    private fun text(value: String?): XlsxCell? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { XlsxCell.Text(it) }
}
