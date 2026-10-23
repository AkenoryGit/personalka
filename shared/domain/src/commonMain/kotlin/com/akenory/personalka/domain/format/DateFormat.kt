package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.DASH
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number

/**
 * Даты и время (ТЗ 5.20, 10.6): «вт 22.10», «22.10.2026», время 24 часа, неделя с понедельника.
 * Время везде — часы клуба (ТЗ 3.1): сюда приходит уже локальное время, пояс здесь не нужен.
 */
object DateFormat {
    /** Дни недели с понедельника — порядок строки недели в календаре и выбора дней серии. */
    val WEEK: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    )

    private val WEEKDAY_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val WEEKDAY = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val MONTH = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )
    private val MONTH_GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** «пн» … «вс». */
    fun weekdayShort(day: DayOfWeek): String = WEEKDAY_SHORT[day.isoDayNumber - 1]

    /** «понедельник» … «воскресенье». */
    fun weekday(day: DayOfWeek): String = WEEKDAY[day.isoDayNumber - 1]

    /** «22.10». */
    fun dayMonth(date: LocalDate): String = "${two(date.day)}.${two(date.month.number)}"

    /** «вт 22.10» — в строках, метках и сообщениях: «Перенесено на вт 22.10, 17:15». */
    fun short(date: LocalDate): String = "${weekdayShort(date.dayOfWeek)}$NBSP${dayMonth(date)}"

    /** «22.10.2026» — полная дата: рождение, согласие, другой год. */
    fun full(date: LocalDate): String = "${dayMonth(date)}.${date.year}"

    /**
     * Дата, набранная тренером: «12.05.2017», «12.5.2017», «12/05/2017» или восемь цифр подряд «12052017».
     * Год — четырьмя цифрами; несуществующая дата (31.02) и всё остальное — `null`.
     */
    fun parseFull(text: String): LocalDate? {
        val clean = text.trim()
        val parts = if (clean.length == 8 && clean.all { it.isDigit() }) {
            listOf(clean.substring(0, 2), clean.substring(2, 4), clean.substring(4))
        } else {
            clean.split('.', '/', '-', ' ', ',').filter { it.isNotEmpty() }
        }
        if (parts.size != 3 || parts[2].length != 4 || parts[0].length > 2 || parts[1].length > 2) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    /** «Вторник, 20 октября» — заголовок «Главной». */
    fun header(date: LocalDate): String =
        "${weekday(date.dayOfWeek).replaceFirstChar { it.uppercaseChar() }}, ${date.day}$NBSP${MONTH_GENITIVE[date.month.number - 1]}"

    /** «Октябрь 2026» — шапка календаря и зарплаты. */
    fun monthYear(date: LocalDate): String =
        "${MONTH[date.month.number - 1].replaceFirstChar { it.uppercaseChar() }}$NBSP${date.year}"

    /** «октябрь» — подпись месяца под графиком результатов. */
    fun month(date: LocalDate): String = MONTH[date.month.number - 1]

    /** «октября» — для фраз вида «за 20 октября». */
    fun monthGenitive(date: LocalDate): String = MONTH_GENITIVE[date.month.number - 1]

    /** Понедельник недели, в которую входит [date] (неделя с понедельника, 10.6). */
    fun weekStart(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    /** «07:05». */
    fun time(time: LocalTime): String = "${two(time.hour)}:${two(time.minute)}"

    /** «07:05» по минуте суток; 1440 — «24:00», конец дня (тренировка до полуночи, запрет до 00:00). */
    fun minuteOfDay(minute: Int): String {
        require(minute in 0..ClubTime.MINUTES_PER_DAY) { "Минута суток вне 0..1440: $minute" }
        return "${two(minute / 60)}:${two(minute % 60)}"
    }

    /** «16:00–16:50». */
    fun timeRange(startMinute: Int, endMinute: Int): String = "${minuteOfDay(startMinute)}$DASH${minuteOfDay(endMinute)}"

    /**
     * Промежуток запрета или дежурства: «17:00–18:00». Конец 00:00 — это 24:00 (решение шага 1.4),
     * 00:00–00:00 — весь день: «00:00–24:00».
     */
    fun timeRange(start: LocalTime, end: LocalTime): String {
        val endMinute = (end.hour * 60 + end.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
        return timeRange(start.hour * 60 + start.minute, endMinute)
    }

    /** Время тренировки: «23:10–24:00», если конец пришёлся на полночь следующего дня. */
    fun timeRange(start: LocalDateTime, end: LocalDateTime): String {
        val days = (end.date.toEpochDays() - start.date.toEpochDays()).toInt()
        val endMinute = ClubTime.minuteOfDay(end) + days * ClubTime.MINUTES_PER_DAY
        return timeRange(ClubTime.minuteOfDay(start), endMinute)
    }

    private fun two(n: Int): String = n.toString().padStart(2, '0')
}
