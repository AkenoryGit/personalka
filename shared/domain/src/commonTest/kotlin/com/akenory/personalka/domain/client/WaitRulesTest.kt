package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.model.TimeRange
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Лист ожидания (шаг 2.4а, ТЗ 4.16, 5.8): примеры — макеты ClNoBooking и ClWaitAdd, вт 20.10.2026, 15:40. */
class WaitRulesTest {
    private val today = LocalDate(2026, 10, 20)
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val petrov = WaitWant(
        series = true,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        ranges = listOf(WaitRules.MORNING),
        locationId = "bb",
        typeId = "pt",
        note = "пн, ср на 8 или на 10, можно сплит с братом",
    )
    private val smirnova = WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = "pt", until = LocalDate(2026, 10, 31))

    @Test
    fun mockupLines() {
        assertEquals("постоянно · пн, ср · 07–12 · ПТ · ББ", WaitFormat.want(petrov, "ПТ", "ББ"))
        assertEquals("разово до 31.10 · любой день · 17–23 · ПТ", WaitFormat.want(smirnova, "ПТ", null))
        val exact = petrov.copy(ranges = listOf(TimeRange(LocalTime(7, 0), LocalTime(10, 0))), days = setOf(DayOfWeek.FRIDAY))
        assertEquals("постоянно · пт · 07–10 · ПТ · ББ", WaitFormat.want(exact, "ПТ", "ББ"))
        val both = smirnova.copy(series = true, ranges = listOf(TimeRange(LocalTime(7, 30), LocalTime(9, 0))))
        assertEquals("постоянно или разово до 31.10 · любой день · 07:30–09:00 · ПТ", WaitFormat.want(both, "ПТ", null))
        assertEquals("постоянно · любой день · любое время · ПТ", WaitFormat.want(WaitWant(typeId = "pt"), "ПТ", null))
        assertEquals("Утро 07–12", WaitFormat.preset(WaitRules.MORNING))
        assertEquals("Вечер 17–23", WaitFormat.preset(WaitRules.EVENING))
        assertEquals(s("ждёт 37_дней"), WaitFormat.waiting(WaitRules.daysWaiting(LocalDate(2026, 9, 13), today)))
        assertEquals(s("ждёт 1_день"), WaitFormat.waiting(1))
        assertEquals("ждёт с сегодня", WaitFormat.waiting(0))
    }

    @Test
    fun formProblems() {
        assertEquals(setOf(WaitProblem.NO_CLIENT), WaitRules.problems(WaitDraft(null, petrov), today))
        assertEquals(setOf(WaitProblem.NO_KIND), WaitRules.problems(WaitDraft("c", petrov.copy(series = false)), today))
        assertEquals(setOf(WaitProblem.NO_TYPE), WaitRules.problems(WaitDraft("c", petrov.copy(typeId = null)), today))
        assertEquals(setOf(WaitProblem.UNTIL_PAST), WaitRules.problems(WaitDraft("c", smirnova.copy(until = LocalDate(2026, 10, 19))), today))
        assertEquals(emptySet(), WaitRules.problems(WaitDraft("c", smirnova), today))
        // Срок — только у разового; заметка без пробелов.
        val cleaned = WaitRules.clean(petrov.copy(until = LocalDate(2026, 11, 1), note = "  утром  "))
        assertNull(cleaned.until)
        assertEquals("утром", cleaned.note)
    }

    @Test
    fun oneOffClosesDayAfterUntilSeriesNever() {
        assertFalse(WaitRules.expired(smirnova, LocalDate(2026, 10, 31)))
        assertTrue(WaitRules.expired(smirnova, LocalDate(2026, 11, 1)))
        assertFalse(WaitRules.expired(smirnova.copy(until = null), LocalDate(2027, 1, 1)))
        assertFalse(WaitRules.expired(smirnova.copy(series = true), LocalDate(2026, 11, 1)))
        assertFalse(WaitRules.purge(LocalDate(2026, 8, 1), LocalDate(2026, 10, 29)))
        assertTrue(WaitRules.purge(LocalDate(2026, 8, 1), LocalDate(2026, 10, 30)))
    }

    @Test
    fun bookOnNearestSuitableDay() {
        // Пн и ср утром → ср 21.10 в 07:00.
        assertEquals(LocalDateTime(2026, 10, 21, 7, 0), WaitRules.bookingStart(petrov, now))
        // Любой день вечером → сегодня в 17:00.
        assertEquals(LocalDateTime(2026, 10, 20, 17, 0), WaitRules.bookingStart(smirnova, now))
        // Время не указано — время предложит форма.
        assertNull(WaitRules.bookingStart(WaitWant(typeId = "pt"), now))
    }
}
