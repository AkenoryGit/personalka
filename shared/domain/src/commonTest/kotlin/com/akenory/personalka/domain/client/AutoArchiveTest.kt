package com.akenory.personalka.domain.client

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Архив сам собой (шаг 5.24): не был 90 дней и никуда не записан. */
class AutoArchiveTest {
    private val today = LocalDate(2026, 11, 20)

    @Test
    fun ninetyDaysWithoutVisits() {
        val inputs = listOf(
            // Был 22.08 — ровно 90 дней назад: в архив.
            ArchiveInput("a", LocalDate(2026, 3, 1), LocalDate(2026, 8, 22), planned = false, colleagueGroup = false),
            // Был 23.08 — 89 дней: остаётся.
            ArchiveInput("b", LocalDate(2026, 3, 1), LocalDate(2026, 8, 23), planned = false, colleagueGroup = false),
            // Ни разу не был, карточка заведена в июле: в архив.
            ArchiveInput("c", LocalDate(2026, 7, 1), null, planned = false, colleagueGroup = false),
            // Давно не был, но вернули из архива вчера: остаётся.
            ArchiveInput("d", LocalDate(2026, 11, 19), LocalDate(2026, 5, 1), planned = false, colleagueGroup = false),
            // Записан на тренировку впереди: остаётся.
            ArchiveInput("e", LocalDate(2026, 3, 1), LocalDate(2026, 5, 1), planned = true, colleagueGroup = false),
            // В группе коллеги: остаётся.
            ArchiveInput("f", LocalDate(2026, 3, 1), null, planned = false, colleagueGroup = true),
        )
        assertEquals(listOf("a", "c"), AutoArchiveRules.stale(inputs, today))
    }
}
