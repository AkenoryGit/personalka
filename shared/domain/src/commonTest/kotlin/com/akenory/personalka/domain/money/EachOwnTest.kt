package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.format.SettlementFormat
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.group.coachOn
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.report.ReportRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Каждый своих» (ТЗ 4.8, решение 31.10.2026): примеры 27–30 ТЗ 12, прогноз, разбивка, взаиморасчёт и отчёт. */
class EachOwnTest {

    // Группа · ББ 270 ₽ за ребёнка (ставки тренера, 3.3).
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val types = listOf(group)
    private val from = LocalDate(2026, 1, 1)
    private fun rub(r: Int): Long = r * 100L
    private val rates = listOf(PayRate("group", "bb", from, rub(270)))
    private val october = YearMonth(2026, 10)

    // Я и Лена — половина официально (w = 0,065), если не сказано иное.
    private val halfOfficial = OwnerSchemes(
        self = listOf(OwnerScheme(from, officialShareBp = 5000, ndflBp = 1300)),
        colleagues = mapOf("lena" to listOf(OwnerScheme.of(from, PaySchemeKind.HALF_OFFICIAL))),
    )

    private var seq = 0

    /** Группа «Каждый своих»: [mine] моих детей и [lenas] детей Лены, все со статусом [status]. */
    private fun t(
        mine: Int,
        lenas: Int,
        owner: Owner = Owner.SELF,
        by: ConductedBy = ConductedBy.EACH_OWN,
        day: Int = 17,
        status: TrainingStatus = TrainingStatus.DONE,
        each: ParticipationStatus = ATTENDED,
    ) = PayTraining(
        "t${seq++}", "group", "bb", LocalDateTime(2026, 10, day, 9, 0), 45, status, List(mine + lenas) { each },
        owner = owner,
        ownerColleagueId = "lena".takeIf { owner == Owner.COLLEAGUE },
        conductedBy = by,
        colleagueId = "lena".takeIf { by != ConductedBy.SELF },
        groupId = "sat",
        coaches = List(mine) { Owner.SELF } + List(lenas) { Owner.COLLEAGUE },
    )

    private fun single(training: PayTraining, owners: OwnerSchemes = halfOfficial) =
        PayRules.pay(training, types.associateBy { it.id }, rates, tier = 1, owners = owners)

    private fun input(trainings: List<PayTraining>) = MonthInput(october, trainings, types, rates, emptyList(), halfOfficial)

    @Test
    fun example27_myGroupEachOwn() {
        val pay = single(t(mine = 7, lenas = 5))
        assertEquals(rub(3240), pay.amount, "G — за всех 12 детей")
        assertEquals(rub(1890), pay.earned, "Заработано 7 × 270")
        assertEquals(rub(3240), pay.accrued, "Начислено клубом мне — вся группа")
        assertEquals(-126_225, pay.settlement, "Я отдаю Лене 5 × 270 × 0,935 = 1 262,25 ₽")
        assertEquals("lena", pay.colleagueId)
        assertEquals(5, pay.colleagueClients)
        // При «Вместе» (пример 9) было бы 1 514,70.
        assertEquals(-151_470, single(t(mine = 7, lenas = 5, by = ConductedBy.SELF_AND_COLLEAGUE)).settlement)
    }

    @Test
    fun example28_myGroupEachOwnWholeSum() {
        val wholeSum = OwnerSchemes(self = listOf(OwnerScheme.of(from, PaySchemeKind.NONE)))
        val pay = single(t(mine = 7, lenas = 5), wholeSum)
        assertEquals(rub(1890), pay.earned)
        assertEquals(-rub(1350), pay.settlement, "Я отдаю Лене 5 × 270 = 1 350,00 ₽")
        // Схемы владельца нет совсем — тоже без вычета.
        assertEquals(-rub(1350), single(t(mine = 7, lenas = 5), OwnerSchemes()).settlement)
    }

    @Test
    fun example29_lenasGroupEachOwn() {
        val pay = single(t(mine = 4, lenas = 6, owner = Owner.COLLEAGUE))
        assertEquals(rub(2700), pay.amount)
        assertEquals(rub(1080), pay.earned, "Заработано 4 × 270")
        assertEquals(0, pay.accrued, "Группа Лены — клуб начисляет ей")
        assertEquals(100_980, pay.settlement, "Лена отдаёт мне 1 080 × 0,935 = 1 009,80 ₽")
        assertEquals("lena", pay.colleagueId)
    }

    @Test
    fun example30_myGroupIAlone() {
        // Пометки детей те же, но Лены не было: обычное «Провёл один», все деньги мне.
        val pay = single(t(mine = 7, lenas = 5, by = ConductedBy.SELF))
        assertEquals(rub(3240), pay.earned)
        assertEquals(rub(3240), pay.accrued)
        assertEquals(0, pay.settlement)
        assertNull(pay.colleagueId)
        assertEquals(0, pay.colleagueClients)
        // Провела одна Лена — «Замена»: всё ей.
        val replaced = single(t(mine = 7, lenas = 5, by = ConductedBy.COLLEAGUE))
        assertEquals(0, replaced.earned)
        assertEquals(-302_940, replaced.settlement, "3 240 × 0,935")
    }

