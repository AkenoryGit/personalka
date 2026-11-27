package com.akenory.personalka.domain.cabinet

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Посещения кабинетов в админке (шаг 5.35): что считается посещением, группы и порядок. */
class CabinetVisitsTest {
    private val today = LocalDate(2026, 11, 26)

    private fun cabinet(
        id: String,
        name: String?,
        last: LocalDateTime?,
        days: Map<LocalDate, Int> = emptyMap(),
        issued: LocalDate = LocalDate(2026, 10, 1),
    ) = CabinetVisits(id, issued, last, days, name)

    @Test
    fun visitStartsAfterHalfAnHour() {
        val now = 1_800_000_000_000L
        assertTrue(CabinetVisitRules.newVisit(null, now), "первое открытие — посещение")
        assertFalse(CabinetVisitRules.newVisit(now - 29 * 60_000L, now), "график листают — то же посещение")
        assertTrue(CabinetVisitRules.newVisit(now - 30 * 60_000L, now))
    }

    @Test
    fun useByLastOpening() {
        // Окно — сегодня и 29 дней до него: 28.10 ещё в нём, 27.10 — уже нет.
        assertEquals(LocalDate(2026, 10, 28), CabinetVisitRules.recentFrom(today))
        assertEquals(CabinetUse.ACTIVE, CabinetVisitRules.use(cabinet("a", null, LocalDateTime(2026, 10, 28, 8, 0)), today))
        assertEquals(CabinetUse.IDLE, CabinetVisitRules.use(cabinet("a", null, LocalDateTime(2026, 10, 27, 23, 59)), today))
        assertEquals(CabinetUse.NEVER, CabinetVisitRules.use(cabinet("a", null, null), today))
    }

    @Test
    fun countsInWindowAndTotal() {
        val days = mapOf(
            LocalDate(2026, 11, 26) to 2,
            LocalDate(2026, 11, 20) to 1,
            LocalDate(2026, 10, 28) to 1,
            LocalDate(2026, 10, 27) to 4,
        )
        val line = CabinetVisitRules.line(cabinet("a", "Орлова Катя", LocalDateTime(2026, 11, 26, 7, 40), days), today)
        assertEquals(CabinetUse.ACTIVE, line.use)
        assertEquals(4, line.recent)
        assertEquals(3, line.recentDays)
        assertEquals(8, line.total)
    }

    @Test
    fun mediaClicksInWindow() {
        val v = cabinet("a", null, LocalDateTime(2026, 11, 26, 7, 40)).copy(
            lastMedia = LocalDateTime(2026, 11, 25, 21, 14),
            media = mapOf(LocalDate(2026, 11, 25) to 2, LocalDate(2026, 10, 27) to 1),
        )
        assertEquals(2, CabinetVisitRules.line(v, today).mediaRecent, "27.10 — за окном 30 дней")
    }

    @Test
    fun groupsAndOrder() {
        val list = listOf(
            cabinet("1", "Яковлева Алиса", null, issued = LocalDate(2026, 11, 20)),
            cabinet("2", "Беляев Тимофей", LocalDateTime(2026, 11, 25, 21, 14)),
            cabinet("3", "Ершова Злата", null, issued = LocalDate(2026, 10, 3)),
            cabinet("4", "Жуков Марк", LocalDateTime(2026, 9, 1, 10, 0)),
            cabinet("5", "Андреев Глеб", LocalDateTime(2026, 11, 26, 8, 5)),
        )
        val groups = CabinetVisitRules.lines(list, today)
        assertEquals(listOf(CabinetUse.ACTIVE, CabinetUse.IDLE, CabinetUse.NEVER), groups.keys.toList())
        assertEquals(listOf("5", "2"), groups.getValue(CabinetUse.ACTIVE).map { it.visits.clientId }, "последние открывшие сверху")
        assertEquals(listOf("4"), groups.getValue(CabinetUse.IDLE).map { it.visits.clientId })
        assertEquals(listOf("3", "1"), groups.getValue(CabinetUse.NEVER).map { it.visits.clientId }, "раньше выдали — выше")
        assertEquals(CabinetUsage(total = 5, active = 2, idle = 1, never = 2), CabinetVisitRules.usage(list, today))
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        val groups = CabinetVisitRules.lines(listOf(cabinet("1", null, null)), today)
        assertEquals(listOf(CabinetUse.NEVER), groups.keys.toList())
        assertEquals(CabinetUsage(0, 0, 0, 0), CabinetVisitRules.usage(emptyList(), today))
    }
}
