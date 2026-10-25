package com.akenory.personalka.domain.group

import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.money.Sharing
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

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

/** Поля правки места в составе, под которыми показывается ошибка. */
enum class MemberField { FROM, NOT_FROM }

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
     * Последний день в группе по дню, с которого ребёнок «не ходит» (5.10: «исключить с даты»): не ходит
     * с 26.10 — последний день 25.10. Пусто — занимается.
     */
    fun lastDay(notFrom: LocalDate?): LocalDate? = notFrom?.minus(1, DateTimeUnit.DAY)

    /** Обратно: с какого дня не ходит, если последний день [toDate]. */
    fun notFrom(toDate: LocalDate?): LocalDate? = toDate?.plus(1, DateTimeUnit.DAY)

    /**
     * Ошибки правки места в составе: «В группе с» обязательно, «Не ходит с» — позже него. Набранное, но не
     * понятое приложением, — тоже ошибка. [fromText] и [notFromText] — то, что в полях; разбирает их вызывающий.
     */
    fun memberProblems(from: LocalDate?, fromText: String, notFrom: LocalDate?, notFromText: String): Map<MemberField, String> = buildMap {
        when {
            fromText.isBlank() -> put(MemberField.FROM, "Укажите дату")
            from == null -> put(MemberField.FROM, "Дата в виде 22.10.2026")
        }
        when {
            notFromText.isBlank() -> Unit
            notFrom == null -> put(MemberField.NOT_FROM, "Дата в виде 22.10.2026")
            from != null && notFrom <= from -> put(MemberField.NOT_FROM, "Позже, чем «В группе с»")
        }
    }

    /**
     * Дети «на раз» на тренировке группы: участники, которых нет в составе на её дату. Правка состава их не трогает
     * (4.4), в посещаемости они видны отдельно.
     */
    fun guests(participants: List<String>, members: List<GroupMember>, date: LocalDate): List<String> {
        val inGroup = members.membersOn(date).toSet()
        return participants.filter { it !in inGroup }.distinct()
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
