package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.model.Sex

/** Строки о группах: список, карточка, состав (5.10). */
object GroupFormat {
    private const val DOT = " · "

    /** «1 группа», «2 группы», «5 групп». */
    fun groups(count: Int): String = RuText.count(count, "группа", "группы", "групп")

    /** «1 ребёнок», «3 ребёнка», «12 детей». */
    fun children(count: Int): String = RuText.count(count, "ребёнок", "ребёнка", "детей")

    /**
     * Строка группы в списке: дни и время, место, сколько детей — «Сб 09:00 · ББ · 12 детей».
     * Без серии — «без расписания».
     */
    fun subtitle(schedule: List<ScheduleLine>, placeShort: String?, count: Int): String {
        val days = schedule.sortedWith(compareBy({ line -> DateFormat.WEEK.indexOfFirst { it in line.weekdays } }, { it.start }))
            .joinToString("; ") { ClientFormat.daysTime(it) }
            .ifEmpty { "без расписания" }
        return listOfNotNull(days, placeShort, children(count)).joinToString(DOT)
    }

    /** Нажатие на ребёнка в составе (шаг 3.9т): состав — просто список, без дат. */
    const val LEAVE = "Убрать из группы"
    const val LEAVE_NOTE = "Прошедшие тренировки его помнят, в следующих его больше не ждут."

    /** Сообщение с «Отменить»: «Белова Ева убрана из группы». */
    fun left(name: String, sex: Sex?): String = RuText.bySex(sex, "$name убран из группы", "$name убрана из группы", "$name — не в группе")

    /** Посещаемость проведённой: «пришли 5 из 12». */
    fun attended(attended: Int, total: Int): String = "пришли $attended из $total"

    /** Дети «на раз»: «1 на раз». */
    fun guests(count: Int): String = "$count на раз"
}
