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
    const val OWN_RESTRICTIONS = "Мои запреты"
    const val CLUB_RESTRICTIONS = "Запреты клуба"
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

    /** Дежурство в списке: «ББ 11:00–15:00». */
    fun duty(place: String, startMinute: Int, endMinute: Int): String = "$place " + DateFormat.timeRange(startMinute, endMinute)

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
}