    @Test
    fun onlyPaidChildrenCountOnBothSides() {
        // Мои: 2 пришли, 1 отменил заранее; Лены: 1 пришёл, 1 со списанием, 1 отменил заранее.
        val training = t(mine = 0, lenas = 0).copy(
            clients = listOf(ATTENDED, ATTENDED, CANCELLED, ATTENDED, CANCELLED_CHARGED, CANCELLED),
            coaches = listOf(Owner.SELF, Owner.SELF, Owner.SELF, Owner.COLLEAGUE, Owner.COLLEAGUE, Owner.COLLEAGUE),
        )
        val pay = single(training)
        assertEquals(4, pay.paidClients)
        assertEquals(2, pay.colleagueClients)
        assertEquals(rub(540), pay.earned)
        assertEquals(-50_490, pay.settlement, "2 × 270 × 0,935")
    }

    @Test
    fun lenasGroupWithoutMyChildrenGivesNothing() {
        val pay = single(t(mine = 0, lenas = 6, owner = Owner.COLLEAGUE))
        assertEquals(0, pay.earned)
        assertEquals(0, pay.settlement)
        assertNull(pay.colleagueId)
        val input = input(listOf(t(mine = 0, lenas = 6, owner = Owner.COLLEAGUE)))
        val m = input.pay()
        assertEquals(0, m.groupChildren)
        assertEquals(emptyList(), SalaryMonthRules.breakdown(input, m), "В разбивку не попадает")
        assertEquals(emptyList(), SettlementRules.colleagues(input, m))
        assertEquals(listOf(false), ReportRules.items(input, m).map { it.inSalary }, "Не в зарплату")
    }

    @Test
    fun monthBreakdownSettlementAndReport() {
        // Пример 27 и пример 29 в одном месяце.
        val mine = t(mine = 7, lenas = 5, day = 17)
        val lenas = t(mine = 4, lenas = 6, owner = Owner.COLLEAGUE, day = 18)
        val input = input(listOf(mine, lenas))
        val m = input.pay()
        assertEquals(rub(1890) + rub(1080), m.earned)
        assertEquals(rub(3240), m.accrued)
        assertEquals(mapOf("lena" to 100_980L - 126_225L), m.settlements, "Лена отдаёт 1 009,80, я ей 1 262,25")
        assertEquals(7 + 4, m.groupChildren, "Детей в группах — только моих")

        val lines = SalaryMonthRules.breakdown(input, m)
        assertEquals(listOf(GroupCase.MINE_EACH_OWN, GroupCase.THEIRS_EACH_OWN), lines.map { it.groupCase })
        assertEquals(listOf(7, 4), lines.map { it.children })
        assertEquals(listOf(rub(1890), rub(1080)), lines.map { it.amount })
        assertEquals(m.earned, lines.sumOf { it.amount }, "Строки разбивки = «Заработано»")

        val lena = SettlementRules.colleagues(input, m).single()
        assertEquals(listOf(GroupCase.MINE_EACH_OWN, GroupCase.THEIRS_EACH_OWN), lena.cases.map { it.case })
        assertEquals(listOf(5, 4), lena.cases.map { it.children }, "За кого отдают: дети Лены в моей, мои в её")
        assertEquals(listOf(rub(1350), rub(1080)), lena.cases.map { c -> c.trainings.sumOf { it.gross } })
        assertEquals(m.settlement, lena.total)
        assertEquals("(1 − 0,065) от суммы за детей", SettlementFormat.formula(lena.cases.first()))
        assertEquals("Моя группа · каждый своих", SettlementFormat.caseTitle(GroupCase.MINE_EACH_OWN))

        val items = ReportRules.items(input, m)
        assertEquals(listOf(7, 4), items.map { it.children })
        assertEquals("каждый своих", ReportFormat.groupCase(GroupCase.MINE_EACH_OWN))
    }

    @Test
    fun forecastWaitsForOwnAndColleagueChildrenSeparately() {
        // Четыре прошлые субботы «Каждый своих»: моих 7, 6, 7, 6 (в среднем 6,5 → 7), Лены 5, 4, 4, 4 (4,25 → 4).
        val history = listOf(
            t(mine = 7, lenas = 5, day = 3),
            t(mine = 6, lenas = 4, day = 10),
            t(mine = 7, lenas = 4, day = 17),
            t(mine = 6, lenas = 4, day = 24),
            // Суббота, которую вели вместе, в среднее «Каждый своих» не идёт.
            t(mine = 12, lenas = 0, day = 26, by = ConductedBy.SELF_AND_COLLEAGUE),
        )
        val planned = t(mine = 9, lenas = 9, day = 31, status = TrainingStatus.PLANNED, each = EXPECTED)
        assertEquals(ExpectedKids(11, 4), PayRules.expectedChildren(planned, history))

        val m = PayRules.month(october, history + planned, types, rates, emptyList(), halfOfficial)
        val forecast = m.forecastTrainings.last()
        assertEquals(rub(2970), forecast.amount, "11 детей × 270")
        assertEquals(rub(1890), forecast.earned, "Мои 7 × 270")
        assertEquals(-100_980, forecast.settlement, "Лене за её 4: 1 080 × 0,935")

        // Истории нет — ждём состав: 9 моих и 9 Лены.
        val alone = PayRules.month(october, listOf(planned), types, rates, emptyList(), halfOfficial)
        assertEquals(rub(2430), alone.forecastTrainings.single().earned)
    }

