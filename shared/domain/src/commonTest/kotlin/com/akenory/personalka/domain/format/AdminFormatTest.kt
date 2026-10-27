package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки админки на сайте (шаг 5.2б). */
class AdminFormatTest {
    @Test
    fun emailIsHidden() {
        assertEquals("d…@gmail.com", AdminFormat.hide("dmitry@gmail.com"))
        assertEquals("Отправили код на d…@gmail.com — действует 15${NBSP}минут", AdminFormat.codeSent("dmitry@gmail.com", 15))
    }

    @Test
    fun logLine() {
        assertEquals(
            "27.10 09:14 · Елена Соколова · приостановлен",
            AdminFormat.log(LocalDateTime(2026, 10, 27, 9, 14), 2026, listOf("Елена Соколова", AdminFormat.SUSPENDED)),
        )
        assertEquals(
            "22.10.2025 21:40 · Вход в админку · 10.0.0.1",
            AdminFormat.log(LocalDateTime(2025, 10, 22, 21, 40), 2026, listOf(AdminFormat.SIGNED_IN, "", "10.0.0.1")),
        )
        assertEquals("Тренеры · 4", AdminFormat.coaches(4))
    }

    @Test
    fun featuresLine() {
        assertEquals("всё закрыто", AdminFormat.features(emptyMap()))
        assertEquals(
            "Копия на сервере, Кабинеты родителей до 31.12.2026",
            AdminFormat.features(linkedMapOf(Feature.SERVER_COPY to null, Feature.CABINETS to LocalDate(2026, 12, 31))),
        )
        assertEquals("Новым тренерам: Копия на сервере", AdminFormat.defaults(setOf(Feature.SERVER_COPY)))
    }
}
