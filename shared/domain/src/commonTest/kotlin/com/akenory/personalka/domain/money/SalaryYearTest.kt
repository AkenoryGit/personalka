package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Вкладка «Год» (ТЗ 5.12, шаг 3.8б): строки месяцев из расчёта «Месяца», итог года — их сумма. */
class SalaryYearTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val today = LocalDate(2026, 10, 20)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("group", null, jan, rub(270)),
        PayRate("duty", null, jan, rub(200)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt")))
    private val half = PayScheme(LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL, advanceDay = 25, advanceDefault = 1_354_650)
    private val owners = OwnerSchemes(listOf(half.owner()), emptyMap())

    private var seq = 0
    private fun t(month: Int, type: String = "pt", day: Int = 15, status: TrainingStatus = TrainingStatus.DONE, clients: Int = 1, minutes: Int = 50) =
        PayTraining(
            "t${seq++}", type, "bb", LocalDateTime(2026, month, day, 10, 0), minutes, status,
            List(clients) { if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED },
        )

    private fun month(m: Int, trainings: List<PayTraining>, plans: Map<YearMonth, Long>): SalaryMonth {
        val input = MonthInput(YearMonth(2026, m), trainings, listOf(pt, group, duty), rates, tiers, owners, emptyList(), emptyList())
        return SalaryMonthRules.build(input, listOf(half), plans, null, today, "pt", "bb")
    }

    private val plans = mapOf(YearMonth(2026, 8) to rub(50_000), YearMonth(2026, 9) to rub(60_000))

    private fun year(): Pair<SalaryYear, List<SalaryMonth>> {
        val months = listOf(
            month(7, emptyList(), plans),
            month(8, List(60) { t(8) }, plans),
            month(9, List(50) { t(9) } + t(9, "group", clients = 10) + t(9, "duty", minutes = 180), plans),
            month(10, List(30) { t(10) } + List(20) { t(10, day = 28, status = TrainingStatus.PLANNED) }, plans),
        )
        return SalaryYearRules.build(2026, months, today) to months
    }

    @Test
    fun monthsBeforeFirstWorkAreHidden() {
        val (y, _) = year()
        assertEquals(listOf(8, 9, 10), y.rows.map { it.month.month.ordinal + 1 })
        assertEquals(YearMonth(2026, 10), y.current)
    }

    @Test
    fun rowsMatchMonthTabAndSumToYear() {
        val (y, months) = year()
        val october = months.last()
        val row = y.rows.last()
        assertEquals(october.pay.earned, row.earned)
        assertEquals(october.pay.forecast, row.forecast)
        assertEquals(october.payout.ndfl, row.ndfl)
        assertEquals(october.totals.onHand, row.onHand)
        assertEquals(rub(27_600), row.earned)
        assertEquals(y.rows.sumOf { it.earned }, y.earned)
        assertEquals(rub(55_200) + rub(46_000) + rub(2_700) + rub(600) + rub(27_600), y.earned)
        assertEquals(140, y.ptCount)
        assertEquals(10, y.groupChildren)
        assertEquals(180, y.dutyMinutes)
        assertEquals(y.rows.sumOf { it.onHand }, y.onHand)
        assertEquals(y.rows.sumOf { it.ndfl }, y.ndfl)
    }

    @Test
    fun planMetOnlyInFinishedMonths() {
        val (y, _) = year()
        // Август 55 200 при плане 50 000 — выполнен; сентябрь 49 300 при 60 000 — нет; октябрь идёт (план как в сентябре).
        assertTrue(y.rows[0].planMet)
        assertEquals(false, y.rows[1].planMet)
        assertEquals(false, y.rows[2].planMet)
        assertEquals(rub(60_000), y.rows[2].plan)
        assertNull(y.rows[0].forecast)
        assertEquals(2, y.planMonths)
        assertEquals(1, y.planMetMonths)
        assertEquals("план выполнен в 1 из 2 месяцев · октябрь идёт", SalaryFormat.yearPlan(y))
        assertEquals(110, y.rows[0].percent)
    }

    @Test
    fun monthsToCount() {
        assertEquals(10, SalaryYearRules.months(2026, today).size)
        assertEquals(12, SalaryYearRules.months(2025, today).size)
        assertTrue(SalaryYearRules.months(2027, today).isEmpty())
        assertTrue(SalaryYearRules.build(2027, emptyList(), today).rows.isEmpty())
        assertNull(SalaryFormat.yearPlan(SalaryYearRules.build(2027, emptyList(), today)))
    }

    @Test
    fun rowText() {
        val (y, _) = year()
        val sep = y.rows[1]
        assertEquals(
            "ПТ 50 · детей 10 · дежурств 3${NBSP}ч · налог ${money(sep.ndfl)} · на руки ${money(sep.onHand)}",
            SalaryFormat.yearRow(sep),
        )
        assertEquals("сен", SalaryFormat.monthShort(sep.month))
        assertEquals("май", SalaryFormat.monthShort(YearMonth(2026, 5)))
        assertEquals("Заработано за 2026", SalaryFormat.yearTitle(2026))
        assertEquals("план выполнен в 1 из 21 месяца", SalaryFormat.yearPlan(SalaryYear(2025, List(21) { pastRow(it == 0) }, null)))
        assertEquals("27${THIN}600${NBSP}₽", com.akenory.personalka.domain.format.MoneyFormat.rub(y.rows.last().earned))
    }

    private fun pastRow(met: Boolean) =
        YearMonthRow(YearMonth(2025, 1), MonthPeriod.PAST, if (met) rub(10) else 0, null, rub(10), 0, 0, 0, 0, 0)

    private fun money(k: Long) = com.akenory.personalka.domain.format.MoneyFormat.rub(k)
}
