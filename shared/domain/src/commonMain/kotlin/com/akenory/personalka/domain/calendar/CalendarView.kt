package com.akenory.personalka.domain.calendar

/**
 * Вид календаря (ТЗ 5.3): «День» или «Несколько дней» — 3, 5 или 7 колонок рядом. «Месяц» и «Список» — шаг 1.12а.
 * Последний вид запоминается на телефоне отдельно для сложенного и разложенного Fold; [code] — как он хранится.
 */
sealed interface CalendarView {
    /** Сколько дней видно рядом. */
    val columns: Int

    val code: String

    data object Day : CalendarView {
        override val columns: Int get() = 1
        override val code: String get() = "day"
    }

    data class Days(val count: Int) : CalendarView {
        override val columns: Int get() = count
        override val code: String get() = "days$count"
    }
}

/** Какие виды можно выбрать и какой открыть (ТЗ 5.3, 3.2 `week_view_days`). */
object CalendarViews {
    /** Сколько дней бывает в «Нескольких днях». */
    val DAY_COUNTS: List<Int> = listOf(3, 5, 7)

    /**
     * Виды в переключателе. На сложенном — «День» и «3 дня»: в колонку 5 или 7 дней (50–70 dp) не помещается
     * ни имя, ни место, а наложения сразу уходят в «+N».
     */
    fun options(wide: Boolean): List<CalendarView> =
        if (wide) listOf(CalendarView.Day) + DAY_COUNTS.map { CalendarView.Days(it) } else listOf(CalendarView.Day, CalendarView.Days(3))

    /** Пока вид не выбирали: на сложенном — «День», на разложенном — «Несколько дней» по настройке тренера. */
    fun default(wide: Boolean, weekViewDays: Int): CalendarView =
        if (wide) CalendarView.Days(weekViewDays.takeIf { it in DAY_COUNTS } ?: DAY_COUNTS.first()) else CalendarView.Day

    /** Запомненный вид, если он есть среди [options] этого состояния; иначе `null` — открывается [default]. */
    fun parse(code: String?, wide: Boolean): CalendarView? = options(wide).firstOrNull { it.code == code }
}
