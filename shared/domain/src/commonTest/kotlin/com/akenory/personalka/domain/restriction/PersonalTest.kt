package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.calendar.FreeSlots
import com.akenory.personalka.domain.calendar.SlotBusy
import com.akenory.personalka.domain.calendar.SlotQuery
import com.akenory.personalka.domain.format.PersonalFormat
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Рабочий график (5.10д), личные дела (5.10е) и напоминания (5.10ж) — просьба Дмитрия 18.11.2026. */
class PersonalTest {
    private val tuesday = LocalDate(2026, 10, 20)
    private val sunday = LocalDate(2026, 10, 25)
    private fun at(h: Int, m: Int = 0, date: LocalDate = tuesday) = LocalDateTime(date, LocalTime(h, m))

    private fun task(
        start: LocalTime = LocalTime(14, 0),
        end: LocalTime = LocalTime(15, 0),
        travel: Int = 60,
        blocks: Boolean = true,
        notify: Int? = 15,
        kind: BlockKind = BlockKind.PERSONAL,
    ) = Restriction(
        id = "p1",
        locationId = Restriction.ALL_PLACES,
        reason = "Стоматолог",
        startTime = start,
        endTime = end,
        date = tuesday,
        validFrom = tuesday,
        kind = kind,
        travelMinutes = travel,
        blocksTrainings = blocks,
        notifyMinutes = notify,
    )

    @Test
    fun scheduleEncodesAndDecodes() {
        val text = WorkSchedule.DEFAULT.encode()
        assertEquals("07:00-21:00,07:00-21:00,07:00-21:00,07:00-21:00,07:00-21:00,09:00-14:00,-", text)
        assertEquals(WorkSchedule.DEFAULT, WorkSchedule.decode(text))
        assertNull(WorkSchedule.decode(null))
        assertNull(WorkSchedule.decode("07:00-21:00"))
    }

    @Test
    fun outsideScheduleAsksLikeRestriction() {
        val rules = PersonalRules.forTrainings(emptyList(), personalOn = false, schedule = WorkSchedule.DEFAULT)
        // Вторник 21:00–21:50 — вне графика (до 21:00): окно «Поставить всё равно?».
        val late = RestrictionRules.check(null, Placement(at(21, 0), 50, "bb"), rules)
        assertIs<RestrictionCheck.Confirm>(late)
        assertEquals(BlockKind.SCHEDULE, late.hits.single().restriction.kind)
        // 20:00–20:50 — в графике.
        assertEquals(RestrictionCheck.Save(false), RestrictionRules.check(null, Placement(at(20, 0), 50, "bb"), rules))
        // Воскресенье — выходной целиком.
        assertIs<RestrictionCheck.Confirm>(RestrictionRules.check(null, Placement(at(12, 0, sunday), 50, "db"), rules))
        // Серия: одно окно на все повторы, под графиком только воскресный.
        val batch = RestrictionRules.checkAll(listOf(Placement(at(12, 0), 50, "bb"), Placement(at(12, 0, sunday), 50, "bb")), rules)!!
        assertEquals(2, batch.checked)
        assertEquals(1, batch.conflicting)
    }

    @Test
    fun personalTaskBlocksWithTravel() {
        val rules = PersonalRules.forTrainings(listOf(task()), personalOn = true)
        val r = rules.single()
        assertEquals(LocalTime(13, 0), r.startTime)
        assertEquals(LocalTime(16, 0), r.endTime)
        assertEquals("Стоматолог · с дорогой 1 ч", r.reason)
        // Тренировка 15:30 — в дороге после дела: спросит; 16:00 — уже нет.
        assertIs<RestrictionCheck.Confirm>(RestrictionRules.check(null, Placement(at(15, 30), 30, "db"), rules))
        assertEquals(RestrictionCheck.Save(false), RestrictionRules.check(null, Placement(at(16, 0), 50, "db"), rules))
    }

