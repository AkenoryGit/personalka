package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.WaitFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Освободившееся время (шаг 2.4б, ТЗ 4.16): примеры — проверка шага 0.8 («Отмена ПТ Орлова вт 17:00 ДБ: это время
 * ждут — Смирнова И. (разово, вечер), а не Петров К.») и проверка плана (конец серии на вт 08:00). Вт 20.10.2026, 15:40.
 */
class WaitMatchTest {
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private val tuesday = LocalDate(2026, 10, 27)

    private val petrov = WaitWant(series = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), ranges = listOf(WaitRules.MORNING), locationId = "bb", typeId = "pt")
    private val smirnova = WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = "pt", until = LocalDate(2026, 10, 31))
    private val morozov = WaitWant(series = true, days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), ranges = listOf(WaitRules.MORNING), typeId = "pt")

    private fun match(want: WaitWant, slot: FreedSlot, busy: List<Busy> = emptyList()) = WaitMatch.matches(want, slot, 50, busy, now)

    @Test
    fun oneOffCancelTuesdayEveningIsForSmirnovaNotPetrov() {
        val orlov = FreedSlot(LocalDate(2026, 10, 20), LocalTime(17, 0), "db", series = false)
        assertTrue(match(smirnova, orlov))
        assertFalse(match(petrov, orlov))
        assertFalse(match(morozov, orlov))
    }

    @Test
    fun seriesEndOnTuesday8IsForSeriesWaiters() {
        val ended = FreedSlot(tuesday, LocalTime(8, 0), "bb", series = true)
        assertTrue(match(morozov, ended))
        assertFalse(match(petrov, ended), "пн и ср — не вторник")
        assertFalse(match(smirnova, ended), "разовая запись постоянное время не ждёт")
        // Конец серии освобождает и ближайшую дату — разово.
        val nearest = ended.copy(series = false)
        assertFalse(match(smirnova, nearest), "08:00 — не вечер")
        assertTrue(match(smirnova.copy(ranges = emptyList()), nearest))
    }

    @Test
    fun placeUntilLeadAndWindow() {
        val slot = FreedSlot(LocalDate(2026, 10, 22), LocalTime(17, 0), "db", series = false)
        assertFalse(match(smirnova.copy(locationId = "bb"), slot), "другое место")
        assertFalse(match(smirnova.copy(until = LocalDate(2026, 10, 21)), slot), "после срока")
        // Час до начала и меньше — разовое уже не предлагается.
        val anyTime = smirnova.copy(ranges = emptyList())
        assertFalse(match(anyTime, slot.copy(date = LocalDate(2026, 10, 20), start = LocalTime(16, 40))))
        assertTrue(match(anyTime, slot.copy(date = LocalDate(2026, 10, 20), start = LocalTime(16, 45))))
        // Окно не вмещает 50 минут: в 17:30 другая тренировка.
        val busy = listOf(Busy(LocalDateTime(2026, 10, 22, 17, 30), LocalDateTime(2026, 10, 22, 18, 20)))
        assertFalse(match(smirnova, slot, busy))
        assertTrue(WaitMatch.fits(LocalDateTime(2026, 10, 22, 17, 0), 30, busy))
    }

    @Test
    fun currentMatchRollsWeeklyAndExpiresOnce() {
        val series = FreedSlot(LocalDate(2026, 10, 13), LocalTime(8, 0), "bb", series = true)
        assertEquals(LocalDate(2026, 10, 27), WaitMatch.current(series, now)?.date)
        val today = FreedSlot(LocalDate(2026, 10, 20), LocalTime(17, 0), "bb", series = true)
        assertEquals(LocalDate(2026, 10, 20), WaitMatch.current(today, now)?.date)
        assertNull(WaitMatch.current(today.copy(series = false, start = LocalTime(15, 0)), now))
        assertEquals(LocalDate(2026, 10, 27), WaitMatch.nearest(DayOfWeek.TUESDAY, LocalDate(2026, 10, 21)))
    }

    @Test
    fun orderMatchedFirstThenLongestWaiting() {
        data class E(val name: String, val match: FreedSlot?, val since: Long)
        val list = listOf(
            E("ждёт давно", null, 1),
            E("ждёт недавно", null, 5),
            E("освободилось чт", FreedSlot(LocalDate(2026, 10, 22), LocalTime(8, 0), "bb", false), 9),
            E("освободилось ср", FreedSlot(LocalDate(2026, 10, 21), LocalTime(8, 0), "bb", false), 7),
        )
        assertEquals(listOf("освободилось ср", "освободилось чт", "ждёт давно", "ждёт недавно"), WaitMatch.order(list, { it.match }, { it.since }).map { it.name })
    }

    @Test
    fun strings() {
        val nbsp = RuText.NBSP
        val series = FreedSlot(LocalDate(2026, 10, 26), LocalTime(8, 0), "bb", series = true)
        assertEquals("Освободилось пн 08:00", WaitFormat.freed(series))
        assertEquals("Освободилось вт${nbsp}20.10 17:00", WaitFormat.freed(series.copy(date = LocalDate(2026, 10, 20), start = LocalTime(17, 0), series = false)))
        assertEquals("Освободилось пн 08:00 · ждут 2", WaitFormat.todo(series, 2))
        assertEquals(
            "Это время ждут: Петров К. (разово), Морозов Д. (постоянно)",
            WaitFormat.waitingFor(listOf("Петров К." to true, "Морозов Д." to false)),
        )
    }
}
