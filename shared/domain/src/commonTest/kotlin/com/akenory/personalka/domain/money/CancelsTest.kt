package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ConductedBy
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
import kotlin.test.assertNull

/** Прогноз месяца с обычной долей отмен ПТ и сплитов и часы работы в неделю (ТЗ 4.12, 5.12, шаг 3.9щ). */
class CancelsTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val october = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val split = PayType("split", TrainingKind.SPLIT, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true, splitFallbackTypeId = "pt")
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, split, group, duty)
    private val rates = listOf(
        PayRate("pt", null, jan, rub(920), rub(960), rub(1000)),
        PayRate("split", null, jan, rub(1550), rub(1600)),
        PayRate("group", null, jan, rub(270)),
        PayRate("duty", null, jan, rub(200)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt", "split")))

    private var seq = 0
    private fun t(
        type: String = "pt",
        status: TrainingStatus = TrainingStatus.DONE,
        date: LocalDate = LocalDate(2026, 10, 15),
        hour: Int = 10,
        minute: Int = 0,
        minutes: Int = 50,
        clients: List<ParticipationStatus> = listOf(if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED),
        byUs: Boolean = false,
        conductedBy: ConductedBy = ConductedBy.SELF,
    ) = PayTraining(
        "t${seq++}", type, "bb", LocalDateTime(date.year, date.month, date.day, hour, minute), minutes, status, clients,
        conductedBy = conductedBy, byUs = byUs,
    )

    private fun cancelled(date: LocalDate = LocalDate(2026, 10, 15), byUs: Boolean = false) =
        t(status = TrainingStatus.CANCELLED, date = date, clients = listOf(CANCELLED), byUs = byUs)

    private fun pts(count: Int, status: TrainingStatus = TrainingStatus.DONE, day: Int = 15) =
        List(count) { t(status = status, date = LocalDate(2026, 10, day)) }

    private fun pay(trainings: List<PayTraining>, cancels: CancelShare?) =
        MonthInput(october, trainings, types, rates, tiers, cancels = cancels).pay()

    @Test
    fun shareOfLastEightWeeks() {
        val history = pts(30) + List(8) { cancelled() } + listOf(
            // Отмена со списанием оплачена — не потеря; по инициативе тренера и клуба — не в счёт.
            t(status = TrainingStatus.CANCELLED, clients = listOf(CANCELLED_CHARGED)),
            t(type = "split", clients = listOf(ATTENDED, ATTENDED)),
            cancelled(byUs = true),
            cancelled(byUs = true),
            // Старше 8 недель, сегодняшняя, запланированная и группа — не в счёт.
            cancelled(date = LocalDate(2026, 8, 24)),
            cancelled(date = today),
            t(status = TrainingStatus.PLANNED, date = LocalDate(2026, 10, 25)),
            t(type = "group", status = TrainingStatus.CANCELLED, clients = listOf(CANCELLED, CANCELLED)),
        )
        val share = CancelRules.share(history, types, today)
        assertEquals(CancelShare(8, 40), share)
        assertEquals(20, share?.percent)
        // Первый день окна — 25.08 (за 56 дней до 20.10).
        assertEquals(CancelShare(9, 41), CancelRules.share(history + cancelled(date = LocalDate(2026, 8, 25)), types, today))
    }

    @Test
    fun noShareWithoutHistoryOrCancels() {
        assertNull(CancelRules.share(pts(8) + cancelled(), types, today))
        assertNull(CancelRules.share(pts(40), types, today))
    }

    @Test
    fun plannedPtAreDiscounted() {
        // 90 проведено, 20 по расписанию: по расписанию 110 — ступень 2, 960 ₽. С отменами 20 % — ждём 16 из 20,
        // счётчик 106 — та же ступень 2: 90 × 960 + 20 × 960 × 0,8 = 101 760 ₽.
        val trainings = pts(90) + pts(20, TrainingStatus.PLANNED, day = 25)
        val m = pay(trainings, CancelShare(8, 40))
        assertEquals(rub(105_600), m.scheduled)
        assertEquals(rub(101_760), m.forecast)
        assertEquals(2, m.likelyTier)
        assertEquals(rub(82_800), m.earned)
        // Без доли — как раньше.
        val plain = pay(trainings, null)
        assertEquals(rub(105_600), plain.forecast)
        assertEquals(plain.forecast, plain.scheduled)
    }

    @Test
    fun cancelsCanLowerTheTier() {
        // По расписанию 102 ПТ — ступень 2; с отменами ждём 10 из 12, счётчик 100 — ступень 1 на весь месяц.
        val m = pay(pts(90) + pts(12, TrainingStatus.PLANNED, day = 25), CancelShare(8, 40))
        assertEquals(2, m.forecastTier)
        assertEquals(1, m.likelyTier)
        assertEquals(rub(97_920), m.scheduled)
        assertEquals(rub(82_800) + rub(8_832), m.forecast)
    }

    @Test
    fun groupsAreNotDiscounted() {
        // Группа ждёт среднее пришедших (4.12) — в нём отмены детей уже есть.
        val planned = t(type = "group", status = TrainingStatus.PLANNED, date = LocalDate(2026, 10, 25), clients = List(5) { EXPECTED })
        val m = pay(pts(10) + planned + t(type = "split", status = TrainingStatus.PLANNED, clients = listOf(EXPECTED, EXPECTED)), CancelShare(1, 4))
        // 10 × 920 + 5 × 270 + 1 550 × 0,75.
        assertEquals(rub(9_200 + 1_350) + rub(1_550) * 3 / 4, m.forecast)
        assertEquals(rub(9_200 + 1_350 + 1_550), m.scheduled)
    }

    @Test
    fun shortfallCountsFromRealForecast() {
        val trainings = pts(50) + pts(20, TrainingStatus.PLANNED, day = 25)
        val input = MonthInput(october, trainings, types, rates, tiers, cancels = CancelShare(1, 4))
        // По расписанию 70 × 920 = 64 400 ₽, с отменами 50 × 920 + 15 × 920 = 59 800 ₽: до 64 400 не хватает 4 600 ₽.
        // Добавочные ПТ тоже отменяются: каждая даёт 690 ₽, нужно 7 (6 — 4 140 ₽).
        val s = SalaryPlan.shortfall(input, rub(64_400), "pt", "bb", today)
        assertEquals(Shortfall(rub(4_600), 7), s)
    }

    @Test
    fun scheduledLine() {
        val m = pay(pts(90) + pts(20, TrainingStatus.PLANNED, day = 25), CancelShare(8, 40))
        assertEquals("по расписанию ≈${NBSP}105${THIN}600$NBSP₽, обычно отменяется 20$NBSP%", SalaryFormat.scheduled(m))
        assertNull(SalaryFormat.scheduled(pay(pts(90), CancelShare(8, 40))))
        assertNull(SalaryFormat.scheduled(pay(pts(90) + pts(20, TrainingStatus.PLANNED, day = 25), null)))
    }

    @Test
    fun workHoursPerWeek() {
        // 20.10: 20 дней месяца. 40 часовых тренировок в разные дни и часы — 40 ч, 14 ч в неделю.
        val hours = List(40) { t(date = LocalDate(2026, 10, 1 + it / 4), hour = 8 + it % 4 * 2, minutes = 60) }
        val w = CancelRules.work(hours, october, today, rub(46_000))
        assertEquals(WorkHours(14, rub(1_150)), w)
        assertEquals("Работа: 14${NBSP}ч в неделю, ≈${NBSP}1${THIN}150$NBSP₽ за час", SalaryFormat.work(w!!))
        // Пересекающиеся считаются один раз; запланированные, отменённые и проведённые коллегой — не работа.
        val overlap = listOf(
            t(hour = 10, minutes = 60),
            t(hour = 10, minute = 30, minutes = 60),
            t(hour = 10, minute = 40, minutes = 10),
            t(status = TrainingStatus.PLANNED, date = LocalDate(2026, 10, 25), minutes = 600),
            cancelled(),
            t(type = "group", conductedBy = ConductedBy.COLLEAGUE, hour = 15, minutes = 600),
        )
        // Сентябрь прошёл — весь месяц, 30 дней: 1,5 ч × 7 / 30 = 0,35 ч в неделю → 0; за час — 1 000 ₽ × 60 / 90.
        val sep = overlap.map { it.copy(start = LocalDateTime(2026, 9, 15, it.start.hour, it.start.minute)) }
        assertEquals(WorkHours(0, rub(1_000) * 60 / 90 + 1), CancelRules.work(sep, YearMonth(2026, 9), today, rub(1_000)))
        assertNull(CancelRules.work(hours, YearMonth(2026, 11), today, 0))
        assertNull(CancelRules.work(emptyList(), october, today, 0))
    }
}
