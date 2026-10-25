package com.akenory.personalka.domain.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Перенос плана (шаг 2.2, ТЗ 4.3) — все четыре случая таблицы, по макетам «После отмены» и «У следующей уже есть план». */
class PlanCarryRulesTest {
    private val brass = "Брасс: согласование рук и ног, 8 × 25 м."
    private val back = "Кроль на спине: 4 × 50 м, повороты."

    @Test
    fun cancelWithEmptyNextMovesSilently() {
        // Случай 1: у следующей план пуст — переносится молча.
        assertEquals(CarryStep.MOVE, PlanCarryRules.onCancel(brass, hasNext = true, nextPlan = null))
        assertEquals(CarryStep.MOVE, PlanCarryRules.onCancel(brass, hasNext = true, nextPlan = "  "))
        // Плана нет — переносить нечего.
        assertEquals(CarryStep.NONE, PlanCarryRules.onCancel(null, hasNext = true, nextPlan = back))
        assertEquals(CarryStep.NONE, PlanCarryRules.onCancel("", hasNext = false, nextPlan = null))
    }

    @Test
    fun cancelWithPlannedNextAsks() {
        // Случай 2: у следующей есть план — вопрос; «Добавить» дописывает с новой строки.
        assertEquals(CarryStep.ASK, PlanCarryRules.onCancel(brass, hasNext = true, nextPlan = back))
        assertEquals("$back\n$brass", PlanCarryRules.carried(CarryChoice.ADD, brass, back))
        assertEquals(brass, PlanCarryRules.carried(CarryChoice.REPLACE, brass, back))
        assertEquals(back, PlanCarryRules.carried(CarryChoice.KEEP, brass, back))
        assertEquals(brass, PlanCarryRules.carried(CarryChoice.ADD, brass, null))
    }

    @Test
    fun cancelWithoutNextWaits() {
        // Случай 3: следующей нет — в pending_plan; в новую тренировку — тексты клиентов без повторов.
        assertEquals(CarryStep.PENDING, PlanCarryRules.onCancel(brass, hasNext = false, nextPlan = null))
        assertEquals(brass, PlanCarryRules.pending(listOf(brass, brass, null)))
        assertEquals("$brass\n$back", PlanCarryRules.pending(listOf(brass, "", back)))
        assertNull(PlanCarryRules.pending(listOf(null, " ")))
        assertEquals("$back\n$brass", PlanCarryRules.join(back, brass))
    }

    @Test
    fun returnRemovesUnchangedAndAsksIfChanged() {
        // Случай 4: отмену вернули. Текст не менялся — убирается молча.
        assertEquals(ReturnStep.Remove(null), PlanCarryRules.onReturn(brass, brass))
        assertEquals(ReturnStep.Remove(back), PlanCarryRules.onReturn(brass, "$back\n$brass"))
        // Правили или дописали после — вопрос; «Убрать перенесённое» оставляет остальное.
        assertEquals(ReturnStep.Ask("Старт с тумбы"), PlanCarryRules.onReturn(brass, "$brass\nСтарт с тумбы"))
        assertEquals(ReturnStep.Ask("$back\nСтарт с тумбы"), PlanCarryRules.onReturn(brass, "$back\n$brass\nСтарт с тумбы"))
        // Перенесённое уже стёрли или переписали — возвращать нечего.
        assertEquals(ReturnStep.Nothing, PlanCarryRules.onReturn(brass, "Брасс 8 × 25"))
        assertEquals(ReturnStep.Nothing, PlanCarryRules.onReturn(brass, null))
    }

    @Test
    fun movedToNearestTraining() {
        // Уточнение 25.10.2026: появилась тренировка раньше — перенесённый план уходит на неё.
        assertEquals(null to brass, PlanCarryRules.move(brass, from = brass, to = null))
        // У новой ближайшей свой план — перенесённый дописывается с новой строки, у прежней остаётся её план.
        assertEquals(back to "$back\n$brass", PlanCarryRules.move(brass, from = "$back\n$brass", to = back))
        // У новой ближайшей текст уже есть — её план не меняется.
        assertEquals(null to brass, PlanCarryRules.move(brass, from = brass, to = brass))
        // Перенесённый текст правили или убрали — план остаётся, где был.
        assertNull(PlanCarryRules.move(brass, from = "$brass Ещё 4 × 25 м.", to = null))
        assertNull(PlanCarryRules.move(brass, from = back, to = null))
    }
}
