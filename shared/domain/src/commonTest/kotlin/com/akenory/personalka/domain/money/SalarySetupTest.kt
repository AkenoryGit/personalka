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
        // У всех — только «Вся сумма, без аванса»: шага схемы в мастере нет, строки в «Профиле» тоже.
        assertEquals(listOf(PaySchemeKind.NONE), SalarySetupRules.schemes(emptySet()))
        assertFalse(SalarySetupRules.schemeStep(emptySet()))
        assertFalse(SalarySetupRules.schemeRow(emptySet(), PaySchemeKind.NONE))
        assertFalse(SalarySetupRules.schemeRow(emptySet(), null))
        // Открыта «налог 6,5 %» — выбор из двух.
        val open = setOf(Feature.HALF_OFFICIAL)
        assertEquals(listOf(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL), SalarySetupRules.schemes(open))
        assertTrue(SalarySetupRules.schemeStep(open))
        assertTrue(SalarySetupRules.schemeRow(open, PaySchemeKind.NONE))
        // Срок кончился или стоит старая схема — строка видна, чтобы сменить её на «Вся сумма».
        assertTrue(SalarySetupRules.schemeRow(emptySet(), PaySchemeKind.HALF_OFFICIAL))
        assertTrue(SalarySetupRules.schemeRow(emptySet(), PaySchemeKind.IP))
        assertFalse(SalarySetupRules.selectable(PaySchemeKind.FULL_OFFICIAL, open))
        assertFalse(SalarySetupRules.selectable(PaySchemeKind.IP, open))
        // «Кабинеты» «налог 6,5 %» не открывают.
        assertFalse(SalarySetupRules.selectable(PaySchemeKind.HALF_OFFICIAL, setOf(Feature.CABINETS)))
    }

    @Test
    fun wizardSchemeFromMonth() {
        val jan = d(1, 1)
        // Переход с марта (схема Дмитрия, ТЗ 3.2): январь–февраль — вся сумма, с марта — налог 6,5 % с авансом.
        val march = SalarySetupRules.wizardSchemes(jan, PaySchemeKind.HALF_OFFICIAL, 1_354_650, YearMonth(2026, 3))
        assertEquals(listOf(YearMonth(2026, 1), YearMonth(2026, 3)), march.map { it.first })
        assertEquals(PaySchemeKind.NONE, march[0].second.kind)
        assertEquals(jan, march[0].second.validFrom)
        assertEquals(d(3, 1), march[1].second.validFrom)
        assertEquals(1_354_650, march[1].second.advanceDefault)
        // С января — одна версия.
        assertEquals(listOf(YearMonth(2026, 1)), SalarySetupRules.wizardSchemes(jan, PaySchemeKind.HALF_OFFICIAL, null, YearMonth(2026, 1)).map { it.first })
        // «Вся сумма» — всегда одна версия с января, месяц не важен.
        val none = SalarySetupRules.wizardSchemes(jan, PaySchemeKind.NONE, null, YearMonth(2026, 5)).single()
        assertEquals(YearMonth(2026, 1), none.first)
        assertEquals(jan, none.second.validFrom)
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
        assertEquals("Шаг 2 из 4", SalarySetupFormat.step(2, 4))
        assertEquals(s("Официально с налогом 6,5_%"), SalarySetupFormat.scheme(PaySchemeKind.HALF_OFFICIAL))
        assertEquals("Вся сумма, без аванса", SalarySetupFormat.scheme(PaySchemeKind.NONE))
    }
}
