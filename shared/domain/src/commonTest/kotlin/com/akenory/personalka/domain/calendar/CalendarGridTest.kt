package com.akenory.personalka.domain.calendar

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Сетка календаря (ТЗ 5.3): часы, строки блока по высоте, прокрутка и щипок. */
class CalendarGridTest {
    private val club = GridHours(7, 23)

    /** Сложенный Fold в макете «День»: 960 − шапка 56 − неделя 64 − панель 72 = 768 dp. */
    private val folded = 768f

    private fun span(from: String, to: String): DaySpan {
        fun minute(t: String) = t.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        return DaySpan("x", minute(from), minute(to))
    }

    @Test
    fun gridIsWorkHoursWidenedToTrainings() {
        assertEquals(club, CalendarGrid.hours(LocalTime(7, 0), LocalTime(23, 0)))
        // Тренировка в 06:30 — сетка с 06:00; ночь не показывается.
        assertEquals(GridHours(6, 23), CalendarGrid.hours(LocalTime(7, 0), LocalTime(23, 0), listOf(span("06:30", "07:20"))))
        // До 23:40 — до полуночи; конец работы 00:00 — тоже полночь.
        assertEquals(GridHours(7, 24), CalendarGrid.hours(LocalTime(7, 0), LocalTime(23, 0), listOf(span("22:50", "23:40"))))
        assertEquals(GridHours(8, 24), CalendarGrid.hours(LocalTime(8, 0), LocalTime(0, 0)))
        // Часы работы не на ровный час: 07:30–22:30 — сетка 07:00–23:00.
        assertEquals(club, CalendarGrid.hours(LocalTime(7, 30), LocalTime(22, 30)))
        // Тренировки внутри часов работы сетку не меняют.
        assertEquals(club, CalendarGrid.hours(LocalTime(7, 0), LocalTime(23, 0), listOf(span("10:00", "10:50"))))
        assertEquals(16, club.count)
    }

    @Test
    fun rowsByHeightAtDefaultHour() {
        val hour = CalendarGrid.HOUR_DEFAULT_DP
        fun rows(minutes: Int) = CalendarGrid.rows(CalendarGrid.heightDp(minutes, hour))
        // При часе 64 dp: 60 минут — три строки, 45–50 — две, 30 — одна.
        assertEquals(BlockRows.THREE, rows(60))
        assertEquals(BlockRows.TWO, rows(50))
        assertEquals(BlockRows.TWO, rows(45))
        assertEquals(BlockRows.ONE, rows(30))
        // Границы таблицы: 56 dp — уже три, 40 — уже две.
        assertEquals(BlockRows.THREE, CalendarGrid.rows(56f))
        assertEquals(BlockRows.TWO, CalendarGrid.rows(55.9f))
        assertEquals(BlockRows.TWO, CalendarGrid.rows(40f))
        assertEquals(BlockRows.ONE, CalendarGrid.rows(39.9f))
        // Крупный масштаб: 30 минут при часе 160 dp — 80 dp, три строки.
        assertEquals(BlockRows.THREE, CalendarGrid.rows(CalendarGrid.heightDp(30, 160f)))
    }

    @Test
    fun opensAtNowMinusHourButNotPastEnd() {
        // В 15:40 на сложенном Fold виден 11:00–23:00: «сейчас − 1 час» упирается в конец часов работы.
        assertEquals(GridView(660f, 64f), CalendarGrid.open(LocalTime(15, 40), club, folded))
        // Утром — ровно час назад: в 09:20 сверху 08:20.
        assertEquals(500f, CalendarGrid.open(LocalTime(9, 20), club, folded).top)
        // Рано утром — с начала сетки.
        assertEquals(420f, CalendarGrid.open(LocalTime(6, 10), club, folded).top)
        // Масштаб запомнен крупнее — в экран входит меньше часов, и прокрутка может идти дальше.
        assertEquals(880f, CalendarGrid.open(LocalTime(15, 40), club, folded, hourDp = 128f).top)
    }

    @Test
    fun scrollStaysInsideGrid() {
        val view = GridView(660f, 64f)
        // 64 dp вниз — на час позже.
        assertEquals(GridView(600f, 64f), GridView(540f, 64f).scrolled(64f, club, folded))
        assertEquals(660f, view.scrolled(640f, club, folded).top)
        assertEquals(420f, view.scrolled(-10_000f, club, folded).top)
        // Сетка короче экрана — стоит с начала.
        assertEquals(420f, GridView(500f, 40f).scrolled(30f, club, 1000f).top)
    }

    @Test
    fun pinchKeepsMinuteUnderFingers() {
        // Сверху 11:00, пальцы в 320 dp от верха — это 16:00. Вдвое крупнее: 16:00 остаётся под пальцами.
        val zoomed = GridView(660f, 64f).zoomed(2f, 320f, club, folded)
        assertEquals(128f, zoomed.hourDp)
        assertEquals(810f, zoomed.top)
        assertEquals(960f, zoomed.top + zoomed.minutesIn(320f))
        // Высота часа — от 40 до 160 dp.
        assertEquals(160f, GridView(660f, 64f).zoomed(10f, 0f, club, folded).hourDp)
        assertEquals(40f, GridView(660f, 64f).zoomed(0.1f, 0f, club, folded).hourDp)
        // Мельче — сетка не уходит за конец: в 768 dp при часе 48 dp входит вся сетка 07–23.
        assertEquals(420f, GridView(660f, 64f).zoomed(0.75f, 700f, club, folded).top)
    }
}
