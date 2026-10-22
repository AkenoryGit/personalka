package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.calendar.CalendarBadge.CHARGED
import com.akenory.personalka.domain.calendar.CalendarBadge.DONE
import com.akenory.personalka.domain.calendar.CalendarBadge.NO_DIARY
import com.akenory.personalka.domain.calendar.CalendarBadge.OVER_DUTY
import com.akenory.personalka.domain.calendar.CalendarBadge.OVER_RESTRICTION
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.DiaryRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Значки блока по важности (ТЗ 5.3) на примерах макетов «День» и «Блоки, значки, наложения» (шаг 0.3). */
class CalendarBadgesTest {
    @Test
    fun allBadgesInOrderOfImportance() {
        val all = CalendarBadges.of(
            BadgeFacts(charged = true, overRestriction = true, noDiary = true, overDuty = true, done = true),
        )
        assertEquals(listOf(CHARGED, OVER_RESTRICTION, NO_DIARY, OVER_DUTY, DONE), all)
    }

    @Test
    fun lowBlockShowsOneTallBlockTwo() {
        // Морозов на 60 минут — точка и галочка; на 50 и 30 минут — только точка.
        val morozov = CalendarBadges.of(BadgeFacts(noDiary = true, done = true))
        assertEquals(listOf(NO_DIARY, DONE), CalendarBadges.visible(morozov, BlockRows.THREE))
        assertEquals(listOf(NO_DIARY), CalendarBadges.visible(morozov, BlockRows.TWO))
        assertEquals(listOf(NO_DIARY), CalendarBadges.visible(morozov, BlockRows.ONE))
        assertEquals(emptyList(), CalendarBadges.visible(emptyList(), BlockRows.THREE))
    }

    @Test
    fun dayMockup() {
        // Орлова 13:30 проведена, поверх дежурства и без дневника: на блоке 53 dp — точка, «слои» не видны.
        val orlova = CalendarBadges.of(BadgeFacts(noDiary = true, overDuty = true, done = true))
        assertEquals(listOf(NO_DIARY), CalendarBadges.visible(orlova, BlockRows.TWO))
        // Никитин 12:00 поверх дежурства и проведён: «слои» важнее галочки.
        val nikitin = CalendarBadges.of(BadgeFacts(overDuty = true, done = true))
        assertEquals(listOf(OVER_DUTY), CalendarBadges.visible(nikitin, BlockRows.TWO))
        // Орлов 17:00 поверх запрета ещё не начался.
        assertEquals(listOf(OVER_RESTRICTION), CalendarBadges.of(BadgeFacts(overRestriction = true)))
        // Соколова 15:00 идёт сейчас: значков нет.
        assertEquals(emptyList(), CalendarBadges.of(BadgeFacts()))
    }

    @Test
    fun cancelledShowsOnlyRuble() {
        // Зайцева — отмена со списанием: «₽». Захаров — отмена без списания: значков нет, хоть и стоял поверх запрета.
        assertEquals(listOf(CHARGED), CalendarBadges.of(BadgeFacts(cancelled = true, charged = true, overRestriction = true, overDuty = true)))
        assertEquals(emptyList(), CalendarBadges.of(BadgeFacts(cancelled = true, overRestriction = true)))
        // Сплит, где один отменил со списанием, а второй пришёл: «₽» и галочка.
        assertEquals(listOf(CHARGED, DONE), CalendarBadges.of(BadgeFacts(charged = true, done = true)))
    }

    @Test
    fun diaryMissingOnlyWhenDoneAndFactKeptButEmpty() {
        assertTrue(DiaryRules.missing(TrainingStatus.DONE, diaryEnabled = true, keepsFact = true, fact = null))
        assertTrue(DiaryRules.missing(TrainingStatus.DONE, diaryEnabled = true, keepsFact = true, fact = "  "))
        assertFalse(DiaryRules.missing(TrainingStatus.DONE, diaryEnabled = true, keepsFact = true, fact = "Кроль 8 × 25"))
        // Ещё не проведена или отменена — дневник не ждут.
        assertFalse(DiaryRules.missing(TrainingStatus.PLANNED, diaryEnabled = true, keepsFact = true, fact = null))
        assertFalse(DiaryRules.missing(TrainingStatus.CANCELLED, diaryEnabled = true, keepsFact = true, fact = null))
        // Дневник выключен в настройках или у типа нет факта (дежурство).
        assertFalse(DiaryRules.missing(TrainingStatus.DONE, diaryEnabled = false, keepsFact = true, fact = null))
        assertFalse(DiaryRules.missing(TrainingStatus.DONE, diaryEnabled = true, keepsFact = false, fact = null))
    }
}
