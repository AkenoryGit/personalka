package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.series.plusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** «Отпуск с … по …» (4.4): оба дня включительно. */
data class Vacation(val from: LocalDate, val to: LocalDate)

/**
 * Раздел «Календарь» в настройках (ТЗ 5.16, 3.2; шаг 1.13г): часы работы, шаг перетаскивания, горизонт серий,
 * «Отпуск» и какие запреты показывать в списках.
 */
object CalendarSettingsRules {
    /** Шаг перетаскивания (3.2): 5, 10, 15 или 30 минут. */
    val DRAG_STEPS: List<Int> = listOf(5, 10, 15, 30)

    /** На сколько недель вперёд серии ставят тренировки; 8 — по умолчанию (3.2). */
    val HORIZONS: List<Int> = listOf(4, 8, 12, 26)

    /**
     * Ошибка часов работы: конец позже начала. Конец 00:00 — это 24:00, как у запрета (4.5): 07:00–00:00 — до полуночи.
     * `null` — часы подходят.
     */
    fun workHoursProblem(start: LocalTime, end: LocalTime): String? =
        if (endMinute(end) <= minute(start)) "Конец позже начала" else null

    /** Отпуск по умолчанию — неделя с сегодняшнего дня. */
    fun defaultVacation(today: LocalDate): Vacation = Vacation(today, today.plusDays(6))

    /** Ошибка первого дня отпуска: прошлое серия не меняет (4.4). `null` — дата подходит. */
    fun vacationFromProblem(vacation: Vacation, today: LocalDate): String? =
        if (vacation.from < today) "Не раньше сегодняшнего дня" else null

    /** Ошибка последнего дня отпуска. `null` — дата подходит. */
    fun vacationToProblem(vacation: Vacation): String? =
        if (vacation.to < vacation.from) "Не раньше первого дня" else null

    /**
     * Запреты в списке настроек: только те, что ещё действуют, — разовый с сегодняшнего дня, повторяющийся
     * без конца или с концом не раньше сегодня. Сначала повторяющиеся по первому дню недели и времени,
     * ниже разовые по дате.
     */
    fun current(list: List<Restriction>, today: LocalDate): List<Restriction> {
        val (oneOff, weekly) = list
            .filter { r -> if (r.isOneOff) r.date != null && r.date >= today else r.validTo == null || r.validTo >= today }
            .partition { it.isOneOff }
        return weekly.sortedWith(compareBy({ r -> DateFormat.WEEK.indexOfFirst { it in r.weekdays } }, { it.startTime }, { it.validFrom })) +
            oneOff.sortedWith(compareBy({ it.date }, { it.startTime }))
    }

    private fun minute(time: LocalTime): Int = time.hour * 60 + time.minute

    private fun endMinute(time: LocalTime): Int = minute(time).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
}
