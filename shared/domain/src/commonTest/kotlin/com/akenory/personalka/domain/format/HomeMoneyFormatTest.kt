package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.AdvanceState
import com.akenory.personalka.domain.money.PayScheme
import com.akenory.personalka.domain.money.Payouts
import com.akenory.personalka.domain.money.TierProgress
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Деньги» и аванс на «Главной» (шаг 3.9, ТЗ 5.2): строки из макета HomeDay и примеры ступеней. */
class HomeMoneyFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun tiers(count: Int, forecast: Int, tier: Int, forecastTier: Int) =
        TierProgress(count, forecast, tier, forecastTier, 101, 151, emptySet())

    @Test
    fun tierLine() {
        // Прогноз меняет ступень — «по расписанию будет 2» (макет HomeDay).
        assertEquals(s("94_ПТ · ступень 1 · по расписанию будет 2"), HomeFormat.tierLine(94, tiers(94, 145, 1, 2)))
        // Не меняет — сколько до следующего порога по проведённым.
        assertEquals(s("102_ПТ · ступень 2 · до_151 ещё 49_ПТ"), HomeFormat.tierLine(102, tiers(102, 130, 2, 2)))
        assertEquals(s("60_ПТ · ступень 1 · до_101 ещё 41_ПТ"), HomeFormat.tierLine(60, tiers(60, 90, 1, 1)))
        // Ступень 3 — выше некуда; ступени выключены — только число.
        assertEquals(s("160_ПТ · ступень 3"), HomeFormat.tierLine(160, tiers(160, 170, 3, 3)))
        assertEquals(s("94_ПТ"), HomeFormat.tierLine(94, null))
    }

    @Test
    fun planLine() {
        assertEquals(s("60_% плана"), SalaryFormat.ofPlan(60))
        assertEquals(s("прогноз 97_%"), HomeFormat.forecastPercent(97))
        assertEquals("Деньги · октябрь", HomeFormat.moneyTitle(LocalDate(2026, 10, 20)))
    }

    @Test
    fun advance() {
        val october = YearMonth(2026, 10)
        val half = PayScheme(LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL, advanceDay = 25, advanceDefault = 1_354_650)
        val state = Payouts.advance(october, listOf(half), null)!!
        assertEquals(AdvanceState(october, LocalDate(2026, 10, 25), 1_354_650, null), state)
        // С 25-го, пока сумма не введена.
        assertFalse(state.ask(LocalDate(2026, 10, 24)))
        assertTrue(state.ask(LocalDate(2026, 10, 25)))
        assertTrue(state.ask(LocalDate(2026, 10, 31)))
        assertFalse(state.copy(actual = 1_354_650).ask(LocalDate(2026, 10, 25)))
        assertEquals("аванс 25.10 · ожидается " + MoneyFormat.rub(1_354_650), HomeFormat.advanceNote(state))
        assertEquals("аванс 25.10", HomeFormat.advanceNote(state.copy(expected = null)))
        // «Вся сумма, без аванса» — ни пункта, ни уведомления.
        assertNull(Payouts.advance(october, listOf(PayScheme(LocalDate(2026, 3, 1), PaySchemeKind.NONE)), null))
        assertNull(Payouts.advance(october, emptyList(), null))
    }
}