    @Test
    fun freeTasksRemindersAndOffSwitchTakeNoTime() {
        assertTrue(PersonalRules.forTrainings(listOf(task(blocks = false)), personalOn = true).isEmpty())
        assertTrue(PersonalRules.forTrainings(listOf(task(kind = BlockKind.REMINDER)), personalOn = true).isEmpty())
        assertTrue(PersonalRules.forTrainings(listOf(task()), personalOn = false).isEmpty())
        val restriction = task(kind = BlockKind.RESTRICTION, travel = 0)
        assertEquals(listOf(restriction), PersonalRules.forTrainings(listOf(restriction), personalOn = false))
    }

    @Test
    fun slotsSkipPersonalAndOffHoursEvenWithoutRestrictions() {
        val rules = PersonalRules.forTrainings(listOf(task()), personalOn = true, schedule = WorkSchedule.DEFAULT)
        val query = SlotQuery(tuesday, tuesday, locationId = "bb", restrictions = false, emptyDays = true)
        val windows = FreeSlots.find(query, emptyList<SlotBusy>(), rules, listOf("bb"), at(6, 0)).flatMap { it.windows }
        // 07:00–13:00 и 16:00–21:00: дело с дорогой и время после 21:00 закрыты, хотя «Учитывать запреты» выключено.
        assertEquals(listOf(7 * 60 to 13 * 60, 16 * 60 to 21 * 60), windows.map { it.startMinute to it.endMinute })
    }

    @Test
    fun alertsBeforeLeaving() {
        val alerts = PersonalRules.alerts(listOf(task()), tuesday, tuesday)
        // Начало 14:00, дорога 1 ч, напомнить за 15 минут — в 12:45.
        assertEquals(at(12, 45), alerts.single().at)
        val reminder = task(kind = BlockKind.REMINDER, start = LocalTime(12, 0), end = LocalTime(12, 15), notify = 10)
        assertEquals(at(11, 50), PersonalRules.alerts(listOf(reminder), tuesday, tuesday).single().at)
        assertTrue(PersonalRules.alerts(listOf(task(notify = null)), tuesday, tuesday).isEmpty())
        assertEquals("Через 10${NBSP}мин, в 12:00".replace(NBSP, ' '), PersonalFormat.alertText(at(11, 50), at(12, 0), 0))
    }

    @Test
    fun monthlyReminderOnItsDay() {
        val r = task(kind = BlockKind.REMINDER).copy(date = null, monthly = true, validFrom = LocalDate(2026, 1, 31))
        assertTrue(RestrictionRules.occursOn(r, LocalDate(2026, 3, 31)))
        // В феврале 31-го нет — последний день месяца.
        assertTrue(RestrictionRules.occursOn(r, LocalDate(2026, 2, 28)))
        assertFalse(RestrictionRules.occursOn(r, LocalDate(2026, 3, 30)))
        assertFalse(RestrictionRules.occursOn(r, LocalDate(2025, 12, 31)))
    }

    @Test
    fun reminderRepeatsInForm() {
        val draft = RestrictionFormRules.newPersonal(at(12, 0), BlockKind.REMINDER)
        assertEquals(LocalTime(12, 15), draft.end)
        assertEquals(ReminderRepeat.NONE, RestrictionFormRules.repeatOf(draft))
        val weekdays = RestrictionFormRules.withRepeat(draft, ReminderRepeat.WEEKDAYS)
        assertEquals(ReminderRepeat.WEEKDAYS, RestrictionFormRules.repeatOf(weekdays))
        assertFalse(DayOfWeek.SATURDAY in weekdays.weekdays)
        val monthly = RestrictionFormRules.withRepeat(draft, ReminderRepeat.MONTHLY)
        assertTrue(RestrictionFormRules.toRestriction("x", monthly).monthly)
        // Без названия не сохраняется.
        assertEquals(setOf(RestrictionField.TITLE), RestrictionFormRules.problems(draft).keys)
        assertTrue(RestrictionFormRules.problems(draft.copy(reason = "Позвонить")).isEmpty())
    }
}
