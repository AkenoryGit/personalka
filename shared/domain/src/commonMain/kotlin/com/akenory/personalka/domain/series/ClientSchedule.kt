package com.akenory.personalka.domain.series

import com.akenory.personalka.domain.format.DateFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Серия клиента во вкладке «Расписание» карточки (5.9): дни, время, тип · место, период.
 * Групповые серии сюда не входят — их меняет группа (5.10).
 */
data class ScheduleSeries(
    val id: String,
    val weekdays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val durationMin: Int,
    val typeShort: String,
    val placeShort: String,
    val validFrom: LocalDate,
    /** Пусто — бессрочно. */
    val validTo: LocalDate? = null,
) {
    /** Завершена: последний день прошёл. Такую не меняют — «Изменить с даты…» и «Завершить» у неё нет. */
    fun endedOn(today: LocalDate): Boolean = validTo != null && validTo < today
}

/** Правила вкладки «Расписание» (5.9) и окон «Изменить с даты…», «Завершить» (4.4). */
object ClientScheduleRules {
    /**
     * Порядок вкладки: сначала действующие и будущие — по первому дню недели и времени, ниже завершённые —
     * от недавних к давним. Серия, которую завершили раньше её начала, пуста — её нет.
     */
    fun ordered(list: List<ScheduleSeries>, today: LocalDate): List<ScheduleSeries> {
        val shown = list.filter { it.validTo == null || it.validTo >= it.validFrom }
        val (ended, current) = shown.partition { it.endedOn(today) }
        return current.sortedWith(compareBy({ s -> DateFormat.WEEK.indexOfFirst { it in s.weekdays } }, { it.startTime }, { it.validFrom })) +
            ended.sortedByDescending { it.validTo }
    }

    /** С какой даты «Изменить с даты…» по умолчанию: сегодня, а у серии, что ещё не началась, — её первый день. */
    fun defaultFrom(series: ScheduleSeries, today: LocalDate): LocalDate = maxOf(today, series.validFrom)

    /**
     * Ошибка даты «Изменить с даты…»: прошлое серия не меняет (4.4), а после конца серии менять нечего.
     * `null` — дата подходит.
     */
    fun fromProblem(from: LocalDate, today: LocalDate, validTo: LocalDate?): String? = when {
        from < today -> "Не раньше сегодняшнего дня"
        validTo != null && from > validTo -> "Серия заканчивается ${DateFormat.full(validTo)}"
        else -> null
    }

    /** Последний день по умолчанию в «Завершить»: сегодня — сегодняшняя тренировка остаётся. */
    fun defaultLastDay(today: LocalDate): LocalDate = today

    /** Ошибка последнего дня в «Завершить»: самое раннее вчера (4.4). `null` — дата подходит. */
    fun lastDayProblem(lastDay: LocalDate, today: LocalDate): String? =
        if (lastDay < today.plusDays(-1)) "Не раньше вчерашнего дня" else null
}
