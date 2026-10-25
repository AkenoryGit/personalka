package com.akenory.personalka.domain.training

/** Что делать с планом отменённой тренировки (4.3). */
enum class CarryStep {
    /** План пуст — переносить нечего. */
    NONE,

    /** У следующей план пуст — план переносится молча, сообщение «План перенесён на вт 22.10». */
    MOVE,

    /** У следующей уже есть план — вопрос «Добавить / Заменить / Оставить как есть». */
    ASK,

    /** Следующей нет — план ждёт в `pending_plan` клиента или группы. */
    PENDING,
}

/** Ответ на вопрос «У следующей тренировки уже есть план» (4.3): «Добавить» — главная, ничего не теряется. */
enum class CarryChoice { ADD, REPLACE, KEEP }

/** Что делать с перенесённым планом, когда отмену вернули (4.3). */
sealed interface ReturnStep {
    /** Перенесённого текста там уже нет — возвращать нечего. */
    data object Nothing : ReturnStep

    /** Текст не менялся — убирается молча, остаётся [left] (`null` — план пуст). */
    data class Remove(val left: String?) : ReturnStep

    /** План после переноса правили — вопрос; «Убрать перенесённое» оставит [left]. */
    data class Ask(val left: String?) : ReturnStep
}

/**
 * Перенос плана отменённой тренировки на следующую (ТЗ 4.3). «Следующая» — [PlanRules.next]. Отменённая свой
 * план сохраняет: перенос — это копия в следующую, а откат убирает копию оттуда.
 *
 * Для отката у следующей хранятся `plan_carried_from_id` (откуда) и `plan_carried_text` (что перенесено).
 */
object PlanCarryRules {
    /** Шаг при отмене: [plan] — план отменённой; [hasNext] — следующая есть, [nextPlan] — её план. */
    fun onCancel(plan: String?, hasNext: Boolean, nextPlan: String?): CarryStep = when {
        plan.isNullOrBlank() -> CarryStep.NONE
        !hasNext -> CarryStep.PENDING
        nextPlan.isNullOrBlank() -> CarryStep.MOVE
        else -> CarryStep.ASK
    }

    /**
     * План следующей после ответа: «Добавить» — с новой строки после того, что было; «Заменить» — только
     * перенесённый; «Оставить как есть» — без изменений. Пустой — `null`.
     */
    fun carried(choice: CarryChoice, carried: String, existing: String?): String? = when (choice) {
        CarryChoice.ADD -> join(existing, carried)
        CarryChoice.REPLACE -> carried
        CarryChoice.KEEP -> existing
    }?.ifBlank { null }

    /**
     * Отмену вернули: [carried] — перенесённый текст, [current] — план следующей (или отложенный план) сейчас.
     * Текст стоит как был — весь план или его конец после новой строки («Добавить») — убирается молча. Его правили
     * или дописали после — вопрос. Его уже нет — ничего.
     */
    fun onReturn(carried: String, current: String?): ReturnStep {
        val text = current.orEmpty()
        return when {
            carried.isBlank() || carried !in text -> ReturnStep.Nothing
            text == carried -> ReturnStep.Remove(null)
            text.endsWith("\n$carried") -> ReturnStep.Remove(text.removeSuffix("\n$carried").ifBlank { null })
            else -> ReturnStep.Ask(remove(carried, text))
        }
    }

    /**
     * Перенесённый план идёт на ближайшую тренировку (уточнение 25.10.2026): [carried] уходит из плана [from] в план
     * [to]. Текст стоит у [from] как был — ответ: что остаётся у [from] (`null` — пусто) и новый план [to]; у [to]
     * текст уже есть — её план не меняется. Текст у [from] правили или его там нет — `null`: план остаётся, где был.
     */
    fun move(carried: String, from: String?, to: String?): Pair<String?, String>? {
        val left = (onReturn(carried, from) as? ReturnStep.Remove ?: return null).left
        val target = if (carried in to.orEmpty()) to.orEmpty() else join(to, carried)
        return left to target
    }

    /** Отложенный план для новой тренировки: тексты клиентов без повторов, с новой строки. Пусто — `null`. */
    fun pending(texts: List<String?>): String? =
        texts.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null }

    /** «Добавить с новой строки». */
    fun join(old: String?, add: String): String = if (old.isNullOrBlank()) add else "$old\n$add"

    /** Убрать первое вхождение [carried] вместе с переводом строки рядом. */
    private fun remove(carried: String, text: String): String? {
        val at = text.indexOf(carried)
        val before = text.substring(0, at).removeSuffix("\n")
        val after = text.substring(at + carried.length).removePrefix("\n")
        return listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n").ifBlank { null }
    }
}
