package com.akenory.personalka.domain.client

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Что знает приложение о клиенте для архива сам собой (шаг 5.24, просьба Дмитрия 20.11.2026).
 *
 * @param touchedOn день, когда карточку завели или последний раз меняли — в том числе вернули из архива.
 * @param lastVisit последний день, когда клиент пришёл (отметка «пришёл» в проведённой тренировке, и в группе).
 * @param planned впереди есть его тренировка без группы или идёт его серия: он записан, просто давно не был.
 * @param colleagueGroup он в группе коллеги — отметки там ставит коллега, по ним судить нельзя.
 */
data class ArchiveInput(
    val clientId: String,
    val touchedOn: LocalDate,
    val lastVisit: LocalDate?,
    val planned: Boolean,
    val colleagueGroup: Boolean,
)

/** Архив сам собой: клиент не был ни на одном занятии 3 месяца и никуда не записан. */
object AutoArchiveRules {
    /** Три месяца — 90 дней. */
    const val DAYS = 90

    /** Кого убрать в архив в [today]: последняя отметка или правка карточки — 90 дней назад и раньше. */
    fun stale(inputs: List<ArchiveInput>, today: LocalDate): List<String> {
        val edge = today.minus(DAYS, DateTimeUnit.DAY)
        return inputs.filter { c ->
            !c.planned && !c.colleagueGroup && maxOf(c.touchedOn, c.lastVisit ?: c.touchedOn) <= edge
        }.map { it.clientId }
    }
}
