package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.format.DiaryFormat
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Дневник (шаг 2.1, ТЗ 5.7, 3.3, 4.3) — по макетам «Дневник ПТ», «Дневник сплита», «Дневник группы». Имена условные. */
class DiaryRulesTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m)

    @Test
    fun fieldsByType() {
        // ПТ и сплит — все три поля; у группы поведения нет, даже если оно отмечено в типе; у дежурства — ничего.
        assertEquals(DiaryFields(plan = true, fact = true, behavior = true), DiaryRules.fields(TrainingKind.PERSONAL, true, true, true))
        assertEquals(DiaryFields(plan = true, fact = true, behavior = false), DiaryRules.fields(TrainingKind.GROUP, true, true, true))
        assertEquals(DiaryFields.NONE, DiaryRules.fields(TrainingKind.DUTY, true, true, true))
        assertFalse(DiaryFields.NONE.any)
        // Тип без поведения (например, «Кинезио» с выключенным полем).
        assertEquals(DiaryFields(plan = true, fact = true, behavior = false), DiaryRules.fields(TrainingKind.SPLIT, true, true, false))
    }

    @Test
    fun diaryButton() {
        val pt = TrainingState(TrainingKind.PERSONAL, at(20, 13, 30), 50)
        // С начала минус 15 минут, как «Проведено»: «Проведено», «Дневник», «Отменить».
        assertFalse(DiaryRules.available(pt, at(20, 13, 14), keepsDiary = true))
        assertTrue(DiaryRules.available(pt, at(20, 13, 15), keepsDiary = true))
        assertEquals(
            listOf(CardAction.DONE, CardAction.DIARY, CardAction.CANCEL),
            TrainingCardRules.buttons(pt, at(20, 13, 15), skippable = false, keepsDiary = true).buttons,
        )
        // Проведённая: «Дневник», «Изменить», «Отменить» — не больше трёх (5.4).
        val done = pt.copy(status = TrainingStatus.DONE)
        assertEquals(
            listOf(CardAction.DIARY, CardAction.EDIT, CardAction.CANCEL),
            TrainingCardRules.buttons(done, at(20, 15), skippable = false, keepsDiary = true).buttons,
        )
        // Дневник выключен или у типа нет полей — кнопки нет; у отменённой — нет никогда.
        assertEquals(listOf(CardAction.EDIT, CardAction.CANCEL), TrainingCardRules.buttons(done, at(20, 15), false, keepsDiary = false).buttons)
        assertFalse(DiaryRules.available(pt.copy(status = TrainingStatus.CANCELLED), at(20, 15), keepsDiary = true))
    }

    @Test
    fun nextWithoutDiary() {
        // Вт 20.10: сплит 09:00, Орлова 13:30, группа 18:00 — без дневника; открыта Орлова.
        val split = DiarySlot("split", at(20, 9))
        val orlova = DiarySlot("orlova", at(20, 13, 30))
        val group = DiarySlot("group", at(20, 18))
        val missing = listOf(group, split, orlova)
        assertEquals(2, DiaryRules.left("orlova", missing))
        assertEquals(group, DiaryRules.next(orlova, missing))
        // После последней — самая ранняя: записи за день заполняются подряд.
        assertEquals(split, DiaryRules.next(group, missing))
        // Открытая уже заполнена и выпала из списка — следующая всё равно по её времени.
        assertEquals(group, DiaryRules.next(orlova, listOf(split, group)))
        assertNull(DiaryRules.next(orlova, listOf(orlova)))
        assertEquals(0, DiaryRules.left("orlova", listOf(orlova)))
    }

    @Test
    fun nextTraining() {
        // ПТ: следующая того же клиента, без групповых; отменённые и прошлые не считаются.
        val pt = PlanSlot("pt", at(20, 13, 30), TrainingStatus.DONE, TrainingKind.PERSONAL, seriesId = "s1", clientIds = setOf("orlova"))
        val cancelled = PlanSlot("c", at(21, 10), TrainingStatus.CANCELLED, TrainingKind.PERSONAL, clientIds = setOf("orlova"))
        val group = PlanSlot("g", at(21, 18), TrainingStatus.PLANNED, TrainingKind.GROUP, groupId = "dolphins", clientIds = setOf("orlova"))
        val thursday = PlanSlot("thu", at(22, 13, 30), TrainingStatus.PLANNED, TrainingKind.PERSONAL, seriesId = "s1", clientIds = setOf("orlova"))
        val other = PlanSlot("o", at(21, 9), TrainingStatus.PLANNED, TrainingKind.PERSONAL, clientIds = setOf("belov"))
        val earlier = PlanSlot("e", at(19, 9), TrainingStatus.PLANNED, TrainingKind.PERSONAL, clientIds = setOf("orlova"))
        assertEquals(thursday, PlanRules.next(pt, listOf(cancelled, group, thursday, other, earlier, pt)))

        // Сплит — той же серии, даже если в ней сменился ребёнок.
        val split = PlanSlot("sp", at(20, 9), TrainingStatus.DONE, TrainingKind.SPLIT, seriesId = "s2", clientIds = setOf("t", "a"))
        val sameSeries = PlanSlot("sp2", at(22, 9), TrainingStatus.PLANNED, TrainingKind.SPLIT, seriesId = "s2", clientIds = setOf("t"))
        val ptOfOne = PlanSlot("pt-t", at(21, 9), TrainingStatus.PLANNED, TrainingKind.PERSONAL, clientIds = setOf("t"))
        assertEquals(sameSeries, PlanRules.next(split, listOf(ptOfOne, sameSeries)))

        // Группа — той же группы.
        val tuesday = PlanSlot("g1", at(20, 18), TrainingStatus.DONE, TrainingKind.GROUP, groupId = "dolphins")
        val thursdayGroup = PlanSlot("g2", at(22, 18), TrainingStatus.PLANNED, TrainingKind.GROUP, groupId = "dolphins")
        assertEquals(thursdayGroup, PlanRules.next(tuesday, listOf(group.copy(groupId = "sharks"), thursdayGroup)))

        // Следующей нет — план ждёт в pending_plan.
        assertNull(PlanRules.next(pt, listOf(cancelled, other)))
    }

    @Test
    fun strings() {
        assertEquals("ещё 2 без дневника", DiaryFormat.left(2))
        assertNull(DiaryFormat.left(0))
        assertEquals(s("вт_20.10 · 13:30–14:20 · ПТ · ББ"), DiaryFormat.whenLine(at(20, 13, 30), at(20, 14, 20), "ПТ", "ББ"))
        assertEquals(s("План на следующую · чт_22.10, 13:30"), DiaryFormat.nextPlan(at(22, 13, 30)))
        assertEquals("План на следующую · в первую новую", DiaryFormat.nextPlan(null))
        assertEquals("Поведение · Ковалёв Т.", DiaryFormat.behaviorOf("Ковалёв Т."))

        val today = LocalDate(2026, 10, 20)
        assertEquals(
            "Ковалёв / Ковалёва 09:00 · Орлова К. 13:30",
            HomeFormat.noDiaryNote(listOf(at(20, 9) to "Ковалёв / Ковалёва", at(20, 13, 30) to "Орлова К."), today),
        )
        assertEquals(
            s("Морозов Д. пн_19.10 · Орлова К. 13:30 и ещё 1"),
            HomeFormat.noDiaryNote(listOf(at(19, 8) to "Морозов Д.", at(20, 13, 30) to "Орлова К.", at(20, 18) to "Группа"), today),
        )
    }
}
