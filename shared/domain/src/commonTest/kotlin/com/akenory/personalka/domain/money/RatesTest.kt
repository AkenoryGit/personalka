package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форма «Ставки и ступени» (шаг 3.6, ТЗ 5.16, 3.3): ввод сумм, версии с даты, «Надбавки как у ПТ», ступени. */
class RatesTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)
    private fun rub(r: Long) = r * 100
    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    private fun saved(id: String, place: String?, from: LocalDate, t1: Long, t2: Long? = null, t3: Long? = null) =
        SavedRate(id, PayRate("pt", place, from, rub(t1), t2?.let(::rub), t3?.let(::rub)))

    @Test
    fun moneyIsReadAsTyped() {
        assertEquals(92_000, RateRules.money("920"))
        assertEquals(155_000, RateRules.money("1 550"))
        assertEquals(155_000, RateRules.money("1 550 ₽"))
        assertEquals(1_354_650, RateRules.money("13546,50"))
        assertEquals(1_354_650, RateRules.money("13 546.5"))
        assertNull(RateRules.money(""))
        assertNull(RateRules.money("-920"), "Ставка не бывает меньше нуля")
        assertNull(RateRules.money("9,999"))
        assertNull(RateRules.money("920 руб."))
        assertNull(RateRules.money("10000000000"), "Лишние нули не принимаются")
        assertNull(RateRules.moneyProblem(""))
        assertNotNull(RateRules.moneyProblem("девятьсот"))
        assertEquals("920", RateRules.moneyText(92_000))
        assertEquals("13546,50", RateRules.moneyText(1_354_650))
        assertEquals("", RateRules.moneyText(null))
    }

    @Test
    fun firstRatesFromJanuaryNextFromToday() {
        assertEquals(d(1, 1), RateRules.defaultFrom(hasRates = false, today = d(10, 20)))
        assertEquals(d(10, 20), RateRules.defaultFrom(hasRates = true, today = d(10, 20)))
    }

    @Test
    fun newDateAddsVersionSameDateReplacesIt() {
        val list = listOf(saved("a", "bb", d(1, 1), 920, 960, 1000), saved("b", "db", d(1, 1), 830))
        // Ничего не поменяли — писать нечего.
        assertEquals(
            emptyList(),
            RateRules.changes(list, d(11, 1), mapOf("bb" to RateAmounts(rub(920), rub(960), rub(1000)), "db" to RateAmounts(rub(830)))),
        )
        // С 01.11 ДБ дороже: новая версия только у ДБ.
        assertEquals(
            listOf(RateChange.Add("db", RateAmounts(rub(860)))),
            RateRules.changes(list, d(11, 1), mapOf("bb" to RateAmounts(rub(920), rub(960), rub(1000)), "db" to RateAmounts(rub(860)))),
        )
        // Ошибка в ставке с 01.01 правится в ней же — второй версии с той же датой нет.
        assertEquals(
            listOf(RateChange.Replace("b", RateAmounts(rub(830), rub(860)))),
            RateRules.changes(list, d(1, 1), mapOf("db" to RateAmounts(rub(830), rub(860)))),
        )
        // Зала не было — новая версия.
        assertEquals(listOf(RateChange.Add("zal", RateAmounts(rub(920)))), RateRules.changes(list, d(1, 1), mapOf("zal" to RateAmounts(rub(920)))))
    }

    @Test
    fun emptyFieldsRemoveOnlyVersionOfThatDate() {
        val list = listOf(saved("a", "bb", d(1, 1), 920), saved("c", "bb", d(11, 1), 960))
        assertEquals(listOf(RateChange.Remove("c")), RateRules.changes(list, d(11, 1), mapOf("bb" to null)))
        assertNotNull(RateRules.emptyProblem(list, "bb", d(11, 1)), "До 01.11 ставка есть — пустая версия с 01.11 оставила бы тренировки без ставки")
        assertNull(RateRules.emptyProblem(list, "bb", d(1, 1)), "Самая первая ставка стирается — до неё ставки не было")
        assertNull(RateRules.emptyProblem(list, "db", d(11, 1)), "В ДБ ставки нет — пусто можно")
        assertEquals(emptyList(), RateRules.changes(list, d(12, 1), mapOf("db" to null)))
    }

    @Test
    fun tierTwoWithoutTierOneIsAnError() {
        assertTrue(RateRules.needsT1(null, rub(960), null, null))
        assertTrue(RateRules.needsT1(null, null, null, rub(140)))
        assertFalse(RateRules.needsT1(null, null, null, null))
        assertFalse(RateRules.needsT1(rub(920), null, null, null))
    }

    @Test
    fun bonusesLikePt() {
        // ТЗ 3.3: ступени 2 и 3 = своя базовая + та же прибавка, что у ПТ в этом месте (ПТ · ББ 920 / 960 / 1 000).
        val pt = RateAmounts(rub(920), rub(960), rub(1000))
        assertEquals(rub(790) to rub(830), RateRules.likePt(rub(750), pt))
        // У ПТ в ДБ только две ступени — третьей не появляется.
        assertEquals(rub(1040) to null, RateRules.likePt(rub(1000), RateAmounts(rub(830), rub(870))))
        assertNull(RateRules.likePt(rub(750), null))
        assertTrue(RateRules.canCopyPtBonus(TrainingKind.PERSONAL, tierDependent = true, isPt = false))
        assertFalse(RateRules.canCopyPtBonus(TrainingKind.PERSONAL, tierDependent = true, isPt = true))
        assertFalse(RateRules.canCopyPtBonus(TrainingKind.SPLIT, tierDependent = true, isPt = false))
        assertFalse(RateRules.canCopyPtBonus(TrainingKind.PERSONAL, tierDependent = false, isPt = false))
    }

    @Test
    fun dutyHasOneRateForAnyPlace() {
        assertFalse(RateRules.perPlace(TrainingKind.DUTY))
        assertTrue(RateRules.perPlace(TrainingKind.GROUP))
    }

    @Test
    fun tiersByMonthOrDefault() {
        val month = YearMonth(2026, 10)
        // Пока ступеней нет — без ступеней, поля порогов пустые (пример 101 и 151 — в подсказке, шаг 5.3а).
        assertEquals(TierDraft(false, 101, 151, setOf("pt", "split")), RateRules.tierDraft(emptyList(), month, setOf("pt", "split")))
        assertEquals("", RateRules.thresholdText(emptyList(), month, 101))
        assertEquals("например, 101", com.akenory.personalka.domain.format.RateFormat.example(101))
        val versions = listOf(TierScheme(d(9, 1), enabled = false, ptTypeIds = setOf("pt")), TierScheme(d(11, 1), t2From = 90, t3From = 140, ptTypeIds = setOf("pt", "kin")))
        assertEquals(TierDraft(false, 101, 151, setOf("pt")), RateRules.tierDraft(versions, month, setOf("x")))
        assertEquals(TierDraft(true, 90, 140, setOf("pt", "kin")), RateRules.tierDraft(versions, YearMonth(2026, 11), setOf("x")))
        assertEquals("90", RateRules.thresholdText(versions, YearMonth(2026, 11), 90))
        assertEquals(d(11, 1), RateRules.tierFrom(YearMonth(2026, 11)))
        assertEquals(101, RateRules.threshold(" 101 "))
        assertNull(RateRules.threshold("0"))
        assertNull(RateRules.threshold("сто"))
        assertNull(RateRules.tierProblem(101, 151))
        assertEquals("Ступень 3 начинается позже ступени 2", RateRules.tierProblem(151, 151))
        assertNotNull(RateRules.tierProblem(null, 151))
    }

    @Test
    fun strings() {
        assertEquals(s("920 / 960 / 1^000"), RateFormat.amounts(RateAmounts(rub(920), rub(960), rub(1000))))
        assertEquals("200 + 140 за блок", RateFormat.amounts(RateAmounts(rub(200), sptBonus = rub(140))))
        assertEquals(
            s("ББ_920 / 960 / 1^000 · ДБ_830 · Зал_—"),
            RateFormat.summary(listOf("ББ" to RateAmounts(rub(920), rub(960), rub(1000)), "ДБ" to RateAmounts(rub(830)), "Зал" to null), PayUnit.PER_SESSION),
        )
        assertEquals(s("ББ_270 · ДБ_— за_клиента"), RateFormat.summary(listOf("ББ" to RateAmounts(rub(270)), "ДБ" to null), PayUnit.PER_CLIENT))
        assertEquals(s("200_₽ за_час"), RateFormat.summary(listOf(null to RateAmounts(rub(200))), PayUnit.PER_HOUR))
        assertEquals("Нет ставки", RateFormat.summary(listOf("ББ" to null), PayUnit.PER_SESSION))
        assertEquals(s("Ступень 2 — от 101_ПТ, 3 — от 151 · с_01.10.2026"), RateFormat.tiers(TierDraft(true, 101, 151, emptySet()), d(10, 1)))
        assertEquals("Без ступеней", RateFormat.tiers(TierDraft(false, 101, 151, emptySet()), null))
        assertEquals(s("Нет ставки у 3_типов"), RateFormat.settingsRow(3, TierDraft(true, 101, 151, emptySet())))
        assertEquals(s("Нет ставки у 1_типа"), RateFormat.settingsRow(1, TierDraft(true, 101, 151, emptySet())))
        assertEquals(s("Ставки «ПТ» с_01.11.2026 сохранены"), RateFormat.saved("ПТ", d(11, 1)))
    }
}
