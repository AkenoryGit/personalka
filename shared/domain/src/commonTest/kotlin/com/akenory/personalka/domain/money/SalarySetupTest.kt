package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мастер «Настроить зарплату» и зарплатная часть «Профиля» (шаг 3.6б, ТЗ 5.18, 5.16, 4.11). */
class SalarySetupTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)
    private fun rub(r: Long) = r * 100
    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    @Test
    fun halfOfficialOnlyWhenOpen() {
        assertEquals(
            listOf(PaySchemeKind.FULL_OFFICIAL, PaySchemeKind.IP, PaySchemeKind.NONE),
            SalarySetupRules.schemes(emptySet()),
        )
        assertEquals(PaySchemeKind.HALF_OFFICIAL, SalarySetupRules.schemes(setOf(Feature.HALF_OFFICIAL)).first())
        // «Кабинеты» «Половину официально» не открывают.
        assertFalse(SalarySetupRules.selectable(PaySchemeKind.HALF_OFFICIAL, setOf(Feature.CABINETS)))
    }

    @Test
    fun schemeVersionFromChoice() {
        val half = SalarySetupRules.scheme(d(1, 1), PaySchemeKind.HALF_OFFICIAL, 1_354_650)
        assertEquals(5000, half.officialShareBp)
        assertEquals(25, half.advanceDay)
        assertEquals(1_354_650, half.advanceDefault)
        // У ИП нет аванса: поле аванса не пишется, даже если было заполнено до смены схемы.
        val ip = SalarySetupRules.scheme(d(1, 1), PaySchemeKind.IP, 1_354_650)
        assertEquals(0, ip.officialShareBp)
        assertNull(ip.advanceDay)
        assertNull(ip.advanceDefault)
        assertTrue(SalarySetupRules.hasAdvance(PaySchemeKind.FULL_OFFICIAL))
        assertFalse(SalarySetupRules.hasAdvance(PaySchemeKind.NONE))
    }

    @Test
    fun wizardStartsInJanuary() {
        assertEquals(d(1, 1), SalarySetupRules.start(d(10, 25)))
        assertEquals(YearMonth(2026, 1), SalarySetupRules.tierMonth(hasTiers = false, today = d(10, 25)))
        assertEquals(YearMonth(2026, 10), SalarySetupRules.tierMonth(hasTiers = true, today = d(10, 25)))
        assertTrue(SalarySetupRules.needed(emptyList()))
        assertFalse(SalarySetupRules.needed(listOf(CategoryVersion("c", d(1, 1), CoachCategory.MASTER))))
    }

    @Test
    fun gridFillsMasterAndKeepsOwnRates() {
        val bb = ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.PT, "ББ")
        assertEquals(RateAmounts(rub(920), rub(960), rub(1000)), bb)
        // Зал — по ставкам ББ, в ДБ у ПТ своя ставка.
        assertEquals(bb, ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.PT, "Зал"))
        assertEquals(RateAmounts(rub(830), rub(860), rub(900)), ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.PT, "ДБ"))
        assertEquals(RateAmounts(rub(1550), rub(1600), rub(1650)), ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.SPLIT, "ББ"))
        assertEquals(RateAmounts(rub(1200), rub(1250), rub(1320)), ClubRateGrid.amounts(CoachCategory.TRAINER, ClubService.SPLIT, "ДБ"))
        assertEquals(RateAmounts(rub(1050), rub(1100), rub(1150)), ClubRateGrid.amounts(CoachCategory.ELITE, ClubService.PT, "Зал"))
        // Грудничковое — одна строка на любое место.
        assertEquals(RateAmounts(rub(670), rub(700), rub(730)), ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.INFANT, "ДБ"))
        // 5 услуг × 3 категории; своего места у сетки нет — пусто.
        assertEquals(15, ClubRateGrid.rows.filter { it.placeShort != "Зал" }.size)
        assertNull(ClubRateGrid.amounts(CoachCategory.MASTER, ClubService.PT, "Улица"))
        assertTrue(ClubRateGrid.has(CoachCategory.TRAINER))
        assertEquals(ClubService.PT, ClubRateGrid.service("pt", "ПТ", isSplit = false, ptTypeId = "pt"))
        assertEquals(ClubService.INFANT, ClubRateGrid.service("b", "Грудничковое", isSplit = false, ptTypeId = "pt"))
        assertNull(ClubRateGrid.service("g", "Гидрореабилитация", isSplit = false, ptTypeId = "pt"))
        // Внесённая ставка главнее сетки.
        assertEquals(RateAmounts(rub(950)), SalarySetupRules.prefill(RateAmounts(rub(950)), bb))
        assertEquals(bb, SalarySetupRules.prefill(null, bb))
    }

    @Test
    fun versionEdits() {
        val versions = listOf(CategoryVersion("a", d(1, 1), CoachCategory.TRAINER), CategoryVersion("b", d(9, 1), CoachCategory.MASTER))
        fun edit(date: LocalDate, c: CoachCategory) = SalarySetupRules.edit(versions, date, { it.validFrom }) { it.category == c }
        assertEquals(VersionEdit.NONE, edit(d(10, 1), CoachCategory.MASTER))
        assertEquals(VersionEdit.REPLACE, edit(d(9, 1), CoachCategory.ELITE))
        assertEquals(VersionEdit.ADD, edit(d(11, 1), CoachCategory.ELITE))
        assertEquals(VersionEdit.ADD, SalarySetupRules.edit(emptyList<CategoryVersion>(), d(1, 1), { it.validFrom }) { true })
        assertEquals(d(11, 1), SalarySetupRules.schemeFrom(YearMonth(2026, 11)))
    }

    @Test
    fun strings() {
        assertEquals("Мастер тренер", SalarySetupFormat.category(CoachCategory.MASTER))
        assertEquals(s("Мастер тренер · с_01.01.2026"), SalarySetupFormat.since("Мастер тренер", d(1, 1)))
        assertEquals(
            s("Полностью официально · аванс 13^546,50_₽ · с_01.10.2026"),
            SalarySetupFormat.schemeRow(PaySchemeKind.FULL_OFFICIAL, 1_354_650, d(10, 1)),
        )
        assertEquals(s("ИП · с_01.01.2026"), SalarySetupFormat.schemeRow(PaySchemeKind.IP, null, d(1, 1)))
        assertEquals("Шаг 2 из 4", SalarySetupFormat.step(2))
    }
}
