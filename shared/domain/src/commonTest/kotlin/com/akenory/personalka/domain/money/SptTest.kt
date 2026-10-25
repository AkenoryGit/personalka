package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** СПТ и её закрытие покупкой блока (ТЗ 4.9): примеры 20–22 ТЗ 12. */
class SptTest {

    // СПТ · ББ — 200 ₽, доплата за закрытие — 140 ₽ (ТЗ 3.3).
    private val spt = PayType("spt", TrainingKind.SPT, PayUnit.PER_SESSION, countsTowardPt = false, tierDependent = false)
    private val from = LocalDate(2026, 1, 1)
    private fun rub(r: Int): Long = r * 100L
    private val rates = listOf(PayRate("spt", "bb", from, rub(200), sptCloseBonus = rub(140)))
    private val october = YearMonth(2026, 10)
    private val november = YearMonth(2026, 11)
    private val december = YearMonth(2026, 12)

    private fun d(day: Int, month: Int = 10, year: Int = 2026) = LocalDate(year, month, day)

    private fun case(day: Int, month: Int = 10) = SptRules.open("c", "client", "t", "spt", "bb", d(day, month))

    private fun training(day: Int, month: Int = 10) =
        PayTraining("t", "spt", "bb", LocalDateTime(2026, month, day, 10, 0), 50, TrainingStatus.DONE, listOf(ATTENDED))

    private fun monthPay(month: YearMonth, trainings: List<PayTraining>, cases: List<SptCase>) =
        PayRules.month(month, trainings, listOf(spt), rates, emptyList(), spt = cases)

    @Test
    fun example20_purchaseInSameMonth() {
        val closed = SptRules.close(case(15), d(20))!!
        assertEquals(SptCaseStatus.CLOSED, closed.status)
        val pay = monthPay(october, listOf(training(15)), listOf(closed))
        assertEquals(rub(140), pay.sptBonus)
        assertEquals(rub(340), pay.earned)
        assertEquals(rub(340), pay.accrued)
        assertEquals(rub(340), pay.forecast)
    }

    @Test
    fun example21_bonusGoesToPurchaseMonth() {
        val open = case(31)
        assertEquals(d(30, 11), open.windowUntil)
        val closed = SptRules.close(open, d(1, 11))!!
        val trainings = listOf(training(31))
        assertEquals(rub(200), monthPay(october, trainings, listOf(closed)).earned)
        val nov = monthPay(november, trainings, listOf(closed))
        assertEquals(rub(140), nov.earned)
        assertEquals(rub(140), nov.sptBonus)
    }

    @Test
    fun example22_purchaseAfterWindowCannotBeSaved() {
        val open = case(31)
        assertEquals(PurchaseProblem.AFTER_WINDOW, SptRules.purchaseProblem(open, d(1, 12)))
        assertNull(SptRules.close(open, d(1, 12)))
        assertEquals(SptCaseStatus.NOT_CLOSED, SptRules.statusOn(open, d(1, 12)))
        assertEquals(0, SptRules.bonus(open, rates))
        assertEquals(0, monthPay(december, listOf(training(31)), listOf(open)).sptBonus)
    }

    @Test
    fun windowIsSameDateNextMonthOrItsLastDay() {
        assertEquals(LocalDate(2027, 2, 28), SptRules.windowUntil(LocalDate(2027, 1, 31)))
        assertEquals(LocalDate(2028, 2, 29), SptRules.windowUntil(LocalDate(2028, 1, 31)))
        assertEquals(LocalDate(2027, 1, 15), SptRules.windowUntil(d(15, 12)))
    }

    @Test
    fun purchaseOnLastDayOfWindowClosesAndBeforeSptDoesNot() {
        val open = case(15)
        assertEquals(SptCaseStatus.CLOSED, SptRules.close(open, d(15, 11))?.status)
        assertEquals(PurchaseProblem.BEFORE_SPT, SptRules.purchaseProblem(open, d(14)))
        assertNull(SptRules.close(open, d(14)))
    }

