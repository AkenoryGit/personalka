package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Строки аккаунта (шаг 2.8а): строка в «Данных», письмо с кодом, устройства, открытое. */
class AccountFormatTest {
    private val today = LocalDate(2026, 10, 24)

    @Test
    fun summary() {
        assertEquals("Не выполнен вход", AccountFormat.summary(null, null))
        assertEquals("Дмитрий · coach@example.ru", AccountFormat.summary("Дмитрий", "coach@example.ru"))
        assertEquals("coach@example.ru", AccountFormat.summary("", "coach@example.ru"))
    }

    @Test
    fun codeSent() {
        assertEquals(
            "Код ушёл на coach@example.ru — действует 15${NBSP}минут. Нет письма — загляните в «Спам».",
            AccountFormat.codeSent("coach@example.ru", 15),
        )
        assertEquals("Код ушёл на a@b.ru — действует 1${NBSP}минуту. Нет письма — загляните в «Спам».", AccountFormat.codeSent("a@b.ru", 1))
    }

    @Test
    fun devices() {
        assertEquals("Samsung SM-F976B · это устройство", AccountFormat.device("Samsung SM-F976B", true, LocalDate(2026, 10, 1), today))
        assertEquals("Pixel 8 · был сегодня", AccountFormat.device("Pixel 8", false, today, today))
        assertEquals("Pixel 8 · был чт${NBSP}22.10", AccountFormat.device("Pixel 8", false, LocalDate(2026, 10, 22), today))
        assertEquals("Pixel 8 · был 30.12.2025", AccountFormat.device("Pixel 8", false, LocalDate(2025, 12, 30), today))
    }

    @Test
    fun openFeatures() {
        assertNull(AccountFormat.open(emptyMap(), emptySet()))
        assertEquals(
            "Открыто: Половина официально, Кабинеты родителей, Копия на сервере",
            AccountFormat.open(Feature.entries.associateWith { null }, emptySet()),
        )
        val until = LocalDate(2026, 10, 30)
        assertEquals(
            "Открыто: Копия на сервере — до${NBSP}30.10.2026, скоро конец",
            AccountFormat.open(mapOf(Feature.SERVER_COPY to until), setOf(Feature.SERVER_COPY)),
        )
        assertEquals(
            "Открыто: Половина официально — до${NBSP}31.12.2026",
            AccountFormat.open(mapOf(Feature.HALF_OFFICIAL to LocalDate(2026, 12, 31)), emptySet()),
        )
    }
}
