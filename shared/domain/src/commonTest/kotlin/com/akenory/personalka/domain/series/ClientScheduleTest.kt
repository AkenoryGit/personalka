package com.akenory.personalka.domain.series

import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.RuText
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Вкладка «Расписание» карточки клиента (5.9, макет ClCardSchedule): «сегодня» — вт 20.10.2026. */
class ClientScheduleTest {
    private val today = LocalDate(2026, 10, 20)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun series(
        id: String,
        days: Set<DayOfWeek>,
        hour: Int,
        from: LocalDate,
        to: LocalDate? = null,
    ) = ScheduleSeries(id, days, LocalTime(hour, 0), 50, "ПТ", "ББ", from, to)

    @Test
    fun currentFirstThenEndedFromRecent() {
        val tueThu = series("a", setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), 8, LocalDate(2026, 9, 1))
        val monWed = series("b", setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 7, LocalDate(2026, 6, 2), LocalDate(2026, 8, 31))
        val older = series("c", setOf(DayOfWeek.FRIDAY), 9, LocalDate(2026, 1, 9), LocalDate(2026, 5, 29))
        val monday = series("d", setOf(DayOfWeek.MONDAY), 18, LocalDate(2026, 11, 2))
        // Завершили раньше начала — пустая, её нет.
        val empty = series("e", setOf(DayOfWeek.SUNDAY), 10, LocalDate(2026, 11, 1), LocalDate(2026, 10, 25))
        assertEquals(listOf("d", "a", "b", "c"), ClientScheduleRules.ordered(listOf(older, tueThu, empty, monWed, monday), today).map { it.id })
        // Последний день сегодня — серия ещё идёт.
        assertEquals(false, series("f", setOf(DayOfWeek.TUESDAY), 8, LocalDate(2026, 9, 1), today).endedOn(today))
        assertEquals(true, monWed.endedOn(today))
    }

    @Test
    fun changeFromNotInPastNorAfterEnd() {
        val running = series("a", setOf(DayOfWeek.TUESDAY), 8, LocalDate(2026, 9, 1), LocalDate(2026, 12, 29))
        val future = series("b", setOf(DayOfWeek.MONDAY), 8, LocalDate(2026, 11, 2))
        assertEquals(today, ClientScheduleRules.defaultFrom(running, today))
        assertEquals(LocalDate(2026, 11, 2), ClientScheduleRules.defaultFrom(future, today))
        assertNull(ClientScheduleRules.fromProblem(today, today, running.validTo))
        assertEquals("Не раньше сегодняшнего дня", ClientScheduleRules.fromProblem(LocalDate(2026, 10, 19), today, null))
        assertEquals("Серия заканчивается 29.12.2026", ClientScheduleRules.fromProblem(LocalDate(2026, 12, 30), today, running.validTo))
    }

    @Test
    fun endNotEarlierThanYesterday() {
        assertEquals(today, ClientScheduleRules.defaultLastDay(today))
        assertNull(ClientScheduleRules.lastDayProblem(LocalDate(2026, 10, 19), today))
        assertNull(ClientScheduleRules.lastDayProblem(LocalDate(2026, 12, 31), today))
        assertEquals("Не раньше вчерашнего дня", ClientScheduleRules.lastDayProblem(LocalDate(2026, 10, 18), today))
    }

    @Test
    fun linesLikeMockup() {
        val days = setOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY)
        assertEquals("Вт, Чт · 08:00–08:50", ClientFormat.seriesDays(days, LocalTime(8, 0), 50))
        assertEquals("Сб · 23:30–24:00", ClientFormat.seriesDays(setOf(DayOfWeek.SATURDAY), LocalTime(23, 30), 30))
        assertEquals("ПТ · ББ · с 01.09.2026", ClientFormat.seriesPeriod("ПТ", "ББ", LocalDate(2026, 9, 1), null, today))
        assertEquals("ПТ · ББ · 02.06–31.08.2026 · завершена", ClientFormat.seriesPeriod("ПТ", "ББ", LocalDate(2026, 6, 2), LocalDate(2026, 8, 31), today))
        assertEquals("ПТ · ББ · 01.09–31.12.2026", ClientFormat.seriesPeriod("ПТ", "ББ", LocalDate(2026, 9, 1), LocalDate(2026, 12, 31), today))
        assertEquals("ПТ · ББ · 01.09.2025–31.08.2026 · завершена", ClientFormat.seriesPeriod("ПТ", "ББ", LocalDate(2025, 9, 1), LocalDate(2026, 8, 31), today))
        assertEquals(s("Расписание изменено с вт_27.10"), ClientFormat.changedFrom(LocalDate(2026, 10, 27)))
    }
}
