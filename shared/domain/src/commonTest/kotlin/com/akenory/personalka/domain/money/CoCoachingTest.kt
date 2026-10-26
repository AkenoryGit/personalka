package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.BlockWho
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Совместное ведение групп: коллеги, «На ком числится» и «Проводим» (шаг 3.7г, ТЗ 4.8, 5.4, 5.10, 5.16). Имена придуманы. */
class CoCoachingTest {
    private val lena = "c-lena"
    private val names = mapOf(lena to "Лена")

    @Test
    fun colleagueNameIsRequiredAndUnique() {
        assertEquals("Введите имя", CoCoachingRules.nameProblem("  ", emptyList()))
        assertEquals("Такой коллега уже есть", CoCoachingRules.nameProblem(" лена ", listOf("Лена")))
        assertNull(CoCoachingRules.nameProblem("Алёна", listOf("Лена")))
    }

    @Test
    fun colleagueSchemesAreWholeSumAndHiddenTax() {
        // Решение 3.6в: ИП и «Полностью официально» не выбираются; 6,5 % у коллеги — w = 0,065 (4.8).
        assertEquals(listOf(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL), CoCoachingRules.SCHEMES)
        assertEquals(LocalDate(2026, 1, 1), CoCoachingRules.firstFrom(LocalDate(2026, 10, 25)))
        assertEquals(LocalDate(2026, 11, 1), CoCoachingRules.changeFrom(YearMonth(2026, 11)))
    }

    @Test
    fun normalizeKeepsOnlyTheColleagueThatCounts() {
        // Своя группа, веду один — коллега не нужен; группа коллеги — коллега в расчёте её владелец.
        assertEquals(Sharing.SELF, CoCoachingRules.normalize(Sharing(conductedBy = ConductedBy.SELF, colleagueId = lena)))
        assertEquals(
            Sharing(Owner.SELF, null, ConductedBy.SELF_AND_COLLEAGUE, lena),
            CoCoachingRules.normalize(Sharing(Owner.SELF, lena, ConductedBy.SELF_AND_COLLEAGUE, lena)),
        )
        assertEquals(
            Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF, lena),
            CoCoachingRules.normalize(Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF, null)),
        )
    }

    @Test
    fun colleagueMustBePickedWhenNeeded() {
        assertNull(CoCoachingRules.problem(Sharing.SELF))
        assertEquals("Выберите коллегу", CoCoachingRules.problem(Sharing(conductedBy = ConductedBy.COLLEAGUE)))
        assertEquals("Выберите коллегу", CoCoachingRules.problem(Sharing(owner = Owner.COLLEAGUE)))
        assertNull(CoCoachingRules.problem(Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF_AND_COLLEAGUE)))
    }

    @Test
    fun groupChangeFollowsOnlyUnchangedTrainings() {
        val old = Sharing.SELF
        assertTrue(CoCoachingRules.follows(Sharing.SELF, old))
        // Тренировку уже поменяли в карточке («Коллега (замена)») — правка группы её не трогает.
        assertFalse(CoCoachingRules.follows(Sharing(conductedBy = ConductedBy.COLLEAGUE, colleagueId = lena), old))
    }

    @Test
    fun linesUseNamesWithoutDeclension() {
        // Пол коллеги не хранится — имя пишется как есть, без падежа (уточнение 3.7 к 4.8).
        val together = Sharing(Owner.SELF, null, ConductedBy.SELF_AND_COLLEAGUE, lena)
        assertEquals("Числится на: я", CoCoachingFormat.ownerLine(together, names))
        assertEquals("Проводим: с коллегой · Лена", CoCoachingFormat.conductedLine(together, names))
        val hers = Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF, lena)
        assertEquals("Числится на: Лена", CoCoachingFormat.ownerLine(hers, names))
        assertEquals("Проводим: один", CoCoachingFormat.conductedLine(hers, names))
        assertEquals("числится: Лена", CoCoachingFormat.listNote(hers, names))
        assertNull(CoCoachingFormat.listNote(together, names))
        assertEquals("Проводим: коллега (замена) · коллега", CoCoachingFormat.conductedLine(Sharing(conductedBy = ConductedBy.COLLEAGUE, colleagueId = "нет"), names))
        assertEquals("Пока нет", CoCoachingFormat.count(0))
        assertEquals("2${RuText.NBSP}коллеги", CoCoachingFormat.count(2))
        assertEquals("5${RuText.NBSP}коллег", CoCoachingFormat.count(5))
    }

    @Test
    fun blockShowsWhoLeadsTheGroup() {
        // Своя группа, веду один, — как без совместного ведения: плашки нет (шаг 3.9л).
        assertNull(CoCoachingFormat.blockWho(Sharing.SELF, names))
        fun who(o: Owner, c: ConductedBy) = CoCoachingFormat.blockWho(Sharing(o, lena.takeIf { o == Owner.COLLEAGUE }, c, lena), names)
        assertEquals(BlockWho("Вдвоём · Лена", "Вдвоём"), who(Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE))
        assertEquals(BlockWho("Ведёт Лена", "Ведёт Лена"), who(Owner.SELF, ConductedBy.COLLEAGUE))
        assertEquals(BlockWho("Один · числится Лена", "Один"), who(Owner.COLLEAGUE, ConductedBy.SELF))
        assertEquals(BlockWho("Вдвоём · числится Лена", "Вдвоём"), who(Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE))
        assertEquals(BlockWho("Ведёт Лена · числится Лена", "Ведёт Лена"), who(Owner.COLLEAGUE, ConductedBy.COLLEAGUE))
        // «Веду один» в своей группе с оставшимся коллегой — всё равно своя без коллеги.
        assertNull(CoCoachingFormat.blockWho(Sharing(conductedBy = ConductedBy.SELF, colleagueId = lena), names))
    }
}
