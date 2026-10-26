package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.format.SlotFormat
import com.akenory.personalka.domain.restriction.Restriction
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Свободные окна (шаг 4.3, ТЗ 4.13). Пн 02.11.2026: ПТ 09:00–09:50 и 14:00–14:50 в ББ, дежурство 18:00–20:00.
 * Проверка плана: «нет окон с ожиданием 2 часа» — с перерывом до 1 ч окно 10:00–14:00 режется на два куска у опор.
 */
class FreeSlotsTest {
    private val monday = LocalDate(2026, 11, 2)
    private val now = LocalDateTime(2026, 10, 30, 12, 0)
    private val places = listOf("bb", "db")

    private fun at(h: Int, m: Int = 0, date: LocalDate = monday) = LocalDateTime(date, LocalTime(h, m))

    private val day = listOf(
        SlotBusy(at(9), 50),
        SlotBusy(at(14), 50),
        SlotBusy(at(18), 120, duty = true),
    )

    private fun query(
        gap: Int? = null,
        dutyBusy: Boolean = false,
        location: String? = null,
        ranges: List<com.akenory.personalka.domain.model.TimeRange> = emptyList(),
        empty: Boolean = false,
    ) = SlotQuery(monday, monday, ranges = ranges, locationId = location, dutyBusy = dutyBusy, maxGapMin = gap, emptyDays = empty)

    private fun windows(q: SlotQuery, busy: List<SlotBusy> = day, restrictions: List<Restriction> = emptyList(), current: LocalDateTime = now) =
        FreeSlots.find(q, busy, restrictions, places, current).flatMap { it.windows }.map { SlotFormat.window(it) }

    @Test
    fun windowsAreDayMinusTrainingsAlignedToStep() {
        // Конец ПТ 09:50 выравнивается до 10:00, конец 14:50 — до 15:00; дежурство не занимает время.
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–23:00"), windows(query()))
    }

    @Test
    fun dutyBusyClosesItsTime() {
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–18:00", "20:00–23:00"), windows(query(dutyBusy = true)))
    }

    @Test
    fun noWindowWithTwoHourWait() {
        // Перерыв до 1 ч: слот начинается не позже 1 ч после конца опоры или кончается не раньше 1 ч до начала.
        // 10:00–14:00 режется у обеих ПТ, слот 11:45–12:35 (ждать 1 ч 55 мин и 1 ч 25 мин) не предлагается.
        assertEquals(
            listOf("07:15–09:00", "10:00–11:30", "12:15–14:00", "15:00–16:30", "16:15–21:45"),
            windows(query(gap = 60)),
        )
        FreeSlots.find(query(gap = 60), day, emptyList(), places, now).single().windows.forEach { w ->
            var s = w.startMinute
            while (s + 50 <= w.endMinute) {
                // Каждое начало сетки внутри окна годится.
                val wait = day.minOf { b ->
                    val from = b.start.hour * 60 + b.start.minute
                    val to = from + b.durationMin
                    when {
                        s >= to -> s - to
                        s + 50 <= from -> from - (s + 50)
                        else -> 0
                    }
                }
                assertTrue(wait <= 60, "${SlotFormat.window(w)}: начало $s, ждать $wait мин")
                s += FreeSlots.STEP
            }
        }
    }

    @Test
    fun startRangesLimitStarts() {
        // «Вечер 17–23»: начать можно с 17:00, окно до 23:00.
        assertEquals(listOf("17:00–23:00"), windows(query(ranges = listOf(WaitRules.EVENING))))
    }

    @Test
    fun dayWithoutAnchorsOnlyWhenAsked() {
        assertEquals(emptyList(), windows(query(), busy = emptyList()))
        assertEquals(listOf("07:00–23:00"), windows(query(empty = true), busy = emptyList()))
    }

    @Test
    fun pastOfTodayIsDropped() {
        assertEquals(listOf("15:00–23:00"), windows(query(), current = at(14, 20)))
        assertEquals(listOf("10:15–14:00", "15:00–23:00"), windows(query(), current = at(10, 5)))
    }

