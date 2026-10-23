package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime

/**
 * Какие поля дневника у тренировки (3.3, 5.7): [plan] — «План был» и «План на следующую», [fact] — «Факт»,
 * [behavior] — «Поведение» у каждого клиента.
 */
data class DiaryFields(val plan: Boolean, val fact: Boolean, val behavior: Boolean) {
    val any: Boolean get() = plan || fact || behavior

    companion object {
        val NONE = DiaryFields(plan = false, fact = false, behavior = false)
    }
}

/** Тренировка в очереди «Следующая без дневника» (5.7). */
data class DiarySlot(val id: String, val start: LocalDateTime)

/** Дневник тренировки (ТЗ 5.7). */
object DiaryRules {
    /**
     * Нет дневника — оранжевая точка в календаре (5.3) и пункт «Нужно сделать» (5.2): тренировка проведена,
     * дневник включён в настройках (3.2), у типа ведётся факт (3.3), а факт пуст. План пишут до занятия,
     * поведение — по желанию, поэтому дневник считается заполненным по факту. У дежурства факта нет.
     */
    fun missing(status: TrainingStatus, diaryEnabled: Boolean, keepsFact: Boolean, fact: String?): Boolean =
        status == TrainingStatus.DONE && diaryEnabled && keepsFact && fact.isNullOrBlank()

    /**
     * Поля по настройкам типа (3.3): у группы поведения нет — факт видят родители всех детей (5.7); у дежурства
     * дневника нет совсем.
     */
    fun fields(kind: TrainingKind, diaryPlan: Boolean, diaryFact: Boolean, diaryBehavior: Boolean): DiaryFields = when (kind) {
        TrainingKind.DUTY -> DiaryFields.NONE
        else -> DiaryFields(plan = diaryPlan, fact = diaryFact, behavior = diaryBehavior && kind != TrainingKind.GROUP)
    }

    /**
     * Кнопка «Дневник» в карточке (5.4) и в «Ближайшей» (5.2): дневник включён (3.2), у типа есть поля, тренировка
     * проведена или идёт с начала минус 15 минут — как «Проведено». До этого план пишут в форме тренировки (5.5),
     * у отменённой дневника нет.
     */
    fun available(training: TrainingState, now: LocalDateTime, keepsDiary: Boolean): Boolean =
        keepsDiary && when (training.status) {
            TrainingStatus.DONE -> true
            TrainingStatus.PLANNED -> now >= StatusRules.markableFrom(training)
            TrainingStatus.CANCELLED -> false
        }

    /** «ещё 2 без дневника»: сколько без дневника, кроме открытой. */
    fun left(currentId: String, missing: List<DiarySlot>): Int = missing.count { it.id != currentId }

    /**
     * «Следующая без дневника» (5.7): из [missing] — ближайшая по началу после открытой [current]; после последней —
     * самая ранняя, чтобы записи за день заполнялись подряд и ни одна не терялась. `null` — других без дневника нет.
     */
    fun next(current: DiarySlot, missing: List<DiarySlot>): DiarySlot? {
        val others = missing.filter { it.id != current.id }.sortedWith(compareBy({ it.start }, { it.id }))
        return others.firstOrNull { it.start > current.start || it.start == current.start && it.id > current.id } ?: others.firstOrNull()
    }
}

/**
 * Тренировка для поиска «следующей» (4.3): [clientIds] — кто в ней записан, [seriesId] и [groupId] — чья она.
 */
data class PlanSlot(
    val id: String,
    val start: LocalDateTime,
    val status: TrainingStatus,
    val kind: TrainingKind,
    val seriesId: String? = null,
    val groupId: String? = null,
    val clientIds: Set<String> = emptySet(),
)

/** Куда пишется «План на следующую» (4.3, 5.7). Перенос плана при отмене — шаг 2.2, по тем же правилам. */
object PlanRules {
    /**
     * Следующая тренировка (4.3) — самая ранняя `PLANNED` после [of]: у группы — той же группы, у сплита — той же
     * серии, у ПТ, СПТ и остальных — того же клиента, без групповых и дежурств. Сплит без серии ищет тренировку,
     * где есть все его клиенты. `null` — следующей нет: план ждёт в `pending_plan` клиента или группы.
     */
    fun next(of: PlanSlot, candidates: List<PlanSlot>): PlanSlot? {
        val groupId = of.groupId
        val seriesId = of.seriesId
        val same: (PlanSlot) -> Boolean = when {
            groupId != null -> { c -> c.groupId == groupId }
            of.kind == TrainingKind.SPLIT && seriesId != null -> { c -> c.seriesId == seriesId }
            of.clientIds.isEmpty() -> { _ -> false }
            of.kind == TrainingKind.SPLIT -> { c -> c.ownClients() && c.clientIds.containsAll(of.clientIds) }
            else -> { c -> c.ownClients() && c.clientIds.any { it in of.clientIds } }
        }
        return candidates
            .filter { it.id != of.id && it.status == TrainingStatus.PLANNED && it.start > of.start && same(it) }
            .minWithOrNull(compareBy({ it.start }, { it.id }))
    }

    private fun PlanSlot.ownClients(): Boolean = groupId == null && kind != TrainingKind.GROUP && kind != TrainingKind.DUTY
}
