package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Строки клиентов из макетов ClList, ClCardInfo, ClRelAdd (шаг 0.5). «_» — неразрывный пробел. */
class ClientFormatTest {
    private val today = LocalDate(2026, 10, 20)

    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    @Test
    fun agesLikeMockups() {
        assertEquals(s("1_год"), ClientFormat.age(LocalDate(2025, 9, 1), today))
        assertEquals(s("9_лет"), ClientFormat.age(LocalDate(2017, 5, 12), today))
        assertEquals(s("41_год"), ClientFormat.age(LocalDate(1985, 6, 23), today))
        assertEquals(s("34_года"), ClientFormat.age(LocalDate(1992, 4, 3), today))
        assertEquals(s("8_мес"), ClientFormat.age(LocalDate(2026, 2, 3), today))
        assertEquals(s("0_мес"), ClientFormat.age(LocalDate(2026, 10, 1), today))
        assertEquals(s("03.04.1992 · 34_года"), ClientFormat.birth(LocalDate(1992, 4, 3), today))
        assertEquals(s("9_лет · ребёнок"), ClientFormat.ageHint(LocalDate(2017, 5, 12), today))
        assertEquals(s("34_года · взрослый"), ClientFormat.ageHint(LocalDate(1992, 4, 3), today))
    }

    @Test
    fun scheduleLikeListRows() {
        val pt = ScheduleLine(setOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY), LocalTime(10, 0), "ПТ", "ББ")
        val group = ScheduleLine(setOf(DayOfWeek.SATURDAY), LocalTime(9, 0), "Группа", "ББ")
        assertEquals(s("Вт, Чт_10:00 · ПТ_ББ"), ClientFormat.schedule(pt))
        assertEquals(s("Вт, Чт_10:00 · ПТ_ББ; Сб_09:00 · Группа_ББ"), ClientFormat.schedule(listOf(group, pt)))
        assertNull(ClientFormat.schedule(emptyList()))
        assertEquals(s("9_лет · Сб_09:00 · Группа_ББ"), ClientFormat.subtitle(s("9_лет"), ClientFormat.schedule(listOf(group))))
        assertEquals(s("13_лет · без расписания"), ClientFormat.subtitle(s("13_лет"), null))
        assertEquals("без расписания", ClientFormat.subtitle(null, null))
    }

    @Test
    fun nextTrainingTodayWithTimeOtherwiseDate() {
        assertEquals("сегодня 16:00", ClientFormat.next(LocalDateTime(2026, 10, 20, 16, 0), today))
        assertEquals(s("ср_21.10"), ClientFormat.next(LocalDateTime(2026, 10, 21, 7, 0), today))
    }

    @Test
    fun countsAndTenths() {
        assertEquals(s("42_клиента"), ClientFormat.clients(42))
        assertEquals(s("1_клиент"), ClientFormat.clients(1))
        assertEquals(s("11_клиентов"), ClientFormat.clients(11))
        assertEquals("7,4", ClientFormat.tenths(74))
        assertEquals("1,0", ClientFormat.tenths(10))
        assertEquals("0,0", ClientFormat.tenths(0))
    }

    @Test
    fun phoneShownByGroups() {
        assertEquals("+7 912 384-20-17", ClientFormat.phone("+79123842017"))
        assertEquals("+375 29 123-45-67", ClientFormat.phone("+375 29 123-45-67"))
    }

    @Test
    fun namesDeclinedForRelativePhrases() {
        val genitive = mapOf(
            "Артём" to "Артёма", "Наталья" to "Натальи", "Мария" to "Марии", "Ксения" to "Ксении", "Ольга" to "Ольги",
            "Никита" to "Никиты", "Илья" to "Ильи", "Андрей" to "Андрея", "Дмитрий" to "Дмитрия",
            "Павел" to "Павла", "Лев" to "Льва", "Пётр" to "Петра", "Таня" to "Тани", "Маша" to "Маши",
        )
        genitive.forEach { (name, expected) -> assertEquals(expected, RuName.genitive(name, null), name) }
        val dative = mapOf(
            "Артём" to "Артёму", "Наталья" to "Наталье", "Мария" to "Марии", "Ольга" to "Ольге", "Илья" to "Илье",
            "Игорь" to "Игорю", "Андрей" to "Андрею", "Лев" to "Льву",
        )
        dative.forEach { (name, expected) -> assertEquals(expected, RuName.dative(name, Sex.MALE.takeIf { name in setOf("Игорь") }), name) }
        assertEquals("Игоря", RuName.genitive("Игорь", Sex.MALE))
        assertEquals("Любови", RuName.genitive("Любовь", Sex.FEMALE))
        // То, что так не склоняется, остаётся как есть.
        assertEquals("Ирэн", RuName.genitive("Ирэн", Sex.FEMALE))
        assertEquals("Игорь", RuName.genitive("Игорь", null))
        assertEquals("Нико", RuName.dative("Нико", Sex.MALE))
        assertEquals("Анна-Мария", RuName.genitive("Анна-Мария", Sex.FEMALE))
    }

    @Test
    fun birthDateParsedFromTypedText() {
        assertEquals(LocalDate(2017, 5, 12), DateFormat.parseFull("12.05.2017"))
        assertEquals(LocalDate(2017, 5, 12), DateFormat.parseFull(" 12.5.2017 "))
        assertEquals(LocalDate(2017, 5, 12), DateFormat.parseFull("12/05/2017"))
        assertEquals(LocalDate(2017, 5, 12), DateFormat.parseFull("12052017"))
        assertNull(DateFormat.parseFull("12.05.17"))
        assertNull(DateFormat.parseFull("31.02.2017"))
        assertNull(DateFormat.parseFull("12.13.2017"))
        assertNull(DateFormat.parseFull("2017.05.12"))
        assertNull(DateFormat.parseFull(""))
    }
}
