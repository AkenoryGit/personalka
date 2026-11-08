package com.akenory.personalka.domain.widget

import com.akenory.personalka.domain.money.Payout
import com.akenory.personalka.domain.money.PayoutKind
import com.akenory.personalka.domain.watch.WatchSlot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetTest {
    private val sunday = LocalDate(2026, 11, 8)

    private fun slot(id: String, date: LocalDate, h: Int, m: Int = 0, name: String? = "Никитин А.", marked: Boolean = false, color: String = "blue") =
        WidgetSlot(WatchSlot(id, LocalDateTime(date.year, date.month, date.day, h, m), 50, "ПТ", "ББ", name, group = false, marked = marked), color)

    private val money = WidgetMoney(
        title = "Деньги · ноябрь", monthName = "Ноябрь", tierChip = "Ступень 1 · до бонуса 64 ПТ", tier = 1,
        today = "1 840 ₽", todayWill = "будет ≈ 13 880 ₽", todayPt = "2 ПТ · будет 12", todayCancelled = "2 отмены",
        month = "48 920 ₽", monthWill = "будет ≈ 184 750 ₽", monthPt = "37 ПТ · будет 139", monthCancelled = null,
        done = 304, forecast = 1000, pace = "Хорошо идёте: прогноз 115 % плана", paceShort = "прогноз 115 % плана",
        behind = false, ptTier = "37 ПТ · ступень 1",
    )

    private fun day(vararg slots: WidgetSlot) = WidgetDay(sunday, slots.toList(), money, todo = 3)

    @Test
    fun codecKeepsEverything() {
        val d = day(slot("a", sunday, 11), slot("b", sunday, 12, name = null, color = "indigo"))
        assertEquals(d, WidgetCodec.decode(WidgetCodec.encode(d)))
        val noMoney = d.copy(money = null)
        assertEquals(noMoney, WidgetCodec.decode(WidgetCodec.encode(noMoney)))
        assertNull(WidgetCodec.decode("w1\tx"))
    }

    @Test
    fun runningTrainingIsNowWithEndTimeAndNextRows() {
        val d = day(slot("p", sunday, 9), slot("a", sunday, 11), slot("b", sunday, 12), slot("c", sunday, 13), slot("d", sunday, 14), slot("e", sunday, 20))
        val v = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 11, 24))
        assertEquals(WidgetPhase.NOW, v.phase)
        assertEquals("Сейчас · 11:00–11:50", v.header)
        assertEquals("до 11:50", v.right)
        assertEquals(480, v.progress)
        assertEquals(listOf("b", "c"), v.next.map { it.id })
        assertEquals("ещё 2 · последняя в 20:00", v.more)
        assertEquals("сегодня 6", v.count)
        assertEquals(money, v.money)
        // Виджет выше — строк больше, «ещё» не нужно (шаг 5.9а).
        val tall = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 11, 24), rows = 5)
        assertEquals(listOf("b", "c", "d", "e"), tall.next.map { it.id })
        assertNull(tall.more)
    }

    @Test
    fun beforeStartIsNext() {
        val v = WidgetRules.view(day(slot("a", sunday, 7)), LocalDateTime(2026, 11, 8, 6, 40))
        assertEquals(WidgetPhase.NEXT, v.phase)
        assertEquals("в 07:00", v.right)
        assertNull(v.progress)
        assertNull(v.more)
    }

    @Test
    fun eveningShowsTomorrowFirst() {
        val monday = LocalDate(2026, 11, 9)
        val d = day(slot("a", sunday, 19), slot("t1", monday, 7, name = "Смирнова И."), slot("t2", monday, 8))
        val v = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 21, 5))
        assertEquals(WidgetPhase.DONE, v.phase)
        assertEquals("Сегодня всё · завтра первая", v.header)
        assertEquals("Смирнова И.", v.name)
        assertEquals("завтра 2 тренировки".replace(" 2 ", " 2 "), v.more)
        assertEquals(3, v.todo)
    }

    @Test
    fun markedTrainingIsNotNowAndNoNameShowsType() {
        val d = day(slot("a", sunday, 11, marked = true), slot("b", sunday, 12, name = null))
        val v = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 11, 30))
        assertEquals("b", v.id)
        assertEquals("ПТ", v.name)
    }

    @Test
    fun yesterdaysMoneyIsNotShown() {
        val v = WidgetRules.view(day(), LocalDateTime(2026, 11, 9, 8, 0))
        assertEquals(WidgetPhase.EMPTY, v.phase)
        assertNull(v.money)
        assertNull(v.todo)
    }

    @Test
    fun nextUpdateAtEdgesAndEveryTenMinutesWhileRunning() {
        val d = day(slot("a", sunday, 11), slot("b", sunday, 13))
        assertEquals(LocalDateTime(2026, 11, 8, 11, 0), WidgetRules.nextUpdate(d, LocalDateTime(2026, 11, 8, 10, 0)))
        assertEquals(LocalDateTime(2026, 11, 8, 11, 34), WidgetRules.nextUpdate(d, LocalDateTime(2026, 11, 8, 11, 24)))
        assertEquals(LocalDateTime(2026, 11, 8, 11, 50), WidgetRules.nextUpdate(d, LocalDateTime(2026, 11, 8, 11, 45)))
        assertEquals(LocalDateTime(2026, 11, 9, 0, 0), WidgetRules.nextUpdate(d, LocalDateTime(2026, 11, 8, 14, 0)))
    }

    @Test
    fun brightFieldsSurviveCodec() {
        val m = money.copy(
            planPercent = "31\u00A0% плана", poolNote = "Прогноз 115\u00A0% — план переплывёте", ptCount = 38, tierNext = 101,
            tierToNext = 63, hasTiers = true, paydayDate = LocalDate(2026, 11, 10), paydayAmount = "≈\u00A0103\u00A0980\u00A0₽",
            paydayFrom = LocalDate(2026, 10, 25),
        )
        val d = WidgetDay(sunday, listOf(slot("a", sunday, 11)), m)
        assertEquals(d, WidgetCodec.decode(WidgetCodec.encode(d)))
    }

    @Test
    fun paydayIsNextPayoutDaySummed() {
        val payouts = listOf(
            Payout(PayoutKind.ADVANCE, LocalDate(2026, 10, 25), 1_354_650),
            Payout(PayoutKind.CARD_REST, LocalDate(2026, 11, 10), 4_113_300),
            Payout(PayoutKind.CASH, LocalDate(2026, 11, 10), 6_285_000),
            Payout(PayoutKind.ADVANCE, LocalDate(2026, 11, 25), 1_354_650),
        )
        val p = WidgetRules.payday(payouts, sunday)!!
        assertEquals(LocalDate(2026, 11, 10), p.date)
        assertEquals(10_398_300, p.amount)
        assertEquals(LocalDate(2026, 10, 25), p.from)
        assertEquals("2" to "дня", WidgetRules.paydayDays(p.date, sunday))
        assertEquals("" to "сегодня!", WidgetRules.paydayDays(p.date, p.date))
        val m = money.copy(paydayDate = p.date, paydayFrom = p.from, paydayAmount = "x")
        assertEquals(16 to 14, WidgetRules.paydayBar(m, sunday))
    }

    @Test
    fun bonusRingToNextTier() {
        val (part, big, small) = WidgetRules.bonus(money.copy(hasTiers = true, ptCount = 38, tierNext = 101, tierToNext = 63))!!
        assertEquals(376, part)
        assertEquals("63\u00A0ПТ", big)
        assertEquals("до бонуса", small)
        assertEquals("максимум", WidgetRules.bonus(money.copy(hasTiers = true, tier = 3))!!.third)
        assertNull(WidgetRules.bonus(money))
    }

    @Test
    fun mascotMoods() {
        val d = day(slot("a", sunday, 7), slot("b", sunday, 8), slot("c", sunday, 9))
        val morning = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 6, 40)).mascot!!
        assertEquals("Поехали!", morning.title)
        assertEquals("Сегодня 3\u00A0тренировки, первая в\u00A007:00", morning.line)
        assertEquals("Плывём!", WidgetRules.view(d, LocalDateTime(2026, 11, 8, 7, 10)).mascot!!.title)
        val between = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 7, 55))
        assertEquals("Отлично!", between.mascot!!.title)
        assertEquals("1 из 3 · так держать", between.dayLine)
        val evening = WidgetRules.view(d, LocalDateTime(2026, 11, 8, 21, 0))
        assertEquals(WidgetMood.SLEEP, evening.mascot!!.mood)
        assertEquals("3 из 3 · день закрыт!", evening.dayLine)
    }

    @Test
    fun iphoneTimelineFramesAtEdgesTicksAndMidnights() {
        val d = day(slot("a", sunday, 11), slot("b", sunday, 13))
        val t = WidgetRules.timeline(d, LocalDateTime(2026, 11, 8, 11, 32))
        assertEquals(LocalDateTime(2026, 11, 8, 11, 32), t.first())
        assertEquals(
            listOf(35, 40, 45, 50).map { LocalDateTime(2026, 11, 8, 11, it) },
            t.filter { it.hour == 11 && it.minute > 32 },
        )
        assertEquals(true, LocalDateTime(2026, 11, 8, 13, 0) in t)
        assertEquals(true, LocalDateTime(2026, 11, 9, 0, 0) in t)
        assertEquals(t.sorted(), t)
    }
}