    @Test
    fun restrictionOnlyOfChosenPlace() {
        val group = Restriction("r1", "db", "Группа", LocalTime(16, 0), LocalTime(19, 0), date = monday, validFrom = monday)
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–16:00", "19:00–23:00"), windows(query(location = "db"), restrictions = listOf(group)))
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–23:00"), windows(query(location = "bb"), restrictions = listOf(group)))
        // Любое место: запрет только в ДБ — в ББ свободно.
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–23:00"), windows(query(), restrictions = listOf(group)))
        val both = group.copy(id = "r2", locationId = "bb", startTime = LocalTime(17, 0), endTime = LocalTime(20, 0))
        assertEquals(listOf("07:00–09:00", "10:00–14:00", "15:00–17:00", "19:00–23:00"), windows(query(), restrictions = listOf(group, both)))
        // Запреты выключены — не учитываются.
        assertEquals(3, windows(query(location = "db").copy(restrictions = false), restrictions = listOf(group)).size)
    }

    @Test
    fun durationAndDaysOfWeek() {
        val q = SlotQuery(monday, LocalDate(2026, 11, 8), days = setOf(DayOfWeek.MONDAY), durationMin = 90, dayStart = LocalTime(8, 0))
        val found = FreeSlots.find(q, day + SlotBusy(at(10, date = LocalDate(2026, 11, 3)), 50), emptyList(), places, now)
        assertEquals(listOf(monday), found.map { it.date })
        // 08:00–09:00 короче 90 минут — не показывается.
        assertEquals(listOf("10:00–14:00", "15:00–23:00"), found.single().windows.map { SlotFormat.window(it) })
    }

    @Test
    fun waitListMarks() {
        val window = FreeWindow(monday, 15 * 60, 23 * 60)
        val q = query()
        val evening = WaitWant(days = setOf(DayOfWeek.MONDAY), ranges = listOf(WaitRules.EVENING), typeId = "pt")
        val morning = evening.copy(ranges = listOf(WaitRules.MORNING))
        val tuesday = evening.copy(days = setOf(DayOfWeek.TUESDAY))
        val expired = WaitWant(series = false, oneOff = true, until = LocalDate(2026, 11, 1), typeId = "pt")
        assertTrue(FreeSlots.suits(window, q, evening, 50, emptyList()))
        assertFalse(FreeSlots.suits(window, q, morning, 50, emptyList()))
        assertFalse(FreeSlots.suits(window, q, tuesday, 50, emptyList()))
        assertFalse(FreeSlots.suits(window, q, expired, 50, emptyList()))
        // Ждёт ДБ, а там запрет весь вечер — окно «любого места» ему не подходит.
        val db = evening.copy(locationId = "db")
        val closed = Restriction("r", "db", "Группа", LocalTime(15, 0), LocalTime(23, 0), date = monday, validFrom = monday)
        assertFalse(FreeSlots.suits(window, q, db, 50, listOf(closed)))
        assertTrue(FreeSlots.suits(window, q, db, 50, emptyList()))
    }

    @Test
    fun copyTextHasNoNames() {
        val days = FreeSlots.find(query(), day, emptyList(), places, now)
        assertEquals("Свободное время:\nПн 02.11: 07:00–09:00, 10:00–14:00, 15:00–23:00", SlotFormat.copyText(days))
    }
}

class SlotParamsTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val p = SlotParams(
            periodDays = 7,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ranges = listOf(WaitRules.MORNING, WaitRules.EVENING),
            locationId = "bb",
            durationMin = 60,
            maxGapMin = 60,
            restrictions = false,
            dutyBusy = true,
            emptyDays = true,
        )
        assertEquals(p, SlotParams.decode(p.encode()))
        assertEquals(SlotParams(), SlotParams.decode(SlotParams().encode()))
        assertEquals(SlotParams(), SlotParams.decode(null))
        assertEquals(SlotParams(), SlotParams.decode("мусор"))
    }

    @Test
    fun queryStartsToday() {
        val q = SlotParams(periodDays = 7).query(LocalDate(2026, 10, 26), LocalTime(8, 0), LocalTime(22, 0))
        assertEquals(LocalDate(2026, 11, 1), q.to)
        assertEquals(LocalTime(8, 0), q.dayStart)
    }
}
