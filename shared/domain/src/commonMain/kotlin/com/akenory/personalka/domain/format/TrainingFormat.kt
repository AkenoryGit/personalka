package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Строки формы тренировки (ТЗ 5.5), вопроса для серии (4.4) и сообщений после сохранения. */
object TrainingFormat {
    private const val DOT = " · "

    const val NEW_TITLE = "Новая тренировка"
    const val EDIT_TITLE = "Изменить тренировку"
    const val SAVE = "Сохранить"
    const val PASTE = "Вставить скопированную"

    /** Чип своей длительности и «Не повторять / Каждую неделю». */
    const val OWN_DURATION = "Своя"
    const val NO_REPEAT = "Не повторять"
    const val WEEKLY = "Каждую неделю"

    /** Поле конца повтора без даты. */
    const val FOREVER = "до — бессрочно"

    /** Поле конца повтора с датой: «до 31.12.2026». */
    fun until(date: LocalDate): String = "до ${DateFormat.full(date)}"

    /** Поле времени: «20:00–20:50». */
    fun timeRange(start: LocalDateTime, end: LocalDateTime): String = DateFormat.timeRange(start, end)

    /** Поле даты: «вт 20.10.2026». */
    fun date(date: LocalDate): String = "${DateFormat.weekdayShort(date.dayOfWeek)}$NBSP${DateFormat.full(date)}"

    /** «Изменить с даты…» из карточки клиента (5.9): форма серии и поле её даты — «с вт 27.10.2026». */
    const val SERIES_TITLE = "Изменить расписание"

    fun fromDate(date: LocalDate): String = "с ${date(date)}"

    // --- Дежурство и запрет (шаг 1.13а, макет «Запрет») ---

    /** Переключатель вверху формы новой записи. */
    const val MODE_TRAINING = "Тренировка"
    const val MODE_DUTY = "Дежурство"
    const val MODE_RESTRICTION = "Запрет"

    const val NEW_DUTY_TITLE = "Новое дежурство"
    const val EDIT_DUTY_TITLE = "Изменить дежурство"
    const val NEW_RESTRICTION_TITLE = "Новый запрет"
    const val EDIT_RESTRICTION_TITLE = "Изменить запрет"

    /** Подсказка в поле причины: она нужна только для строки в окне — «ДБ 17:00–18:00 — групповая тренировка». */
    const val REASON_PLACEHOLDER = "Например, групповая тренировка"

    const val DELETE_RESTRICTION = "Удалить запрет"

    /** Сообщения после записи запрета (5.20). */
    const val RESTRICTION_CREATED = "Запрет создан"
    const val RESTRICTION_SAVED = "Запрет сохранён"
    const val RESTRICTION_DELETED = "Запрет удалён"

    /** Поле начала: «с 17:00». */
    fun from(time: LocalTime): String = "с ${DateFormat.time(time)}"

    /** Поле конца: «до 18:00»; полночь — «до 24:00», конец дня. */
    fun to(time: LocalTime): String =
        "до " + DateFormat.minuteOfDay((time.hour * 60 + time.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it })

    /** Начало периода запрета: «с 01.09.2026». */
    fun periodFrom(date: LocalDate): String = "с ${DateFormat.full(date)}"

    /** «Ещё 3» под чипами типов. */
    fun moreTypes(count: Int): String = "Ещё $count"

    /** Строка «Новый клиент «Иванов Пётр»» под поиском. */
    fun newClient(text: String): String = "Новый клиент «${text.trim()}»"

    /** Повтор тренировки серии, когда его не изменить в этой форме: «Каждый вт и чт · бессрочно». */
    fun seriesRepeat(weekdays: Set<DayOfWeek>, validTo: LocalDate?): String =
        ZoneFormat.repeat(weekdays, validTo).let { if (validTo == null) it + DOT + "бессрочно" else it }

    // --- Вопрос «Только эту / Эту и следующие» (4.4, макет «Изменение тренировки серии») ---

    const val SCOPE_EDIT_TITLE = "Изменить тренировку серии?"
    const val SCOPE_MOVE_TITLE = "Перенести тренировку серии?"
    const val FOLLOWING = "Эту и следующие"
    const val BACK_TO_EDIT = "Вернуться к правке"

    /** Главная кнопка вопроса: «Только эту — вт 20.10». */
    fun onlyThis(date: LocalDate): String = "Только эту — ${DateFormat.short(date)}"

    /** Серия одной строкой: «Никитин А. · каждый вт и чт, 12:00». */
    fun series(title: String, weekdays: Set<DayOfWeek>, time: LocalTime): String =
        title + DOT + ZoneFormat.repeat(weekdays, null).replaceFirstChar { it.lowercaseChar() } + ", " + DateFormat.time(time)

    /**
     * Что меняется — по предложению на изменение: «Новое время — 12:30–13:20.», «Новый день — чт 22.10.»,
     * «Новое место — ДБ.», «Новый тип — Сплит.», «Другие клиенты.». Время — и при новой длительности.
     */
    fun changes(
        start: LocalDateTime,
        end: LocalDateTime,
        dayChanged: Boolean,
        timeChanged: Boolean,
        place: String?,
        type: String?,
        clientsChanged: Boolean,
    ): String = buildList {
        if (dayChanged) add("Новый день — ${DateFormat.short(start.date)}.")
        if (timeChanged) add("Новое время — ${DateFormat.timeRange(start, end)}.")
        if (place != null) add("Новое место — $place.")
        if (type != null) add("Новый тип — $type.")
        if (clientsChanged) add("Другие клиенты.")
    }.joinToString(" ")

    /** Текст вопроса: серия и что меняется — «Никитин А. · каждый вт и чт, 12:00. Новое время — 12:30–13:20.» */
    fun scopeQuestion(series: String, changes: String): String = if (changes.isEmpty()) "$series." else "$series. $changes"

    // --- Сообщения после сохранения (5.20) ---

    /** Новая тренировка: «Создано: вт 20.10, 17:15». */
    fun created(start: LocalDateTime): String = "Создано: ${CalendarFormat.at(start)}"

    /** Новая серия: «Создано: каждый вт и чт, 17:15». */
    fun createdSeries(weekdays: Set<DayOfWeek>, time: LocalTime): String =
        "Создано: " + ZoneFormat.repeat(weekdays, null).replaceFirstChar { it.lowercaseChar() } + ", " + DateFormat.time(time)

    /** Изменение без переноса. */
    const val SAVED = "Сохранено"

    /** «Эту и следующие». */
    const val SAVED_FOLLOWING = "Изменены эта и следующие"

    /**
     * Запрет в окне «Поставить всё равно?» для серии (4.5): «Детский бассейн 17:00–18:00: групповая тренировка —
     * 9 из 17 тренировок, первая вт 20.10». Одна тренировка — «… — вт 20.10».
     */
    fun batchConflict(conflict: String, count: Int, total: Int, first: LocalDate): String =
        if (total == 1) {
            "$conflict — ${DateFormat.short(first)}"
        } else {
            "$conflict — $count из ${RuText.digits(total.toLong())}$NBSP${RuText.plural(total, "тренировки", "тренировок", "тренировок")}, первая ${DateFormat.short(first)}"
        }
}
