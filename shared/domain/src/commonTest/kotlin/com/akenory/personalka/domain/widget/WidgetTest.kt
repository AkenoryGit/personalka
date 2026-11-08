package com.akenory.personalka.domain.widget

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
}
