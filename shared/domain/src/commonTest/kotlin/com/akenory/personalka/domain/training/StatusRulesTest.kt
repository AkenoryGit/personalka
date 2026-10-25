package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Переходы статусов (ТЗ 4.1, 4.2). Вт 20.10.2026, клиенты придуманы. */
class StatusRulesTest {
    private val settings = StatusSettings()

    private fun at(hour: Int, minute: Int) = LocalDateTime(2026, 10, 20, hour, minute)

    private fun pt(start: LocalDateTime = at(10, 0)) = TrainingState(
        kind = TrainingKind.PERSONAL,
        start = start,
        durationMin = 50,
        participants = listOf(ParticipantState("orlov")),
    )

    private fun split() = TrainingState(
        kind = TrainingKind.SPLIT,
        start = at(12, 10),
        durationMin = 50,
        participants = listOf(ParticipantState("nosova"), ParticipantState("zaitseva")),
    )

    /** Суббота-подобная группа: 9:00–10:00, четверо детей. */
    private fun group() = TrainingState(
        kind = TrainingKind.GROUP,
        start = at(9, 0),
        durationMin = 60,
        participants = listOf("a", "b", "c", "d").map { ParticipantState(it) },
    )

    private fun duty() = TrainingState(kind = TrainingKind.DUTY, start = at(11, 0), durationMin = 240)

    private fun TrainingState.statusOf(clientId: String) = participant(clientId).status

    // --- Проведено ---

    @Test
    fun markDoneFromFifteenMinutesBeforeStart() {
        val t = pt()
        assertFalse(StatusRules.canMarkDone(t, at(9, 44)))
        assertTrue(StatusRules.canMarkDone(t, at(9, 45)))
        assertFailsWith<IllegalArgumentException> { StatusRules.markDone(t, at(9, 44)) }

        val done = StatusRules.markDone(t, at(9, 45))
        assertEquals(TrainingStatus.DONE, done.status)
        assertEquals(ATTENDED, done.statusOf("orlov"))
        assertEquals(at(9, 45), done.doneAt)
        assertFalse(StatusRules.canMarkDone(done, at(10, 0)))
    }

    @Test
    fun unmarkDoneBeforeEndMakesPlannedAgain() {
        val done = StatusRules.markDone(pt(), at(9, 50))
        assertTrue(StatusRules.canUnmarkDone(done, at(10, 20), settings))
        val t = StatusRules.unmarkDone(done, at(10, 20), settings)
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertNull(t.doneAt)
        assertEquals(EXPECTED, t.statusOf("orlov"))
        assertTrue(StatusRules.canMarkDone(t, at(10, 21)))
        assertFalse(StatusRules.canUnmarkDone(t, at(10, 21), settings))
    }

    @Test
    fun unmarkDoneAfterEndOnlyWhereItSticks() {
        val done = StatusRules.closeAtEnd(pt(), at(10, 50), settings)
        // Само закрытие поставило бы «Проведено» снова — не прошла, значит «Отменить».
        assertFalse(StatusRules.canUnmarkDone(done, at(11, 0), settings))
        assertFailsWith<IllegalArgumentException> { StatusRules.unmarkDone(done, at(11, 0), settings) }
        val manual = StatusSettings(autoDonePersonal = false)
        val waits = StatusRules.unmarkDone(done, at(11, 0), manual)
        assertEquals(TrainingStatus.PLANNED, waits.status)
        assertSame(waits, StatusRules.closeAtEnd(waits, at(11, 1), manual))
        assertTrue(StatusRules.needsMarking(waits, at(11, 1)))
        // Дежурство закрывается само всегда.
        assertFalse(StatusRules.canUnmarkDone(StatusRules.closeAtEnd(duty(), at(15, 0), manual), at(10, 30), manual))
    }

