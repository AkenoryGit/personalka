package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.home.HomeTraining
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/** Строки «Главной» (ТЗ 5.2, макеты «Главная» шага 0.6). */
object HomeFormat {
    private const val DOT = " · "

    // --- Ближайшая ---

    const val NEXT = "Ближайшая"

    /** «Ближайшая · 16:00–16:50». */
    fun nextHeader(training: HomeTraining): String = NEXT + DOT + DateFormat.timeRange(training.start, training.end)

    /** Справа в шапке: «через 12 мин», «через 1 ч 20 мин»; началась (идёт группа) — «идёт». */
    fun startsIn(start: LocalDateTime, now: LocalDateTime): String =
        if (start > now) "через " + DurationFormat.short(ClubTime.minutesBetween(now, start)) else ONGOING

    const val ONGOING = "идёт"

    /** «Ещё сегодня: 3, последняя в 19:00». */
    fun moreToday(count: Int, last: LocalDateTime): String =
        "Ещё сегодня: ${RuText.digits(count.toLong())}, последняя в ${DateFormat.time(last.time)}"

    /** Под ближайшей, если она сегодня последняя: «Завтра первая в 07:00», «Следующая — чт 23.10 в 07:00». */
    fun later(later: HomeTraining, today: LocalDate): String =
        if (later.start.date == today.plus(1, DateTimeUnit.DAY)) {
            "Завтра первая в ${DateFormat.time(later.start.time)}"
        } else {
            "Следующая — ${DateFormat.short(later.start.date)} в ${DateFormat.time(later.start.time)}"
        }

    /** Сегодня больше ничего: «Сегодня всё. Завтра первая в 07:00»; впереди пусто — «Впереди тренировок нет». */
    fun todayDone(later: HomeTraining?, today: LocalDate): String = when (later) {
        null -> NOTHING_AHEAD
        else -> "Сегодня всё. " + later(later, today)
    }

    const val NOTHING_AHEAD = "Впереди тренировок нет"

    /** Строка первой тренировки другого дня: «ср 21.10 · Ковалёва Н. · ПТ · ББ». */
    fun laterRow(later: HomeTraining): String =
        DateFormat.short(later.start.date) + DOT + later.title + DOT + CalendarFormat.typePlace(later.typeShort, later.placeShort)

    // --- Нужно сделать ---

    const val TODO = "Нужно сделать"

    /** «Группа без отметки», «Группы без отметки»; если среди них есть не группы — «Без отметки». */
    fun unmarkedTitle(count: Int, groups: Boolean): String = when {
        !groups -> "Без отметки"
        count == 1 -> "Группа без отметки"
        else -> "Группы без отметки"
    }

    /** «18:00 · Вт, Чт 18:00 ДБ (10)»; не сегодня — с днём: «пн 19.10 18:00 · Орлов М.». */
    fun unmarkedEntry(title: String, start: LocalDateTime, today: LocalDate): String {
        val time = DateFormat.time(start.time)
        return (if (start.date == today) time else DateFormat.short(start.date) + " " + time) + DOT + title
    }

    /** Первые [max] через запятую, остальные — «и ещё N» (5.2). */
    fun names(items: List<String>, max: Int): String {
        val shown = items.take(max).joinToString(", ")
        val rest = items.size - max
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }

    const val BACKUP_FOLDER = "Выберите папку для копии"
    const val BACKUP_FOLDER_NOTE = "Пока данные только в этом телефоне"
    const val BACKUP_OLD = "Нет копии больше 7 дней"

    /** Подпись пункта про старую копию: «Последняя — 12.10.2026» или «Копий ещё не было». */
    fun backupLast(last: LocalDateTime?): String =
        if (last == null) BackupFormat.NO_COPIES else "Последняя — ${DateFormat.full(last.date)}"

    // --- Расписание дня на разложенном ---

    /** «Показать прошедшие · 7». */
    fun showPast(count: Int): String = "Показать прошедшие" + DOT + RuText.digits(count.toLong())

    const val HIDE_PAST = "Скрыть прошедшие"

    /** «Завтра: 9 тренировок, первая в 07:00»; пусто — «Завтра тренировок нет». */
    fun tomorrow(count: Int, first: LocalDateTime?): String =
        if (count == 0 || first == null) "Завтра тренировок нет"
        else "Завтра: ${CalendarFormat.trainings(count)}, первая в ${DateFormat.time(first.time)}"
}
