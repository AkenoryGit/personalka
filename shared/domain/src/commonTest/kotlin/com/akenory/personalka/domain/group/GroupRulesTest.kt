package com.akenory.personalka.domain.group

import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.format.GroupFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Группы 5.10: форма, правка состава, дети «на раз», посещаемость, строки. «_» — неразрывный пробел. */
class GroupRulesTest {
    private fun day(d: Int, m: Int = 10) = LocalDate(2026, m, d)
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun groupFormProblems() {
        assertEquals(
            mapOf(GroupField.NAME to "Введите название", GroupField.TYPE to "Выберите тип", GroupField.PLACE to "Выберите место"),
            GroupRules.problems(GroupDraft(" "), emptyList()),
        )
        assertEquals(
            mapOf(GroupField.NAME to "Такая группа уже есть"),
            GroupRules.problems(GroupDraft("сб 9:00 ББ", "t", "p"), listOf("Сб 9:00 ББ")),
        )
        assertTrue(GroupRules.problems(GroupDraft("Сб 9:00 ББ", "t", "p"), listOf("Вт 18:00 ДБ")).isEmpty())
    }

    @Test
    fun guestsAreThoseNotInGroupOnThatDate() {
        val members = listOf(
            GroupMember("anya", day(1, 9)),
            GroupMember("boris", day(1, 9), toDate = day(10)),
            GroupMember("vera", day(24)),
        )
        // Сб 17.10: Борис ещё в группе, Вера ещё нет — она «на раз», как и Гоша.
        assertEquals(listOf("vera", "gosha"), GroupRules.guests(listOf("anya", "vera", "gosha", "gosha"), members, day(17)))
        // Сб 24.10: Борис ушёл и пришёл «на раз», Вера уже в группе.
        assertEquals(listOf("boris"), GroupRules.guests(listOf("anya", "boris", "vera"), members, day(24)))
    }

    @Test
    fun averageAttendanceInTenths() {
        assertEquals(0, GroupRules.averageTenths(emptyList()))
        assertEquals(83, GroupRules.averageTenths(listOf(8, 9, 8)))
        assertEquals(50, GroupRules.averageTenths(listOf(5)))
    }

    @Test
    fun leavingAllGroupsAfterLastDay() {
        val current = GroupMember("anya", day(1, 9))
        val later = GroupMember("anya", day(1, 11))
        val ended = GroupMember("anya", day(1, 3), toDate = day(31, 5))
        val endsLater = GroupMember("anya", day(1, 9), toDate = day(31, 12))
        assertEquals(
            listOf(MemberChange.End(current, day(19)), MemberChange.Remove(later), MemberChange.End(endsLater, day(19))),
            GroupRules.leave(listOf(current, later, ended, endsLater), day(19)),
        )
    }

    @Test
    fun groupStrings() {
        assertEquals(s("2_группы"), GroupFormat.groups(2))
        assertEquals(s("1_ребёнок"), GroupFormat.children(1))
        assertEquals(s("3_ребёнка"), GroupFormat.children(3))
        assertEquals(s("12_детей"), GroupFormat.children(12))
        val sat = ScheduleLine(setOf(DayOfWeek.SATURDAY), LocalTime(9, 0), "Группа", "ББ")
        assertEquals(s("Сб_09:00 · ББ · 12_детей"), GroupFormat.subtitle(listOf(sat), "ББ", 12))
        assertEquals(s("без расписания · ДБ · 0_детей"), GroupFormat.subtitle(emptyList(), "ДБ", 0))
        assertEquals("Белова Ева убрана из группы", GroupFormat.left("Белова Ева", Sex.FEMALE))
        assertEquals("пришли 5 из 12", GroupFormat.attended(5, 12))
        assertEquals("1 на раз", GroupFormat.guests(1))
    }
}
