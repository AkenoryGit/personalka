package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.LocalDateTime

/** Что не так со временем тренировки: форма показывает это рядом с полем и не сохраняет. */
enum class TimeProblem { TOO_SHORT, TOO_LONG, CROSSES_MIDNIGHT }

/** Время тренировки (ТЗ 3.5, 4.14): длительность от 5 до 600 минут, через полночь не переходит. */
object TrainingTime {
    const val MIN_DURATION = 5
    const val MAX_DURATION = 600

    /** Конец тренировки по часам клуба. */
    fun end(start: LocalDateTime, durationMin: Int): LocalDateTime = ClubTime.plusMinutes(start, durationMin)

    /**
     * Проблема со временем или `null`, если всё в порядке. Конец ровно в 24:00 допустим:
     * тренировка 23:00–24:00 заканчивается в полночь, а не переходит через неё.
     */
    fun problem(start: LocalDateTime, durationMin: Int): TimeProblem? = when {
        durationMin < MIN_DURATION -> TimeProblem.TOO_SHORT
        durationMin > MAX_DURATION -> TimeProblem.TOO_LONG
        ClubTime.minuteOfDay(start) + durationMin > ClubTime.MINUTES_PER_DAY -> TimeProblem.CROSSES_MIDNIGHT
        else -> null
    }
}
