package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Счётчик ПТ, ступень месяца и оплата тренировки (ТЗ 4.6, 4.7): примеры 1–7, 23–25 ТЗ 12. */
class PayRulesTest {

    // Ставки Мастер тренера (ТЗ 3.3): ПТ · ББ 920 / 960 / 1 000, сплит · ББ 1 550 / 1 600 / 1 650, ПТ · ДБ 830 / 860,
    // СПТ 200, зал — как ББ. Ставки грудничкового и гидрореабилитации придуманы: важно, что берётся ступень 2.
    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val split = PayType("split", TrainingKind.SPLIT, PayUnit.PER_SESSION, true, true, splitFallbackTypeId = "pt")
    private val spt = PayType("spt", TrainingKind.SPT, PayUnit.PER_SESSION, countsTowardPt = false, tierDependent = false)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val infant = PayType("infant", TrainingKind.PERSONAL, PayUnit.PER_SESSION, true, true)
    private val hydro = PayType("hydro", TrainingKind.PERSONAL, PayUnit.PER_SESSION, true, true)
    private val types = listOf(pt, split, spt, group, duty, infant, hydro)

    private val from = LocalDate(2026, 1, 1)
    private fun rub(r: Int): Long = r * 100L
    private val rates = listOf(
        PayRate("pt", "bb", from, rub(920), rub(960), rub(1000)),
        PayRate("pt", "gym", from, rub(920), rub(960), rub(1000)),
        PayRate("pt", "db", from, rub(830), rub(860)),
        PayRate("split", "bb", from, rub(1550), rub(1600), rub(1650)),
        PayRate("spt", null, from, rub(200)),
        PayRate("group", "bb", from, rub(270)),
        PayRate("duty", null, from, rub(200)),
        PayRate("infant", "bb", from, rub(600), rub(640), rub(680)),
        PayRate("hydro", "bb", from, rub(1000), rub(1040), rub(1080)),
    )
    private val scheme = TierScheme(from, ptTypeIds = setOf("pt", "split", "infant", "hydro"))
    private val october = YearMonth(2026, 10)

    private var seq = 0
    private fun t(
        type: String,
        place: String = "bb",
        vararg clients: ParticipationStatus = arrayOf(ATTENDED),
        status: TrainingStatus = TrainingStatus.DONE,
        day: Int = 15,
        minutes: Int = 50,
        half: Boolean = false,
    ) = PayTraining(
        "t${seq++}", type, place, LocalDateTime(2026, 10, day, 10, 0), minutes, status, clients.toList(), half,
    )

    private fun many(count: Int, type: String = "pt", place: String = "bb") = List(count) { t(type, place) }

    private fun month(trainings: List<PayTraining>) = PayRules.month(october, trainings, types, rates, listOf(scheme))

    private fun single(training: PayTraining, tier: Int = 1) =
        PayRules.pay(training, types.associateBy { it.id }, rates, tier)

    @Test
    fun example1_100PtIsTier1() {
        val m = month(many(100))
        assertEquals(100, m.ptCount)
        assertEquals(1, m.tier)
        assertEquals(rub(92_000), m.earned)
    }

    @Test
    fun example2_101PtMovesWholeMonthToTier2() {
        val m = month(many(101))
        assertEquals(2, m.tier)
        assertEquals(rub(96_960), m.earned)
    }

    @Test
    fun example3_130Pt() {
        assertEquals(rub(124_800), month(many(130)).earned)
    }

    @Test
    fun example4_151PtIsTier3() {
        val m = month(many(151))
        assertEquals(3, m.tier)
        assertEquals(rub(151_000), m.earned)
    }

    @Test
    fun example5_mixedMonth() {
        val list = many(95) +
            t("pt", clients = arrayOf(CANCELLED_CHARGED), status = TrainingStatus.CANCELLED) +
            t("pt", clients = arrayOf(CANCELLED), status = TrainingStatus.CANCELLED) +
            many(3, place = "db") +
            List(3) { t("split", clients = arrayOf(ATTENDED, ATTENDED)) } +
            List(2) { t("spt") }
        val m = month(list)
        assertEquals(102, m.ptCount)
        assertEquals(2, m.tier)
        assertEquals(rub(99_940), m.earned)
    }

    @Test
    fun example6_splitWithOneClientAsPtOrHalf() {
        val asPt = single(t("split", clients = arrayOf(ATTENDED, CANCELLED)))
        assertEquals(rub(920), asPt.amount)
        assertEquals(SplitMark.AS_PT, asPt.splitMark)
        val half = single(t("split", clients = arrayOf(ATTENDED, CANCELLED), half = true))
        assertEquals(rub(775), half.amount)
        assertEquals(SplitMark.HALF, half.splitMark)
        assertEquals(1, PayRules.ptCount(listOf(t("split", clients = arrayOf(ATTENDED, CANCELLED))), scheme.ptTypeIds))
    }

