package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.TrainingState
import com.akenory.personalka.domain.training.came
import kotlinx.datetime.LocalDateTime

/**
 * «Поделиться» пришедшими на группу (ТЗ 5.4, шаг 5.3п): текст коллеге, с которым ведёте группу. Только пришедшие —
 * фамилия и имя столбиком с номерами, по фамилии; дети «на раз» — в том же списке, без пометки. Кто не пришёл, отменил
 * или не отмечен, процентов и «из 12» нет; ни телефонов, ни дневника (10.3).
 */
object GroupShareFormat {
    const val SHARE = "Поделиться пришедшими"

    /** Значок есть у группы и мини-группы, когда отмечен хоть один ребёнок; у отменённой — нет. */
    fun canShare(state: TrainingState): Boolean =
        state.kind == TrainingKind.GROUP && state.status != TrainingStatus.CANCELLED && state.participants.any { it.came }

    /**
     * «Сб 31.10 · 09:00 · Сб 9:00 ББ», «Пришли: 5» и «1. Белова Ева» … — по фамилии. [groupName] — название группы,
     * у разового списка детей — название типа.
     */
    fun text(start: LocalDateTime, groupName: String, came: List<ClientName>): String = buildString {
        val day = DateFormat.short(start.date).replace(RuText.NBSP, ' ').replaceFirstChar { it.uppercaseChar() }
        appendLine("$day · ${DateFormat.time(start.time)} · $groupName")
        append("Пришли: ${RuText.digits(came.size.toLong())}")
        came.sortedBy { ClientRules.sortKey(it) }.forEachIndexed { i, n ->
            appendLine()
            append("${i + 1}. ${n.lastName} ${n.firstName}")
        }
    }
}
