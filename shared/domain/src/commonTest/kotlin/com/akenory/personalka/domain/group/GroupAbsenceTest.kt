package com.akenory.personalka.domain.group

import com.akenory.personalka.domain.format.GroupFormat
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Не ходит 30 дней» (шаг 3.9у, ТЗ 5.10). Группа — по субботам. */
class GroupAbsenceTest {
    private fun day(d: Int, m: Int = 10) = LocalDate(2026, m, d)
    private val saturdays = listOf(day(5, 9), day(12, 9), day(19, 9), day(26, 9), day(3), day(10), day(17), day(24))

    @Test
    fun asksAfterThirtyDaysWithoutGroup() {
        val input = AbsenceInput(fromDate = day(1, 9), lastAttended = day(19, 9), keptOn = null, keeps = 0)
        assertNull(GroupAbsence.ask(input, saturdays, day(18)), "29 дней — рано")
        assertEquals(Absence(day(19, 9), attendedEver = true), GroupAbsence.ask(input, saturdays, day(19)))
    }

    @Test
    fun neverCameCountsFromJoining() {
        val input = AbsenceInput(fromDate = day(10, 9), lastAttended = null, keptOn = null, keeps = 0)
        assertNull(GroupAbsence.ask(input, saturdays, day(9)))
        assertEquals(Absence(day(10, 9), attendedEver = false), GroupAbsence.ask(input, saturdays, day(10)))
    }

    @Test
    fun noGroupHeldNoQuestion() {
        val input = AbsenceInput(fromDate = day(1, 9), lastAttended = day(19, 9), keptOn = null, keeps = 0)
        // Каникулы: после 19.09 группа не проходила.
        assertNull(GroupAbsence.ask(input, saturdays.filter { it <= day(19, 9) }, day(27)))
    }

    @Test
    fun keepAsksOnceMoreThenNever() {
        val first = AbsenceInput(fromDate = day(1, 9), lastAttended = day(5, 9), keptOn = day(10), keeps = 1)
        val later = saturdays + listOf(day(31), day(7, 11), day(14, 11))
        assertNull(GroupAbsence.ask(first, later, day(8, 11)), "после «Оставить» — 30 дней тишины")
        assertEquals(Absence(day(5, 9), attendedEver = true), GroupAbsence.ask(first, later, day(9, 11)))
        assertNull(GroupAbsence.ask(first.copy(keptOn = day(9, 11), keeps = 2), later, LocalDate(2027, 3, 1)), "после второго — больше не спрашивает")
    }

    @Test
    fun lines() {
        assertEquals("не была с 19.09", GroupFormat.absentSince(Absence(day(19, 9), true), Sex.FEMALE))
        assertEquals("ни разу не был", GroupFormat.absentSince(Absence(day(19, 9), false), Sex.MALE))
        assertEquals("Оставили. Спрошу ещё раз через 30 дней", GroupFormat.kept(1))
        assertEquals("Оставили. Больше не спрошу", GroupFormat.kept(2))
    }
}
