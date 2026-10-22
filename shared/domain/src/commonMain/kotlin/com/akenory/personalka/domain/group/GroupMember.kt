package com.akenory.personalka.domain.group

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
) {
    fun activeOn(date: LocalDate): Boolean = date >= fromDate && (toDate == null || date <= toDate)
}

/** Кто в группе на эту дату — в порядке списка, без повторов. */
fun List<GroupMember>.membersOn(date: LocalDate): List<String> =
    filter { it.activeOn(date) }.map { it.clientId }.distinct()
