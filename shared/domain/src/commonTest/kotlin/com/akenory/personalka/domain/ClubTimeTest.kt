package com.akenory.personalka.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ClubTimeTest {

    @Test
    fun clubZoneIsUtcPlusFiveInAutumn() {
        val club = LocalDateTime(2026, 10, 22, 10, 9).toInstant(ClubTime.defaultZone)
        val utc = LocalDateTime(2026, 10, 22, 5, 9).toInstant(TimeZone.UTC)
        assertEquals(utc, club)
    }

    @Test
    fun clubZoneHasNoSummerTime() {
        val club = LocalDateTime(2026, 7, 1, 7, 0).toInstant(ClubTime.defaultZone)
        val utc = LocalDateTime(2026, 7, 1, 2, 0).toInstant(TimeZone.UTC)
        assertEquals(utc, club)
    }

    @Test
    fun lateEveningInUtcIsNextDayInClub() {
        // 22:30 UTC 21.10 — это уже 03:30 22.10 по клубу: день тренировки считается по клубу (4.6, 4.14).
        val moment = LocalDateTime(2026, 10, 21, 22, 30).toInstant(TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 10, 22, 3, 30), moment.toLocalDateTime(ClubTime.defaultZone))
    }

    @Test
    fun nowIsClubTimeWithoutSeconds() {
        // 10:40:37 UTC — это 15:40 по клубу; секунды отбрасываются (4.2).
        val millis = LocalDateTime(2026, 10, 20, 10, 40, 37, 500_000_000).toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(LocalDateTime(2026, 10, 20, 15, 40), ClubTime.now(millis))
        assertEquals(LocalDate(2026, 10, 20), ClubTime.today(millis))
    }

    @Test
    fun todayFollowsClubNotUtc() {
        // 19:30 UTC 21.10 — по клубу уже 00:30 22.10.
        val millis = LocalDateTime(2026, 10, 21, 19, 30).toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals(LocalDate(2026, 10, 22), ClubTime.today(millis))
    }

    @Test
    fun toMillisIsInverseOfNow() {
        val club = LocalDateTime(2026, 10, 20, 8, 50)
        assertEquals(club, ClubTime.now(ClubTime.toMillis(club)))
    }

    @Test
    fun minutesBetweenIgnoresSecondsAndCrossesMidnight() {
        val start = LocalDateTime(2026, 10, 20, 10, 0)
        assertEquals(40L, ClubTime.minutesBetween(LocalDateTime(2026, 10, 20, 9, 20), start))
        assertEquals(60L, ClubTime.minutesBetween(LocalDateTime(2026, 10, 20, 9, 0, 59), start))
        assertEquals(-5L, ClubTime.minutesBetween(LocalDateTime(2026, 10, 20, 10, 5), start))
        // Предупредили накануне вечером: 22:30 → 07:00 — 8 ч 30 мин.
        assertEquals(510L, ClubTime.minutesBetween(LocalDateTime(2026, 10, 19, 22, 30), LocalDateTime(2026, 10, 20, 7, 0)))
        // Через границу месяца и года.
        assertEquals(24L * 60, ClubTime.minutesBetween(LocalDateTime(2026, 12, 31, 9, 0), LocalDateTime(2027, 1, 1, 9, 0)))
    }

    @Test
    fun plusMinutesKeepsDayOrCrossesIt() {
        assertEquals(LocalDateTime(2026, 10, 20, 13, 0), ClubTime.plusMinutes(LocalDateTime(2026, 10, 20, 12, 10), 50))
        assertEquals(LocalDateTime(2026, 10, 20, 9, 45), ClubTime.plusMinutes(LocalDateTime(2026, 10, 20, 10, 0), -15))
        assertEquals(LocalDateTime(2026, 10, 21, 0, 0), ClubTime.plusMinutes(LocalDateTime(2026, 10, 20, 23, 0), 60))
        assertEquals(LocalDateTime(2026, 10, 31, 23, 50), ClubTime.plusMinutes(LocalDateTime(2026, 11, 1, 0, 5), -15))
    }
}
