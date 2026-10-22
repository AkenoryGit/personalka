package com.akenory.personalka.domain

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
}
