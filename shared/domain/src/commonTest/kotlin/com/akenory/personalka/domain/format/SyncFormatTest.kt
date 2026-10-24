package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.format.RuText.THIN
import com.akenory.personalka.domain.sync.SyncProblem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Строки синхронизации (шаг 2.10а): состояние в «Данных», полоса выгрузки, причины, «Нужно сделать». */
class SyncFormatTest {
    private val today = LocalDate(2026, 10, 24)

    @Test
    fun status() {
        assertEquals("Ещё не синхронизировано", SyncFormat.status(null, 0, today))
        assertEquals("Синхронизировано сегодня в 14:05", SyncFormat.status(LocalDateTime(2026, 10, 24, 14, 5), 0, today))
        assertEquals(
            "Синхронизировано вчера в 09:00 · ждут отправки${NBSP}3",
            SyncFormat.status(LocalDateTime(2026, 10, 23, 9, 0), 3, today),
        )
        assertEquals("Синхронизировано вт${NBSP}20.10 в 21:30", SyncFormat.status(LocalDateTime(2026, 10, 20, 21, 30), 0, today))
        assertEquals("Ещё не синхронизировано · ждут отправки${NBSP}1${THIN}200", SyncFormat.status(null, 1200, today))
    }

    @Test
    fun progressAndLate() {
        assertEquals("Выгружено 120 из 1${THIN}200", SyncFormat.progress(120, 1200))
        assertEquals("последняя синхронизация — 22.10.2026", SyncFormat.lateNote(LocalDateTime(2026, 10, 22, 14, 5)))
        assertEquals("синхронизации ещё не было", SyncFormat.lateNote(null))
    }

    @Test
    fun everyProblemHasText() {
        SyncProblem.entries.forEach { assertTrue(SyncFormat.problem(it).isNotBlank()) }
        assertEquals("Нет связи с сервером — обмен повторится сам", SyncFormat.problem(SyncProblem.OFFLINE))
    }
}
