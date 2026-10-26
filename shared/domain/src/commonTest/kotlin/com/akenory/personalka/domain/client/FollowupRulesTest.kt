package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** «Без записи» (шаг 2.4, ТЗ 4.15, 5.8): пример — макет ClNoBooking, вт 20.10.2026, 15:40. */
class FollowupRulesTest {
    private val today = LocalDate(2026, 10, 20)
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun visit(day: Int, hour: Int, kind: TrainingKind = TrainingKind.PERSONAL, month: Int = 10) =
        Visit(LocalDateTime(2026, month, day, hour, 0), kind, typeId = kind.name, locationId = "bb")

    private fun input(
        id: String,
        vararg visits: Visit,
        booked: Boolean = false,
        contacted: LocalDate? = null,
        paused: LocalDate? = null,
        active: Boolean = true,
        waiting: Boolean = false,
    ) = FollowupInput(id, active, visits.toList(), booked, contacted, paused, waiting)

    // Макет: Яковлева — СПТ пн 12.10; Захаров — пт 09.10, писали 16.10; Соколова — сегодня 15:00.
    private val mockup = listOf(
        input("sokolova", visit(20, 15)),
        input("zakharov", visit(9, 8), contacted = LocalDate(2026, 10, 16)),
        input("yakovleva", visit(12, 10, TrainingKind.SPT)),
    )

    @Test
    fun mockupOrderAfterSptFirstThenOldest() {
        val list = FollowupRules.list(mockup, today)
        assertEquals(listOf("yakovleva", "zakharov", "sokolova"), list.map { it.clientId })
        assertEquals(LocalDate(2026, 10, 12), list[0].afterSpt)
        assertEquals(LocalDate(2026, 10, 16), list[1].contactedOn)
        assertNull(list[2].afterSpt)
    }

    /** Шаг 3.9н: «Убрать из списка» — до новой индивидуальной после нажатия; группы её не заменяют. */
    @Test
    fun dismissedUntilNewIndividualVisit() {
        val moved = FollowupInput("moved", true, listOf(visit(12, 10), visit(17, 9, TrainingKind.GROUP)), booked = false,
            dismissedAt = LocalDateTime(2026, 10, 18, 12, 0))
        assertNull(FollowupRules.notBooked(moved, today))
        assertEquals(emptyList(), FollowupRules.list(listOf(moved), today))
        // Нажали утром, а ПТ была вечером того же дня — это новая ПТ без записи.
        val again = moved.copy(visits = moved.visits + visit(18, 18))
        assertEquals(listOf("moved"), FollowupRules.list(listOf(again), today).map { it.clientId })
    }

    @Test
    fun windowIsFourteenDaysIncludingToday() {
        assertEquals(LocalDate(2026, 10, 7), FollowupRules.firstDay(today, 14))
        val list = FollowupRules.list(listOf(input("in", visit(7, 23)), input("out", visit(6, 23))), today)
        assertEquals(listOf("in"), list.map { it.clientId })
        // Своё N: за 7 дней 09.10 уже не считается.
        assertEquals(emptyList(), FollowupRules.list(listOf(input("old", visit(9, 8))), today, days = 7))
    }

    @Test
    fun futureTrainingOrArchiveOrWaitlistRemoves() {
        assertNull(FollowupRules.notBooked(input("a", visit(19, 10), booked = true), today))
        assertNull(FollowupRules.notBooked(input("a", visit(19, 10), active = false), today))
        assertNull(FollowupRules.notBooked(input("a", visit(19, 10), waiting = true), today))
        assertNotNull(FollowupRules.notBooked(input("a", visit(19, 10)), today))
    }

    @Test
    fun groupsAreNotCounted() {
        // Только группа — не в списке; ПТ и группа — считается последняя ПТ.
        assertNull(FollowupRules.notBooked(input("g", visit(19, 9, TrainingKind.GROUP)), today))
        val both = FollowupRules.notBooked(input("pg", visit(14, 10), visit(19, 9, TrainingKind.GROUP)), today)
        assertEquals(LocalDateTime(2026, 10, 14, 10, 0), both?.start)
        // Сплит и СПТ — индивидуальные.
        assertNotNull(FollowupRules.notBooked(input("sp", visit(19, 10, TrainingKind.SPLIT)), today))
        assertNull(FollowupRules.notBooked(input("d", visit(19, 10, TrainingKind.DUTY)), today))
    }

