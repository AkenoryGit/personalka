package com.akenory.personalka.domain.restriction

import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форма запрета (ТЗ 5.5, макет «Запрет»): «ДБ 17:00–18:00, групповая тренировка, каждый вт и чт с 01.09.2026». */
class RestrictionFormTest {
    private val tuesday = LocalDate(2026, 10, 20)

    @Test
    fun newIsOneHourOnThatDay() {
        val draft = RestrictionFormRules.new(LocalDateTime(tuesday, LocalTime(17, 0)), "db")
        assertEquals(RestrictionDraft("db", LocalTime(17, 0), LocalTime(18, 0), date = tuesday), draft)
        // Позже 23:00 — до полуночи: конец 00:00 — конец дня.
        assertEquals(LocalTime(0, 0), RestrictionFormRules.new(LocalDateTime(tuesday, LocalTime(23, 30)), "db").end)
        assertTrue(RestrictionFormRules.problems(RestrictionFormRules.new(LocalDateTime(tuesday, LocalTime(23, 30)), "db")).isEmpty())
    }

    @Test
    fun weeklyAsInMockup() {
        val draft = RestrictionFormRules.weekly(
            RestrictionDraft("db", LocalTime(17, 0), LocalTime(18, 0), "Групповая тренировка ", date = LocalDate(2026, 9, 1)),
            on = true,
        )
        // День по умолчанию — день даты (01.09.2026 — вторник), второй добавляет тренер.
        assertEquals(setOf(TUESDAY), draft.weekdays)
        val restriction = RestrictionFormRules.toRestriction("r", draft.copy(weekdays = setOf(TUESDAY, THURSDAY)))
        assertEquals(setOf(TUESDAY, THURSDAY), restriction.weekdays)
        assertNull(restriction.date)
        assertEquals(LocalDate(2026, 9, 1), restriction.validFrom)
        assertEquals("Групповая тренировка", restriction.reason)
        assertTrue(RestrictionRules.occursOn(restriction, tuesday))
        // И обратно — форма изменения открывается тем же черновиком.
        assertEquals(draft.copy(weekdays = setOf(TUESDAY, THURSDAY), reason = "Групповая тренировка"), RestrictionFormRules.draftOf(restriction))
    }

    @Test
    fun oneOffKeepsOnlyItsDate() {
        // Выключили «Каждую неделю» — дни и конец периода не действуют, запрет только в свою дату.
        val draft = RestrictionDraft("db", LocalTime(0, 0), LocalTime(0, 0), "Бассейн на чистке", weekly = false, weekdays = setOf(TUESDAY), date = tuesday, validTo = LocalDate(2026, 12, 31))
        val restriction = RestrictionFormRules.toRestriction("r", draft, skipDates = setOf(tuesday))
        assertTrue(restriction.isOneOff)
        assertEquals(tuesday, restriction.date)
        assertNull(restriction.validTo)
        assertEquals(setOf(tuesday), restriction.skipDates)
        assertTrue(RestrictionFormRules.problems(draft).isEmpty())
    }

    @Test
    fun problemsUnderFields() {
        val ok = RestrictionDraft("db", LocalTime(17, 0), LocalTime(18, 0), date = tuesday)
        assertTrue(RestrictionFormRules.problems(ok).isEmpty())
        assertEquals(setOf(RestrictionField.PLACE), RestrictionFormRules.problems(ok.copy(locationId = null)).keys)
        assertEquals(setOf(RestrictionField.TIME), RestrictionFormRules.problems(ok.copy(end = LocalTime(17, 0))).keys)
        assertEquals(setOf(RestrictionField.WEEKDAYS), RestrictionFormRules.problems(ok.copy(weekly = true)).keys)
        assertEquals(
            setOf(RestrictionField.PERIOD),
            RestrictionFormRules.problems(ok.copy(weekly = true, weekdays = setOf(TUESDAY), validTo = LocalDate(2026, 10, 19))).keys,
        )
        // Конец периода у разового не важен.
        assertTrue(RestrictionFormRules.problems(ok.copy(validTo = LocalDate(2026, 10, 19))).isEmpty())
    }
}
