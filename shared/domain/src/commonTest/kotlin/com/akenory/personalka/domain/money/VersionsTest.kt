package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Версии по датам (ТЗ 3.1) и выбор ставки тренировки по типу и месту (ТЗ 3.3). */
class VersionsTest {

    private data class V(val from: LocalDate, val name: String)

    private data class Rate(val type: String, val place: String?, val from: LocalDate, val rub: Int)

    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)

    @Test
    fun versionActsFromItsDateUntilTheNext() {
        // Схема Дмитрия (ТЗ 3.2): с 01.01.2026 — NONE, с 01.03.2026 — HALF_OFFICIAL. Порядок в списке не важен.
        val list = listOf(V(d(3, 1), "half"), V(d(1, 1), "none"))
        assertNull(Versions.activeOn(list, d(12, 31, 2025)) { it.from }, "До первой версии не действует ничего")
        assertEquals("none", Versions.activeOn(list, d(1, 1)) { it.from }?.name)
        assertEquals("none", Versions.activeOn(list, d(2, 28)) { it.from }?.name)
        assertEquals("half", Versions.activeOn(list, d(3, 1)) { it.from }?.name)
        assertEquals("half", Versions.activeOn(list, d(10, 25)) { it.from }?.name)
        assertEquals("none", Versions.activeIn(list, YearMonth(2026, 2)) { it.from }?.name)
        assertEquals("half", Versions.activeIn(list, YearMonth(2026, 3)) { it.from }?.name)
    }

    @Test
    fun twoVersionsFromOneDateTakeTheLaterInList() {
        val list = listOf(V(d(10, 1), "old"), V(d(10, 1), "fixed"))
        assertEquals("fixed", Versions.activeOn(list, d(10, 15)) { it.from }?.name)
    }

    @Test
    fun rateOfPlaceThenAnyPlaceThenNone() {
        val rates = listOf(
            Rate("pt", "bb", d(1, 1), 920),
            Rate("pt", "bb", d(11, 1), 960),
            Rate("pt", "db", d(1, 1), 830),
            Rate("duty", null, d(1, 1), 300),
            Rate("pt", null, d(6, 1), 800),
        )
        fun rub(type: String, place: String?, date: LocalDate) =
            Versions.rateFor(rates, type, place, date, { it.type }, { it.place }, { it.from })?.rub

        assertEquals(920, rub("pt", "bb", d(10, 31)))
        assertEquals(960, rub("pt", "bb", d(11, 1)), "Новая версия — с её даты")
        assertEquals(830, rub("pt", "db", d(10, 20)), "В ДБ ставки свои")
        assertEquals(800, rub("pt", "zal", d(10, 20)), "Для места версии нет — версия с пустым местом")
        assertNull(rub("pt", "zal", d(5, 31)), "Версии с пустым местом ещё нет — «нет ставки»")
        assertEquals(300, rub("duty", "bb", d(10, 20)), "Дежурство — для любого места")
        assertNull(rub("split", "bb", d(10, 20)), "Нет ставки — нет ставки")
    }

    @Test
    fun colleagueShareFollowsSchemeKind() {
        assertEquals(5000, Versions.officialShareBp(PaySchemeKind.HALF_OFFICIAL))
        assertEquals(10000, Versions.officialShareBp(PaySchemeKind.FULL_OFFICIAL))
        assertEquals(0, Versions.officialShareBp(PaySchemeKind.IP))
        assertEquals(0, Versions.officialShareBp(PaySchemeKind.NONE))
        // Доля налога владельца (ТЗ 4.8): половина официально — 0,065.
        assertEquals(650, Versions.officialShareBp(PaySchemeKind.HALF_OFFICIAL) * Versions.NDFL_BP / 10000)
    }
}