    @Test
    fun contactedHidesForThreeDaysThenReturnsWithMark() {
        val visit = visit(14, 10)
        fun on(day: Int) = FollowupRules.list(listOf(input("a", visit, contacted = LocalDate(2026, 10, 18))), LocalDate(2026, 10, day))
        assertEquals(emptyList(), on(18))
        assertEquals(emptyList(), on(20))
        assertEquals(LocalDate(2026, 10, 18), on(21).single().contactedOn)
        // «Написал» до последнего посещения уже ничего не значит: ни скрытия, ни пометки.
        val old = FollowupRules.list(listOf(input("a", visit(19, 10), contacted = LocalDate(2026, 10, 18))), today)
        assertNull(old.single().contactedOn)
    }

    @Test
    fun pauseHidesUntilChosenDay() {
        val paused = listOf(input("a", visit(14, 10), paused = LocalDate(2026, 10, 25)))
        assertEquals(emptyList(), FollowupRules.list(paused, today))
        assertEquals(emptyList(), FollowupRules.list(paused, LocalDate(2026, 10, 24)))
        // В сам день паузы клиент возвращается — если посещение ещё в окне.
        val back = FollowupRules.list(listOf(input("a", visit(19, 10), paused = LocalDate(2026, 10, 25))), LocalDate(2026, 10, 25))
        assertEquals(listOf("a"), back.map { it.clientId })
        // Метка «Нет записи» в карточке паузу не смотрит.
        assertNotNull(FollowupRules.notBooked(paused.single(), today))
    }

    @Test
    fun bookOnNearestSameWeekdayAndTime() {
        // Пн 12.10 10:00 → пн 26.10 10:00 (сегодня вт 20.10).
        assertEquals(LocalDateTime(2026, 10, 26, 10, 0), FollowupRules.bookingStart(visit(12, 10), now))
        // Был сегодня в 15:00 — уже прошло, значит через неделю.
        assertEquals(LocalDateTime(2026, 10, 27, 15, 0), FollowupRules.bookingStart(visit(20, 15), now))
        // Вт 13.10 в 18:00 — сегодня ещё успеем.
        assertEquals(LocalDateTime(2026, 10, 20, 18, 0), FollowupRules.bookingStart(visit(13, 18), now))
    }

    @Test
    fun mockupStrings() {
        assertEquals(s("была пн_12.10 · СПТ_ББ"), FollowupFormat.lastVisit(Sex.FEMALE, LocalDateTime(2026, 10, 12, 10, 0), "СПТ", "ББ", today))
        assertEquals(s("был пт_09.10 · ПТ_ДБ"), FollowupFormat.lastVisit(Sex.MALE, LocalDateTime(2026, 10, 9, 8, 0), "ПТ", "ДБ", today))
        assertEquals(s("была сегодня 15:00 · ПТ_ДБ"), FollowupFormat.lastVisit(Sex.FEMALE, LocalDateTime(2026, 10, 20, 15, 0), "ПТ", "ДБ", today))
        assertEquals(s("последняя вт_13.10 · ПТ_ББ"), FollowupFormat.lastVisit(null, LocalDateTime(2026, 10, 13, 8, 0), "ПТ", "ББ", today))
        assertEquals("после СПТ 12.10", FollowupFormat.afterSpt(LocalDate(2026, 10, 12)))
        assertEquals("писали 16.10", FollowupFormat.contacted(LocalDate(2026, 10, 16)))
        assertEquals(s("Ходили за последние 14_дней, будущих тренировок нет"), FollowupFormat.sectionNote(14))
        assertEquals("Без записи · 6", FollowupFormat.chip(6))
        assertEquals("Пауза до 01.11 · отпуск", FollowupFormat.paused(LocalDate(2026, 11, 1), " отпуск "))
        assertEquals("Пауза до 01.11", FollowupFormat.paused(LocalDate(2026, 11, 1), ""))
    }

    @Test
    fun homeTodo() {
        val names = listOf(ClientName("Яковлева", "Татьяна"), ClientName("Захаров", "Никита"), ClientName("Соколова", "Вера"))
        assertEquals("Клиенты без записи", FollowupFormat.todo(3))
        assertEquals("Клиент без записи", FollowupFormat.todo(1))
        assertEquals(s("Яковлева_Т., Захаров_Н. и ещё 1"), FollowupFormat.todoNote(names))
        assertEquals(s("Соколова_В."), FollowupFormat.todoNote(names.takeLast(1)))
    }
}