    @Test
    fun otherModesIgnoreChildMarks() {
        // Пометки детей есть, а группу вели вместе: деление пополам, как в примере 9.
        val together = single(t(mine = 7, lenas = 5, by = ConductedBy.SELF_AND_COLLEAGUE))
        assertEquals(rub(1620), together.earned)
        assertEquals(0, together.colleagueClients)
        assertEquals(0, PayRules.paidColleague(t(mine = 7, lenas = 5, by = ConductedBy.SELF_AND_COLLEAGUE)))
        assertEquals(ExpectedKids(12), PayRules.expectedChildren(
            t(mine = 0, lenas = 0, by = ConductedBy.SELF, day = 31, status = TrainingStatus.PLANNED),
            listOf(t(mine = 7, lenas = 5, by = ConductedBy.SELF, day = 24)),
        ))
    }

    @Test
    fun coachComesFromMembershipOnTheDay() {
        val members = listOf(
            GroupMember("k1", LocalDate(2026, 9, 1)),
            GroupMember("k2", LocalDate(2026, 9, 1), coach = Owner.COLLEAGUE),
            GroupMember("k3", LocalDate(2026, 9, 1), LocalDate(2026, 10, 10), coach = Owner.COLLEAGUE),
        )
        assertEquals(Owner.SELF, members.coachOn("k1", LocalDate(2026, 10, 17)))
        assertEquals(Owner.COLLEAGUE, members.coachOn("k2", LocalDate(2026, 10, 17)))
        assertEquals(Owner.SELF, members.coachOn("k3", LocalDate(2026, 10, 17)), "Ушёл из группы — «на раз», мой")
        assertEquals(Owner.SELF, members.coachOn("guest", LocalDate(2026, 10, 17)), "Ребёнок «на раз» — мой")
    }

    @Test
    fun screenLinesOfExample27() {
        // Шаг 5.3м: на придуманной группе 7 + 5 детей карточка, «Зарплата» и «Коллеги» показывают числа примера 27.
        val nb = '\u00A0'
        val pay = single(t(mine = 7, lenas = 5))
        assertEquals("мои 7 × 270${nb}₽ = 1${RuText.THIN}890${nb}₽ · Лены 5: отдаёте 1${RuText.THIN}262,25${nb}₽", CoCoachingFormat.eachOwnPay(pay, mineGroup = true, colleague = "Лена"))
        val theirs = single(t(mine = 4, lenas = 6, owner = Owner.COLLEAGUE))
        assertEquals("мои 4 × 270${nb}₽ = 1${RuText.THIN}080${nb}₽ · Лена отдаёт вам 1${RuText.THIN}009,80${nb}₽", CoCoachingFormat.eachOwnPay(theirs, mineGroup = false, colleague = "Лена"))
        assertNull(CoCoachingFormat.eachOwnPay(single(t(mine = 0, lenas = 0)), mineGroup = true, colleague = "Лена"), "Никто не пришёл")
        assertEquals("Моих 7 · Лены 5", CoCoachingFormat.kidsSplit(7, 5, "Лена"))

        val input = input(listOf(t(mine = 7, lenas = 5, day = 17), t(mine = 7, lenas = 5, day = 24)))
        val m = input.pay()
        val line = SalaryMonthRules.breakdown(input, m).single()
        assertEquals(10, line.colleagueChildren)
        assertEquals("моя группа · каждый своих: Лена · 2${nb}трен. · мои 14 × 270${nb}₽ · Лены 10", SalaryFormat.lineNote(line, "Лена"))
        assertEquals(listOf(ConductedBy.SELF, ConductedBy.SELF_AND_COLLEAGUE, ConductedBy.COLLEAGUE, ConductedBy.EACH_OWN), CoCoachingRules.CHOICES)
    }

    @Test
    fun colleagueNameInGenitive() {
        assertEquals(
            listOf("Лены", "Ольги", "Ани", "Марии", "Ивана", "Андрея", "Игоря", "Саши", "Ли", "Лены К."),
            listOf("Лена", "Ольга", "Аня", "Мария", "Иван", "Андрей", "Игорь", "Саша", "Ли", "Лена К.").map(RuText::genitive),
        )
    }
}
