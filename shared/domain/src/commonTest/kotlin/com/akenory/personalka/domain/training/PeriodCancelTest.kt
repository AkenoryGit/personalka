package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.format.PeriodCancelFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Не будет на период» (4.2а, шаг 5.3у). */
class PeriodCancelTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)
    private val today = LocalDate(2026, 11, 2)
    private val now = LocalDateTime(2026, 11, 2, 12, 0)

    private fun slot(
        id: String,
        start: LocalDateTime,
        kind: TrainingKind = TrainingKind.PERSONAL,
        status: TrainingStatus = TrainingStatus.PLANNED,
        cancelled: Boolean = false,
    ) = PeriodSlot(id, start, kind, status, cancelled)

    @Test
    fun picksPlannedNotStartedInsideThePeriod() {
        val slots = listOf(
            slot("morning", LocalDateTime(2026, 11, 2, 8, 0)), // уже прошла сегодня
            slot("today", LocalDateTime(2026, 11, 2, 17, 0)),
            slot("group", LocalDateTime(2026, 11, 7, 9, 0), kind = TrainingKind.GROUP),
            slot("already", LocalDateTime(2026, 11, 5, 13, 30), cancelled = true),
            slot("done", LocalDateTime(2026, 11, 4, 13, 30), status = TrainingStatus.DONE),
            slot("duty", LocalDateTime(2026, 11, 6, 11, 0), kind = TrainingKind.DUTY),
            slot("last", LocalDateTime(2026, 11, 15, 20, 0)),
            slot("after", LocalDateTime(2026, 11, 16, 7, 0)),
        )
        val picks = PeriodCancelRules.picks(slots.shuffled(), today, LocalDate(2026, 11, 15), now)
        assertEquals(listOf("today", "group", "last"), picks.map { it.id })
    }

    @Test
    fun problems() {
        assertNull(PeriodCancelRules.problem(today, today, today))
        assertEquals(PeriodProblem.TO_BEFORE_FROM, PeriodCancelRules.problem(today, LocalDate(2026, 11, 1), today))
        assertEquals(PeriodProblem.FROM_IN_PAST, PeriodCancelRules.problem(LocalDate(2026, 11, 1), today, today))
        assertNull(PeriodCancelRules.problem(LocalDate(2027, 6, 1), LocalDate(2027, 8, 31), today))
        assertEquals(PeriodProblem.TOO_LONG, PeriodCancelRules.problem(today, LocalDate(2027, 11, 3), today))
        assertEquals(LocalDate(2026, 11, 15), PeriodCancelRules.defaultTo(today))
    }

    @Test
    fun words() {
        assertEquals(s("Отменится 6_тренировок · пн_02.11 – вс_15.11"), PeriodCancelFormat.summary(6, today, LocalDate(2026, 11, 15)))
        assertEquals(s("Отменится 1_тренировка · пн_02.11"), PeriodCancelFormat.summary(1, today, today))
        assertEquals(s("Отменить 1_тренировку"), PeriodCancelFormat.button(1))
        assertEquals(s("Отменить 3_тренировки"), PeriodCancelFormat.button(3))
        assertEquals(s("Отменено 6_тренировок: 02.11–15.11"), PeriodCancelFormat.done(6, today, LocalDate(2026, 11, 15)))
        assertEquals(s("Отменена 1_тренировка: 02.11"), PeriodCancelFormat.done(1, today, today))
    }
}
