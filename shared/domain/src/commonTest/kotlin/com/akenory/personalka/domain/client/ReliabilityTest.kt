package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.ReliabilityFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CancelReasons
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReliabilityTest {
    private val first = LocalDate(2026, 1, 5)

    private fun at(day: Int) = LocalDateTime(first.plus(day, DateTimeUnit.DAY), LocalTime(17, 0))

    private fun pt(day: Int, came: Boolean, reason: String? = null) = ReliabilityVisit(
        start = at(day),
        kind = TrainingKind.PERSONAL,
        typeName = "ПТ",
        typeShort = "ПТ",
        training = if (came) TrainingStatus.DONE else TrainingStatus.CANCELLED,
        participation = if (came) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED,
        participationReason = reason,
    )

    @Test
    fun fiftyWithEightCancelledIs84AndYellow() {
        // Пример Дмитрия: из 50 прошедших — 8 отмен, 84 %.
        val visits = (0 until 50).map { pt(it, came = it % 6 != 0 || it >= 48) }
        val r = ReliabilityRules.of(visits)
        assertEquals(42, r.done)
        assertEquals(8, r.cancelled)
        assertEquals(84, r.percent)
        assertEquals(ReliabilityLevel.MIDDLE, r.level)
        assertEquals("Надёжность 84 %", ReliabilityFormat.label(r))
        assertEquals("8 отмен из 50", ReliabilityFormat.basis(r))
    }

    @Test
    fun noCancelsIs100AndGreen() {
        val r = ReliabilityRules.of((0 until 12).map { pt(it, came = true) })
        assertEquals(100, r.percent)
        assertEquals(ReliabilityLevel.HIGH, r.level)
        assertEquals("без отмен из 12", ReliabilityFormat.basis(r))
        assertFalse(ReliabilityRules.ask(r))
    }

    @Test
    fun onlyLastFiftyCount() {
        // 20 старых отмен и 50 новых посещений: старые не считаются.
        val visits = (0 until 20).map { pt(it, came = false) } + (20 until 70).map { pt(it, came = true) }
        assertEquals(Reliability(50, 0), ReliabilityRules.of(visits.shuffled()))
    }

    @Test
    fun boundariesAndRoundingDown() {
        assertEquals(ReliabilityLevel.HIGH, Reliability(9, 1).level)
        // 89,6 % — вниз до 89: жёлтый.
        assertEquals(89, Reliability(43, 5).percent)
        assertEquals(ReliabilityLevel.MIDDLE, Reliability(43, 5).level)
        assertEquals(ReliabilityLevel.MIDDLE, Reliability(75, 25).level)
        assertEquals(ReliabilityLevel.LOW, Reliability(74, 26).level)
        assertTrue(ReliabilityRules.ask(Reliability(7, 3)))
    }

    @Test
    fun lessThanFiveIsNew() {
        val r = ReliabilityRules.of((0 until 4).map { pt(it, came = it != 0) })
        assertTrue(r.isNew)
        assertNull(r.percent)
        assertNull(r.level)
        assertFalse(ReliabilityRules.ask(r))
        assertEquals("Надёжность: новый", ReliabilityFormat.label(r))
        assertEquals("4 тренировки", ReliabilityFormat.basis(r))
        assertEquals("", ReliabilityFormat.basis(Reliability.NONE))
    }

    @Test
    fun coachAndClubCancelsDoNotCount() {
        val visits = (0 until 5).map { pt(it, came = true) } + listOf(
            pt(10, came = false, reason = CancelReasons.BY_COACH),
            pt(11, came = false, reason = "по инициативе клуба"),
            // Вся тренировка отменена клубом, участие осталось «ждём».
            pt(12, came = false).copy(participation = ParticipationStatus.EXPECTED, trainingReason = CancelReasons.BY_CLUB),
            pt(13, came = false, reason = CancelReasons.TRIP),
            pt(14, came = false).copy(participation = ParticipationStatus.CANCELLED_CHARGED, training = TrainingStatus.DONE),
        )
        assertEquals(Reliability(5, 2), ReliabilityRules.of(visits))
    }

    @Test
    fun ptSplitAndMiniGroupCountBigGroupDoesNot() {
        val split = pt(0, came = false).copy(kind = TrainingKind.SPLIT, typeName = "Сплит", typeShort = "Сплит")
        val kinezio = pt(1, came = true).copy(typeName = "Кинезио", typeShort = "Кинезио")
        // Мини-группа проведена, ребёнок не пришёл — отмена.
        val miniNoShow = ReliabilityVisit(at(2), TrainingKind.GROUP, "Мини-группа", "Мини", TrainingStatus.DONE, ParticipationStatus.CANCELLED)
        val miniCame = miniNoShow.copy(start = at(3), participation = ParticipationStatus.ATTENDED)
        // Мини-группу отменили целиком — не его отмена.
        val miniCancelled = miniNoShow.copy(start = at(4), training = TrainingStatus.CANCELLED, participation = ParticipationStatus.EXPECTED)
        val bigNoShow = miniNoShow.copy(start = at(5), typeName = "Группа", typeShort = "Группа")
        val spt = pt(6, came = false).copy(kind = TrainingKind.SPT, typeName = "СПТ", typeShort = "СПТ")
        val planned = pt(7, came = true).copy(training = TrainingStatus.PLANNED, participation = ParticipationStatus.EXPECTED)
        val r = ReliabilityRules.of(listOf(split, kinezio, miniNoShow, miniCame, miniCancelled, bigNoShow, spt, planned))
        assertEquals(Reliability(2, 2), r)
        assertTrue(ReliabilityRules.isMiniGroup("Мини-группа ББ", "Мини"))
        assertFalse(ReliabilityRules.isMiniGroup("Группа", "Группа"))
    }

    @Test
    fun confirmNoteShowsNamesAndTime() {
        val note = ReliabilityFormat.confirmNote(listOf("Иванов И." to at(0), "Петров П." to at(0).let { LocalDateTime(it.date, LocalTime(18, 30)) }, "Сидоров С." to at(0)))
        assertEquals("Иванов И., 17:00 · Петров П., 18:30 и ещё 1", note)
    }

    @Test
    fun groupAttendanceAndRareKids() {
        // Шаг 3.14б: большая группа считается по своим занятиям; группу отменили целиком — не в счёт.
        fun group(day: Int, came: Boolean) = ReliabilityVisit(
            at(day), TrainingKind.GROUP, "Группа", "Группа", TrainingStatus.DONE,
            if (came) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED,
        )
        val visits = (0 until 12).map { group(it, came = it % 3 == 0) } +
            group(20, came = false).copy(training = TrainingStatus.CANCELLED, participation = ParticipationStatus.EXPECTED)
        val r = ReliabilityRules.ofGroup(visits)
        assertEquals(Reliability(4, 8), r)
        assertEquals(33, r.percent)
        assertTrue(ReliabilityRules.rareInGroup(r))
        // Меньше 10 занятий или от 50 % — не предлагаем.
        assertFalse(ReliabilityRules.rareInGroup(Reliability(2, 7)))
        assertFalse(ReliabilityRules.rareInGroup(Reliability(5, 5)))
        assertFalse(ReliabilityRules.rareInGroup(null))

        val input = com.akenory.personalka.domain.group.AbsenceInput(first, lastAttended = first.plus(9, DateTimeUnit.DAY), keptOn = null, keeps = 0)
        val today = first.plus(12, DateTimeUnit.DAY)
        val rare = com.akenory.personalka.domain.group.GroupAbsence.askRare(input, r, today)!!
        assertEquals(33, rare.rarePercent)
        assertEquals("ходит на 33\u00A0% занятий из 12", com.akenory.personalka.domain.format.GroupFormat.absentSince(rare, null))
        // «Оставить» — спросит через 30 дней; дважды — больше никогда.
        assertNull(com.akenory.personalka.domain.group.GroupAbsence.askRare(input.copy(keptOn = today, keeps = 1), r, today.plus(29, DateTimeUnit.DAY)))
        assertTrue(com.akenory.personalka.domain.group.GroupAbsence.askRare(input.copy(keptOn = today, keeps = 1), r, today.plus(30, DateTimeUnit.DAY)) != null)
        assertNull(com.akenory.personalka.domain.group.GroupAbsence.askRare(input.copy(keptOn = today, keeps = 2), r, today.plus(90, DateTimeUnit.DAY)))
    }
}
