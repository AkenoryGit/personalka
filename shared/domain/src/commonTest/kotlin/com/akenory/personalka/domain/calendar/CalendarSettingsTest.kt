package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.restriction.Restriction
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Раздел «Календарь» в настройках (5.16, шаг 1.13г): «сегодня» — вт 20.10.2026. */
class CalendarSettingsTest {
    private val today = LocalDate(2026, 10, 20)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun workHoursEndAfterStartMidnightIsEndOfDay() {
        assertNull(CalendarSettingsRules.workHoursProblem(LocalTime(7, 0), LocalTime(23, 0)))
        assertNull(CalendarSettingsRules.workHoursProblem(LocalTime(7, 0), LocalTime(0, 0)))
        assertEquals("Конец позже начала", CalendarSettingsRules.workHoursProblem(LocalTime(9, 0), LocalTime(9, 0)))
        assertEquals("Конец позже начала", CalendarSettingsRules.workHoursProblem(LocalTime(10, 0), LocalTime(8, 0)))
        assertEquals(s("07:00–23:00 · шаг 15_мин · серии на 8_недель"), CalendarSettingsFormat.summary(LocalTime(7, 0), LocalTime(23, 0), 15, 8))
        assertEquals(s("06:30–24:00 · шаг 5_мин · серии на 4_недели"), CalendarSettingsFormat.summary(LocalTime(6, 30), LocalTime(0, 0), 5, 4))
        assertEquals(listOf(5, 10, 15, 30), CalendarSettingsRules.DRAG_STEPS)
        assertEquals(s("26_недель"), CalendarSettingsFormat.weeks(26))
    }

    @Test
    fun vacationFromTodayAndNotBackwards() {
        val week = CalendarSettingsRules.defaultVacation(today)
        assertEquals(Vacation(today, LocalDate(2026, 10, 26)), week)
        assertNull(CalendarSettingsRules.vacationFromProblem(week, today))
        assertNull(CalendarSettingsRules.vacationToProblem(week))
        assertEquals("Не раньше сегодняшнего дня", CalendarSettingsRules.vacationFromProblem(Vacation(LocalDate(2026, 10, 19), today), today))
        // Один день — тоже отпуск.
        assertNull(CalendarSettingsRules.vacationToProblem(Vacation(today, today)))
        assertEquals("Не раньше первого дня", CalendarSettingsRules.vacationToProblem(Vacation(today, LocalDate(2026, 10, 19))))
        assertEquals(s("Отпуск_02.11–08.11: удалено 12_тренировок"), CalendarSettingsFormat.vacationSaved(LocalDate(2026, 11, 2), LocalDate(2026, 11, 8), 12))
        assertEquals(s("Отпуск_02.11–08.11: удалена 1_тренировка"), CalendarSettingsFormat.vacationSaved(LocalDate(2026, 11, 2), LocalDate(2026, 11, 8), 1))
        assertEquals(s("Отпуск_02.11–08.11 записан"), CalendarSettingsFormat.vacationSaved(LocalDate(2026, 11, 2), LocalDate(2026, 11, 8), 0))
    }

    @Test
    fun currentRestrictionsWeeklyFirstThenOneOff() {
        fun r(id: String, days: Set<DayOfWeek>, hour: Int, date: LocalDate? = null, from: LocalDate = LocalDate(2026, 9, 1), to: LocalDate? = null) =
            Restriction(id, "db", "", LocalTime(hour, 0), LocalTime(hour + 1, 0), days, date, date ?: from, to)
        val thursday = r("thu", setOf(DayOfWeek.THURSDAY), 17)
        val tuesday = r("tue", setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), 18)
        val ended = r("ended", setOf(DayOfWeek.MONDAY), 9, to = LocalDate(2026, 10, 19))
        val endsToday = r("today", setOf(DayOfWeek.MONDAY), 10, to = today)
        val yesterday = r("past", emptySet(), 12, date = LocalDate(2026, 10, 19))
        val later = r("later", emptySet(), 8, date = LocalDate(2026, 11, 3))
        val sooner = r("sooner", emptySet(), 15, date = today)
        assertEquals(
            listOf("today", "tue", "thu", "sooner", "later"),
            CalendarSettingsRules.current(listOf(later, thursday, ended, yesterday, sooner, tuesday, endsToday), today).map { it.id },
        )
    }

    @Test
    fun periodInList() {
        val tueThu = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val start = LocalDate(2026, 9, 1)
        assertEquals("Каждый вт и чт", CalendarSettingsFormat.period(tueThu, null, start, null, today))
        assertEquals("Каждый вт и чт до 31.12.2026", CalendarSettingsFormat.period(tueThu, null, start, LocalDate(2026, 12, 31), today))
        assertEquals("Каждую ср с 04.11.2026", CalendarSettingsFormat.period(setOf(DayOfWeek.WEDNESDAY), null, LocalDate(2026, 11, 4), null, today))
        assertEquals(s("пт_23.10.2026"), CalendarSettingsFormat.period(emptySet(), LocalDate(2026, 10, 23), LocalDate(2026, 10, 23), null, today))
        assertEquals("ББ 11:00–15:00", CalendarSettingsFormat.duty("ББ", 660, 900))
        assertEquals("ББ 11:00–15:00 · за${RuText.NBSP}Александра", CalendarSettingsFormat.duty("ББ", 660, 900, "Александра"))
    }
}
