package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** График накопления по дням и «Месяц сверен» (ТЗ 5.12, 4.1, шаг 3.7б). */
class SalaryChartTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
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
    private fun pts(count: Int, day: Int, status: TrainingStatus = TrainingStatus.DONE) = List(count) {
        PayTraining(
            "t${seq++}", "pt", "bb", LocalDateTime(2026, 10, day, 10, 0), 50, status,
            listOf(if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED),
        )
    }

    private fun build(
        trainings: List<PayTraining>,
        on: LocalDate = today,
        spt: List<SptCase> = emptyList(),
        adjustments: List<PayAdjustment> = emptyList(),
        plan: Long? = null,
    ): SalaryMonth {
        val input = MonthInput(october, trainings, listOf(pt, sptType), rates, tiers, spt = spt, adjustments = adjustments)
        return SalaryMonthRules.build(input, listOf(scheme), plan?.let { mapOf(october to it) }.orEmpty(), null, on, "pt", "bb")
    }

    /** По 5 ПТ в дни 1–19 (95), сегодня 20-го ещё 4, по расписанию 51 на 28-е. */
    private fun october() = (1..19).flatMap { pts(5, it) } + pts(4, 20) + pts(51, 28, TrainingStatus.PLANNED)

    @Test
    fun earnedLineEndsAtEarned() {
        val view = build(october(), plan = rub(210_000))
        val chart = view.chart
        assertEquals(31, chart.days)
        assertEquals(20, chart.factDay)
        assertEquals(21, chart.earned.size)
        assertEquals(0L, chart.earned.first())
        // 99 ПТ — ступень 1 на весь месяц, и в прошлых днях тоже.
        assertEquals(rub(5 * 920), chart.earned[1])
        assertEquals(view.pay.earned, chart.earned.last())
        assertEquals(rub(99 * 920), chart.earned.last())
    }

    @Test
    fun forecastFromTodayToForecast() {
        val view = build(october(), plan = rub(210_000))
        val chart = view.chart
        assertEquals(12, chart.forecast.size)
        assertEquals(chart.earned.last(), chart.forecast.first())
        // До 28-го запланированных нет — линия ровная; на 28-м весь рост, и ступень 2 на все 150 ПТ.
        assertEquals(chart.forecast.first(), chart.forecast[27 - 20])
        assertEquals(view.pay.forecast, chart.forecast.last())
        assertEquals(rub(150 * 960), chart.forecast.last())
        assertEquals(rub(210_000), chart.pace(31))
        assertEquals(rub(210_000) * 10 / 31, chart.pace(10))
    }

    @Test
    fun sptAndAdjustmentsByDay() {
        val case = SptCase("c1", "kid", "spt-old", "spt", "bb", LocalDate(2026, 9, 30), purchaseDate = LocalDate(2026, 10, 5), status = SptCaseStatus.CLOSED)
        val adjustments = listOf(
            PayAdjustment(rub(1200), date = LocalDate(2026, 10, 11)),
            PayAdjustment(rub(300)),
            PayAdjustment(-rub(100), date = LocalDate(2026, 9, 28)),
        )
        val view = build(pts(1, 3), spt = listOf(case), adjustments = adjustments)
        val e = view.chart.earned
        assertEquals(-rub(100), e[1])
        assertEquals(rub(820), e[3])
        assertEquals(rub(960), e[5])
        assertEquals(rub(2160), e[11])
        assertEquals(rub(2460), e[20])
        assertEquals(view.pay.earned, e.last())
    }

    @Test
    fun pastAndFutureMonths() {
        val past = build(october(), on = LocalDate(2026, 11, 3)).chart
        assertEquals(31, past.factDay)
        assertEquals(32, past.earned.size)
        assertTrue(past.forecast.isEmpty())

        val future = build(pts(10, 5, TrainingStatus.PLANNED), on = LocalDate(2026, 9, 15))
        assertEquals(listOf(0L), future.chart.earned)
        assertEquals(32, future.chart.forecast.size)
        assertEquals(0L, future.chart.forecast[4])
        assertEquals(future.pay.forecast, future.chart.forecast[5])
        assertFalse(future.canClose)
    }

    @Test
    fun forecastWithoutPlannedIsEven() {
        // Запланированных нет — прогноз равен заработанному, пунктир ровный до конца месяца.
        val chart = build(pts(2, 3)).chart
        assertEquals(chart.earned.last(), chart.forecast.last())
    }

    @Test
    fun gridStepAndLabels() {
        assertEquals(rub(100_000), SalaryChartRules.step(rub(210_000)))
        assertEquals(rub(10_000), SalaryChartRules.step(rub(30_000)))
        assertEquals(rub(1_000), SalaryChartRules.step(rub(3_500)))
        assertEquals(rub(2_000), SalaryChartRules.step(rub(7_000)))
        assertEquals("100${NBSP}тыс.", SalaryFormat.axis(rub(100_000)))
        assertEquals("1${THIN}000${NBSP}₽", SalaryFormat.axis(rub(1_000)))
        val chart = build(october(), plan = rub(210_000)).chart
        assertEquals(listOf(rub(100_000), rub(200_000)), chart.ticks)
    }

    @Test
    fun closedMonths() {
        val sep = YearMonth(2026, 9)
        // Записи — по возрастанию изменения: у октября последняя снимает сверку.
        val records = listOf(sep to true, october to true, october to false)
        assertEquals(setOf(sep), SalaryMonthRules.closedMonths(records))
        assertEquals("Октябрь сверен", SalaryFormat.closedMessage(october))
        assertEquals("После сверки правки тренировок октября спросят подтверждение", SalaryFormat.closeNote(october))
        assertEquals(
            "Октябрь${NBSP}2026 отмечен в «Зарплате» как сверенный. Правка этой тренировки изменит его заработок.",
            TrainingCardFormat.closedText(october),
        )
    }
}
