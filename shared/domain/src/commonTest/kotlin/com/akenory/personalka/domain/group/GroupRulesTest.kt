package com.akenory.personalka.domain.group

import kotlin.test.assertFalse
import com.akenory.personalka.domain.model.ParticipationStatus
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
        // Сб 17.10: Борис ещё в группе; Вера в составе с 24.10 и сейчас в группе — уже не «на раз» (шаг 5.3н), Гоша — «на раз».
        assertEquals(listOf("gosha"), GroupRules.guests(listOf("anya", "vera", "gosha", "gosha"), members, day(17)))
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

    @Test
    fun pastUnmarkedGetCurrentMembers() {
        // Шаг 5.3н: Аня и Вера в составе сейчас (Вера — с 24.10), Борис ушёл 10.10.
        val members = listOf(
            GroupMember("anya", day(1, 9)),
            GroupMember("boris", day(1, 9), toDate = day(10)),
            GroupMember("vera", day(24)),
        )
        val today = day(31)
        assertEquals(listOf("anya", "vera"), GroupRules.pastFill(true, true, emptyList(), members, today))
        assertEquals(listOf("vera"), GroupRules.pastFill(true, true, listOf("anya" to false), members, today), "Кто есть — не трогается")
        assertEquals(emptyList(), GroupRules.pastFill(true, true, listOf("gosha" to true), members, today), "Отмеченная не меняется")
        assertEquals(emptyList(), GroupRules.pastFill(true, false, emptyList(), members, today), "Отменённая или проведённая — тоже")
        assertEquals(emptyList(), GroupRules.pastFill(false, true, emptyList(), members, today), "Будущая — по составу на свою дату")
    }

    @Test
    fun historyStartsAtFirstAttended() {
        val starts = GroupRules.historyStarts(
            listOf(Triple("sat", day(3), false), Triple("sat", day(17), true), Triple("sat", day(24), true), Triple("tue", day(6), false)),
        )
        assertEquals(mapOf("sat" to day(17)), starts)
        fun hidden(group: String?, d: Int, status: ParticipationStatus, started: Boolean = true) =
            GroupRules.hiddenFromHistory(group, day(d), started, status, starts)
        assertTrue(hidden("sat", 10, ParticipationStatus.CANCELLED), "Не пришёл до первого «пришёл»")
        assertTrue(hidden("sat", 3, ParticipationStatus.EXPECTED))
        assertFalse(hidden("sat", 3, ParticipationStatus.CANCELLED_CHARGED), "Списание видно")
        assertFalse(hidden("sat", 24, ParticipationStatus.CANCELLED), "После начала — видно")
        assertTrue(hidden("tue", 6, ParticipationStatus.CANCELLED), "В группе ни разу не был — прошлых нет")
        assertFalse(hidden("tue", 6, ParticipationStatus.EXPECTED, started = false), "Будущие видны")
        assertFalse(hidden(null, 3, ParticipationStatus.CANCELLED), "У ПТ ничего не меняется")
    }
}
