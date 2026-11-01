package com.akenory.personalka.domain.watch

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.WatchFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Часы (шаг 5.3т): что уходит на часы и что они показывают. Клиенты придуманы, время — вт 20.10, как в макетах 0.6. */
class WatchTest {
    private val today = LocalDate(2026, 10, 20)
    private val tomorrow = LocalDate(2026, 10, 21)

    /** Пробелы внутри чисел и после времени — неразрывные и тонкие; в ожиданиях — «_». */
    private fun s(text: String) = text.replace(RuText.NBSP, '_').replace(RuText.THIN, '_')

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today) = LocalDateTime(day.year, day.month, day.day, hour, minute)

    private fun pt(id: String, hour: Int, minute: Int = 0, day: LocalDate = today, marked: Boolean = false, name: String? = "Никитин А.") =
        WatchSlot(id, at(hour, minute, day), 50, "ПТ", "ББ", name, group = false, marked = marked)

    private fun group(id: String, hour: Int, day: LocalDate = today, marked: Boolean = false) =
        WatchSlot(id, at(hour, 0, day), 50, "Мини-гр", "ДБ", "Вт 16:00 ДБ", group = true, marked = marked)

    private val day = WatchDay(
        date = today,
        slots = listOf(pt("a", 12, 20, marked = true), group("b", 16), pt("c", 17, name = "Орлов М."), pt("d", 19), pt("e", 7, day = tomorrow)),
        earned = 678_000,
        ahead = 581_500,
        percent = 60,
        forecastPercent = 97,
        todo = 3,
    )

    @Test
    fun slotsSkipEndedAndLaterDaysAndHideNames() {
        val all = listOf(pt("old", 8), pt("now", 15, 30), pt("x", 9, day = LocalDate(2026, 10, 23)), pt("y", 9, day = LocalDate(2026, 10, 22)))
        val kept = WatchRules.slots(all, at(15, 48), names = true)
        assertEquals(listOf("now", "y"), kept.map { it.id })
        assertEquals("Никитин А.", kept.first().name)
        assertTrue(WatchRules.slots(all, at(15, 48), names = false).all { it.name == null })
    }

    @Test
    fun nearestIsLikeHomeAndSwitchesByTime() {
        assertEquals("b", WatchRules.nearest(day, at(15, 48))?.id)
        // Мини-группа идёт и не отмечена — она «Ближайшая» до конца.
        assertEquals("b", WatchRules.nearest(day, at(16, 30))?.id)
        assertEquals("c", WatchRules.nearest(day, at(16, 51))?.id)
        // Вечером сегодня всё — следующая завтра.
        assertEquals("e", WatchRules.nearest(day, at(21, 0))?.id)
        // Отмеченная на часах сразу уходит.
        assertEquals("c", WatchRules.nearest(WatchRules.marked(day, "b"), at(16, 10))?.id)
    }

    @Test
    fun markButtonFromFifteenMinutesBeforeStart() {
        val b = day.slots.first { it.id == "b" }
        assertFalse(WatchRules.canMark(b, at(15, 44)))
        assertTrue(WatchRules.canMark(b, at(15, 45)))
        assertFalse(WatchRules.canMark(b.copy(marked = true), at(16, 10)))
    }

    @Test
    fun laterTodayLine() {
        val nearest = WatchRules.nearest(day, at(15, 48))!!
        val later = WatchRules.laterToday(day, nearest, at(15, 48))
        assertEquals(listOf("c", "d"), later.map { it.id })
        assertEquals(s("Ещё сегодня: 2, последняя в_19:00"), s(WatchFormat.laterToday(later.size, later.last().start)))
    }

    @Test
    fun moneyOnlyForTheSameDay() {
        val money = WatchRules.money(day, today)!!
        assertEquals(678_000, money.earned)
        assertEquals(1_259_500, money.evening)
        assertEquals(s("Сегодня +6_780_₽ · к вечеру ≈_12_600_₽"), s(WatchFormat.money(money.earned, money.evening)))
        assertEquals(s("12_600"), s(WatchFormat.moneyShort(money.evening)))
        assertEquals(60, WatchRules.percent(day, today))
        assertEquals(3, WatchRules.todo(day, today))
        // Телефон не открывали со вчера — вчерашние деньги и дела не показываются, расписание — да.
        assertNull(WatchRules.money(day, tomorrow))
        assertNull(WatchRules.percent(day, tomorrow))
        assertNull(WatchRules.todo(day, tomorrow))
        assertNull(WatchRules.money(day.copy(earned = null), today))
    }

    @Test
    fun complicationTexts() {
        val c = day.slots.first { it.id == "c" }
        assertEquals(s("17:00_ПТ"), s(WatchFormat.short(c)))
        assertEquals(s("17:00_ПТ · Орлов М. · ББ"), s(WatchFormat.long(c)))
        assertEquals(s("17:00_ПТ · ББ"), s(WatchFormat.long(c.copy(name = null))))
        assertEquals("Все пришли", WatchFormat.mark(day.slots.first { it.id == "b" }))
        assertEquals("Проведено", WatchFormat.mark(c))
        assertEquals(s("97_%"), s(WatchFormat.percent(97)))
    }

    @Test
    fun codecRoundTrip() {
        val withTab = day.copy(slots = day.slots + pt("t", 20, name = "Имя\tс\nтабом"))
        val back = WatchCodec.decode(WatchCodec.encode(withTab))!!
        assertEquals(day.slots, back.slots.dropLast(1))
        assertEquals("Имя с табом", back.slots.last().name)
        assertEquals(day.copy(slots = emptyList()), back.copy(slots = emptyList()))
        val noMoney = WatchDay(today, listOf(pt("z", 9, name = null)))
        assertEquals(noMoney, WatchCodec.decode(WatchCodec.encode(noMoney)))
        assertNull(WatchCodec.decode("w9\t2026-10-20"))
        assertNull(WatchCodec.decode(""))
    }
}
