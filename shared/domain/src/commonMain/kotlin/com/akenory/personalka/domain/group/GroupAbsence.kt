package com.akenory.personalka.domain.group

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// ТЗ 5.10, шаг 3.9у: ребёнок в составе, который давно не ходит в группу.

/**
 * Место ребёнка в составе для вопроса «Не ходит 30 дней»: [fromDate] — с какого дня в группе, [lastAttended] — последняя
 * тренировка группы, на которую он пришёл, [keptOn] — когда тренер последний раз нажал «Оставить», [keeps] — сколько раз.
 */
data class AbsenceInput(
    val fromDate: LocalDate,
    val lastAttended: LocalDate?,
    val keptOn: LocalDate?,
    val keeps: Int,
)

/** Вопрос о ребёнке, который не ходит в группу: [since] — с какого дня его нет (последний раз пришёл или пришёл в группу). */
data class Absence(val since: LocalDate, val attendedEver: Boolean)

object GroupAbsence {
    /** Через сколько дней без группы приложение спрашивает. */
    const val DAYS = 30

    /** После стольких «Оставить» больше не спрашивает. */
    const val KEEPS = 2

    /**
     * Спросить ли о ребёнке на [today]: 30 дней и больше он не был на группе (считая от последнего прихода, от дня, когда
     * пришёл в группу, и от последнего «Оставить»), и за это время группа хоть раз прошла — на каникулах никто
     * не «пропадает». [held] — даты проведённых тренировок группы. После второго «Оставить» — не спрашивает.
     */
    fun ask(input: AbsenceInput, held: List<LocalDate>, today: LocalDate): Absence? {
        if (input.keeps >= KEEPS) return null
        val from = listOfNotNull(input.fromDate, input.lastAttended, input.keptOn).max()
        if (from.plus(DAYS, DateTimeUnit.DAY) > today) return null
        if (held.none { it > from && it <= today }) return null
        return Absence(since = input.lastAttended ?: input.fromDate, attendedEver = input.lastAttended != null)
    }
}
