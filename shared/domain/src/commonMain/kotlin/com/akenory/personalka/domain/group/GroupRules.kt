package com.akenory.personalka.domain.group

import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.money.Sharing
import kotlinx.datetime.LocalDate

// ТЗ 3.5, 4.4, 5.10. Постоянная группа для форм и правил — без служебных полей базы.

/**
 * Группа в форме (5.10): название («Сб 9:00 ББ»), тип вида «группа» и место. [sharing] — «На ком числится» и «Кто
 * обычно ведёт», с совместным ведением (3.2, выключено по умолчанию, шаг 3.7г): пока оно выключено, группа числится
 * на тренере и он ведёт сам.
 */
data class GroupDraft(
    val name: String = "",
    val typeId: String? = null,
    val locationId: String? = null,
    val sharing: Sharing = Sharing.SELF,
)

enum class GroupField { NAME, TYPE, PLACE }

/** Правила групп: проверка форм, правка состава, гости «на раз», посещаемость. */
object GroupRules {
    /** Ошибки формы группы словами тренера. [otherNames] — названия других групп: не повторяются. */
    fun problems(draft: GroupDraft, otherNames: List<String>): Map<GroupField, String> = buildMap {
        val name = draft.name.trim()
        when {
            name.isEmpty() -> put(GroupField.NAME, "Введите название")
            otherNames.any { ClientRules.key(it) == ClientRules.key(name) } -> put(GroupField.NAME, "Такая группа уже есть")
        }
        if (draft.typeId == null) put(GroupField.TYPE, "Выберите тип")
        if (draft.locationId == null) put(GroupField.PLACE, "Выберите место")
    }

    /**
     * Дети «на раз» на тренировке группы: участники, которых нет в составе на её дату. Правка состава их не трогает
     * (4.4), в посещаемости они видны отдельно.
     */
    fun guests(participants: List<String>, members: List<GroupMember>, date: LocalDate): List<String> {
        // Шаг 5.3н: кто в составе сейчас и пришёл позже этой даты — не «на раз»: прошедшие неотмеченные тренировки
        // получают текущий состав без учёта «в группе с» (4.4).
        val inGroup = members.membersOn(date).toSet() + members.filter { it.fromDate > date && it.toDate == null }.map { it.clientId }
        return participants.filter { it !in inGroup }.distinct()
    }

    /**
     * Кого добавить ожидаемым в прошедшую неотмеченную тренировку группы (4.4, шаг 5.3н): текущий состав на [today]
     * без учёта «в группе с». Тренировка — запланированная, уже начавшаяся ([started]), без единого «пришёл»;
     * кто в ней уже есть (и отменённые), не трогается. Будущие тренировки получают состав на свою дату, как раньше.
     */
    fun pastFill(started: Boolean, planned: Boolean, participants: List<Pair<String, Boolean>>, members: List<GroupMember>, today: LocalDate): List<String> {
        if (!started || !planned || participants.any { it.second }) return emptyList()
        val inside = participants.map { it.first }.toSet()
        return members.membersOn(today).filter { it !in inside }
    }

    /**
     * Начало истории ребёнка в группах (4.4, шаг 5.3н): по каждой группе — дата первой тренировки, где он «пришёл».
     * [visits] — его тренировки групп: id группы, день и «пришёл» ли.
     */
    fun historyStarts(visits: List<Triple<String, LocalDate, Boolean>>): Map<String, LocalDate> =
        visits.filter { it.third }.groupBy { it.first }.mapValues { (_, list) -> list.minOf { it.second } }

    /**
     * Скрыта ли тренировка группы из истории ребёнка (шаг 5.3н): уже начавшаяся ([started]), раньше его первого
     * «пришёл» в этой группе, а сам он «не пришёл», отменил или ещё не отмечен. Ни разу не приходил — скрыты все
     * прошедшие. Списание видно всегда: это деньги. Будущие и не групповые ([groupId] `null`) видны.
     */
    fun hiddenFromHistory(groupId: String?, date: LocalDate, started: Boolean, status: ParticipationStatus, starts: Map<String, LocalDate>): Boolean {
        if (groupId == null || !started) return false
        if (status == ParticipationStatus.ATTENDED || status == ParticipationStatus.CANCELLED_CHARGED) return false
        val from = starts[groupId] ?: return true
        return date < from
    }

    /** Средняя посещаемость (5.10): сколько детей пришло на проведённую тренировку, в среднем, в десятых: 83 — «8,3». */
    fun averageTenths(attended: List<Int>): Int =
        if (attended.isEmpty()) 0 else kotlin.math.round(attended.sum() * 10.0 / attended.size).toInt()

    /**
     * Ребёнок уходит из всех групп после [lastDay] (архив клиента, 4.4: «из группы клиент уходит через состав»).
     * Для каждой записи состава — что с ней сделать: закончить [lastDay] или убрать совсем, если ребёнок
     * пришёл в группу позже. Уже закончившиеся записи не меняются.
     */
    fun leave(members: List<GroupMember>, lastDay: LocalDate): List<MemberChange> = members.mapNotNull { m ->
        when {
            m.toDate != null && m.toDate <= lastDay -> null
            m.fromDate > lastDay -> MemberChange.Remove(m)
            else -> MemberChange.End(m, lastDay)
        }
    }
}

/** Что сделать с записью состава. */
sealed interface MemberChange {
    val member: GroupMember

    /** Последний день в группе — [lastDay]. */
    data class End(override val member: GroupMember, val lastDay: LocalDate) : MemberChange

    /** Записи не нужно: ребёнок в группу так и не пришёл. */
    data class Remove(override val member: GroupMember) : MemberChange
}
