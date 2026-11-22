package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.CalendarMoneyFormat
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Деньги в календаре (ТЗ 5.3, шаг 5.10г): суммы дней, фильтр места, строка месяца. */
class CalendarMoneyTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val october = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("pt", "db", jan, rub(920), rub(960), rub(1000)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt")))
    private val scheme = PayScheme(jan, PaySchemeKind.NONE)

    private var seq = 0
    private fun pts(count: Int, day: Int, status: TrainingStatus = TrainingStatus.DONE, place: String = "bb", month: Int = 10) = List(count) {
        PayTraining(
            "t${seq++}", "pt", place, LocalDateTime(2026, month, day, 10 + it % 10, 0), 50, status,
            listOf(if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED),
        )
    }

    private fun input(trainings: List<PayTraining>, month: YearMonth = october) =
        MonthInput(month, trainings, listOf(pt), rates, tiers)

    private fun view(input: MonthInput, on: LocalDate = today) =
        SalaryMonthRules.build(input, listOf(scheme), mapOf(input.month to rub(150_000)), null, on, "pt", "bb")

    /** По 5 ПТ в дни 1–19, сегодня 3 проведены и 2 впереди, по расписанию 10 на 22-е и 5 в ДБ на 23-е. */
    private fun october() = (1..19).flatMap { pts(5, it) } + pts(3, 20) + pts(2, 20, TrainingStatus.PLANNED) +
        pts(10, 22, TrainingStatus.PLANNED) + pts(5, 23, TrainingStatus.PLANNED, place = "db")

    @Test
    fun pastTodayAndFutureDays() {
        val inp = input(october())
        val v = view(inp)
        val money = CalendarMoneyRules.month(v, inp, today)
        assertEquals(MonthPeriod.CURRENT, money.period)
        // Прошедший день — заработано, жирным.
        val first = money.days.getValue(1)
        assertEquals(rub(5 * 920), first.amount)
        assertFalse(first.forecast)
        // Сегодня — заработано + остаток дня, как «Сегодня» на «Главной».
        val now = money.days.getValue(20)
        assertTrue(now.forecast)
        assertEquals(v.today!! + v.todayAhead!!, now.amount)
        assertEquals(v.today, money.today)
        // Будущие — «если все придут»: по расписанию, по ступени всего расписания (115 ПТ — вторая, 960 ₽).
        assertTrue(money.days.getValue(22).forecast)
        assertEquals(rub(10 * 960), money.days.getValue(22).amount)
        assertEquals(rub(5 * 960), money.days.getValue(23).amount)
        // «Будет» месяца — как в «Зарплате» (там вторая ступень и у проведённых), а не сумма дней.
        assertEquals(v.shown.forecast, money.forecast)
        assertEquals(rub(98 * 920 + 2 * 920 + 15 * 960), money.days.values.sumOf { it.amount })
        assertEquals(v.pay.earned, money.earned)
        assertEquals(rub(150_000), money.plan)
        // Воскресенье без тренировок — денег нет, клетка пустая.
        assertNull(money.days[25])
        // 22-го 10 ПТ — вдвое больше обычного дня: сильный; обычный день — 5 ПТ.
        assertEquals(DayTone.STRONG, money.days.getValue(22).tone)
        assertEquals(DayTone.NORMAL, money.days.getValue(1).tone)
    }

    /**
     * Шаг 5.34 (решение Дмитрия 22.11.2026): будущий день — «если все придут». Доля отмен 22 % снижает только «будет»
     * месяца; отменённая в будущем дне не считается; неотмеченные прошлых дней не переезжают в будущие; группа —
     * средним пришедших, а снятая в «Что считать в „будет“» — не считается.
     */
    @Test
    fun futureDaysIfEveryoneComes() {
        val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
        val groupRate = PayRate("group", null, jan, rub(400))
        fun groupOn(day: Int, status: TrainingStatus, clients: List<ParticipationStatus>) = PayTraining(
            "g${seq++}", "group", "bb", LocalDateTime(2026, 10, day, 18, 0), 45, status, clients, groupId = "g",
        )
        val cancelled = PayTraining("c${seq++}", "pt", "bb", LocalDateTime(2026, 10, 22, 21, 0), 50, TrainingStatus.CANCELLED, listOf(CANCELLED))
        val trainings = october() + pts(2, 19, TrainingStatus.PLANNED) + cancelled +
            groupOn(6, TrainingStatus.DONE, List(3) { ATTENDED }) + groupOn(13, TrainingStatus.DONE, List(3) { ATTENDED }) +
            groupOn(27, TrainingStatus.PLANNED, List(5) { EXPECTED })
        fun money(groupInForecast: Boolean): Pair<SalaryMonth, CalendarMoney> {
            val inp = MonthInput(
                october, trainings, listOf(pt, group.copy(inForecast = groupInForecast)), rates + groupRate, tiers,
                cancels = CancelShare(11, 50),
            )
            val v = view(inp)
            return v to CalendarMoneyRules.month(v, inp, today)
        }
        val (v, m) = money(groupInForecast = true)
        // 10 ПТ на 22-е — все десять по 960 ₽, без скидки на 22 % отмен; отменённая — не в счёт.
        assertEquals(rub(10 * 960), m.days.getValue(22).amount)
        // 19-е — только заработанное: две неотмеченные не в нём и не в будущих днях.
        assertEquals(rub(5 * 920), m.days.getValue(19).amount)
        assertEquals(rub(5 * 960), m.days.getValue(23).amount)
        // Группа 27-го — средним пришедших: 3 ребёнка по 400 ₽.
        assertEquals(rub(3 * 400), m.days.getValue(27).amount)
        // «Будет» месяца — со средними, как в «Зарплате»: с долей отмен, ниже «по расписанию».
        assertEquals(v.shown.forecast, m.forecast)
        assertTrue(v.shown.forecast < v.shown.scheduled)

        // Группа снята из «будет» — её дня нет, ПТ те же.
        val (_, off) = money(groupInForecast = false)
        assertNull(off.days[27])
        assertEquals(rub(10 * 960), off.days.getValue(22).amount)
    }

    @Test
    fun placeFilterCountsOnlyThatPlace() {
        val inp = input(october())
        val v = view(inp)
        val all = CalendarMoneyRules.month(v, inp, today)
        val db = CalendarMoneyRules.month(v, inp, today, placeIds = setOf("db"))
        val bb = CalendarMoneyRules.month(v, inp, today, placeIds = setOf("bb"))
        assertNull(db.days[1])
        assertEquals(0L, db.earned)
        assertEquals(all.days.getValue(23).amount, db.days.getValue(23).amount)
        assertNull(bb.days[23])
        // Места вместе — то же, что все места.
        assertEquals(all.forecast, (db.forecast ?: 0) + (bb.forecast ?: 0))
        // План общий — с фильтром его нет.
        assertNull(db.plan)
    }

    @Test
    fun pastAndFutureMonths() {
        val sept = YearMonth(2026, 9)
        val past = input((1..5).flatMap { pts(4, it, month = 9) }, sept)
        val pm = CalendarMoneyRules.month(view(past), past, today)
        assertEquals(MonthPeriod.PAST, pm.period)
        assertNull(pm.forecast)
        assertTrue(pm.days.values.none { it.forecast })
        assertEquals(rub(20 * 920), pm.earned)

        val nov = YearMonth(2026, 11)
        val future = input(pts(6, 3, TrainingStatus.PLANNED, month = 11), nov)
        val fv = view(future)
        val fm = CalendarMoneyRules.month(fv, future, today)
        assertEquals(MonthPeriod.FUTURE, fm.period)
        assertEquals(fv.shown.forecast, fm.forecast)
        assertTrue(fm.days.getValue(3).forecast)
        assertEquals(fv.shown.forecast, fm.days.getValue(3).amount)
    }

    @Test
    fun tones() {
        // Средний день 8 050 ₽ (ноябрь Дмитрия): ниже 6 440 — слабый, выше 9 660 — сильный.
        val average = rub(8_050)
        assertEquals(DayTone.WEAK, CalendarMoneyRules.tone(rub(4_820), average))
        assertEquals(DayTone.WEAK, CalendarMoneyRules.tone(rub(6_320), average))
        assertEquals(DayTone.NORMAL, CalendarMoneyRules.tone(rub(6_440), average))
        assertEquals(DayTone.NORMAL, CalendarMoneyRules.tone(rub(9_260), average))
        assertEquals(DayTone.STRONG, CalendarMoneyRules.tone(rub(9_730), average))
        assertEquals(DayTone.NORMAL, CalendarMoneyRules.tone(rub(100), 0))
    }

    @Test
    fun format() {
        assertEquals("7${THIN}400", CalendarMoneyFormat.cell(DayMoney(rub(7_400) + 40, forecast = false, tone = DayTone.NORMAL)))
        assertEquals("6${THIN}440", CalendarMoneyFormat.cell(DayMoney(rub(6_437), forecast = true, tone = DayTone.NORMAL)))
        assertEquals("заработано 5${THIN}500${NBSP}₽", CalendarMoneyFormat.dayLine(DayMoney(rub(5_500), false, DayTone.NORMAL)))
        assertEquals("будет ≈${NBSP}5${THIN}300${NBSP}₽", CalendarMoneyFormat.dayLine(DayMoney(rub(5_300), true, DayTone.NORMAL)))
        assertEquals(
            "заработано 3${THIN}200${NBSP}₽ · будет ≈${NBSP}6${THIN}440${NBSP}₽",
            CalendarMoneyFormat.dayLine(DayMoney(rub(6_440), true, DayTone.NORMAL), todayEarned = rub(3_200)),
        )
        assertNull(CalendarMoneyFormat.dayLine(null))
    }
}
