package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Тренировка клиента для «Не будет на период» (4.2а): [clientCancelled] — этот клиент в ней уже отменён.
 */
data class PeriodSlot(
    val id: String,
    val start: LocalDateTime,
    val kind: TrainingKind,
    val status: TrainingStatus,
    val clientCancelled: Boolean,
)

/** Что пишется под ошибкой периода. */
enum class PeriodProblem { TO_BEFORE_FROM, FROM_IN_PAST, TOO_LONG }

/**
 * Отмена тренировок клиента на период (ТЗ 4.2а, шаг 5.3у): «нас не будет с 01.06 по 31.08». Каждая тренировка
 * отменяется для этого клиента, как отмена из её карточки, с одной причиной на всех: ПТ и СПТ — целиком, в сплите
 * и группе — только этот клиент, тренировка остаётся. Списание — по правилу 4.2 для каждой (предупредили сейчас),
 * у групп его нет.
 */
object PeriodCancelRules {
    /** Самый длинный период — год: «до лета» больше не бывает, а опечатка в годе не отменит всё расписание. */
    const val MAX_DAYS: Int = 366

    /** «По» при открытии: через две недели от «С». */
    fun defaultTo(from: LocalDate): LocalDate = from.plus(DatePeriod(days = 13))

    fun problem(from: LocalDate, to: LocalDate, today: LocalDate): PeriodProblem? = when {
        to < from -> PeriodProblem.TO_BEFORE_FROM
        from < today -> PeriodProblem.FROM_IN_PAST
        from.daysUntil(to) >= MAX_DAYS -> PeriodProblem.TOO_LONG
        else -> null
    }

    /**
     * Какие тренировки отменятся, по началу: день в периоде включительно, ещё не началась, запланирована и клиент
     * в ней не отменён. Начавшиеся и прошедшие не трогаются — их отменяют в карточке, по одной.
     */
    fun picks(slots: List<PeriodSlot>, from: LocalDate, to: LocalDate, now: LocalDateTime): List<PeriodSlot> =
        slots.filter {
            it.start.date in from..to &&
                it.start > now &&
                it.status == TrainingStatus.PLANNED &&
                !it.clientCancelled &&
                it.kind != TrainingKind.DUTY
        }.sortedBy { it.start }
}