    @Test
    fun example7_splitSecondChargedIsFullSplit() {
        val pay = single(t("split", clients = arrayOf(ATTENDED, CANCELLED_CHARGED)))
        assertEquals(rub(1550), pay.amount)
        assertEquals(null, pay.splitMark)
    }

    @Test
    fun example23_currentAndForecastTier() {
        val planned = List(15) { t("pt", clients = arrayOf(EXPECTED), status = TrainingStatus.PLANNED, day = 28) }
        val m = month(many(91) + planned)
        assertEquals(91, m.ptCount)
        assertEquals(1, m.tier)
        assertEquals(rub(83_720), m.earned)
        assertEquals(106, m.forecastPtCount)
        assertEquals(2, m.forecastTier)
        assertEquals(rub(101_760), m.forecast)
    }

    @Test
    fun example24_infantAndHydroCountAndGetTier2() {
        val m = month(many(100) + t("hydro") + t("infant", minutes = 30))
        assertEquals(102, m.ptCount)
        assertEquals(2, m.tier)
        assertEquals(rub(100 * 960 + 1040 + 640), m.earned)
    }

    @Test
    fun example25_gymCountsAndPaysAsBb() {
        val m = month(many(98) + many(3, place = "gym"))
        assertEquals(101, m.ptCount)
        assertEquals(2, m.tier)
        assertEquals(rub(96_960), m.earned)
    }

    @Test
    fun tierTakesSchemeOfFirstDayAndDisabledIsAlways1() {
        val later = TierScheme(LocalDate(2026, 10, 2), t2From = 50, ptTypeIds = setOf("pt"))
        assertEquals(1, PayRules.month(october, many(60), types, rates, listOf(scheme, later)).tier)
        val off = TierScheme(from, enabled = false, ptTypeIds = setOf("pt"))
        assertEquals(1, PayRules.month(october, many(160), types, rates, listOf(off)).tier)
        assertEquals(1, PayRules.month(october, many(160), types, rates, emptyList()).tier)
        // Нет версии ступеней — счётчик по отметкам «Считать как ПТ» типов.
        assertEquals(160, PayRules.month(october, many(160), types, rates, emptyList()).ptCount)
    }

    @Test
    fun ptListChangeIsPerVersion() {
        val noSplit = TierScheme(LocalDate(2026, 10, 1), ptTypeIds = setOf("pt"))
        val m = PayRules.month(october, many(100) + t("split", clients = arrayOf(ATTENDED, ATTENDED)), types, rates, listOf(scheme, noSplit))
        assertEquals(100, m.ptCount)
        assertEquals(1, m.tier)
    }

    @Test
    fun monthIsByStartDateAndOtherMonthsIgnored() {
        val sept = PayTraining("s", "pt", "bb", LocalDateTime(2026, 9, 30, 23, 30), 50, TrainingStatus.DONE, listOf(ATTENDED))
        val m = month(many(2) + sept)
        assertEquals(2, m.ptCount)
        assertEquals(2, m.trainings.size)
    }

    @Test
    fun noRateIsZeroAndFlagged() {
        val m = month(many(1) + t("pt", place = "pool"))
        // Для «pool» своей ставки ПТ нет, ставки «для любого места» — тоже.
        assertEquals(rub(920), m.earned)
        assertEquals(1, m.missingRate.size)
        assertTrue(single(t("split", clients = arrayOf(ATTENDED)), tier = 1).amount > 0)
    }

    @Test
    fun cancelledAndUnpaidPayNothing() {
        assertEquals(0, single(t("pt", clients = arrayOf(CANCELLED), status = TrainingStatus.CANCELLED)).amount)
        assertEquals(0, single(t("split", clients = arrayOf(CANCELLED, CANCELLED), status = TrainingStatus.CANCELLED)).amount)
        assertEquals(0, single(t("pt", clients = arrayOf(EXPECTED), status = TrainingStatus.PLANNED)).amount)
        assertEquals(0, single(t("duty", clients = emptyArray(), status = TrainingStatus.CANCELLED, minutes = 240)).amount)
    }

    @Test
    fun groupDutyAndRounding() {
        assertEquals(rub(3 * 270), single(t("group", clients = arrayOf(ATTENDED, ATTENDED, CANCELLED_CHARGED, CANCELLED))).amount)
        assertEquals(rub(800), single(t("duty", clients = emptyArray(), minutes = 240)).amount)
        assertEquals(rub(500), single(t("duty", clients = emptyArray(), minutes = 150)).amount)
        // 200 ₽ за час × 7 мин = 23,333… ₽ → 23,33 ₽; половина копейки — вверх.
        assertEquals(2333, PayRules.perHour(rub(200), 7))
        assertEquals(51, PayRules.half(101))
    }
}
