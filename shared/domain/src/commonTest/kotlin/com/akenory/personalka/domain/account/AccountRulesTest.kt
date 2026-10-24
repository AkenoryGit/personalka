package com.akenory.personalka.domain.account

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountRulesTest {
    private val oct24 = LocalDate(2026, 10, 24)

    @Test
    fun emailIsTrimmedAndLowercased() {
        assertEquals("trener@example.ru", AccountRules.email("  Trener@Example.RU "))
        assertNull(AccountRules.email("trener@example"))
        assertNull(AccountRules.email("тренер example.ru"))
        assertNull(AccountRules.email(""))
    }

    @Test
    fun passwordIsAtLeastTenSigns() {
        assertEquals("Пароль — не короче 10 знаков", AccountRules.passwordProblem("123456789"))
        assertNull(AccountRules.passwordProblem("1234567890"))
        assertEquals("Пароль не может состоять из пробелов", AccountRules.passwordProblem(" ".repeat(12)))
        assertEquals("Пароль — не длиннее 200 знаков", AccountRules.passwordProblem("а".repeat(201)))
    }

    @Test
    fun nameCollapsesSpaces() {
        assertEquals("Иван Петров", AccountRules.name("  Иван   Петров "))
        assertNull(AccountRules.name("   "))
    }

    @Test
    fun newAccountHasNothingOpen() {
        assertEquals(emptySet(), AccountRules.open(emptyList(), oct24))
    }

    @Test
    fun lastDayIsStillOpen() {
        val grants = listOf(FeatureGrant(Feature.HALF_OFFICIAL, LocalDate(2026, 10, 31)))
        assertEquals(setOf(Feature.HALF_OFFICIAL), AccountRules.open(grants, LocalDate(2026, 10, 31)))
        assertEquals(emptySet(), AccountRules.open(grants, LocalDate(2026, 11, 1)))
    }

    @Test
    fun cabinetsOpenServerCopyToo() {
        val dec31 = LocalDate(2026, 12, 31)
        val grants = listOf(FeatureGrant(Feature.SERVER_COPY, LocalDate(2026, 11, 30)), FeatureGrant(Feature.CABINETS, dec31))
        assertEquals(mapOf(Feature.CABINETS to dec31, Feature.SERVER_COPY to dec31), AccountRules.effective(grants))
        assertEquals(setOf(Feature.CABINETS, Feature.SERVER_COPY), AccountRules.open(grants, LocalDate(2026, 12, 15)))
    }

    @Test
    fun serverCopyAloneDoesNotOpenCabinets() {
        val grants = listOf(FeatureGrant(Feature.SERVER_COPY, null))
        assertEquals(setOf(Feature.SERVER_COPY), AccountRules.open(grants, oct24))
    }

    @Test
    fun ownLongerDateWins() {
        val grants = listOf(FeatureGrant(Feature.CABINETS, LocalDate(2026, 11, 30)), FeatureGrant(Feature.SERVER_COPY, null))
        assertEquals(mapOf(Feature.CABINETS to LocalDate(2026, 11, 30), Feature.SERVER_COPY to null), AccountRules.effective(grants))
    }

    @Test
    fun adminHasEverythingWithoutDate() {
        assertEquals(Feature.entries.toSet(), AccountRules.open(AccountRules.admin(), LocalDate(2040, 1, 1)))
    }

    @Test
    fun remindSevenDaysBeforeEnd() {
        val grants = listOf(FeatureGrant(Feature.HALF_OFFICIAL, LocalDate(2026, 10, 31)), FeatureGrant(Feature.SERVER_COPY, LocalDate(2026, 11, 1)))
        assertEquals(mapOf(Feature.HALF_OFFICIAL to LocalDate(2026, 10, 31)), AccountRules.endingSoon(grants, oct24))
        assertEquals(emptyMap(), AccountRules.endingSoon(grants, LocalDate(2026, 11, 2)))
    }

    @Test
    fun codesAreStable() {
        assertEquals(listOf("half_official", "cabinets", "server_copy"), Feature.entries.map { it.code })
        assertEquals(Feature.CABINETS, Feature.of("cabinets"))
        assertNull(Feature.of("photo"))
    }
}
