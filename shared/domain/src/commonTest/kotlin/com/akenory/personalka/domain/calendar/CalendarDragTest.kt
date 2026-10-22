package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Жесты календаря (ТЗ 5.3): привязка к шагу, быстрое создание, края, кого нельзя перенести. «Сейчас» — вт 20.10.2026 15:40. */
class CalendarDragTest {
    private fun minute(text: String) = text.split(':').map { it.toInt() }.let { (h, m) -> h * 60 + m }

    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    @Test
    fun moveSnapsToStep() {
        // Проверка шага: верх блока 15:00 дотянули до 12:17 — начало 12:15 (макет «Перенос на другой день»).
        assertEquals(minute("12:15"), CalendarDrag.moveMinute(minute("15:00"), minute("12:17").toFloat(), 50, 15))
        assertEquals(minute("12:30"), CalendarDrag.moveMinute(minute("15:00"), minute("12:23").toFloat(), 50, 15))
        // Шаг из настроек: 5, 10, 30; неверный — 15.
        assertEquals(minute("12:20"), CalendarDrag.moveMinute(minute("15:00"), minute("12:18").toFloat(), 50, 5))
        assertEquals(minute("12:30"), CalendarDrag.moveMinute(minute("15:00"), minute("12:16").toFloat(), 50, 30))
        assertEquals(minute("12:15"), CalendarDrag.moveMinute(minute("15:00"), minute("12:17").toFloat(), 50, 0))
    }

    @Test
    fun nearOldStartStaysPut() {
        // Начало не кратно шагу: пока верх ближе полушага, 12:07 остаётся 12:07, дальше — к шагу.
        assertEquals(minute("12:07"), CalendarDrag.moveMinute(minute("12:07"), minute("12:13").toFloat(), 50, 15))
        assertEquals(minute("12:07"), CalendarDrag.moveMinute(minute("12:07"), minute("12:00").toFloat(), 50, 15))
        assertEquals(minute("12:15"), CalendarDrag.moveMinute(minute("12:07"), minute("12:15").toFloat(), 50, 15))
        assertEquals(minute("12:00"), CalendarDrag.moveMinute(minute("12:07"), minute("11:59").toFloat(), 50, 15))
    }

    @Test
    fun moveStaysInsideDay() {
        // Через полночь не перенести: 50 минут — не позже 23:10; раньше полуночи — 00:00.
        assertEquals(minute("23:10"), CalendarDrag.moveMinute(minute("20:00"), minute("23:40").toFloat(), 50, 15))
        assertEquals(0, CalendarDrag.moveMinute(minute("07:00"), -30f, 50, 15))
    }

    @Test
    fun createRoundsDown() {
        // ТЗ 5.3: время под пальцем, вниз до шага.
        assertEquals(minute("17:15"), CalendarDrag.createMinute(minute("17:29").toFloat() + 0.9f, 15))
        assertEquals(minute("17:00"), CalendarDrag.createMinute(minute("17:14").toFloat(), 15))
        assertEquals(minute("17:20"), CalendarDrag.createMinute(minute("17:29").toFloat(), 10))
        assertEquals(minute("23:45"), CalendarDrag.createMinute(1440f, 15))
    }

    @Test
    fun edgesScrollAndFlip() {
        // Сетка 576 dp: у верхнего края — вверх, у нижнего — вниз, у самого края — быстрее всего.
        assertEquals(-CalendarDrag.SCROLL_MAX_DP_PER_SECOND, CalendarDrag.autoScroll(0f, 576f))
        assertEquals(-CalendarDrag.SCROLL_MAX_DP_PER_SECOND / 2, CalendarDrag.autoScroll(24f, 576f))
        assertEquals(0f, CalendarDrag.autoScroll(300f, 576f))
        assertEquals(CalendarDrag.SCROLL_MAX_DP_PER_SECOND, CalendarDrag.autoScroll(580f, 576f))
        // Колонка дня 364 dp: края по 32 dp.
        assertEquals(-1, CalendarDrag.side(10f, 364f))
        assertEquals(0, CalendarDrag.side(180f, 364f))
        assertEquals(1, CalendarDrag.side(340f, 364f))
    }

    @Test
    fun cancelledAndMarkedInFuture() {
        val tomorrow = LocalDateTime(2026, 10, 21, 9, 0)
        assertEquals(MoveProblem.CANCELLED, CalendarDrag.problem(TrainingStatus.CANCELLED, false, tomorrow, now))
        assertNull(CalendarDrag.problem(TrainingStatus.PLANNED, false, tomorrow, now))
        // Проведённая — только туда, где её можно было отметить: начало не позже «сейчас + 15 минут».
        assertEquals(MoveProblem.MARKED_IN_FUTURE, CalendarDrag.problem(TrainingStatus.DONE, true, tomorrow, now))
        assertNull(CalendarDrag.problem(TrainingStatus.DONE, true, LocalDateTime(2026, 10, 20, 15, 55), now))
        assertEquals(MoveProblem.MARKED_IN_FUTURE, CalendarDrag.problem(TrainingStatus.DONE, true, LocalDateTime(2026, 10, 20, 16, 0), now))
        assertNull(CalendarDrag.problem(TrainingStatus.DONE, true, LocalDateTime(2026, 10, 19, 12, 0), now))
        // Группа с отметками «пришёл» ещё не закрыта, но так же не уходит в будущее.
        assertEquals(MoveProblem.MARKED_IN_FUTURE, CalendarDrag.problem(TrainingStatus.PLANNED, true, tomorrow, now))
    }
}
