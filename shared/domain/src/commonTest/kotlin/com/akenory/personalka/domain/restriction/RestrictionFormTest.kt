package com.akenory.personalka.domain.restriction

import kotlinx.datetime.DayOfWeek.FRIDAY
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

    @Test
    fun severalPlacesInNewRestriction() {
        val order = listOf("bb", "db", "gym")
        val empty = RestrictionDraft(null, LocalTime(12, 0), LocalTime(13, 0), date = tuesday)
        // Шаг 1.13д: первое выбранное в порядке мест — основное, остальные — ещё места.
        val two = RestrictionFormRules.togglePlace(RestrictionFormRules.togglePlace(empty, "gym", order), "db", order)
        assertEquals("db", two.locationId)
        assertEquals(listOf("db", "gym"), two.placeIds)
        assertEquals(listOf("gym"), RestrictionFormRules.togglePlace(two, "db", order).placeIds)
        // Шаг 5.3ю: «Все места» — одна запись на все места, чипы показывают выбранными все.
        val all = RestrictionFormRules.toggleAll(two, order)
        assertTrue(all.allPlaces)
        assertEquals(listOf(Restriction.ALL_PLACES), all.placeIds)
        assertEquals(order.toSet(), all.selected(order))
        assertEquals(Restriction.ALL_PLACES, RestrictionFormRules.toRestriction("r", all).locationId)
        // Все три чипа по одному — тоже «Все места»; снятое место возвращает остальные два.
        assertTrue(RestrictionFormRules.togglePlace(two, "bb", order).allPlaces)
        assertEquals(listOf("db", "gym"), RestrictionFormRules.togglePlace(all, "bb", order).placeIds)
        // Все уже выбраны — «Все места» снимает выбор, и форма просит место.
        val none = RestrictionFormRules.toggleAll(all, order)
        assertNull(none.locationId)
        assertEquals(setOf(RestrictionField.PLACE), RestrictionFormRules.problems(none).keys)
        // Одно место — его выбор не превращается в «Все места».
        assertEquals(listOf("bb"), RestrictionFormRules.togglePlace(empty, "bb", listOf("bb")).placeIds)
    }

    /** Изменение запрета: одно место или «Все места» (шаг 5.3ю). */
    @Test
    fun editSwitchesBetweenPlaceAndAllPlaces() {
        val stored = RestrictionFormRules.draftOf(
            Restriction("r", Restriction.ALL_PLACES, "Свои дела", LocalTime(12, 0), LocalTime(13, 0), date = tuesday, validFrom = tuesday),
        )
        assertTrue(stored.allPlaces)
        assertEquals("db", RestrictionFormRules.pickPlace(stored, "db").locationId)
        assertTrue(RestrictionFormRules.pickPlace(stored.copy(locationId = "db"), Restriction.ALL_PLACES).allPlaces)
    }

    @Test
    fun endNeverBeforeStart() {
        // Шаг 5.22: дело 17:00–18:00. Начало 19:30 — конец следом, 20:30; начало 23:30 — конец 00:00 (конец дня).
        val d = RestrictionDraft(null, LocalTime(17, 0), LocalTime(18, 0), date = tuesday)
        assertEquals(LocalTime(20, 30), RestrictionFormRules.withStart(d, LocalTime(19, 30)).end)
        assertEquals(LocalTime(0, 0), RestrictionFormRules.withStart(d, LocalTime(23, 30)).end)
        // Конец 16:00 раньше начала — начало уходит на час назад, 15:00; конец позже — просто конец.
        val back = RestrictionFormRules.withEnd(d, LocalTime(16, 0))
        assertEquals(LocalTime(15, 0) to LocalTime(16, 0), back.start to back.end)
        assertEquals(LocalTime(17, 0), RestrictionFormRules.withEnd(d, LocalTime(19, 0)).start)
        // Конец 00:30 при начале 00:00 и длительности час — начало 00:00, ошибки «конец раньше начала» нет.
        val early = RestrictionFormRules.withEnd(d.copy(start = LocalTime(0, 0), end = LocalTime(1, 0)), LocalTime(0, 30))
        assertEquals(LocalTime(0, 0) to LocalTime(0, 30), early.start to early.end)
        assertTrue(RestrictionFormRules.problems(back.copy(locationId = "db")).isEmpty())
    }

    @Test
    fun oneDayOfRepeatingTask() {
        // Шаг 5.23: дело «Бассейн с сыном» по вт и чт 17:00–18:00 с 01.09.2026; в чт 22.10 его сдвинули на 19:00–20:00.
        val stored = RestrictionDraft(null, LocalTime(17, 0), LocalTime(18, 0), "Бассейн с сыном", weekly = true, weekdays = setOf(TUESDAY, THURSDAY), date = LocalDate(2026, 9, 1), kind = BlockKind.PERSONAL)
        val before = RestrictionFormRules.toRestriction("p1", stored)
        val moved = stored.copy(start = LocalTime(19, 0), end = LocalTime(20, 0))
        val day = LocalDate(2026, 10, 22)
        assertTrue(RestrictionFormRules.asksScope(stored, moved, day))
        assertTrue(!RestrictionFormRules.asksScope(stored, stored, day))
        assertTrue(!RestrictionFormRules.asksScope(stored, moved, null))
        // Только этот день: у прежнего 22.10 пропущен, новое — разовое 22.10 в 19:00.
        val (kept, one) = RestrictionFormRules.scoped(before, moved, day, onlyThis = true)
        assertEquals(setOf(day), kept?.skipDates)
        assertEquals(day, one.date)
        assertTrue(!one.weekly)
        assertEquals(LocalTime(19, 0), one.start)
        // Этот и следующие: прежний — до 21.10, новое повторяется с 22.10.
        val (cut, next) = RestrictionFormRules.scoped(before, moved, day, onlyThis = false)
        assertEquals(LocalDate(2026, 10, 21), cut?.validTo)
        assertEquals(day, next.date)
        assertTrue(next.weekly)
    }

    @Test
    fun dragTaskInCalendar() {
        // Шаг 5.25: разовое дело 20.11 17:00–18:00 перетащили на 21.11 19:30 — день и время новые, час сохранён.
        val once = RestrictionDraft(null, LocalTime(17, 0), LocalTime(18, 0), "Шиномонтаж", date = LocalDate(2026, 11, 20), kind = BlockKind.PERSONAL)
        val moved = RestrictionFormRules.movedTo(once, LocalDate(2026, 11, 20), LocalDateTime(2026, 11, 21, 19, 30), 60)
        assertEquals(Triple(LocalDate(2026, 11, 21), LocalTime(19, 30), LocalTime(20, 30)), Triple(moved.date, moved.start, moved.end))
        // Повторяющееся по пятницам с 01.09: в тот же день — меняется только время; к полуночи конец упирается в 00:00.
        val weekly = once.copy(weekly = true, weekdays = setOf(FRIDAY), date = LocalDate(2026, 9, 4))
        val late = RestrictionFormRules.movedTo(weekly, LocalDate(2026, 11, 20), LocalDateTime(2026, 11, 20, 23, 30), 60)
        assertEquals(Triple(LocalDate(2026, 9, 4), LocalTime(23, 30), LocalTime(0, 0)), Triple(late.date, late.start, late.end))
        // На другой день — дата черновика новая: её возьмёт «Только этот день».
        val other = RestrictionFormRules.movedTo(weekly, LocalDate(2026, 11, 20), LocalDateTime(2026, 11, 21, 10, 0), 60)
        val (kept, one) = RestrictionFormRules.scoped(RestrictionFormRules.toRestriction("p", weekly), other, LocalDate(2026, 11, 20), onlyThis = true)
        assertEquals(setOf(LocalDate(2026, 11, 20)), kept?.skipDates)
        assertEquals(LocalDate(2026, 11, 21), one.date)
    }
}
