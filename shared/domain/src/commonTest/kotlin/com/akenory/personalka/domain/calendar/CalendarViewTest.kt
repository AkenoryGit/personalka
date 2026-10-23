package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.calendar.CalendarView.Agenda
import com.akenory.personalka.domain.calendar.CalendarView.Day
import com.akenory.personalka.domain.calendar.CalendarView.Days
import com.akenory.personalka.domain.calendar.CalendarView.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Виды календаря по состоянию Fold (ТЗ 5.3, шаги 1.12 и 1.12а). */
class CalendarViewTest {
    @Test
    fun defaultsByFoldAndSettings() {
        assertEquals(Day, CalendarViews.default(wide = false, weekViewDays = 3))
        assertEquals(Days(3), CalendarViews.default(wide = true, weekViewDays = 3))
        // «Дней в разложенном виде» из настроек тренера (3.2); неверное значение — 3.
        assertEquals(Days(5), CalendarViews.default(wide = true, weekViewDays = 5))
        assertEquals(Days(3), CalendarViews.default(wide = true, weekViewDays = 4))
        // На сложенном настройка не действует: там по умолчанию «День».
        assertEquals(Day, CalendarViews.default(wide = false, weekViewDays = 7))
    }

    @Test
    fun optionsByFold() {
        // «Месяц» и «Список» — на обоих состояниях, последними.
        assertEquals(listOf(Day, Days(3), Month, Agenda), CalendarViews.options(wide = false))
        assertEquals(listOf(Day, Days(3), Days(5), Days(7), Month, Agenda), CalendarViews.options(wide = true))
        assertEquals(listOf(true, true, true, true, false, false), CalendarViews.options(wide = true).map { it.grid })
        assertEquals(listOf(1, 3, 5, 7), CalendarViews.options(wide = true).filter { it.grid }.map { it.columns })
    }

    @Test
    fun remembersByCode() {
        CalendarViews.options(wide = true).forEach { view -> assertEquals(view, CalendarViews.parse(view.code, wide = true)) }
        CalendarViews.options(wide = false).forEach { view -> assertEquals(view, CalendarViews.parse(view.code, wide = false)) }
        assertEquals(Day, CalendarViews.parse("day", wide = false))
        assertEquals(Days(3), CalendarViews.parse("days3", wide = false))
        assertEquals(Month, CalendarViews.parse("month", wide = false))
        assertEquals(Agenda, CalendarViews.parse("list", wide = true))
        // «7 дней», запомненные на разложенном, сложенному не подходят — там откроется вид по умолчанию.
        assertNull(CalendarViews.parse("days7", wide = false))
        assertNull(CalendarViews.parse(null, wide = true))
        assertNull(CalendarViews.parse("week", wide = true))
    }
}
