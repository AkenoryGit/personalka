package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.NBSP

/**
 * Длительности и промежутки (ТЗ 10.6). Знак не учитывается: «через» или «назад» решает фраза вокруг.
 */
object DurationFormat {
    private const val MINUTES_PER_HOUR = 60

    /**
     * Коротко, как в строках и метках приложения: «40 мин», «1 ч», «1 ч 20 мин», «1 день 3 ч».
     * Так собираются «предупредили за 1 ч 20 мин» (4.2), «через 12 мин», «до конца 30 мин» (5.4).
     * От суток — дни и часы, минуты уже не важны.
     */
    fun short(minutes: Long): String {
        val total = if (minutes < 0) -minutes else minutes
        val days = total / ClubTime.MINUTES_PER_DAY
        val hours = total % ClubTime.MINUTES_PER_DAY / MINUTES_PER_HOUR
        val rest = total % MINUTES_PER_HOUR
        return when {
            days > 0 -> RuText.count(days, "день", "дня", "дней") + if (hours > 0) " $hours${NBSP}ч" else ""
            hours > 0 -> "$hours${NBSP}ч" + if (rest > 0) " $rest${NBSP}мин" else ""
            else -> "$rest${NBSP}мин"
        }
    }

    fun short(minutes: Int): String = short(minutes.toLong())

    /** Только минутами — длительность типа тренировки: «30 мин», «60 мин», «90 мин» (макеты 0.5). */
    fun minutes(minutes: Int): String = "${RuText.digits(minutes.toLong())}${NBSP}мин"

    /**
     * Полными словами — в правиле отмены в кабинете родителя (6.3): 60 → «1 час», 90 → «1 час 30 минут»,
     * 120 → «2 часа», 1440 → «24 часа». Только часы и минуты: правило отмены люди называют в часах.
     */
    fun long(minutes: Long): String {
        val total = if (minutes < 0) -minutes else minutes
        val hours = total / MINUTES_PER_HOUR
        val rest = total % MINUTES_PER_HOUR
        val h = RuText.count(hours, "час", "часа", "часов")
        val m = RuText.count(rest, "минута", "минуты", "минут")
        return when {
            hours == 0L -> m
            rest == 0L -> h
            else -> "$h $m"
        }
    }

    fun long(minutes: Int): String = long(minutes.toLong())
}
