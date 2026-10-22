package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/** Сервер считает тем же кодом, что и приложение (ТЗ 2.2): JVM-сборка shared/domain подключается и работает. */
@OptIn(ExperimentalTime::class)
class SharedDomainTest {

    @Test
    fun serverSeesClubTimeFromSharedDomain() {
        val moment = LocalDateTime(2026, 10, 22, 5, 9).toInstant(TimeZone.UTC)
        assertEquals(LocalDateTime(2026, 10, 22, 10, 9), moment.toLocalDateTime(ClubTime.defaultZone))
    }
}
