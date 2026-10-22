package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.format.RuText.DASH
import kotlinx.datetime.LocalDate

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

    /** Место в составе: «с 01.09.2026», ушедший — «01.09–15.10.2026», через год — «01.09.2025–15.10.2026». */
    fun membership(from: LocalDate, to: LocalDate?): String = when {
        to == null -> "с ${DateFormat.full(from)}"
        to.year == from.year -> "${DateFormat.dayMonth(from)}$DASH${DateFormat.full(to)}"
        else -> "${DateFormat.full(from)}$DASH${DateFormat.full(to)}"
    }

    /** Посещаемость проведённой: «пришли 5 из 12». */
    fun attended(attended: Int, total: Int): String = "пришли $attended из $total"

    /** Дети «на раз»: «1 на раз». */
    fun guests(count: Int): String = "$count на раз"
}
