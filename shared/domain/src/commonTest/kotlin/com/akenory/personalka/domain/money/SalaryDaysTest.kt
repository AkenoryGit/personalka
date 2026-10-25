package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Заработок за день и за период (ТЗ 5.12, шаг 3.7в). */
class SalaryDaysTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val september = YearMonth(2026, 9)
    private val october = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val sptType = PayType("spt", TrainingKind.SPT, PayUnit.PER_SESSION, countsTowardPt = false, tierDependent = false)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("spt", null, jan, rub(200), sptCloseBonus = rub(140)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt")))
    private val scheme = PayScheme(jan, PaySchemeKind.NONE)

    private var seq = 0
    private fun pts(count: Int, month: YearMonth, day: Int, status: TrainingStatus = TrainingStatus.DONE) = List(count) {
        PayTraining("t${seq++}", "pt", "bb", LocalDateTime(month.year, month.month, day, 10, 0), 50, status, listOf(if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED))
    }

    private fun input(
        month: YearMonth,
        trainings: List<PayTraining>,
        spt: List<SptCase> = emptyList(),
        adjustments: List<PayAdjustment> = emptyList(),
    ) = MonthInput(month, trainings, listOf(pt, sptType), rates, tiers, spt = spt, adjustments = adjustments)

    private fun view(input: MonthInput, on: LocalDate = today): SalaryMonth =
        SalaryMonthRules.build(input, listOf(scheme), emptyMap(), null, on, "pt", "bb")

    /** Сентябрь: по 4 ПТ в дни 1–30 — 120, ступень 2. */
    private fun septemberInput() = input(september, (1..30).flatMap { pts(4, september, it) })

    /** Октябрь до 20-го: по 5 ПТ в дни 1–19 и 4 сегодня — 99, ступень 1; доплата 15-го, СПТ куплен 5-го. */
    private fun octoberInput(): MonthInput {
        val case = SptCase("c1", "kid", "spt-old", "spt", "bb", LocalDate(2026, 9, 30), purchaseDate = LocalDate(2026, 10, 5), status = SptCaseStatus.CLOSED)
        return input(
            october,
            (1..19).flatMap { pts(5, october, it) } + pts(4, october, 20) + pts(30, october, 28, TrainingStatus.PLANNED),
            spt = listOf(case),
            adjustments = listOf(PayAdjustment(rub(1500), date = LocalDate(2026, 10, 15)), PayAdjustment(-rub(200))),
        )
    }

    @Test
    fun daysSumToEarned() {
        val v = view(octoberInput())
        assertEquals(v.pay.earned, v.days.sumOf { it.amount })
        // Каждый день графика — ровно его сумма дня.
        (1..20).forEach { d ->
            val date = LocalDate(2026, 10, d)
            assertEquals(v.chart.earned[d] - v.chart.earned[d - 1], SalaryDaysRules.day(v.days, date).sumOf { it.amount })
        }
        val fifth = SalaryDaysRules.day(v.days, LocalDate(2026, 10, 5))
        assertEquals(6, fifth.size)
        assertEquals(rub(5 * 920 + 140), fifth.sumOf { it.amount })
        assertEquals(1, fifth.count { it.kind == DayItemKind.SPT && it.sptId == "c1" })
        // Удержание без даты — в сегодняшний день.
        val todayItems = SalaryDaysRules.day(v.days, today)
        assertEquals(1, todayItems.count { it.kind == DayItemKind.ADJUSTMENT && it.adjustment == 1 })
        // Запланированные не заработаны и в дни не попадают.
        assertTrue(SalaryDaysRules.day(v.days, LocalDate(2026, 10, 28)).isEmpty())
    }

    @Test
    fun dayByTierOfItsMonth() {
        // 120 ПТ сентября — ступень 2, поэтому и 1-е сентября по 960 ₽.
        val v = view(septemberInput())
        assertEquals(2, v.pay.tier)
        assertEquals(rub(4 * 960), SalaryDaysRules.day(v.days, LocalDate(2026, 9, 1)).sumOf { it.amount })
        assertEquals(v.pay.earned, v.days.sumOf { it.amount })
    }

    @Test
    fun periodAcrossTwoMonthsIsSumOfParts() {
        val sep = septemberInput()
        val oct = octoberInput()
        val months = listOf(oct to oct.pay(), sep to sep.pay())
        val period = SalaryDaysRules.period(months, LocalDate(2026, 9, 21), LocalDate(2026, 10, 10), today)
        assertEquals(2, period.parts.size)
        val (p9, p10) = period.parts
        assertEquals(september, p9.month)
        assertEquals(rub(10 * 4 * 960), p9.earned)
        assertEquals(2, p9.tier)
        assertEquals(rub(10 * 5 * 920 + 140), p10.earned)
        assertEquals(1, p10.tier)
        assertEquals(p9.earned + p10.earned, period.total)
        // Разбивка: ПТ сентября и октября — разные строки (960 и 920 ₽), доплата СПТ одна.
        val ptLines = period.breakdown.filter { it.section == BreakdownSection.PT }
        assertEquals(listOf(40 to rub(960), 50 to rub(920)), ptLines.map { it.count to it.unit }.sortedByDescending { it.second })
        assertEquals(1, period.breakdown.single { it.sptClosed > 0 }.sptClosed)
        assertEquals(period.total, period.breakdown.sumOf { it.amount } + period.adjustments.sumOf { it.second.amount })
        assertTrue(period.adjustments.isEmpty())
    }

    @Test
    fun wholeMonthsEqualEarned() {
        val sep = septemberInput()
        val oct = octoberInput()
        val months = listOf(sep to sep.pay(), oct to oct.pay())
        val period = SalaryDaysRules.period(months, LocalDate(2026, 9, 1), LocalDate(2026, 10, 31), today)
        assertTrue(period.parts.all { it.whole })
        assertEquals(sep.pay().earned + oct.pay().earned, period.total)
        assertEquals(2, period.adjustments.size)
        assertEquals(period.total, period.breakdown.sumOf { it.amount } + period.adjustments.sumOf { it.second.amount })
        // Те же ПТ одной ступени в двух месяцах складываются в одну строку.
        val same = SalaryDaysRules.period(listOf(oct to oct.pay()), LocalDate(2026, 10, 1), LocalDate(2026, 10, 31), today)
        assertEquals(99, same.breakdown.single { it.section == BreakdownSection.PT }.count)
    }

    @Test
    fun strings() {
        assertEquals("вт${NBSP}20.10 — 3${THIN}680${NBSP}₽", SalaryFormat.dayTitle(today, rub(3680)))
        assertEquals("10:00 ПТ · ББ", SalaryFormat.dayTraining(LocalTime(10, 0), "ПТ", "ББ"))
        val part = PeriodPart(october, LocalDate(2026, 10, 1), LocalDate(2026, 10, 20), 0, 1, whole = false)
        assertEquals("Октябрь, 1–20", SalaryFormat.periodPart(part))
        assertEquals("Сентябрь", SalaryFormat.periodPart(part.copy(month = september, whole = true)))
        assertEquals("21.09.2026 – 10.10.2026 · 20${NBSP}дней", SalaryFormat.periodRange(LocalDate(2026, 9, 21), LocalDate(2026, 10, 10)))
    }
}
