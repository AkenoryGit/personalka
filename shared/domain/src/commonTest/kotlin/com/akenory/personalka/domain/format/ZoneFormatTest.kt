package com.akenory.personalka.domain.format

import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SATURDAY
import kotlinx.datetime.DayOfWeek.SUNDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки зон календаря (ТЗ 5.3) — как в макетах «День» и «Нажатие на запрет». */
class ZoneFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun restrictionAndDutyLines() {
        assertEquals("ДБ 17:00–18:00 — групповая тренировка", ZoneFormat.restriction("ДБ", 17 * 60, 18 * 60, "Групповая тренировка"))
        assertEquals("ДБ · групповая тренировка", ZoneFormat.restrictionLabel("ДБ", "Групповая тренировка"))
        // Весь день и без причины.
        assertEquals("ББ 00:00–24:00", ZoneFormat.restriction("ББ", 0, 1440, " "))
        assertEquals("ББ", ZoneFormat.restrictionLabel("ББ", ""))
        assertEquals("ББ 11:00–15:00 — дежурство", ZoneFormat.duty("ББ", 11 * 60, 15 * 60, "Дежурство"))
        assertEquals("Дежурство · ББ", ZoneFormat.dutyLabel("Дежурство", "ББ"))
        // За коллегу: «за» не отрывается от имени.
        assertEquals(s("ББ 11:00–15:00 — дежурство за_Александра"), ZoneFormat.duty("ББ", 11 * 60, 15 * 60, "Дежурство", "Александра"))
        assertEquals(s("Дежурство · ББ · за_Дмитрия Мальцева"), ZoneFormat.dutyLabel("Дежурство", "ББ", "Дмитрия Мальцева"))
        assertEquals(s("Запрет снят на вт_20.10"), ZoneFormat.skipped(LocalDate(2026, 10, 20)))
    }

    @Test
    fun conflictLineWithFullPlaceName() {
        // ТЗ 4.5: «Малый бассейн 17:00–18:30: групповая тренировка. Поставить всё равно?»
        assertEquals("Малый бассейн 17:00–18:30: групповая тренировка", ZoneFormat.conflict("Малый бассейн", 17 * 60, 18 * 60 + 30, "Групповая тренировка"))
        assertEquals("Большой бассейн 00:00–24:00", ZoneFormat.conflict("Большой бассейн", 0, 1440, ""))
        assertEquals("Детский бассейн 09:00–10:00: ДЮСШ", ZoneFormat.conflict("Детский бассейн", 9 * 60, 10 * 60, "ДЮСШ"))
    }

    @Test
    fun reasonInPhrase() {
        assertEquals("бассейн на чистке", ZoneFormat.inPhrase("Бассейн на чистке"))
        assertEquals("ДЮСШ", ZoneFormat.inPhrase("ДЮСШ"))
        assertEquals("МБ закрыт", ZoneFormat.inPhrase(" МБ закрыт "))
        assertEquals("Я", ZoneFormat.inPhrase("Я"))
    }

    @Test
    fun repeatByFirstDay() {
        fun repeat(vararg days: DayOfWeek, to: LocalDate? = null) = ZoneFormat.repeat(days.toSet(), to)
        assertEquals("Каждый вт и чт", repeat(THURSDAY, TUESDAY))
        assertEquals("Каждую ср", repeat(WEDNESDAY))
        assertEquals("Каждую сб и вс", repeat(SUNDAY, SATURDAY))
        assertEquals("Каждое вс", repeat(SUNDAY))
        assertEquals("Каждый пн, ср и пт до 31.12.2026", repeat(MONDAY, WEDNESDAY, FRIDAY, to = LocalDate(2026, 12, 31)))
        assertEquals("Каждый день", repeat(*DayOfWeek.entries.toTypedArray()))
        assertEquals("Только в этот день", repeat())
    }
}
