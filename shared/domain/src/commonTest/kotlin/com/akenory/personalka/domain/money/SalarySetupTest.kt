package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мастер «Настроить зарплату» и зарплатная часть «Профиля» (шаги 3.6б и 5.3а, ТЗ 5.18, 5.16, 4.11). */
class SalarySetupTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)
    private fun rub(r: Long) = r * 100
    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    @Test
    fun halfOfficialOnlyWhenOpen() {
        // У всех — только «Вся сумма, без аванса»: шага схемы в мастере нет, строки в «Профиле» тоже.
        assertEquals(listOf(PaySchemeKind.NONE), SalarySetupRules.schemes(emptySet()))
        assertFalse(SalarySetupRules.schemeStep(emptySet()))
        assertFalse(SalarySetupRules.schemeRow(emptySet()))
        assertFalse(SalarySetupRules.payTypes(emptySet()))
        // Открыта «налог 6,5 %» — выбор из двух.
        val open = setOf(Feature.HALF_OFFICIAL)
        assertEquals(listOf(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL), SalarySetupRules.schemes(open))
        assertTrue(SalarySetupRules.schemeStep(open))
        assertTrue(SalarySetupRules.schemeRow(open))
        assertTrue(SalarySetupRules.payTypes(open))
        // Решение 27.10.2026: закрыта — строки нет и со старой схемой, считается «100 % одной выплатой»; свой день
        // зарплаты остаётся (5.3а).
        val plain = SalarySetupRules.plain(PayScheme(LocalDate(2026, 1, 1), PaySchemeKind.HALF_OFFICIAL, advanceDefault = 1_000_00, salaryDay = 5))
        assertEquals(PayScheme(LocalDate(2026, 1, 1), PaySchemeKind.NONE, salaryDay = 5), plain)
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
        // Дни выплат из мастера (5.3а) — в обеих версиях перехода; день аванса — только у схемы с авансом.
        val days = SalarySetupRules.wizardSchemes(jan, PaySchemeKind.HALF_OFFICIAL, null, YearMonth(2026, 3), salaryDay = 5, advanceDay = 20)
        assertEquals(listOf(5, 5), days.map { it.second.salaryDay })
        assertEquals(listOf(null, 20), days.map { it.second.advanceDay })
    }

    @Test
    fun schemeVersionFromChoice() {
        val half = SalarySetupRules.scheme(d(1, 1), PaySchemeKind.HALF_OFFICIAL, 1_354_650)
        assertEquals(5000, half.officialShareBp)
        // День аванса не вписан — пример, 25-е; вписан — свой.
        assertEquals(25, half.advanceDay)
        assertEquals(20, SalarySetupRules.scheme(d(1, 1), PaySchemeKind.HALF_OFFICIAL, null, advanceDay = 20).advanceDay)
        assertEquals(10, half.salaryDay)
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
        // Признак настроенной зарплаты — схема выплат (категорий нет, 5.3а).
        assertTrue(SalarySetupRules.needed(emptyList<SavedScheme>()))
        assertFalse(SalarySetupRules.needed(listOf(SavedScheme("s", PayScheme(d(1, 1), PaySchemeKind.NONE)))))
    }

    @Test
    fun exampleRatesAndOwnRatesWin() {
        // Пример одинаков для любого места: мест и категорий одного клуба у приложения нет (5.3а).
        val pt = ExampleRates.amounts(ExampleService.PT)
        assertEquals(RateAmounts(rub(920), rub(960), rub(1000)), pt)
        assertEquals(RateAmounts(rub(1550), rub(1600), rub(1650)), ExampleRates.amounts(ExampleService.SPLIT))
        assertEquals(RateAmounts(rub(670), rub(700), rub(730)), ExampleRates.amounts(ExampleService.INFANT))
        assertEquals(ExampleService.PT, ExampleRates.service("pt", "ПТ", isSplit = false, ptTypeId = "pt"))
        assertEquals(ExampleService.SPLIT, ExampleRates.service("s", "Сплит", isSplit = true, ptTypeId = "pt"))
        assertEquals(ExampleService.INFANT, ExampleRates.service("b", "Грудничковое", isSplit = false, ptTypeId = "pt"))
        assertNull(ExampleRates.service("g", "Гидрореабилитация", isSplit = false, ptTypeId = "pt"))
        // Внесённая ставка главнее примера; «Вписать свои» — пусто.
        assertEquals(RateAmounts(rub(950)), SalarySetupRules.prefill(RateAmounts(rub(950)), pt))
        assertEquals(pt, SalarySetupRules.prefill(null, pt))
        assertNull(SalarySetupRules.prefill(null, null))
    }

    @Test
    fun daysFromFields() {
        assertEquals(10, SalarySetupRules.day(" 10 "))
        assertEquals(31, SalarySetupRules.day("31"))
        assertNull(SalarySetupRules.day(""))
        assertNull(SalarySetupRules.day("0"))
        assertNull(SalarySetupRules.day("32"))
        assertEquals("Число месяца от 1 до 31", SalarySetupRules.dayProblem("десятое"))
        assertNull(SalarySetupRules.dayProblem("5"))
        // Смена дня зарплаты — другая версия схемы.
        val base = PayScheme(d(1, 1), PaySchemeKind.NONE)
        assertTrue(SalarySetupRules.sameScheme(base, base.copy()))
        assertFalse(SalarySetupRules.sameScheme(base, base.copy(salaryDay = 5)))
        assertFalse(SalarySetupRules.sameScheme(base, base.copy(advanceDay = 20)))
    }

    @Test
    fun versionEdits() {
        val versions = listOf(PayScheme(d(1, 1), PaySchemeKind.NONE), PayScheme(d(9, 1), PaySchemeKind.NONE, salaryDay = 5))
        fun edit(date: LocalDate, day: Int) = SalarySetupRules.edit(versions, date, { it.validFrom }) { it.salaryDay == day }
        assertEquals(VersionEdit.NONE, edit(d(10, 1), 5))
        assertEquals(VersionEdit.REPLACE, edit(d(9, 1), 7))
        assertEquals(VersionEdit.ADD, edit(d(11, 1), 7))
        assertEquals(VersionEdit.ADD, SalarySetupRules.edit(emptyList<PayScheme>(), d(1, 1), { it.validFrom }) { true })
        assertEquals(d(11, 1), SalarySetupRules.schemeFrom(YearMonth(2026, 11)))
    }

    @Test
    fun strings() {
        assertEquals("10-го числа следующего месяца", SalarySetupFormat.salaryDay(10))
        assertEquals(s("Вся сумма · с_01.01.2026"), SalarySetupFormat.since("Вся сумма", d(1, 1)))
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
