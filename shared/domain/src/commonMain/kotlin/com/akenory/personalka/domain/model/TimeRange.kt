package com.akenory.personalka.domain.model

import kotlinx.datetime.LocalTime

/** Промежуток времени начала «с–до» внутри дня, например утро 07:00–12:00 в листе ожидания (4.16). */
data class TimeRange(val start: LocalTime, val end: LocalTime) {
    init {
        require(start < end) { "Начало $start должно быть раньше конца $end" }
    }

    /** Время [time] попадает в промежуток: с началом включительно, до конца не включая. */
    operator fun contains(time: LocalTime): Boolean = time >= start && time < end

    override fun toString(): String = "$start-$end"

    companion object {
        /** Разбирает запись вида `07:00-12:00`. */
        fun parse(text: String): TimeRange {
            val parts = text.split('-')
            require(parts.size == 2) { "Ожидается «чч:мм-чч:мм»: $text" }
            return TimeRange(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]))
        }
    }
}
