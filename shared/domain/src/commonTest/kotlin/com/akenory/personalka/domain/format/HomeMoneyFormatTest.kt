package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.AdvanceState
import com.akenory.personalka.domain.money.PayScheme
import com.akenory.personalka.domain.money.Payouts
import com.akenory.personalka.domain.money.PlanPace
import com.akenory.personalka.domain.money.SalaryPlan
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
    fun tierChip() {
        // До 101-й — сколько осталось по проведённым; дальше — «бонус» и «максимум» (просьба Дмитрия 27.10.2026).
        assertEquals(s("Ступень 1 · до бонуса 61_ПТ"), HomeFormat.tierChip(tiers(40, 55, 1, 1)))
        assertEquals(s("Ступень 1 · до бонуса 7_ПТ"), HomeFormat.tierChip(tiers(94, 145, 1, 2)))
        assertEquals("Ступень 2 · бонус", HomeFormat.tierChip(tiers(105, 132, 2, 2)))
        assertEquals("Ступень 3 · максимум", HomeFormat.tierChip(tiers(157, 184, 3, 3)))
        assertNull(HomeFormat.tierChip(null))
    }

    @Test
    fun pace() {
        // План 80 000: проведено 36 800, по расписанию 61 640 — не хватает; план 120 000 при прогнозе 126 720 — идёт.
        assertEquals(PlanPace.BEHIND, SalaryPlan.pace(3_680_000, 6_164_000, 8_000_000))
        assertEquals(PlanPace.ON_TRACK, SalaryPlan.pace(10_080_000, 12_672_000, 12_000_000))
        assertEquals(PlanPace.DONE, SalaryPlan.pace(15_700_000, 18_400_000, 15_000_000))
        assertEquals(PlanPace.DONE, SalaryPlan.pace(15_000_000, 15_000_000, 15_000_000))
        assertNull(SalaryPlan.pace(100, 200, null))
        assertNull(SalaryPlan.pace(100, 200, 0))
        assertEquals(s("Поднажать: до плана ещё 20_ПТ"), HomeFormat.pace(PlanPace.BEHIND, 46, 77, 20))
        assertEquals(s("Поднажать: прогноз 77_% плана"), HomeFormat.pace(PlanPace.BEHIND, 46, 77, null))
        assertEquals(s("Хорошо идёте: прогноз 105_% плана"), HomeFormat.pace(PlanPace.ON_TRACK, 84, 105, null))
        assertEquals(s("План выполнен: 104_%"), HomeFormat.pace(PlanPace.DONE, 104, 122, null))
    }

    @Test
    fun todayAndMonthColumns() {
        // Сегодня 3 680 ₽ проведено, по расписанию к вечеру 6 440 ₽.
        assertEquals("будет " + MoneyFormat.approxRub(644_000), HomeFormat.willBe(368_000, 644_000))
        assertEquals("всё проведено", HomeFormat.willBe(644_000, 644_000))
        assertEquals("тренировок нет", HomeFormat.willBe(0, 0))
        assertEquals(s("2_ПТ · будет 5"), HomeFormat.ptLine(2, 5))
        assertEquals(s("94_ПТ · будет 131"), HomeFormat.ptLine(94, 131))
        assertEquals(s("4_ПТ"), HomeFormat.ptLine(4, 4))
        assertEquals("без ПТ", HomeFormat.ptLine(0, 0))
        // Отмены (просьба Дмитрия 26.10.2026): нет — строки нет.
        assertEquals(s("1_отмена"), HomeFormat.cancelledLine(1))
        assertEquals(s("3_отмены"), HomeFormat.cancelledLine(3))
        assertEquals(s("5_отмен"), HomeFormat.cancelledLine(5))
        assertNull(HomeFormat.cancelledLine(0))
    }

    @Test
    fun planLine() {
        assertEquals(s("60_% плана"), SalaryFormat.ofPlan(60))
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
