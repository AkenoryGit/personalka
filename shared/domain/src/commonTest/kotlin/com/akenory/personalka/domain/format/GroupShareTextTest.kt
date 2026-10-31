package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ParticipantState
import com.akenory.personalka.domain.training.TrainingState
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Поделиться» пришедшими на группу (ТЗ 5.4, шаг 5.3п). Имена придуманы. */
class GroupShareTextTest {
    private val start = LocalDateTime(2026, 10, 31, 9, 0)

    private fun state(status: TrainingStatus, vararg people: ParticipantState) =
        TrainingState(TrainingKind.GROUP, start, 45, status, participants = people.toList())

    @Test
    fun onlyCameSortedBySurname() {
        // Идёт: двое пришли, один ещё не отмечен, один отменил. «На раз» — тот же список.
        val going = state(TrainingStatus.PLANNED, ParticipantState("a", ATTENDED), ParticipantState("b", EXPECTED), ParticipantState("c", CANCELLED), ParticipantState("d", ATTENDED))
        assertTrue(GroupShareFormat.canShare(going))
        val text = GroupShareFormat.text(start, "Сб 9:00 ББ", listOf(ClientName("Данилова", "Ева"), ClientName("Белов", "Артём")))
        assertEquals("Сб 31.10 · 09:00 · Сб 9:00 ББ\nПришли: 2\n1. Белов Артём\n2. Данилова Ева", text)
        assertFalse("из" in text.substringAfter("Пришли"), "Без «из 12» и процентов")
    }

    @Test
    fun doneGroupAndWhenNoIcon() {
        val done = state(TrainingStatus.DONE, ParticipantState("a", ATTENDED), ParticipantState("b", CANCELLED))
        assertTrue(GroupShareFormat.canShare(done))
        assertEquals("Сб 31.10 · 09:00 · Сб 9:00 ББ\nПришли: 1\n1. Белова Аня", GroupShareFormat.text(start, "Сб 9:00 ББ", listOf(ClientName("Белова", "Аня"))))
        // Никто не отмечен, отменённая, не группа — значка нет.
        assertFalse(GroupShareFormat.canShare(state(TrainingStatus.PLANNED, ParticipantState("a", EXPECTED))))
        assertFalse(GroupShareFormat.canShare(state(TrainingStatus.CANCELLED, ParticipantState("a", ATTENDED))))
        assertFalse(GroupShareFormat.canShare(done.copy(kind = TrainingKind.PERSONAL)))
    }
}
