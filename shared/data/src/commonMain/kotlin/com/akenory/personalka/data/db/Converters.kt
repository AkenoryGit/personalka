package com.akenory.personalka.data.db

import androidx.room3.TypeConverter
import com.akenory.personalka.domain.model.TimeRange
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

/**
 * Как типы Kotlin лежат в SQLite. Даты и время — текстом ISO («2026-10-20», «17:00», «2026-10-20T17:00»):
 * такие строки сравниваются и сортируются как сами даты, их видно глазами в копии базы,
 * и они не зависят от пояса телефона — время тренировок локальное, в поясе клуба (ТЗ 3.1).
 * Перечисления Room сам хранит строками — именами значений.
 */
class Converters {

    @TypeConverter
    fun localDateToText(value: LocalDate): String = value.toString()

    @TypeConverter
    fun textToLocalDate(value: String): LocalDate = LocalDate.parse(value)

    @TypeConverter
    fun localTimeToText(value: LocalTime): String = value.toString()

    @TypeConverter
    fun textToLocalTime(value: String): LocalTime = LocalTime.parse(value)

    @TypeConverter
    fun localDateTimeToText(value: LocalDateTime): String = value.toString()

    @TypeConverter
    fun textToLocalDateTime(value: String): LocalDateTime = LocalDateTime.parse(value)

    /** Дни недели — номерами ISO через запятую по порядку: вт и чт → «2,4». Пусто — «». */
    @TypeConverter
    fun weekdaysToText(value: Set<DayOfWeek>): String =
        value.map { it.isoDayNumber }.sorted().joinToString(",")

    @TypeConverter
    fun textToWeekdays(value: String): Set<DayOfWeek> =
        splitList(value).map { DayOfWeek(it.toInt()) }.toSet()

    /** Список дат через запятую: «2026-10-24,2026-11-07». */
    @TypeConverter
    fun datesToText(value: List<LocalDate>): String = value.joinToString(",")

    @TypeConverter
    fun textToDates(value: String): List<LocalDate> = splitList(value).map { LocalDate.parse(it) }

    /** Список идентификаторов через запятую. */
    @TypeConverter
    fun idsToText(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun textToIds(value: String): List<String> = splitList(value)

    /** Промежутки времени через запятую: «07:00-12:00,18:00-21:00». */
    @TypeConverter
    fun timeRangesToText(value: List<TimeRange>): String = value.joinToString(",")

    @TypeConverter
    fun textToTimeRanges(value: String): List<TimeRange> = splitList(value).map { TimeRange.parse(it) }

    private fun splitList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(',')
}