    @Test
    fun statusChangesWhenWindowEnds() {
        val open = case(15)
        assertEquals(SptCaseStatus.OPEN, SptRules.statusOn(open, d(15, 11)))
        assertEquals(SptCaseStatus.NOT_CLOSED, SptRules.statusOn(open, d(16, 11)))
        assertEquals(listOf(open.copy(status = SptCaseStatus.NOT_CLOSED)), SptRules.expired(listOf(open), d(16, 11)))
        assertEquals(emptyList(), SptRules.expired(listOf(open), d(15, 11)))
        val closed = SptRules.close(open, d(20))!!
        assertEquals(SptCaseStatus.CLOSED, SptRules.statusOn(closed, d(1, 12)))
        assertEquals(emptyList(), SptRules.expired(listOf(closed), d(1, 12)))
    }

    @Test
    fun notClosedCaseClosesRetroactivelyWithDateInWindow() {
        val notClosed = case(15).copy(status = SptCaseStatus.NOT_CLOSED)
        val closed = SptRules.close(notClosed, d(10, 11))!!
        assertEquals(SptCaseStatus.CLOSED, SptRules.statusOn(closed, d(1, 12)))
        assertEquals(rub(140), SptRules.bonusIn(november, listOf(closed), rates))
    }

    @Test
    fun reopenDependsOnWindow() {
        val closed = SptRules.close(case(15), d(20))!!
        assertEquals(SptCaseStatus.OPEN, SptRules.reopen(closed, d(1, 11)).status)
        assertNull(SptRules.reopen(closed, d(1, 11)).purchaseDate)
        assertEquals(SptCaseStatus.NOT_CLOSED, SptRules.reopen(closed, d(1, 12)).status)
    }

    @Test
    fun remindsThreeDaysBeforeWindowEnds() {
        val open = case(15)
        assertEquals(false, SptRules.remind(open, d(11, 11)))
        assertTrue(SptRules.remind(open, d(12, 11)))
        assertTrue(SptRules.remind(open, d(15, 11)))
        assertEquals(false, SptRules.remind(open, d(16, 11)))
        assertEquals(false, SptRules.remind(SptRules.close(open, d(20))!!, d(13, 11)))
    }

    @Test
    fun firstPaidPtInWindowOffersToClose() {
        val open = case(15)
        val dates = listOf(d(10), d(22), d(29))
        assertEquals(SptPrompt.CloseNow(open, d(22)), SptRules.prompt(open, d(22), dates))
        assertNull(SptRules.prompt(open, d(29), dates))
        assertNull(SptRules.prompt(SptRules.close(open, d(20))!!, d(22), dates))
    }

    @Test
    fun firstPaidPtAfterWindowAsksAboutPurchase() {
        val notClosed = case(15).copy(status = SptCaseStatus.NOT_CLOSED)
        assertEquals(SptPrompt.AskRetro(notClosed, d(15, 11)), SptRules.prompt(notClosed, d(3, 12), listOf(d(3, 12))))
        val stillOpenInBase = case(15)
        assertEquals(SptPrompt.AskRetro(stillOpenInBase, d(15, 11)), SptRules.prompt(stillOpenInBase, d(3, 12), listOf(d(3, 12))))
    }

    @Test
    fun bonusUsesSptRateOnSptDayAndNoBonusRateIsZero() {
        val newer = rates + PayRate("spt", "bb", d(1, 11), rub(200), sptCloseBonus = rub(150))
        val closed = SptRules.close(case(20), d(5, 11))!!
        assertEquals(rub(140), SptRules.bonus(closed, newer))
        assertEquals(0, SptRules.bonus(closed, listOf(PayRate("spt", "bb", from, rub(200)))))
        assertEquals(0, SptRules.bonus(closed, emptyList()))
    }

    @Test
    fun sptIsPaidWhenCancelledWithCharge() {
        val charged = training(15).copy(status = TrainingStatus.CANCELLED, clients = listOf(CANCELLED_CHARGED))
        assertEquals(rub(200), monthPay(october, listOf(charged), emptyList()).earned)
    }

    @Test
    fun conversionCountsOnlyDecided() {
        val closed = SptRules.close(case(1), d(5))!!
        val notClosed = case(2).copy(status = SptCaseStatus.NOT_CLOSED)
        val expired = case(3)
        val open = case(25)
        val other = case(1, 9)
        val conv = SptRules.conversion(listOf(closed, notClosed, expired, open, other), d(1), d(31), d(10, 11))
        assertEquals(SptConversion(closed = 1, notClosed = 2), conv)
        assertEquals(33, conv.percent)
        assertNull(SptConversion(0, 0).percent)
    }
}
