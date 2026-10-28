package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.DASH
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Строки раздела «Календарь» в настройках (ТЗ 5.16, шаг 1.13г). */
object CalendarSettingsFormat {
    private const val DOT = " · "

    const val TITLE = "Календарь"
    const val WORK_HOURS = "Часы работы"
    const val WORK_HOURS_NOTE = "Часы сетки календаря. Тренировку можно поставить и вне них — сетка этого дня расширится."
    const val DRAG_STEP = "Шаг перетаскивания"
    const val HORIZON = "Постоянное расписание вперёд"
    const val HORIZON_NOTE = "На столько недель серии ставят тренировки в календарь. Дальше — продлеваются сами."
    const val CANCEL_NOTICE = "Отмена без списания — если предупредили за"
    const val CANCEL_NOTICE_NOTE =
        "Предупредили позже — отмена со списанием. По инициативе тренера или клуба — всегда без списания."
    const val VACATION = "Отпуск"
    const val VACATION_BUTTON = "Отпуск с … по …"
    const val VACATION_TITLE = "Отпуск"
    const val VACATION_FROM = "Первый день"
    const val VACATION_TO = "Последний день"
    const val VACATION_NOTE =
        "Тренировки постоянного расписания и дежурства в эти дни удалятся, планы перейдут на первую тренировку после отпуска. " +
            "Разовые тренировки останутся."
    const val VACATION_SAVE = "Записать отпуск"
    const val VACATION_WHO = "Кого предупредить"
    const val VACATION_WHO_NOTE = "После записи список останется на «Главной» — отмечайте, кого предупредили."
    const val VACATION_NOBODY = "В эти дни тренировок постоянного расписания нет."
    const val VACATION_DUTIES = "Дежурства"
    const val NOTICE_TODO = "Предупредить об отпуске"
    const val NOTICE_DONE = "Предупредил"
    const val NOTICE_COPY = "Скопировать список"
    const val NOTICE_COPIED = "Список скопирован"
    const val NOTICE_CLOSE = "Закрыть"
    // Запретов клуба нет (решение 28.10.2026, шаг 5.3а): каждый тренер ведёт свои.
    const val OWN_RESTRICTIONS = "Запреты"
    const val DUTIES = "Дежурства"

    /** Строка раздела в «Настройках»: «07:00–23:00 · шаг 15 мин · серии на 8 недель». */
    fun summary(start: LocalTime, end: LocalTime, step: Int, horizonWeeks: Int): String =
        DateFormat.timeRange(start, end) + DOT + "шаг " + DurationFormat.minutes(step) + DOT + "серии на " + weeks(horizonWeeks)

    /** «8 недель» на чипе горизонта. */
    fun weeks(n: Int): String = RuText.count(n, "неделю", "недели", "недель")

    /** «1 час», «24 часа» на чипе отмены. */
    fun notice(minutes: Int): String = RuText.count(minutes / 60, "час", "часа", "часов")

    /** «15 мин» на чипе шага. */
    fun step(minutes: Int): String = DurationFormat.minutes(minutes)

    /** Начало и конец часов работы: «с 07:00», «до 23:00»; конец 00:00 — «до 24:00». */
    fun from(time: LocalTime): String = TrainingFormat.from(time)
    fun to(time: LocalTime): String = TrainingFormat.to(time)

    /**
     * Когда действует запрет или дежурство в списке: разовый — «вт 20.10.2026»; повторяющийся — «Каждый вт и чт»,
     * ещё не начался — «… с 02.11.2026», с концом — «… до 31.12.2026».
     */
    fun period(weekdays: Set<DayOfWeek>, date: LocalDate?, validFrom: LocalDate, validTo: LocalDate?, today: LocalDate): String {
        if (weekdays.isEmpty()) return TrainingFormat.date(date ?: validFrom)
        val from = if (validFrom > today) " с ${DateFormat.full(validFrom)}" else ""
        val to = validTo?.let { " до ${DateFormat.full(it)}" }.orEmpty()
        return ZoneFormat.repeat(weekdays, null) + from + to
    }

    /** Дежурство в списке: «ББ 11:00–15:00»; за коллегу — «ББ 11:00–15:00 · за Александра». */
    fun duty(place: String, startMinute: Int, endMinute: Int, coverFor: String? = null): String =
        "$place " + DateFormat.timeRange(startMinute, endMinute) + (coverFor?.takeIf { it.isNotBlank() }?.let { DOT + ZoneFormat.coverFor(it) } ?: "")

    /** Сообщение после «Записать отпуск»: «Отпуск 02.11–08.11: удалено 12 тренировок»; без тренировок — «Отпуск 02.11–08.11 записан». */
    fun vacationSaved(from: LocalDate, to: LocalDate, deleted: Int): String {
        val days = DateFormat.dayMonth(from) + DASH + DateFormat.dayMonth(to)
        return if (deleted == 0) {
            "Отпуск$NBSP$days записан"
        } else {
            "Отпуск$NBSP$days: " + RuText.plural(deleted, "удалена", "удалено", "удалено") + " " +
                RuText.count(deleted, "тренировка", "тренировки", "тренировок")
        }
    }

    /** Главная кнопка окна отпуска: «Удалить 12 тренировок»; удалять нечего — «Записать отпуск». */
    fun vacationDelete(n: Int): String =
        if (n == 0) VACATION_SAVE else "Удалить " + RuText.count(n, "тренировку", "тренировки", "тренировок")

    /** Подпись строки «Кого предупредить»: «2 тренировки · пн 02.11, ср 04.11». */
    fun vacationDays(dates: List<LocalDate>): String =
        RuText.count(dates.size, "тренировка", "тренировки", "тренировок") + DOT + dates.joinToString(", ") { DateFormat.short(it) }

    /** Заголовок списка «Предупредить об отпуске»: «Отпуск 02.11–08.11». */
    fun vacationTitle(from: LocalDate, to: LocalDate): String =
        "Отпуск$NBSP" + DateFormat.dayMonth(from) + DASH + DateFormat.dayMonth(to)

    /** Сообщение после «Предупредил»: «Предупредили: Смирнова А.». */
    fun noticeDone(name: String): String = "Предупредили: $name"

    /**
     * Список для мессенджера (кнопка «Скопировать список»): заголовок отпуска и строка на клиента —
     * «Смирнова А. — пн 02.11, ср 04.11».
     */
    fun noticeList(title: String, rows: List<Pair<String, List<LocalDate>>>): String =
        (listOf(title) + rows.map { (name, dates) -> "$name — " + dates.joinToString(", ") { DateFormat.short(it) } }).joinToString("\n")
}
