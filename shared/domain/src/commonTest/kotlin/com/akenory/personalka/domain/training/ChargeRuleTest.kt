package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChargeRuleTest {
    private val start = LocalDateTime(2026, 10, 20, 10, 0)

    private fun at(hour: Int, minute: Int, second: Int = 0) = LocalDateTime(2026, 10, 20, hour, minute, second)

    private fun decide(
        notifiedAt: LocalDateTime,
        reason: String? = CancelReasons.CANNOT_COME,
        kind: TrainingKind = TrainingKind.PERSONAL,
        threshold: Int = 60,
    ) = ChargeRule.decide(kind, start, notifiedAt, reason, threshold)

    /** ТЗ 12, пример 19: начало 10:00, порог 60 мин; 09:20 / 09:00 / 08:59 / 10:05 — со списанием / без / без / со списанием. */
    @Test
    fun example19() {
        val results = listOf(at(9, 20), at(9, 0), at(8, 59), at(10, 5)).map { decide(it).status }
        assertEquals(
            listOf(
                ParticipationStatus.CANCELLED_CHARGED,
                ParticipationStatus.CANCELLED,
                ParticipationStatus.CANCELLED,
                ParticipationStatus.CANCELLED_CHARGED,
            ),
            results,
        )
    }

    @Test
    fun example19Causes() {
        assertEquals(ChargeDecision(true, ChargeCause.LATE, 40), decide(at(9, 20)))
        assertEquals(ChargeDecision(false, ChargeCause.IN_TIME, 60), decide(at(9, 0)))
        assertEquals(ChargeDecision(false, ChargeCause.IN_TIME, 61), decide(at(8, 59)))
        assertEquals(ChargeDecision(true, ChargeCause.AFTER_START, -5), decide(at(10, 5)))
    }

    @Test
    fun exactlyAtStartIsCharged() {
        assertEquals(ChargeDecision(true, ChargeCause.LATE, 0), decide(at(10, 0)))
    }

    @Test
    fun secondsDoNotMatter() {
        // «Сейчас, 09:00» нажато в 09:00:45: тренер видит «за 1 ч» — и списания нет.
        assertFalse(decide(at(9, 0, 45)).charged)
        assertTrue(decide(at(9, 1, 0)).charged)
    }

    @Test
    fun thresholdComesFromSettings() {
        assertTrue(decide(at(8, 45), threshold = 90).charged)
        assertFalse(decide(at(8, 30), threshold = 90).charged)
        // Порог 0: списание только если предупредили после начала.
        assertFalse(decide(at(10, 0), threshold = 0).charged)
        assertTrue(decide(at(10, 1), threshold = 0).charged)
    }

    @Test
    fun coachOrClubInitiativeIsNeverCharged() {
        assertEquals(ChargeCause.NOT_BY_CLIENT, decide(at(9, 50), CancelReasons.BY_COACH).cause)
        assertFalse(decide(at(9, 50), CancelReasons.BY_CLUB).charged)
        assertFalse(decide(at(10, 30), CancelReasons.BY_CLUB).charged)
        // Свой текст, совпадающий с чипом, — тоже без списания.
        assertFalse(decide(at(9, 50), "  по инициативе клуба ").charged)
    }

    @Test
    fun otherReasonsFollowTheRule() {
        assertTrue(decide(at(9, 50), CancelReasons.TRIP).charged)
        assertTrue(decide(at(9, 50), "Сломалась машина").charged)
        assertTrue(decide(at(9, 50), null).charged)
    }

    @Test
    fun startTrainingIsFreeForClient() {
        val d = decide(at(9, 50), kind = TrainingKind.SPT)
        assertEquals(ChargeCause.FREE_SPT, d.cause)
        assertEquals(ParticipationStatus.CANCELLED, d.status)
    }

    @Test
    fun noticeFromPreviousDay() {
        assertFalse(decide(LocalDateTime(2026, 10, 19, 21, 30)).charged)
        assertEquals(750L, decide(LocalDateTime(2026, 10, 19, 21, 30)).noticeMinutes)
    }

    @Test
    fun defaultChipsHaveNoIllness() {
        assertEquals(5, CancelReasons.DEFAULT_CHIPS.size)
        assertFalse(CancelReasons.DEFAULT_CHIPS.any { it.contains("Болезнь", ignoreCase = true) })
    }
}
