package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.TrainingStatus.CANCELLED
import com.akenory.personalka.domain.model.TrainingStatus.DONE
import com.akenory.personalka.domain.model.TrainingStatus.PLANNED
import com.akenory.personalka.domain.money.BreakdownSection
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.MonthPay
import com.akenory.personalka.domain.money.PayRate
import com.akenory.personalka.domain.money.PayTraining
import com.akenory.personalka.domain.money.PayType
import com.akenory.personalka.domain.money.SalaryDaysRules
import com.akenory.personalka.domain.money.SalaryMonthRules
import com.akenory.personalka.domain.money.SptCase
import com.akenory.personalka.domain.money.TierScheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Отчёты» (ТЗ 5.15, шаг 5.3и): числа сходятся с расчётом «Зарплаты». Клиенты придуманы. */
class ReportsTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val september = YearMonth(2026, 9)
    private val october = YearMonth(2026, 10)
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val split = PayType("split", TrainingKind.SPLIT, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true, splitFallbackTypeId = "pt")
    private val spt = PayType("spt", TrainingKind.SPT, PayUnit.PER_SESSION, countsTowardPt = false, tierDependent = false)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, split, spt, group, duty)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("pt", "db", jan, rub(830), rub(860)),
        PayRate("split", null, jan, rub(1550), rub(1600)),
        PayRate("spt", null, jan, rub(200), sptCloseBonus = rub(140)),
        PayRate("group", null, jan, rub(270)),
        PayRate("duty", null, jan, rub(200)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt", "split")))

    private var seq = 0
    private fun t(
        type: String,
        place: String,
        day: Int,
        status: TrainingStatus,
        clients: List<ParticipationStatus>,
        month: YearMonth = october,
        hour: Int = 10,
        minutes: Int = 50,
        owner: Owner = Owner.SELF,
        by: ConductedBy = ConductedBy.SELF,
    ) = PayTraining(
        "t${seq++}", type, place, LocalDateTime(month.year, month.month, day, hour, 0), minutes, status, clients,
        owner = owner, ownerColleagueId = "lena".takeIf { owner == Owner.COLLEAGUE },
        conductedBy = by, colleagueId = "lena".takeIf { by != ConductedBy.SELF },
    )

    private fun october(): List<PayTraining> = buildList {
        repeat(3) { add(t("pt", "bb", 5 + it, DONE, listOf(ATTENDED))) }
        add(t("pt", "bb", 9, CANCELLED, listOf(CANCELLED_CHARGED)))
        add(t("pt", "bb", 10, CANCELLED, listOf(ParticipationStatus.CANCELLED)))
        repeat(2) { add(t("pt", "db", 12 + it, DONE, listOf(ATTENDED))) }
        add(t("split", "bb", 14, DONE, listOf(ATTENDED, CANCELLED_CHARGED)))
        add(t("group", "bb", 3, DONE, List(5) { ATTENDED } + ParticipationStatus.CANCELLED))
        add(t("group", "bb", 10, DONE, List(4) { ATTENDED }))
        add(t("group", "bb", 17, DONE, List(6) { ATTENDED }, by = ConductedBy.SELF_AND_COLLEAGUE))
        // Группа коллеги, которую вёл коллега, — не наша: в отчёт не попадает, как в разбивку зарплаты.
        add(t("group", "bb", 17, DONE, List(6) { ATTENDED }, hour = 11, owner = Owner.COLLEAGUE, by = ConductedBy.COLLEAGUE))
        add(t("duty", "bb", 6, DONE, emptyList(), minutes = 240))
        add(t("duty", "bb", 7, DONE, emptyList(), minutes = 180))
        add(t("duty", "bb", 8, CANCELLED, emptyList(), minutes = 240))
        add(t("spt", "bb", 15, DONE, listOf(ATTENDED)))
        // Сегодня: одна идёт (начата и не отмечена), одна вечером; потом будущее и отменённая заранее на будущее.
        add(t("pt", "bb", 20, PLANNED, listOf(EXPECTED), hour = 15))
        add(t("pt", "bb", 20, PLANNED, listOf(EXPECTED), hour = 19))
        repeat(4) { add(t("pt", "bb", 22 + it, PLANNED, listOf(EXPECTED))) }
        add(t("pt", "bb", 27, CANCELLED, listOf(ParticipationStatus.CANCELLED)))
    }

    private val sptCases = listOf(
        SptCase("c1", "kid", "spt-sep", "spt", "bb", LocalDate(2026, 9, 28), purchaseDate = LocalDate(2026, 10, 2), status = SptCaseStatus.CLOSED),
    )

    private fun input(month: YearMonth, trainings: List<PayTraining>) =
        MonthInput(month, trainings, types, rates, tiers, spt = sptCases)

    private fun months(trainings: List<PayTraining>): List<Pair<MonthInput, MonthPay>> =
        listOf(september, october).map { m -> input(m, trainings).let { it to it.pay() } }

    private val sept = List(4) { t("pt", "bb", 10 + it, DONE, listOf(ATTENDED), month = september) } +
        t("pt", "bb", 20, CANCELLED, listOf(ParticipationStatus.CANCELLED), month = september)

    @Test
    fun monthRowsAndColumns() {
        val table = ReportRules.month(months(october() + sept), october, now, placeOrder = listOf("bb", "db"))
        val keys = table.rows.map { it.key }
        assertEquals(
            listOf(
                ReportKey("pt", "bb"), ReportKey("pt", "db"), ReportKey("split", "bb"), ReportKey("spt", "bb"),
                ReportKey("group", "bb", GroupCase.MINE_ALONE), ReportKey("group", "bb", GroupCase.MINE_TOGETHER), ReportKey("duty", "bb"),
            ),
            keys,
        )
        val ptBb = table.row(ReportKey("pt", "bb"))!!
        // Отменённая заранее на 27.10 — будущее: в отчёт ещё не входит.
        assertEquals(ReportCounts(done = 3, charged = 1, cancelled = 1, inSalary = 4), ptBb.counts)
        assertEquals("проведено 3 · со списанием 1 · отменено 1", ReportFormat.rowNote(ptBb))
        assertEquals("4", ReportFormat.salaryValue(ptBb))

        val alone = table.row(ReportKey("group", "bb", GroupCase.MINE_ALONE))!!
        assertEquals(9, alone.children)
        assertEquals(45, alone.averageTenths)
        assertEquals("детей 9 · в среднем 4,5", ReportFormat.children(alone))
        assertEquals("Группа · ББ · вдвоём с коллегой", ReportFormat.rowTitle(ReportKey("group", "bb", GroupCase.MINE_TOGETHER), "Группа", "ББ"))

        val duties = table.row(ReportKey("duty", "bb"))!!
        assertEquals(420, duties.doneMinutes)
        assertEquals(240, duties.cancelledMinutes)
        assertEquals("проведено 7${NBSP}ч · отменено 4${NBSP}ч", ReportFormat.rowNote(duties))
        assertEquals("7${NBSP}ч", ReportFormat.salaryValue(duties))

        val sptRow = table.row(ReportKey("spt", "bb"))!!
        assertEquals(1, sptRow.sptClosed)
        assertEquals("проведено 1 · закрыто 1", ReportFormat.rowNote(sptRow))

        assertEquals(1, table.waiting)
        assertEquals(5, table.ahead)
        assertEquals(LocalDate(2026, 10, 20), table.counted)
        assertEquals("по 20.10", ReportFormat.countedTo(table))
    }

    /** «В зарплату» по каждой паре — ровно число тренировок этой пары в разбивке «Зарплаты» (4.10). */
    @Test
    fun matchesSalaryBreakdown() {
        val all = october() + sept
        val inp = input(october, all)
        val pay = inp.pay()
        val table = ReportRules.month(listOf(inp to pay), october, now)
        val breakdown = SalaryMonthRules.breakdown(inp, pay).filter { it.typeId != null }
        val bySalary = breakdown.groupBy { Triple(it.typeId, it.locationId, it.groupCase) }.mapValues { (_, l) -> l.sumOf { it.count } }
        val byReport = table.rows.filter { it.counts.inSalary > 0 }
            .associate { Triple(it.key.typeId, it.key.locationId, it.key.groupCase) to it.counts.inSalary }
        assertEquals(bySalary, byReport)
        // Счётчик ступени — тот же, что на «Деньгах».
        assertEquals(pay.ptCount, table.ptCount)
        // Дети и часы дежурств — те же, что в «Зарплате».
        assertEquals(pay.groupChildren, table.rows.filter { it.kind == TrainingKind.GROUP && it.key.groupCase != GroupCase.MINE_REPLACED }.sumOf { it.children })
        assertEquals(pay.dutyMinutes, table.row(ReportKey("duty", "bb"))!!.doneMinutes)
        assertTrue(breakdown.none { it.section == BreakdownSection.GROUPS && it.groupCase == null })
    }

    /** Свой период — тренировки этих дней; сходится с «Заработком за период». */
    @Test
    fun customPeriodMatchesSalaryPeriod() {
        val list = months(october() + sept)
        val from = LocalDate(2026, 9, 12)
        val to = LocalDate(2026, 10, 9)
        val table = ReportRules.table(list, from, to, now)
        val ptBb = table.row(ReportKey("pt", "bb"))!!
        // Сентябрь 12–13 (2), октябрь 5–9 (3 проведено + 1 со списанием); отмена 20.09 — тоже в периоде.
        assertEquals(ReportCounts(done = 5, charged = 1, cancelled = 1, inSalary = 6), ptBb.counts)
        val period = SalaryDaysRules.period(list.map { it.first to it.second }, from, to, now.date)
        val salaryPt = period.breakdown.filter { it.typeId == "pt" && it.locationId == "bb" }.sumOf { it.count }
        assertEquals(salaryPt, ptBb.counts.inSalary)
        assertNull(ReportFormat.countedTo(table))
    }

    /** «Год»: итог года — сумма месяцев, по любой паре. */
    @Test
    fun yearSumsMonths() {
        val list = months(october() + sept)
        val year = ReportRules.year(2026, list, now, closed = setOf(september))
        assertEquals(listOf(september, october), year.months.map { it.month })
        assertTrue(year.months.first().closed)
        (year.keys + listOf(null)).forEach { key ->
            assertEquals(year.totalCounts(key), year.months.fold(ReportCounts()) { acc, m -> acc + year.counts(m, key) }, "пара $key")
        }
        assertEquals(ReportCounts(done = 4, cancelled = 1, inSalary = 4), year.counts(year.months.first(), ReportKey("pt", "bb")))
        assertEquals("Сентябрь · сверен", ReportFormat.yearMonth(september, true))
        assertEquals("4 / 0 / 1", ReportFormat.cell(year.counts(year.months.first(), ReportKey("pt", "bb"))))
    }

    @Test
    fun emptyAndShare() {
        val empty = ReportRules.month(months(emptyList()), october, now)
        assertTrue(empty.isEmpty)
        assertEquals("Отчёт: Октябрь 2026 (по 20.10)\n${ReportFormat.EMPTY}", ReportFormat.shareText("Октябрь 2026", empty) { "" })

        val table = ReportRules.month(months(october()), october, now)
        val text = ReportFormat.shareText("Октябрь 2026", table) { ReportFormat.rowTitle(it, it.typeId, it.locationId) }
        assertTrue("pt · bb – проведено 3, со списанием 1, отменено 1, в зарплату 4" in text, text)
        assertTrue("duty · bb – проведено 7${NBSP}ч, отменено 4${NBSP}ч" in text, text)
        assertTrue(text.endsWith("в счёт ступени ${table.ptCount}"), text)
    }

    @Test
    fun monthsOfPeriod() {
        assertEquals(
            listOf(YearMonth(2025, 11), YearMonth(2025, 12), YearMonth(2026, 1)),
            ReportRules.monthsOf(LocalDate(2025, 11, 20), LocalDate(2026, 1, 5)),
        )
        assertEquals(emptyList(), ReportRules.monthsOf(LocalDate(2026, 2, 1), LocalDate(2026, 1, 1)))
    }
}
