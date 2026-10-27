package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CancelReasons
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDateTime

/**
 * Тренировка клиента для надёжности (шаг 3.14): начало, вид и имена типа, статусы тренировки и участия, причины отмены
 * участия и всей тренировки.
 */
data class ReliabilityVisit(
    val start: LocalDateTime,
    val kind: TrainingKind,
    val typeName: String,
    val typeShort: String,
    val training: TrainingStatus,
    val participation: ParticipationStatus,
    val participationReason: String? = null,
    val trainingReason: String? = null,
)

/** Цвет надёжности: от 90 % — зелёный, 75–89 % — жёлтый, ниже 75 % — красный. */
enum class ReliabilityLevel { HIGH, MIDDLE, LOW }

/**
 * Надёжность клиента (шаг 3.14): из последних [done] + [cancelled] тренировок он пришёл на [done]. Меньше
 * [ReliabilityRules.MIN_TRAININGS] — «новый», без процента и цвета.
 */
data class Reliability(val done: Int, val cancelled: Int) {
    val total: Int get() = done + cancelled

    val isNew: Boolean get() = total < ReliabilityRules.MIN_TRAININGS

    /** Процент вниз до целого: 89,6 % — 89, жёлтый, а не зелёный. 42 из 50 — 84. */
    val percent: Int? get() = if (isNew) null else done * 100 / total

    val level: ReliabilityLevel?
        get() = percent?.let {
            when {
                it >= ReliabilityRules.HIGH_FROM -> ReliabilityLevel.HIGH
                it >= ReliabilityRules.MIDDLE_FROM -> ReliabilityLevel.MIDDLE
                else -> ReliabilityLevel.LOW
            }
        }

    companion object {
        val NONE = Reliability(0, 0)
    }
}

/**
 * Надёжность клиента (шаг 3.14, решение Дмитрия 27.10.2026) — только для тренера: в кабинет и выгрузку не попадает.
 * Проведено ÷ (проведено + отменено) за последние [WINDOW] тренировок клиента по ПТ (и персональным со своим именем),
 * сплитам и мини-группам; большие группы, СПТ и прочее не считаются. Отмены «По инициативе тренера» и «По инициативе
 * клуба» — не вина клиента и не считаются вовсе; отмена со списанием и «не пришёл» на проведённую мини-группу —
 * отмены. Мини-группа, отменённая целиком, — не его отмена. Запланированные, ещё не отмеченные — не считаются.
 */
object ReliabilityRules {
    const val WINDOW = 50
    const val MIN_TRAININGS = 5
    const val HIGH_FROM = 90
    const val MIDDLE_FROM = 75

    /**
     * Мини-группа — тип вида «Группа», чьё имя или короткое имя начинается с «Мини» (типы первого запуска: «Мини-группа»,
     * «Мини»). Отдельного признака у типа нет (3.3): мини-группа и группа различаются только именем.
     */
    fun isMiniGroup(typeName: String, typeShort: String): Boolean =
        listOf(typeName, typeShort).any { it.trim().lowercase().startsWith("мини") }

    /** Считается ли тренировка этого типа в надёжности. */
    fun counts(kind: TrainingKind, typeName: String, typeShort: String): Boolean = when (kind) {
        TrainingKind.PERSONAL, TrainingKind.SPLIT -> true
        TrainingKind.GROUP -> isMiniGroup(typeName, typeShort)
        TrainingKind.SPT, TrainingKind.DUTY, TrainingKind.OTHER -> false
    }

    /** Пришёл (`true`), отменил (`false`) или не считается (`null`). */
    fun came(visit: ReliabilityVisit): Boolean? {
        if (!counts(visit.kind, visit.typeName, visit.typeShort)) return null
        return when (ClientOutcome.of(visit.training, visit.participation)) {
            ClientOutcome.PLANNED -> null
            ClientOutcome.DONE -> true
            ClientOutcome.CANCELLED, ClientOutcome.CANCELLED_CHARGED -> {
                val ownCancel = visit.participation != ParticipationStatus.EXPECTED
                val reason = (if (ownCancel) visit.participationReason else null) ?: visit.trainingReason
                when {
                    CancelReasons.isWithoutCharge(reason) -> null
                    // Мини-группу отменили целиком — клиент тут ни при чём.
                    !ownCancel && visit.kind == TrainingKind.GROUP -> null
                    else -> false
                }
            }
        }
    }

    /** Надёжность по всем тренировкам клиента: считаются последние [WINDOW] по началу. */
    fun of(visits: List<ReliabilityVisit>): Reliability {
        val last = visits.sortedByDescending { it.start }.mapNotNull { came(it) }.take(WINDOW)
        val done = last.count { it }
        return Reliability(done, last.size - done)
    }

    /** Спросить, придёт ли: надёжность красная. */
    fun ask(reliability: Reliability?): Boolean = reliability?.level == ReliabilityLevel.LOW
}
