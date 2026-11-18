package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.CalendarMoneyFormat
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
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
        // Будущие — доля прогноза; вместе с прошлым и сегодня — ровно «будет» месяца.
        assertTrue(money.days.getValue(22).forecast)
        assertEquals(v.shown.forecast, money.days.values.sumOf { it.amount })
        assertEquals(v.shown.forecast, money.forecast)
        assertEquals(v.pay.earned, money.earned)
        assertEquals(rub(150_000), money.plan)
        // Воскресенье без тренировок — денег нет, клетка пустая.
        assertNull(money.days[25])
        // Самый денежный день — ступень 4.
        assertEquals(CalendarMoneyRules.LEVELS, money.days.values.maxOf { it.level })
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
    fun levels() {
        assertEquals(0, CalendarMoneyRules.level(0, rub(9_100)))
        assertEquals(1, CalendarMoneyRules.level(rub(2_300), rub(9_100)))
        assertEquals(2, CalendarMoneyRules.level(rub(4_600), rub(9_100)))
        assertEquals(3, CalendarMoneyRules.level(rub(7_400), rub(9_100)))
        assertEquals(4, CalendarMoneyRules.level(rub(9_100), rub(9_100)))
    }

    @Test
    fun format() {
        assertEquals("7${THIN}400", CalendarMoneyFormat.cell(DayMoney(rub(7_400) + 40, forecast = false, level = 3)))
        assertEquals("6${THIN}440", CalendarMoneyFormat.cell(DayMoney(rub(6_437), forecast = true, level = 3)))
        assertEquals("заработано 5${THIN}500${NBSP}₽", CalendarMoneyFormat.dayLine(DayMoney(rub(5_500), false, 2)))
        assertEquals("будет ≈${NBSP}5${THIN}300${NBSP}₽", CalendarMoneyFormat.dayLine(DayMoney(rub(5_300), true, 2)))
        assertEquals(
            "заработано 3${THIN}200${NBSP}₽ · будет ≈${NBSP}6${THIN}440${NBSP}₽",
            CalendarMoneyFormat.dayLine(DayMoney(rub(6_440), true, 3), todayEarned = rub(3_200)),
        )
        assertNull(CalendarMoneyFormat.dayLine(null))
    }
}
