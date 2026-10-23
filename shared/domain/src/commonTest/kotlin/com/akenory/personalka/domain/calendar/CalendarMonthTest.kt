package com.akenory.personalka.domain.calendar

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Месяц» и мини-календарь (ТЗ 5.3, шаг 1.12а): страницы, строки недель, число и точки дня. */
class CalendarMonthTest {
    private val today = LocalDate(2026, 10, 20)

    private fun item(color: String, shade: Int, minute: Int, cancelled: Boolean = false) = MonthItem(color, shade, cancelled, minute)

    @Test
    fun pagesAreMonthsSince1970() {
        assertEquals(0, MonthRules.page(LocalDate(1970, 1, 31)))
        assertEquals((2026 - 1970) * 12 + 9, MonthRules.page(today))
        assertEquals(LocalDate(2026, 10, 1), MonthRules.firstOf(MonthRules.page(today)))
        assertEquals(LocalDate(2099, 12, 1), MonthRules.firstOf(MonthRules.PAGE_COUNT - 1))
        assertEquals(LocalDate(2026, 2, 28), MonthRules.lastOf(LocalDate(2026, 2, 10)))
        assertEquals(LocalDate(2028, 2, 29), MonthRules.lastOf(LocalDate(2028, 2, 1)))
    }

    @Test
    fun weeksLikeMockup() {
        // Макет «Месяц»: октябрь 2026 — пять строк, с пн 28.09 по вс 01.11.
        val weeks = MonthRules.weeks(today)
        assertEquals(listOf(28, 5, 12, 19, 26), weeks.map { it.day })
        assertEquals(LocalDate(2026, 9, 28), weeks.first())
        assertEquals(MonthRules.Span(LocalDate(2026, 9, 28), LocalDate(2026, 11, 2)), MonthRules.span(today))
        // Февраль 2027 начинается в понедельник и длится 28 дней — ровно четыре строки.
        assertEquals(4, MonthRules.weeks(LocalDate(2027, 2, 14)).size)
        // Март 2026: 1-е — воскресенье, 31-е — вторник: шесть строк.
        assertEquals(6, MonthRules.weeks(LocalDate(2026, 3, 1)).size)
    }

    @Test
    fun sameDayAfterSwipe() {
        val november = MonthRules.page(LocalDate(2026, 11, 1))
        assertEquals(LocalDate(2026, 11, 20), MonthRules.sameDay(today, november, today))
        // 31-го нет в ноябре — последний день месяца.
        assertEquals(LocalDate(2026, 11, 30), MonthRules.sameDay(LocalDate(2026, 10, 31), november, today))
        // Вернулись в месяц сегодняшнего дня — снова сегодня.
        assertEquals(today, MonthRules.sameDay(LocalDate(2026, 11, 5), MonthRules.page(today), today))
    }

    @Test
    fun countWithoutCancelledAndFourDots() {
        // День макета: 10 тренировок без отмены; ПТ ББ чаще всего — первая точка.
        val day = MonthRules.day(
            listOf(
                item("blue", 1, 8 * 60),
                item("blue", 2, 9 * 60),
                item("blue", 1, 10 * 60, cancelled = true),
                item("pink", 2, 10 * 60),
                item("blue", 1, 12 * 60),
                item("blue", 1, 13 * 60 + 30),
                item("pink", 1, 15 * 60),
                item("pink", 3, 16 * 60),
                item("pink", 1, 17 * 60),
                item("pink", 4, 18 * 60),
                item("blue", 1, 19 * 60),
            ),
        )
        assertEquals(10, day.count)
        // ПТ ББ — 4, ПТ ДБ — 2, остальные по одной — в порядке дня: сплит ББ 09:00, грудничковое ДБ 10:00.
        assertEquals(listOf(MonthDot("blue", 1), MonthDot("pink", 1), MonthDot("blue", 2), MonthDot("pink", 2)), day.dots)
    }

    @Test
    fun cancelledOnlyAndEmptyDays() {
        // Отменённая не считается и точки не даёт: день с одной отменой — как пустой.
        assertEquals(MonthDay.EMPTY, MonthRules.day(listOf(item("blue", 1, 600, cancelled = true))))
        assertEquals(MonthDay.EMPTY, MonthRules.day(emptyList()))
        // Одинаковые на вид (то же место и тот же оттенок) — одна точка.
        assertEquals(MonthDay(2, listOf(MonthDot("blue", 3))), MonthRules.day(listOf(item("blue", 3, 600), item("blue", 3, 700))))
    }
}
