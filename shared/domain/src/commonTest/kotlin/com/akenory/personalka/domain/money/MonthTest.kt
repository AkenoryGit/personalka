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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Итоги месяца, выплаты, план и прогноз (ТЗ 4.10–4.12): примеры 17, 18 и 26 ТЗ 12. */
class MonthTest {

    private fun rub(r: Int): Long = r * 100L
    private val from = LocalDate(2026, 1, 1)
    private val october = YearMonth(2026, 10)

    // Схема Дмитрия: половина официально с 01.03.2026, аванс 25-го (ТЗ 3.2).
    private val half = PayScheme(LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL, advanceDay = 25, advanceDefault = 1_354_650)
    private val ip = PayScheme(from, PaySchemeKind.IP)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, group)
    private val rates = listOf(
        PayRate("pt", "bb", from, rub(920), rub(960), rub(1000)),
        PayRate("group", "bb", from, rub(270)),
    )
    private val tiers = listOf(TierScheme(from, ptTypeIds = setOf("pt")))

    private var seq = 0
    private fun t(
        type: String = "pt",
        status: TrainingStatus = TrainingStatus.DONE,
        day: Int = 15,
        clients: Int = 1,
        groupId: String? = null,
        month: Int = 10,
    ) = PayTraining(
        "t${seq++}", type, "bb", LocalDateTime(2026, month, day, 10, 0), 50, status,
        List(clients) { if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED }, groupId = groupId,
    )

    private fun many(count: Int, status: TrainingStatus = TrainingStatus.DONE, day: Int = 15) = List(count) { t(status = status, day = day) }

    private fun input(trainings: List<PayTraining>, adjustments: List<PayAdjustment> = emptyList()) =
        MonthInput(october, trainings, types, rates, tiers, adjustments = adjustments)

    @Test
    fun example17_halfOfficialWithAdvance() {
        val p = Payouts.of(october, rub(120_000), listOf(PayScheme(from, PaySchemeKind.NONE), half), advanceActual = 1_354_650)
        assertEquals(PaySchemeKind.HALF_OFFICIAL, p.kind)
        assertEquals(rub(60_000), p.official)
        assertEquals(rub(7_800), p.ndfl)
        assertEquals(rub(52_200), p.card)
        assertEquals(rub(60_000), p.cash)
        assertEquals(1_354_650, p.advance)
        assertEquals(3_865_350, p.cardRest)
        assertEquals(
            listOf(
                Payout(PayoutKind.ADVANCE, LocalDate(2026, 10, 25), 1_354_650),
                Payout(PayoutKind.CARD_REST, LocalDate(2026, 11, 10), 3_865_350),
                Payout(PayoutKind.CASH, LocalDate(2026, 11, 10), rub(60_000)),
            ),
            p.payouts,
        )
        val month = MonthPay(october, 0, 1, 0, 1, rub(120_000), rub(120_000), emptyList())
        assertEquals(rub(112_200), Payouts.totals(month, listOf(half), advanceActual = 1_354_650).onHand)
    }

    @Test
    fun example18_ipWholeSumOnTenthOfNextMonth() {
        val p = Payouts.of(october, rub(120_000), listOf(ip))
        assertEquals(0, p.ndfl)
        assertEquals(0, p.advance)
        assertEquals(listOf(Payout(PayoutKind.WHOLE, LocalDate(2026, 11, 10), rub(120_000))), p.payouts)
        assertEquals(rub(120_000), p.card + p.cash)
    }

    @Test
    fun schemeOfMonthIsVersionOnFirstDay() {
        // Половина официально с 01.03 — февраль ещё без схемы (NONE), декабрь выплачивается 10.01.
        assertEquals(PaySchemeKind.NONE, Payouts.of(YearMonth(2026, 2), rub(1_000), listOf(half)).kind)
        assertEquals(LocalDate(2027, 1, 10), Payouts.salaryDate(YearMonth(2026, 12), half))
        assertEquals(LocalDate(2026, 2, 28), Payouts.advanceDate(YearMonth(2026, 2), half.copy(advanceDay = 31)))
    }

    @Test
    fun advanceDefaultUntilActualAndNegativeRest() {
        assertEquals(1_354_650, Payouts.of(october, rub(120_000), listOf(half)).advance)
        // Начислено мало, аванс больше суммы на карту — остаток отрицательный, с предупреждением.
        val small = Payouts.of(october, rub(20_000), listOf(half))
        assertEquals(870_000, small.card)
        assertEquals(-484_650, small.cardRest)
        assertTrue(small.advanceExceedsCard)
        assertFalse(Payouts.of(october, rub(120_000), listOf(half)).advanceExceedsCard)
    }

    @Test
    fun ndflRoundsToKopeck() {
        // 1 000,03 ₽: официальная 500,02 (500,015 вверх), НДФЛ 65,00 (65,0026), на карту 435,02, наличные 500,01.
        val p = Payouts.of(october, 100_003, listOf(half))
        assertEquals(50_002, p.official)
        assertEquals(6_500, p.ndfl)
        assertEquals(43_502, p.card)
        assertEquals(50_001, p.cash)
    }

    @Test
    fun askAdvanceOnAdvanceDayUntilEntered() {
        assertFalse(Payouts.askAdvance(LocalDate(2026, 10, 24), listOf(half), null))
        assertTrue(Payouts.askAdvance(LocalDate(2026, 10, 25), listOf(half), null))
        assertTrue(Payouts.askAdvance(LocalDate(2026, 10, 27), listOf(half), null))
        assertFalse(Payouts.askAdvance(LocalDate(2026, 10, 25), listOf(half), 1_354_650))
        assertFalse(Payouts.askAdvance(LocalDate(2026, 10, 25), listOf(ip), null))
    }

    @Test
    fun adjustmentsGoToEarnedAndOnlyClubOnesToAccrued() {
        val m = input(many(10), listOf(PayAdjustment(rub(1_000)), PayAdjustment(rub(-300), inClubAccrual = false))).pay()
        assertEquals(rub(9_200 + 700), m.earned)
        assertEquals(rub(9_200 + 1_000), m.accrued)
        assertEquals(rub(9_200 + 700), m.forecast)
        assertEquals(rub(700), m.adjustments)
    }

    @Test
    fun onHandIncludesSettlement() {
        val month = MonthPay(october, 0, 1, 0, 1, rub(10_000), rub(10_000), emptyList(), settlements = mapOf("lena" to 126_225, "oleg" to -10_000))
        val totals = Payouts.totals(month, listOf(ip))
        assertEquals(116_225, totals.settlement)
        assertEquals(rub(10_000) + 116_225, totals.onHand)
    }

    @Test
    fun example26_shortfallCountsTierJump() {
        val trainings = many(100) + many(45, TrainingStatus.PLANNED, day = 28)
        val m = input(trainings).pay()
        assertEquals(rub(139_200), m.forecast)
        val s = SalaryPlan.shortfall(input(trainings), rub(150_000), "pt", "bb", LocalDate(2026, 10, 25))!!
        assertEquals(rub(10_800), s.missing)
        assertEquals(6, s.pts)
        assertEquals(3, s.newTier)
        assertEquals(151, s.tierFrom)
        assertEquals(
            "До плана не хватает 10${THIN}800$NBSP₽ — это 6${NBSP}ПТ: со${NBSP}151-й весь месяц пойдёт по ступени 3",
            SalaryFormat.shortfall(s),
        )
    }

    @Test
    fun shortfallWithoutTierJumpAndWhenPlanReached() {
        // 120 ПТ по 960 = 115 200; до 120 000 — 4 800, это 5 ПТ без новой ступени.
        val s = SalaryPlan.shortfall(input(many(120)), rub(120_000), "pt", "bb", LocalDate(2026, 10, 25))!!
        assertEquals(5, s.pts)
        assertNull(s.newTier)
        assertEquals("До плана не хватает 4${THIN}800$NBSP₽ — это 5${NBSP}ПТ", SalaryFormat.shortfall(s))
        assertNull(SalaryPlan.shortfall(input(many(120)), rub(115_200), "pt", "bb", LocalDate(2026, 10, 25)))
        assertNull(SalaryPlan.shortfall(input(many(120)), null, "pt", "bb", LocalDate(2026, 10, 25)))
    }

    @Test
    fun shortfallWithoutRateHasNoCount() {
        val s = SalaryPlan.shortfall(input(many(10)), rub(50_000), "pt", "db", LocalDate(2026, 10, 25))!!
        assertNull(s.pts)
        assertEquals("До плана не хватает 40${THIN}800$NBSP₽", SalaryFormat.shortfall(s))
        assertFailsWith<IllegalArgumentException> { SalaryPlan.shortfall(input(many(10)), rub(1), "pt", "bb", LocalDate(2026, 11, 1)) }
    }

    @Test
    fun planOfPreviousMonthAndProgress() {
        val plans = mapOf(YearMonth(2026, 8) to rub(140_000), YearMonth(2026, 9) to rub(150_000))
        assertEquals(rub(150_000), SalaryPlan.planFor(october, plans))
        assertEquals(rub(140_000), SalaryPlan.planFor(YearMonth(2026, 8), plans))
        assertNull(SalaryPlan.planFor(YearMonth(2026, 7), plans))
        assertEquals(66, SalaryPlan.percent(rub(99_999), rub(150_000)))
        assertEquals(99, SalaryPlan.percent(rub(149_999), rub(150_000)))
        assertEquals(100, SalaryPlan.percent(rub(150_000), rub(150_000)))
        assertNull(SalaryPlan.percent(rub(1), null))
    }

    @Test
    fun forecastGroupExpectsAverageOfLastFourDone() {
        // Прошлые проведённые группы: 12 (давно), 10, 8, 9, 11 — последние 4 дают (10 + 8 + 9 + 11) / 4 = 9,5 → 10 детей.
        val history = listOf(
            t("group", clients = 12, groupId = "g", month = 9, day = 1),
            t("group", clients = 10, groupId = "g", month = 9, day = 8),
            t("group", clients = 8, groupId = "g", month = 9, day = 15),
            t("group", clients = 9, groupId = "g", month = 9, day = 22),
            t("group", clients = 11, groupId = "g", month = 9, day = 29),
            t("group", clients = 3, groupId = "other", month = 9, day = 30),
        )
        val planned = t("group", TrainingStatus.PLANNED, day = 20, clients = 14, groupId = "g")
        val m = input(history + planned).pay()
        assertEquals(0, m.earned)
        assertEquals(10 * rub(270), m.forecast)
        // Без истории — состав группы.
        val fresh = t("group", TrainingStatus.PLANNED, day = 20, clients = 14, groupId = "new")
        assertEquals(14 * rub(270), input(listOf(fresh)).pay().forecast)
    }
}
