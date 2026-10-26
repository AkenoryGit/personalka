package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.RuText.MINUS
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Экран «Зарплата» (ТЗ 5.12): месяц, ступень, разбивка 4.10 и выплаты — по цифрам макета октября (шаг 0.6). */
class SalaryMonthTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val october = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val split = PayType("split", TrainingKind.SPLIT, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true, splitFallbackTypeId = "pt")
    private val spt = PayType("spt", TrainingKind.SPT, PayUnit.PER_SESSION, countsTowardPt = false, tierDependent = false)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, split, spt, group, duty)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("pt", "db", jan, rub(830), rub(860)),
        PayRate("split", "bb", jan, rub(1550), rub(1600)),
        PayRate("spt", null, jan, rub(200), sptCloseBonus = rub(140)),
        PayRate("group", null, jan, rub(270)),
        PayRate("duty", null, jan, rub(200)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt", "split")))
    private val half = PayScheme(LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL, advanceDay = 25, advanceDefault = 1_354_650)
    private val owners = OwnerSchemes(listOf(half.owner()), mapOf("lena" to listOf(OwnerScheme.of(jan, PaySchemeKind.HALF_OFFICIAL))))

    private var seq = 0
    private fun t(
        type: String = "pt",
        place: String = "bb",
        status: TrainingStatus = TrainingStatus.DONE,
        day: Int = 15,
        clients: Int = 1,
        minutes: Int = 50,
        owner: Owner = Owner.SELF,
        ownerColleagueId: String? = null,
        conductedBy: ConductedBy = ConductedBy.SELF,
        colleagueId: String? = null,
        coverFor: String? = null,
    ) = PayTraining(
        "t${seq++}", type, place, LocalDateTime(2026, 10, day, 10, 0), minutes, status,
        List(clients) { if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED },
        owner = owner, ownerColleagueId = ownerColleagueId, conductedBy = conductedBy, colleagueId = colleagueId, coverFor = coverFor,
    )

    private fun pts(count: Int, status: TrainingStatus = TrainingStatus.DONE, day: Int = 15, place: String = "bb") =
        List(count) { t(status = status, day = day, place = place) }

    private fun input(trainings: List<PayTraining>, spt: List<SptCase> = emptyList(), adjustments: List<PayAdjustment> = emptyList()) =
        MonthInput(october, trainings, types, rates, tiers, owners, spt, adjustments)

    private fun build(input: MonthInput, plans: Map<YearMonth, Long> = emptyMap(), advance: Long? = null, on: LocalDate = today) =
        SalaryMonthRules.build(input, listOf(half), plans, advance, on, "pt", "bb")

    @Test
    fun tierHintsAsInMockup() {
        // Макет: проведено 94, по расписанию ещё 51 → 145; до 101 ещё 7 ПТ — это +40 ₽ к каждой ПТ.
        val view = build(input(pts(94) + pts(51, TrainingStatus.PLANNED, day = 28)))
        val tier = view.tiers!!
        assertEquals(94, tier.count)
        assertEquals(145, tier.forecastCount)
        assertEquals(1, tier.tier)
        assertEquals(2, tier.forecastTier)
        assertEquals(101, tier.nextFrom)
        assertEquals(7, tier.toNext)
        assertEquals(rub(40), tier.perPt)
        // Ещё 6 ПТ сверх расписания — 151, весь месяц по ступени 3: 151 × 1 000 − 145 × 960 = 11 800 ₽, из них 6 000 за сами ПТ.
        assertEquals(TierBeyond(6, 151, 3, rub(11_800), rub(6_000)), tier.beyond)
        assertEquals("1 сейчас · 2 по расписанию", SalaryFormat.tierHeader(tier, MonthPeriod.CURRENT))
        assertEquals("До${NBSP}101 ещё 7${NBSP}ПТ — это +40${NBSP}₽ к каждой ПТ месяца.", SalaryFormat.toNext(tier))
        assertEquals(
            "По расписанию 145. Ещё 6${NBSP}ПТ сверх него дадут 11${THIN}800${NBSP}₽: 6${THIN}000${NBSP}₽ за них и 5${THIN}800${NBSP}₽ — ступень 3 на весь месяц.",
            SalaryFormat.beyond(tier.forecastCount, tier.beyond!!),
        )
    }

    @Test
    fun pastMonthHasNoHintsNorForecast() {
        val view = build(input(pts(94)), on = LocalDate(2026, 11, 3))
        assertEquals(MonthPeriod.PAST, view.period)
        assertNull(view.tiers!!.toNext)
        assertNull(view.tiers.beyond)
        assertNull(view.shortfall)
        assertNull(view.today)
    }

    @Test
    fun todayAndAhead() {
        val list = listOf(t(day = 20), t(day = 20), t(status = TrainingStatus.PLANNED, day = 20), t(day = 19))
        val view = build(input(list))
        assertEquals(rub(1840), view.today)
        assertEquals(rub(920), view.todayAhead)
        assertEquals("сегодня +1${THIN}840${NBSP}₽ · впереди ≈${NBSP}920${NBSP}₽", SalaryFormat.today(view.today, view.todayAhead))
        assertEquals("сегодня впереди ≈${NBSP}920${NBSP}₽", SalaryFormat.today(0, rub(920)))
        assertNull(SalaryFormat.today(0, 0))
    }

    @Test
    fun cancelledTodayAndMonth() {
        // Сегодня отменены ПТ и группа, 12-го — ПТ; отменённое дежурство и прошлый месяц не считаются.
        val c = TrainingStatus.CANCELLED
        val september = PayTraining("old", "pt", "bb", LocalDateTime(2026, 9, 30, 10, 0), 50, c, listOf(EXPECTED))
        val list = listOf(t(status = c, day = 20), t(type = "group", status = c, day = 20), t(status = c, day = 12), t(type = "duty", status = c, day = 20), t(day = 20), september)
        val view = build(input(list))
        assertEquals(2, view.todayCancelled)
        assertEquals(3, view.monthCancelled)
        // Прошлый месяц: «сегодня» нет, месяц считается.
        val past = build(input(list), on = LocalDate(2026, 11, 3))
        assertNull(past.todayCancelled)
        assertEquals(3, past.monthCancelled)
    }

    @Test
    fun breakdownLinesAddUpToEarned() {
        val list = pts(61) + pts(12, place = "db") +
            listOf(t("split", clients = 2), t("split", clients = 1)) +
            listOf(t("spt"), t("spt"), t("spt")) +
            // Моя группа, провёл один: 2 тренировки, 8 и 7 детей.
            listOf(t("group", clients = 8), t("group", clients = 7)) +
            // Моя вместе с Леной: 12 детей → G = 3 240, моё — половина.
            listOf(t("group", clients = 12, conductedBy = ConductedBy.SELF_AND_COLLEAGUE, colleagueId = "lena")) +
            // Группа Лены, провёл я (замена): 8 детей = 2 160 (макет: ср 14.10).
            listOf(t("group", clients = 8, day = 14, owner = Owner.COLLEAGUE, ownerColleagueId = "lena")) +
            // Группа Лены, провела Лена — в разбивку не попадает.
            listOf(t("group", clients = 10, owner = Owner.COLLEAGUE, ownerColleagueId = "lena", conductedBy = ConductedBy.COLLEAGUE)) +
            listOf(t("duty", clients = 0, minutes = 600), t("duty", clients = 0, minutes = 840), t("duty", clients = 0, minutes = 180, day = 14, coverFor = "Лена"))
        val closed = SptCase("c1", "kid", "spt-old", "spt", "bb", LocalDate(2026, 9, 30), purchaseDate = LocalDate(2026, 10, 5), status = SptCaseStatus.CLOSED)
        val view = build(input(list, spt = listOf(closed), adjustments = listOf(PayAdjustment(rub(1200)))))
        val lines = view.breakdown
        assertEquals(view.pay.earned, lines.sumOf { it.amount } + rub(1200))

        assertEquals(
            listOf(BreakdownSection.PT, BreakdownSection.PT, BreakdownSection.PT, BreakdownSection.PT, BreakdownSection.SPT, BreakdownSection.SPT),
            lines.take(6).map { it.section },
        )
        val ptBb = lines.first()
        assertEquals(BreakdownLine(BreakdownSection.PT, "pt", "bb", 61, rub(920), rub(56_120)), ptBb)
        assertEquals("61 × 920${NBSP}₽", SalaryFormat.lineNote(ptBb, null))
        val asPt = lines.single { it.splitMark == SplitMark.AS_PT }
        assertEquals("1 × 920${NBSP}₽ · один клиент, как ПТ", SalaryFormat.lineNote(asPt, null))
        val bonus = lines.single { it.sptClosed > 0 }
        assertEquals(rub(140), bonus.amount)
        assertEquals("Закрыты покупкой блока", SalaryFormat.lineTitle(bonus, "", ""))

        val groups = lines.filter { it.section == BreakdownSection.GROUPS }
        assertEquals(listOf(GroupCase.MINE_ALONE, GroupCase.MINE_TOGETHER, GroupCase.THEIRS_BY_ME), groups.map { it.groupCase })
        assertEquals(15, groups[0].children)
        assertEquals("моя группа · 2${NBSP}трен. · 15${NBSP}детей × 270${NBSP}₽", SalaryFormat.lineNote(groups[0], null))
        assertEquals(rub(1620), groups[1].amount)
        assertEquals("моя группа · вдвоём: Лена · 1${NBSP}трен. · 12${NBSP}детей · ½ от 3${THIN}240${NBSP}₽", SalaryFormat.lineNote(groups[1], "Лена"))
        assertEquals("группа коллеги: Лена · замена · 1${NBSP}трен. · 8${NBSP}детей × 270${NBSP}₽", SalaryFormat.lineNote(groups[2], "Лена"))

        val duties = lines.filter { it.section == BreakdownSection.DUTY }
        assertEquals(2, duties.size)
        assertEquals("24${NBSP}ч × 200${NBSP}₽", SalaryFormat.lineNote(duties[0], null))
        assertEquals("ср${NBSP}14.10 · за: Лена · 3${NBSP}ч × 200${NBSP}₽", SalaryFormat.lineNote(duties[1], null))
    }

    @Test
    fun payoutsAndSettlementsAsInMockup() {
        // Замена у Лены: 8 детей × 270 = 2 160, Лена отдаёт 2 160 × (1 − 0,065) = 2 019,60 ₽ (макет «Выплаты»).
        val replaced = t("group", clients = 8, day = 14, owner = Owner.COLLEAGUE, ownerColleagueId = "lena")
        val planned = t("group", clients = 8, day = 28, status = TrainingStatus.PLANNED, owner = Owner.COLLEAGUE, ownerColleagueId = "lena")
        val view = build(input(pts(100) + listOf(replaced, planned)), advance = null)
        assertEquals(mapOf("lena" to 201_960L), view.pay.settlements)
        assertEquals(mapOf("lena" to 403_920L), view.pay.forecastSettlements)
        // Начислено — только мои ПТ: 100 × 920; половина официально, НДФЛ 5 980.
        assertEquals(rub(92_000), view.payout.accrued)
        assertEquals(rub(46_000), view.payout.official)
        assertEquals(rub(5_980), view.payout.ndfl)
        assertEquals(1_354_650, view.payout.advance)
        assertEquals(rub(92_000) - rub(5_980) + 201_960, view.totals.onHand)
        assertEquals(view.totals.onHand + 201_960, view.forecastOnHand)
        assertEquals("Лена отдаёт вам", SalaryFormat.settlement("Лена", 201_960))
        assertEquals("Вы отдаёте Лене", SalaryFormat.settlement("Лена", -95_000))
        assertEquals("Официально · 50${NBSP}%", SalaryFormat.official(half.officialShareBp))
        assertEquals("НДФЛ 13${NBSP}% · оценка", SalaryFormat.ndfl(half.ndflBp))
    }

    @Test
    fun planCarriedAndShortfall() {
        val view = build(input(pts(10) + pts(10, TrainingStatus.PLANNED, day = 28)), plans = mapOf(YearMonth(2026, 9) to rub(30_000)))
        assertEquals(rub(30_000), view.plan)
        assertEquals(false, view.planOwn)
        assertEquals(30, view.percent)
        assertEquals(61, view.forecastPercent)
        val s = view.shortfall!!
        assertEquals(rub(30_000) - rub(18_400), s.missing)
        assertEquals(13, s.pts)
    }

    @Test
    fun noPtTypeStillShowsMissing() {
        val view = SalaryMonthRules.build(input(pts(10)), listOf(half), mapOf(october to rub(20_000)), null, today, null, null)
        assertEquals(Shortfall(rub(10_800), null), view.shortfall)
        assertTrue(view.planOwn)
    }

    @Test
    fun ptPlaceIsWhereMostPts() {
        val list = pts(3, place = "db") + pts(2)
        assertEquals("db", SalaryMonthRules.ptPlace(list, "pt", listOf("bb", "db")))
        assertEquals("bb", SalaryMonthRules.ptPlace(emptyList(), "pt", listOf("bb", "db")))
    }

    @Test
    fun dativeNames() {
        assertEquals("Лене", RuText.dative("Лена"))
        assertEquals("Марии", RuText.dative("Мария"))
        assertEquals("Илье", RuText.dative("Илья"))
        assertEquals("Ивану", RuText.dative("Иван"))
        assertEquals("Андрею", RuText.dative("Андрей"))
        assertEquals("Игорю", RuText.dative("Игорь"))
        assertEquals("Саше П.", RuText.dative("Саша П."))
        assertEquals("Нико", RuText.dative("Нико"))
    }

    @Test
    fun hoursWithoutDays() {
        assertEquals("24${NBSP}ч", SalaryFormat.hours(1440))
        assertEquals("3${NBSP}ч 30${NBSP}мин", SalaryFormat.hours(210))
        assertEquals("45${NBSP}мин", SalaryFormat.hours(45))
        assertEquals("${MINUS}8${THIN}170,50${NBSP}₽", com.akenory.personalka.domain.format.MoneyFormat.rub(-817_050))
    }
}
