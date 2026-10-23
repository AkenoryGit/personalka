package com.akenory.personalka.domain.reminder

import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.home.HomeSlot
import com.akenory.personalka.domain.home.TodoKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Уведомления (шаг 2.5, ТЗ 5.19). День — вт 20.10.2026; имена придуманы. */
class ReminderRulesTest {
    private val today = LocalDate(2026, 10, 20)
    private val tomorrow = LocalDate(2026, 10, 21)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today) = LocalDateTime(day, LocalTime(hour, minute))

    private fun slot(id: String, hour: Int, group: Boolean = false, marked: Boolean = false, cancelled: Boolean = false) =
        HomeSlot(id, at(hour), 50, group, cancelled, marked)

    private val defaults = ReminderSettings()
    private val all = ReminderSettings(instantDiary = true, instantGroup = true, dayPlan = true)

    // ПТ 16:00–16:50, группа 18:00–18:50, отменённая ПТ в 19:00.
    private val slots = listOf(slot("pt16", 16, marked = true), slot("group18", 18, group = true), slot("pt19", 19, cancelled = true))

    @Test
    fun digestComesOnceAtItsTime() {
        assertEquals(listOf(Reminder.Digest), ReminderRules.due(at(20, 59), at(21, 0), defaults, slots, emptySet()))
        assertEquals(emptyList(), ReminderRules.due(at(21, 0), at(21, 5), defaults, slots, emptySet()))
        // Будильник опоздал на 7 минут — сводка всё равно приходит.
        assertEquals(listOf(Reminder.Digest), ReminderRules.due(at(20, 30), at(21, 7), defaults, slots, emptySet()))
    }

    @Test
    fun digestTimeIsConfigurable() {
        val late = defaults.copy(digestTime = LocalTime(22, 30))
        assertEquals(emptyList(), ReminderRules.due(at(20, 0), at(21, 0), late, slots, emptySet()))
        assertEquals(listOf(Reminder.Digest), ReminderRules.due(at(22, 0), at(22, 30), late, slots, emptySet()))
        assertEquals(emptyList(), ReminderRules.due(at(20, 0), at(23, 0), defaults.copy(digest = false), slots, emptySet()))
    }

    @Test
    fun instantRemindersAreOffByDefault() {
        assertEquals(emptyList(), ReminderRules.due(at(16, 0), at(19, 30), defaults, slots, setOf("pt16")))
        // Сводка — единственный будильник по умолчанию.
        assertEquals(at(21, 0), ReminderRules.next(at(16, 0), defaults, slots))
    }

    @Test
    fun instantDiaryFifteenMinutesAfterEnd() {
        val on = defaults.copy(instantDiary = true)
        assertEquals(at(17, 5), ReminderRules.next(at(16, 0), on, slots))
        assertEquals(emptyList(), ReminderRules.due(at(16, 0), at(17, 4), on, slots, setOf("pt16")))
        assertEquals(listOf(Reminder.Diary("pt16")), ReminderRules.due(at(17, 0), at(17, 5), on, slots, setOf("pt16")))
        // Дневник успели заполнить — напоминания нет.
        assertEquals(emptyList(), ReminderRules.due(at(17, 0), at(17, 5), on, slots, emptySet()))
    }

    @Test
    fun instantGroupOnlyWhileNobodyMarked() {
        val on = defaults.copy(instantGroup = true)
        // Группа 18:00–18:50 — в 19:05; отменённая в 19:00 будильника не ставит.
        assertEquals(at(19, 5), ReminderRules.next(at(17, 30), on, slots))
        assertEquals(at(21, 0), ReminderRules.next(at(19, 5), on, slots))
        assertEquals(listOf(Reminder.Group("group18")), ReminderRules.due(at(19, 0), at(19, 5), on, slots, emptySet()))
        val marked = slots.map { if (it.id == "group18") it.copy(marked = true) else it }
        assertEquals(emptyList(), ReminderRules.due(at(19, 0), at(19, 5), on, marked, emptySet()))
    }

    @Test
    fun dayPlanAtSevenAndNextDayDigest() {
        val on = defaults.copy(dayPlan = true)
        assertEquals(at(7, 0, tomorrow), ReminderRules.next(at(21, 0), on, slots))
        assertEquals(listOf(Reminder.DayPlan), ReminderRules.due(at(21, 0), at(7, 0, tomorrow), on, slots, emptySet()))
        assertEquals(at(21, 0, tomorrow), ReminderRules.next(at(7, 0, tomorrow), on, slots))
    }

    @Test
    fun missedDaysGiveOneDigestAndNothingOld() {
        // Телефон был выключен двое суток: одна сводка, старые мгновенные — нет (смотрим назад не больше 12 часов).
        val due = ReminderRules.due(at(10, 0), at(21, 30, LocalDate(2026, 10, 22)), all, slots, setOf("pt16"))
        assertEquals(listOf(Reminder.Digest), due)
        // Утром после выключенного на ночь телефона — пропущенная сводка и план на день.
        assertEquals(listOf(Reminder.DayPlan, Reminder.Digest), ReminderRules.due(at(20, 0), at(8, 0, tomorrow), all, slots, emptySet()))
    }

    @Test
    fun nothingEnabledNeedsNoAlarm() {
        assertNull(ReminderRules.next(at(12, 0), ReminderSettings(digest = false), slots))
    }

    @Test
    fun digestTextFromTodoItems() {
        val text = ReminderFormat.digest(listOf(TodoKind.UNMARKED_GROUPS to 1, TodoKind.NO_DIARY to 3, TodoKind.NOT_BOOKED to 2))
        assertEquals(s("Группа не отмечена · 3_без дневника · 2_клиента без записи"), text)
        assertEquals(s("2_группы не отмечены · нет резервной копии"), ReminderFormat.digest(listOf(TodoKind.UNMARKED_GROUPS to 2, TodoKind.BACKUP to null)))
        assertEquals(s("5_клиентов без записи"), ReminderFormat.digest(listOf(TodoKind.NOT_BOOKED to 5)))
        assertEquals("Нужно сделать: 3", ReminderFormat.digestTitle(3))
    }

    @Test
    fun dayPlanAndInstantTexts() {
        assertEquals(s("Сегодня 9_тренировок, первая в_08:00"), ReminderFormat.dayPlan(9, at(8)))
        assertEquals(s("Сегодня 1_тренировка, в_08:00"), ReminderFormat.dayPlan(1, at(8)))
        assertEquals("16:00–16:50 · заполните дневник", ReminderFormat.instantDiary(at(16), at(16, 50)))
        assertEquals("18:00–18:50 · отметьте, кто пришёл", ReminderFormat.instantGroup(at(18), at(18, 50)))
        assertEquals(s("Дневник · сводка в_21:00"), ReminderFormat.summary(diary = true, digest = true, time = LocalTime(21, 0)))
        assertTrue(ReminderFormat.summary(diary = false, digest = false, time = LocalTime(21, 0)).startsWith("Без дневника"))
    }
}