    @Test
    fun unmarkDoneSplitKeepsCancelledClient() {
        val one = StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.CANNOT_COME, at(8, 0), settings)
        val done = StatusRules.markDone(one, at(12, 0))
        val t = StatusRules.unmarkDone(done, at(12, 30), settings)
        assertEquals(CANCELLED, t.statusOf("nosova"))
        assertEquals(CancelReasons.CANNOT_COME, t.participant("nosova").cancelReason)
        assertEquals(EXPECTED, t.statusOf("zaitseva"))
    }

    @Test
    fun unmarkDoneGroupResetsMarksAndWaits() {
        var t = StatusRules.cancelClients(group(), listOf("d"), CancelReasons.COMPETITION, at(8, 0), settings)
        t = StatusRules.toggleAttendance(t, "a", at(9, 0))
        t = StatusRules.closeAtEnd(t, at(10, 0), settings)
        assertEquals(listOf(ATTENDED, CANCELLED, CANCELLED, CANCELLED), t.participants.map { it.status })
        assertTrue(StatusRules.canUnmarkDone(t, at(20, 0), settings))
        val back = StatusRules.unmarkDone(t, at(20, 0), settings)
        // Пришедший и «не пришли» снова ждут отметки, отменённый заранее — отменён.
        assertEquals(listOf(EXPECTED, EXPECTED, EXPECTED, CANCELLED), back.participants.map { it.status })
        assertSame(back, StatusRules.closeAtEnd(back, at(20, 1), settings))
        assertTrue(StatusRules.needsMarking(back, at(20, 1)))
    }

    @Test
    fun personalClosesItselfAtEnd() {
        val t = pt()
        assertSame(t, StatusRules.closeAtEnd(t, at(10, 49), settings))

        val done = StatusRules.closeAtEnd(t, at(10, 50), settings)
        assertEquals(TrainingStatus.DONE, done.status)
        assertEquals(ATTENDED, done.statusOf("orlov"))
        assertEquals(at(10, 50), done.doneAt)

        // Приложение открыли вечером — «отмечено само» всё равно в момент окончания.
        assertEquals(at(10, 50), StatusRules.closeAtEnd(t, at(21, 15), settings).doneAt)
        // Повторный вызов ничего не меняет.
        assertEquals(done, StatusRules.closeAtEnd(done, at(11, 0), settings))
    }

    @Test
    fun personalWaitsWhenAutoDoneIsOff() {
        val off = StatusSettings(autoDonePersonal = false)
        val t = StatusRules.closeAtEnd(pt(), at(11, 0), off)
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertTrue(StatusRules.needsMarking(t, at(11, 0)))
        assertFalse(StatusRules.needsMarking(t, at(10, 30)))
    }

    @Test
    fun startTrainingClosesLikePersonal() {
        val spt = pt().copy(kind = TrainingKind.SPT)
        assertEquals(TrainingStatus.DONE, StatusRules.closeAtEnd(spt, at(10, 50), settings).status)
    }

    @Test
    fun splitWithOneCancelledClientClosesWithTheOther() {
        val t = StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.CANNOT_COME, at(11, 50), settings)
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertEquals(CANCELLED_CHARGED, t.statusOf("nosova"))

        val done = StatusRules.closeAtEnd(t, at(13, 0), settings)
        assertEquals(TrainingStatus.DONE, done.status)
        assertEquals(ATTENDED, done.statusOf("zaitseva"))
        assertEquals(CANCELLED_CHARGED, done.statusOf("nosova"))
        assertEquals(ClientOutcome.CANCELLED_CHARGED, ClientOutcome.of(done.status, done.statusOf("nosova")))
    }

    @Test
    fun dutyClosesAtEndEvenWithAutoDoneOff() {
        val t = StatusRules.closeAtEnd(duty(), at(15, 0), StatusSettings(autoDonePersonal = false))
        assertEquals(TrainingStatus.DONE, t.status)
    }

    // --- Отмена (4.2) ---

    @Test
    fun personalCancelledLateIsCharged() {
        val t = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(9, 20), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertEquals(CancelReasons.CANNOT_COME, t.cancelReason)
        val p = t.participant("orlov")
        assertEquals(CANCELLED_CHARGED, p.status)
        assertEquals(at(9, 20), p.notifiedAt)
        assertEquals(CancelReasons.CANNOT_COME, p.cancelReason)
        assertFalse(p.chargeOverridden)
        assertEquals(ClientOutcome.CANCELLED_CHARGED, ClientOutcome.of(t.status, p.status))
    }

    @Test
    fun personalCancelledInTimeIsNotCharged() {
        val t = StatusRules.cancelTraining(pt(), CancelReasons.TRIP, at(8, 59), settings)
        assertEquals(CANCELLED, t.statusOf("orlov"))
        assertEquals(ClientOutcome.CANCELLED, ClientOutcome.of(t.status, t.statusOf("orlov")))
    }

    @Test
    fun notifiedTimeIsStoredWithoutSeconds() {
        val t = StatusRules.cancelTraining(pt(), null, LocalDateTime(2026, 10, 20, 9, 0, 45), settings)
        assertEquals(at(9, 0), t.participant("orlov").notifiedAt)
        assertEquals(CANCELLED, t.statusOf("orlov"))
    }

    @Test
    fun blankReasonIsNoReason() {
        val t = StatusRules.cancelTraining(pt(), "   ", at(8, 0), settings)
        assertNull(t.cancelReason)
        assertNull(t.participant("orlov").cancelReason)
    }

    @Test
    fun manualChargeSwitchMarksOverride() {
        // Предупредили за 40 минут, но тренер снимает списание.
        val t = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(9, 20), settings, charged = false)
        assertEquals(CANCELLED, t.statusOf("orlov"))
        assertTrue(t.participant("orlov").chargeOverridden)

        // Вернул списание — итог снова совпадает с правилом.
        val back = StatusRules.setCharge(t, "orlov", charged = true, settings = settings)
        assertEquals(CANCELLED_CHARGED, back.statusOf("orlov"))
        assertFalse(back.participant("orlov").chargeOverridden)

        // Тренер ставит списание при отмене вовремя.
        val inTime = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(8, 0), settings)
        val charged = StatusRules.setCharge(inTime, "orlov", charged = true, settings = settings)
        assertEquals(CANCELLED_CHARGED, charged.statusOf("orlov"))
        assertTrue(charged.participant("orlov").chargeOverridden)
    }

    @Test
    fun overrideEqualToRuleIsNotOverride() {
        val t = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(9, 20), settings, charged = true)
        assertFalse(t.participant("orlov").chargeOverridden)
    }

    @Test
    fun startTrainingCancelIsNeverCharged() {
        val spt = pt().copy(kind = TrainingKind.SPT)
        val t = StatusRules.cancelTraining(spt, CancelReasons.CANNOT_COME, at(9, 50), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertEquals(CANCELLED, t.statusOf("orlov"))
    }

    @Test
    fun coachInitiativeIsNotCharged() {
        val t = StatusRules.cancelTraining(pt(), CancelReasons.BY_COACH, at(9, 55), settings)
        assertEquals(CANCELLED, t.statusOf("orlov"))
    }

    @Test
    fun splitIsCancelledOnlyWhenBothAre() {
        val one = StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.CANNOT_COME, at(9, 0), settings)
        assertEquals(TrainingStatus.PLANNED, one.status)
        assertNull(one.cancelReason)

        val both = StatusRules.cancelClients(one, listOf("zaitseva"), CancelReasons.TRIP, at(11, 30), settings)
        assertEquals(TrainingStatus.CANCELLED, both.status)
        assertEquals(CancelReasons.TRIP, both.cancelReason)
        // Каждый — по своему времени: Носова вовремя, Зайцева за 40 минут.
        assertEquals(CANCELLED, both.statusOf("nosova"))
        assertEquals(CANCELLED_CHARGED, both.statusOf("zaitseva"))
    }

    @Test
    fun wholeSplitCancelKeepsEarlierCancellation() {
        val one = StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.CANNOT_COME, at(11, 50), settings)
        val all = StatusRules.cancelTraining(one, CancelReasons.BY_CLUB, at(12, 0), settings)
        assertEquals(TrainingStatus.CANCELLED, all.status)
        assertEquals(CANCELLED_CHARGED, all.statusOf("nosova"))
        assertEquals(CancelReasons.CANNOT_COME, all.participant("nosova").cancelReason)
        assertEquals(CANCELLED, all.statusOf("zaitseva"))
    }

    @Test
    fun cannotCancelTwiceOrCancelClientsOfDuty() {
        val t = StatusRules.cancelTraining(pt(), null, at(8, 0), settings)
        assertFailsWith<IllegalArgumentException> { StatusRules.cancelTraining(t, null, at(8, 0), settings) }
        assertFailsWith<IllegalArgumentException> {
            StatusRules.cancelClients(duty(), listOf("x"), null, at(8, 0), settings)
        }
        assertFailsWith<IllegalArgumentException> {
            StatusRules.cancelClients(split(), listOf("nobody"), null, at(8, 0), settings)
        }
    }

    @Test
    fun dutyIsCancelledWithoutReason() {
        val t = StatusRules.cancelTraining(duty(), CancelReasons.CANNOT_COME, at(10, 59), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertNull(t.cancelReason)
    }

    @Test
    fun cancelAfterDoneCorrectsThePast() {
        // Отметилось само, а клиент на самом деле не пришёл и не предупредил.
        val done = StatusRules.closeAtEnd(pt(), at(11, 0), settings)
        val t = StatusRules.cancelTraining(done, null, at(11, 0), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertNull(t.doneAt)
        assertEquals(CANCELLED_CHARGED, t.statusOf("orlov"))
    }

    // --- Группа ---

    @Test
    fun groupMarkingStartsFifteenMinutesBefore() {
        assertFailsWith<IllegalArgumentException> { StatusRules.toggleAttendance(group(), "a", at(8, 44)) }
        val t = StatusRules.toggleAttendance(group(), "a", at(8, 45))
        assertEquals(ATTENDED, t.statusOf("a"))
        assertEquals(TrainingStatus.PLANNED, t.status)
        // Повторное нажатие — обратно в «Ещё не отмечены».
        assertEquals(EXPECTED, StatusRules.toggleAttendance(t, "a", at(9, 4)).statusOf("a"))
    }

    @Test
    fun groupClosesAtEndWhenSomeoneIsMarked() {
        var t = group()
        t = StatusRules.toggleAttendance(t, "a", at(9, 4))
        t = StatusRules.toggleAttendance(t, "b", at(9, 5))
        assertSame(t, StatusRules.closeAtEnd(t, at(9, 59), settings))

        val done = StatusRules.closeAtEnd(t, at(10, 0), settings)
        assertEquals(TrainingStatus.DONE, done.status)
        assertEquals(at(10, 0), done.doneAt)
        assertEquals(listOf(ATTENDED, ATTENDED, CANCELLED, CANCELLED), done.participants.map { it.status })
        val noShow = done.participant("c")
        assertNull(noShow.cancelReason)
        assertNull(noShow.notifiedAt)
        assertEquals(ClientOutcome.CANCELLED, ClientOutcome.of(done.status, noShow.status))
    }

    @Test
    fun groupWithNobodyMarkedWaits() {
        val t = StatusRules.closeAtEnd(group(), at(12, 0), settings)
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertTrue(StatusRules.needsMarking(t, at(12, 0)))
    }

    @Test
    fun allCameMarksEveryoneExceptCancelled() {
        val t = StatusRules.cancelClients(group(), listOf("d"), CancelReasons.COMPETITION, at(8, 0), settings)
        val done = StatusRules.markDone(t, at(9, 4))
        assertEquals(TrainingStatus.DONE, done.status)
        assertEquals(listOf(ATTENDED, ATTENDED, ATTENDED, CANCELLED), done.participants.map { it.status })
    }

    @Test
    fun groupChildCancelKeepsGroup() {
        val t = StatusRules.cancelClients(group(), listOf("b"), CancelReasons.CANNOT_COME, at(8, 30), settings)
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertEquals(CANCELLED_CHARGED, t.statusOf("b"))
        assertEquals(EXPECTED, t.statusOf("a"))
    }

    @Test
    fun wholeGroupCancelIsWithoutChargeForAll() {
        val one = StatusRules.cancelClients(group(), listOf("b"), CancelReasons.CANNOT_COME, at(8, 30), settings)
        val t = StatusRules.cancelTraining(one, CancelReasons.BY_CLUB, at(8, 40), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertEquals(CancelReasons.BY_CLUB, t.cancelReason)
        t.participants.forEach {
            assertEquals(CANCELLED, it.status)
            assertEquals(CancelReasons.BY_CLUB, it.cancelReason)
            assertNull(it.notifiedAt)
        }
        assertFalse(StatusRules.autoCharged(t, "b", settings))
    }

    @Test
    fun groupWithEveryChildCancelledIsCancelledAndKeepsCharges() {
        var t = group()
        t = StatusRules.cancelClients(t, listOf("a", "b"), CancelReasons.CANNOT_COME, at(7, 0), settings)
        t = StatusRules.cancelClients(t, listOf("c", "d"), CancelReasons.CANNOT_COME, at(8, 30), settings)
        assertEquals(TrainingStatus.CANCELLED, t.status)
        assertEquals(listOf(CANCELLED, CANCELLED, CANCELLED_CHARGED, CANCELLED_CHARGED), t.participants.map { it.status })
    }

    @Test
    fun doneGroupCanBeCorrected() {
        val done = StatusRules.markDone(group(), at(9, 4))
        val noShow = StatusRules.toggleAttendance(done, "c", at(20, 0))
        assertEquals(CANCELLED, noShow.statusOf("c"))
        assertEquals(TrainingStatus.DONE, noShow.status)
        assertEquals(ATTENDED, StatusRules.toggleAttendance(noShow, "c", at(20, 1)).statusOf("c"))
    }

    @Test
    fun attendanceTogglingIsOnlyForGroups() {
        assertFailsWith<IllegalArgumentException> { StatusRules.toggleAttendance(split(), "nosova", at(12, 10)) }
    }

    // --- Вернуть ---

    @Test
    fun returnBeforeEndMakesPlannedAgain() {
        val cancelled = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(9, 20), settings)
        assertTrue(StatusRules.canReturn(cancelled))
        val t = StatusRules.returnTraining(cancelled, at(9, 30))
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertNull(t.cancelReason)
        assertEquals(ParticipantState("orlov"), t.participant("orlov"))
    }

    @Test
    fun returnAfterEndMakesDone() {
        val cancelled = StatusRules.cancelTraining(pt(), CancelReasons.CANNOT_COME, at(9, 20), settings)
        val t = StatusRules.returnTraining(cancelled, at(18, 0))
        assertEquals(TrainingStatus.DONE, t.status)
        assertEquals(ATTENDED, t.statusOf("orlov"))
        assertEquals(at(18, 0), t.doneAt)
        assertFailsWith<IllegalArgumentException> { StatusRules.returnTraining(t, at(18, 1)) }
    }

    @Test
    fun returnedGroupAfterEndWaitsForMarking() {
        val cancelled = StatusRules.cancelTraining(group(), CancelReasons.BY_CLUB, at(8, 0), settings)
        val t = StatusRules.returnTraining(cancelled, at(18, 0))
        assertEquals(TrainingStatus.PLANNED, t.status)
        assertTrue(t.participants.all { it.status == EXPECTED })
        assertTrue(StatusRules.needsMarking(t, at(18, 0)))
    }

    @Test
    fun returnOneClient() {
        // Проведённый сплит, Носова отменила со списанием, а потом оказалось — пришла.
        val t = StatusRules.closeAtEnd(
            StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.CANNOT_COME, at(11, 50), settings),
            at(13, 0),
            settings,
        )
        val back = StatusRules.returnClient(t, "nosova", at(14, 0))
        assertEquals(ATTENDED, back.statusOf("nosova"))
        assertNull(back.participant("nosova").cancelReason)

        // Запланированная — снова ожидается.
        val planned = StatusRules.cancelClients(split(), listOf("nosova"), CancelReasons.TRIP, at(8, 0), settings)
        assertEquals(EXPECTED, StatusRules.returnClient(planned, "nosova", at(9, 0)).statusOf("nosova"))
        assertFailsWith<IllegalArgumentException> { StatusRules.returnClient(planned, "zaitseva", at(9, 0)) }
    }

    @Test
    fun returnOneClientOfCancelledSplit() {
        val both = StatusRules.cancelTraining(split(), CancelReasons.CANNOT_COME, at(8, 0), settings)
        val before = StatusRules.returnClient(both, "zaitseva", at(9, 0))
        assertEquals(TrainingStatus.PLANNED, before.status)
        assertNull(before.cancelReason)
        assertEquals(EXPECTED, before.statusOf("zaitseva"))
        assertEquals(CANCELLED, before.statusOf("nosova"))

        val after = StatusRules.returnClient(both, "zaitseva", at(14, 0))
        assertEquals(TrainingStatus.DONE, after.status)
        assertEquals(ATTENDED, after.statusOf("zaitseva"))
        assertEquals(at(14, 0), after.doneAt)
    }

    // --- Итоговый статус и фаза ---

    @Test
    fun clientOutcomeTable() {
        assertEquals(ClientOutcome.PLANNED, ClientOutcome.of(TrainingStatus.PLANNED, EXPECTED))
        assertEquals(ClientOutcome.DONE, ClientOutcome.of(TrainingStatus.DONE, ATTENDED))
        assertEquals(ClientOutcome.CANCELLED, ClientOutcome.of(TrainingStatus.DONE, CANCELLED))
        assertEquals(ClientOutcome.CANCELLED_CHARGED, ClientOutcome.of(TrainingStatus.DONE, CANCELLED_CHARGED))
        assertEquals(ClientOutcome.CANCELLED_CHARGED, ClientOutcome.of(TrainingStatus.CANCELLED, CANCELLED_CHARGED))
        assertEquals(ClientOutcome.CANCELLED, ClientOutcome.of(TrainingStatus.CANCELLED, CANCELLED))
        assertEquals(ClientOutcome.CANCELLED, ClientOutcome.of(TrainingStatus.CANCELLED, EXPECTED))
        assertEquals("Отмена со списанием", ClientOutcome.CANCELLED_CHARGED.label)
    }

    @Test
    fun phaseByTime() {
        val t = pt()
        assertEquals(TrainingPhase.UPCOMING, TrainingPhase.of(t, at(9, 44)))
        assertEquals(TrainingPhase.STARTING_SOON, TrainingPhase.of(t, at(9, 45)))
        assertEquals(TrainingPhase.ONGOING, TrainingPhase.of(t, at(10, 0)))
        assertEquals(TrainingPhase.ONGOING, TrainingPhase.of(t, at(10, 49)))
        assertEquals(TrainingPhase.ENDED, TrainingPhase.of(t, at(10, 50)))
    }

    // --- Время тренировки (4.14) ---

    @Test
    fun trainingDoesNotCrossMidnight() {
        assertNull(TrainingTime.problem(at(23, 10), 50))
        assertEquals(TimeProblem.CROSSES_MIDNIGHT, TrainingTime.problem(at(23, 30), 50))
        assertEquals(TimeProblem.TOO_SHORT, TrainingTime.problem(at(10, 0), 4))
        assertEquals(TimeProblem.TOO_LONG, TrainingTime.problem(at(7, 0), 601))
        assertNull(TrainingTime.problem(at(6, 30), 30))
        assertEquals(LocalDateTime(2026, 10, 21, 0, 0), TrainingTime.end(at(23, 10), 50))
    }
}
