package com.akenory.personalka.domain.group

import com.akenory.personalka.domain.model.Owner
import kotlinx.datetime.LocalDate

/**
 * Ребёнок в составе группы с датами (ТЗ 3.5, `GroupMember`). Состав на дату подставляется
 * в новую тренировку группы как ожидаемые участники.
 */
data class GroupMember(
    val clientId: String,
    val fromDate: LocalDate,
    /** Последний день в группе; пусто — занимается. */
    val toDate: LocalDate? = null,
    /** Чей ребёнок в группе «Каждый своих» (4.8): мой или коллеги. */
    val coach: Owner = Owner.SELF,
) {
    fun activeOn(date: LocalDate): Boolean = date >= fromDate && (toDate == null || date <= toDate)
}

/** Кто в группе на эту дату — в порядке списка, без повторов. */
fun List<GroupMember>.membersOn(date: LocalDate): List<String> =
    filter { it.activeOn(date) }.map { it.clientId }.distinct()

/**
 * Чей ребёнок [clientId] на тренировке группы в день [date] (4.8): пометка его места в составе на эту дату. Не в
 * составе (ребёнок «на раз») — мой: его отмечает тот, кто его привёл.
 */
fun List<GroupMember>.coachOn(clientId: String, date: LocalDate): Owner =
    firstOrNull { it.clientId == clientId && it.activeOn(date) }?.coach ?: Owner.SELF
