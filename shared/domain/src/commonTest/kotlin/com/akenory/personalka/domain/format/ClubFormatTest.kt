package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ClubFormatTest {
    private val today = LocalDate(2026, 10, 26)

    @Test
    fun statusLine() {
        assertEquals("Обмен сегодня", ClubFormat.status(true, false, today, 0, today))
        assertEquals("Обмен чт${NBSP}22.10${" · "}3${NBSP}кабинета", ClubFormat.status(true, false, LocalDate(2026, 10, 22), 3, today))
        assertEquals("Обмен 30.12.2025", ClubFormat.status(true, false, LocalDate(2025, 12, 30), 0, today))
        assertEquals("Обмена не было", ClubFormat.status(true, false, null, 0, today))
        assertEquals("Почта не подтверждена", ClubFormat.status(false, false, null, 0, today))
        assertEquals("Доступ приостановлен · 1${NBSP}кабинет", ClubFormat.status(true, true, today, 1, today))
    }

    @Test
    fun selfIsMarked() {
        assertEquals("Дмитрий · вы", ClubFormat.name("Дмитрий", self = true))
        assertEquals("Лена", ClubFormat.name("Лена", self = false))
    }
}
