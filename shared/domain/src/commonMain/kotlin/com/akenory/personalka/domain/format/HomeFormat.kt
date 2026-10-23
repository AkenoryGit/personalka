package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.home.BackupTodo
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/** Строки «Главной» (ТЗ 5.2, шаг 1.16). */
object HomeFormat {
    private const val DOT = " · "

    // --- «Ближайшая» ---

    const val NEAREST = "Ближайшая"

    /** «Ближайшая · 16:00–16:50». */
    fun nearest(start: LocalDateTime, end: LocalDateTime): String = NEAREST + DOT + DateFormat.timeRange(start, end)

    /** Справа в шапке: «через 12 мин», «через 1 ч 20 мин»; идущая группа — «идёт». */
    fun startsIn(now: LocalDateTime, start: LocalDateTime): String =
        if (now >= start) ONGOING else "через " + DurationFormat.short(ClubTime.minutesBetween(now, start).coerceAtLeast(1))

    const val ONGOING = "идёт"

    /**
     * Строка под именем у группы: к «Мини-группа · ДБ» добавляется «ждём 2 из 3» — кого ждут из записанных; все —
     * «ждём 3». Никого не ждут (все отменены) — без добавки.
     */
    fun waiting(expected: Int, total: Int): String? = when {
        expected <= 0 -> null
        expected == total -> "ждём ${RuText.digits(expected.toLong())}"
        else -> "ждём ${RuText.digits(expected.toLong())} из ${RuText.digits(total.toLong())}"
    }

    /** «Ещё сегодня: 3, последняя в 19:00»; одна — «Ещё сегодня: 1, в 19:00». */
    fun laterToday(count: Int, last: LocalDateTime): String =
        "Ещё сегодня: ${RuText.digits(count.toLong())}, " + (if (count == 1) "" else "последняя ") + "в$NBSP" + DateFormat.time(last.time)

    /**
     * Сегодня больше ничего нет: «Сегодня всё. Завтра первая в 07:00»; следующая не завтра — «Сегодня всё. Следующая —
     * пт 24.10 в 07:00»; впереди ничего — «Сегодня всё. Впереди тренировок нет».
     */
    fun todayDone(next: LocalDateTime?, today: LocalDate): String = "Сегодня всё. " + when {
        next == null -> "Впереди тренировок нет"
        next.date == today.plus(1, DateTimeUnit.DAY) -> "Завтра первая в$NBSP" + DateFormat.time(next.time)
        else -> "Следующая — " + DateFormat.short(next.date) + " в$NBSP" + DateFormat.time(next.time)
    }

    /** Строка следующей тренировки под «Сегодня всё»: «ср 21.10 · Ковалёва Н. · ПТ · ББ». */
    fun nextLine(start: LocalDateTime, title: String, typeShort: String, placeShort: String): String =
        DateFormat.short(start.date) + DOT + title + DOT + typeShort + DOT + placeShort

    // --- Расписание дня на разложенном ---

    /** «Показать прошедшие · 7». */
    fun showPast(count: Int): String = "Показать прошедшие" + DOT + RuText.digits(count.toLong())

    const val HIDE_PAST = "Скрыть прошедшие"

    /** «Завтра: 9 тренировок, первая в 07:00»; пусто — «Завтра тренировок нет». */
    fun tomorrow(count: Int, first: LocalDateTime?): String =
        if (count == 0 || first == null) {
            "Завтра тренировок нет"
        } else {
            "Завтра: ${CalendarFormat.trainings(count)}, первая в$NBSP" + DateFormat.time(first.time)
        }

    /** Строка под именем в расписании дня: «ПТ · ДБ», у ближайшей — «ПТ · ДБ · через 12 мин». */
    fun dayLine(typeShort: String, placeShort: String, startsIn: String? = null): String =
        typeShort + DOT + placeShort + if (startsIn != null) DOT + startsIn else ""

    /** Расписание дня пусто. */
    const val NO_TRAININGS_TODAY = "Сегодня тренировок нет"

    // --- «Нужно сделать» ---

    const val TODO = "Нужно сделать"

    /** «Группа без отметки», несколько — «Группы без отметки». */
    fun unmarkedGroups(count: Int): String = if (count == 1) "Группа без отметки" else "Группы без отметки"

    /** Сколько групп видно в подписи; остальные — «и ещё N». */
    const val MAX_NAMES = 2

    /**
     * Подпись: одна — «18:00 · Вт, Чт 18:00 ДБ (10)» (не сегодня — «пн 19.10 18:00 · …»); несколько — время через
     * запятую: «пн 19.10 18:00, 18:00 и ещё 1».
     */
    fun unmarkedGroupsNote(groups: List<Pair<LocalDateTime, String>>, today: LocalDate): String {
        fun at(start: LocalDateTime) =
            if (start.date == today) DateFormat.time(start.time) else DateFormat.short(start.date) + " " + DateFormat.time(start.time)
        groups.singleOrNull()?.let { (start, title) -> return at(start) + DOT + title }
        val shown = groups.take(MAX_NAMES).joinToString(", ") { at(it.first) }
        val rest = groups.size - MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }

    fun backup(todo: BackupTodo): String = when (todo) {
        BackupTodo.NO_FOLDER -> "Выберите папку для копии"
        BackupTodo.STALE -> "Нет копии больше 7 дней"
    }

    /** Подпись пункта про копию: «Пока данные только в этом телефоне»; давно нет копии — «последняя — 12.10.2026». */
    fun backupNote(todo: BackupTodo, last: LocalDateTime?): String = when {
        todo == BackupTodo.NO_FOLDER -> "Пока данные только в этом телефоне"
        last == null -> BackupFormat.NO_COPIES
        else -> "последняя — " + DateFormat.full(last.date)
    }

    /** Для TalkBack у счётчика дел рядом с заголовком: «Нужно сделать: 3». */
    fun todoCount(count: Int): String = "$TODO: $count"
}
