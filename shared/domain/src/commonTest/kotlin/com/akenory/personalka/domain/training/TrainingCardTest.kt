package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Карточка тренировки и панель отмены (шаг 1.14, ТЗ 5.4, 5.6). Имена условные. */
class TrainingCardTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(h: Int, m: Int = 0) = LocalDateTime(2026, 10, 20, h, m)

    private fun pt(status: TrainingStatus = TrainingStatus.PLANNED, kind: TrainingKind = TrainingKind.PERSONAL, clients: List<String> = listOf("a")) =
        TrainingState(kind, at(17), 50, status, participants = clients.map { ParticipantState(it) })

    @Test
    fun buttonsByState() {
        val planned = pt()
        // До начала минус 15 минут: «Отменить», «Изменить»; в «…» — пропуск даты, если это будущая тренировка серии.
        assertEquals(
            CardButtons(listOf(CardAction.CANCEL, CardAction.EDIT), listOf(CardMenu.COPY, CardMenu.SKIP_DATE, CardMenu.DELETE)),
            TrainingCardRules.buttons(planned, at(16, 44), skippable = true),
        )
        // С 16:45: «Проведено» главная, «Изменить» уходит в «…».
        assertEquals(
            CardButtons(listOf(CardAction.DONE, CardAction.CANCEL), listOf(CardMenu.EDIT, CardMenu.COPY, CardMenu.DELETE)),
            TrainingCardRules.buttons(planned, at(16, 45), skippable = false),
        )
        assertEquals(CardAction.ALL_CAME, TrainingCardRules.buttons(pt(kind = TrainingKind.GROUP), at(17, 5), false).buttons.first())
        // Проведённую не удалить; отменённая — только «Вернуть».
        assertEquals(
            CardButtons(listOf(CardAction.EDIT, CardAction.CANCEL), listOf(CardMenu.COPY)),
            TrainingCardRules.buttons(pt(TrainingStatus.DONE), at(18), false),
        )
        assertEquals(listOf(CardAction.RETURN), TrainingCardRules.buttons(pt(TrainingStatus.CANCELLED), at(18), false).buttons)
        assertFalse(TrainingCardRules.canDelete(TrainingStatus.DONE))
    }

    @Test
    fun clientRowsAndGroupToggle() {
        val split = pt(kind = TrainingKind.SPLIT, clients = listOf("a", "b"))
        assertTrue(TrainingCardRules.canCancelClient(split, "a"))
        assertFalse(TrainingCardRules.perClient(pt()))
        val oneCancelled = StatusRules.cancelClients(split, listOf("b"), CancelReasons.CANNOT_COME, at(16, 20), StatusSettings())
        assertFalse(TrainingCardRules.canCancelClient(oneCancelled, "b"))
        assertTrue(TrainingCardRules.canReturnClient(oneCancelled, "b"))
        assertEquals(listOf("a"), CancelRules.open(oneCancelled))

        val group = pt(kind = TrainingKind.GROUP, clients = listOf("a", "b", "c"))
        assertFalse(TrainingCardRules.canToggle(group, at(16, 44)))
        assertTrue(TrainingCardRules.canToggle(group, at(16, 45)))
        // Проведённая: «a» пришёл, «b» и «c» — нет; «пришли 1 из 3», не пришедших не вернуть значком — только нажатием.
        val done = StatusRules.closeAtEnd(StatusRules.toggleAttendance(group, "a", at(17)), at(18), StatusSettings())
        assertEquals(TrainingStatus.DONE, done.status)
        assertTrue(TrainingCardRules.noShow(done, done.participant("b")))
        assertEquals(3, TrainingCardRules.groupTotal(done))
        assertFalse(TrainingCardRules.canReturnClient(done, "b"))
        assertTrue(TrainingCardRules.canToggle(done, at(18)))
    }

    @Test
    fun cancelFortyMinutesBeforeIsChargedSixtyOneIsNot() {
        // Проверка шага 1.14: начало 17:00, порог 60 минут.
        val training = pt()
        val late = CancelRules.summary(training, whole = true, CancelReasons.CANNOT_COME, at(16, 20), StatusSettings())
        assertTrue(late.charged)
        assertEquals(s("предупредили за_40_мин"), TrainingCardFormat.summaryNote(late.decision))
        assertEquals("Отмена со списанием", TrainingCardFormat.summaryLabel(late.charged))
        val early = CancelRules.summary(training, whole = true, CancelReasons.CANNOT_COME, at(15, 59), StatusSettings())
        assertFalse(early.charged)
        assertEquals(s("предупредили за_1_ч 1_мин"), TrainingCardFormat.summaryNote(early.decision))

        // Переключатель меняет итог; у тренера — без списания и без переключателя ничего не ломается.
        assertFalse(CancelRules.summary(training, true, CancelReasons.CANNOT_COME, at(16, 20), StatusSettings(), override = false).charged)
        assertFalse(CancelRules.summary(training, true, CancelReasons.BY_COACH, at(16, 20), StatusSettings()).charged)
        val applied = CancelRules.apply(training, emptyList(), CancelReasons.CANNOT_COME, at(16, 20), StatusSettings())
        assertEquals(TrainingStatus.CANCELLED, applied.status)
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, applied.participants.single().status)
    }

    @Test
    fun wholeGroupHasNoChargeAndSptNoSwitch() {
        val group = pt(kind = TrainingKind.GROUP, clients = listOf("a", "b"))
        val whole = CancelRules.summary(group, whole = true, null, at(16, 50), StatusSettings())
        assertNull(whole.decision)
        assertFalse(whole.charged)
        assertTrue(CancelRules.asksWho(group))
        // Один ребёнок группы за 10 минут — со списанием.
        assertTrue(CancelRules.summary(group, whole = false, CancelReasons.CANNOT_COME, at(16, 50), StatusSettings()).charged)
        val spt = CancelRules.summary(pt(kind = TrainingKind.SPT), true, CancelReasons.CANNOT_COME, at(16, 50), StatusSettings(), override = true)
        assertFalse(spt.switchable)
        assertFalse(spt.charged)
    }

    @Test
    fun statusWords() {
        val start = at(17)
        val end = at(17, 50)
        val planned = ClientOutcome.PLANNED
        assertEquals(s("через 1_ч 20_мин"), TrainingCardFormat.statusNote(planned, TrainingPhase.UPCOMING, at(15, 40), start, end, null, 1, null))
        assertEquals("Идёт", TrainingCardFormat.statusLabel(planned, TrainingPhase.ONGOING))
        assertEquals(s("до конца 30_мин"), TrainingCardFormat.statusNote(planned, TrainingPhase.ONGOING, at(17, 20), start, end, null, 1, null))
        assertEquals("пришли 5 из 12", TrainingCardFormat.statusNote(planned, TrainingPhase.ONGOING, at(17, 4), start, end, 5, 12, null))
        assertEquals("ждёт отметки", TrainingCardFormat.statusNote(planned, TrainingPhase.ENDED, at(18), start, end, null, 1, null))
        assertEquals("отмечено само в 17:50", TrainingCardFormat.statusNote(ClientOutcome.DONE, TrainingPhase.ENDED, at(18), start, end, null, 1, end))
        assertEquals("отмечено в 17:45", TrainingCardFormat.statusNote(ClientOutcome.DONE, TrainingPhase.ONGOING, at(17, 45), start, end, null, 1, at(17, 45)))
        assertEquals("Отмена со списанием", TrainingCardFormat.statusLabel(ClientOutcome.CANCELLED_CHARGED, TrainingPhase.ENDED))

        assertEquals(s("вт_20.10 · 17:00–17:50 · 50_мин"), TrainingCardFormat.whenLine(start, end, 50))
        assertEquals("ПТ · ББ · каждый вт и чт", TrainingCardFormat.typeLine("ПТ", "ББ", setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)))
        assertEquals("ПТ · ББ", TrainingCardFormat.typeLine("ПТ", "ББ", null))
        assertEquals(s("вт_20.10, 09:20 — за_40_мин"), TrainingCardFormat.notified(at(9, 20), at(10)))
        assertEquals(s("Отменить: Носова_Е."), TrainingCardFormat.cancelFor(listOf(s("Носова_Е."))))
        assertEquals(s("24_часа"), CalendarSettingsFormat.notice(1440))
        assertEquals(s("6_часов"), CalendarSettingsFormat.notice(360))
    }
}
