package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.format.DateFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * Точка дня в «Месяце» (ТЗ 5.3): цвет места и оттенок типа — как блок тренировки на сетке часов: ПТ в ББ — светлая
 * синяя, группа в ББ — тёмная синяя, ПТ в ДБ — светлая розовая (шаг 1.13в).
 */
data class MonthDot(val color: String, val shade: Int)

/**
 * Тренировка для сводки дня: ключ цвета места, оттенок типа, отменена ли и начало — минута суток, по ней точки
 * с одинаковым числом тренировок встают в порядке дня. Дежурства сюда не попадают: это не тренировка, а зона.
 */
data class MonthItem(val color: String, val shade: Int, val cancelled: Boolean, val startMinute: Int)

/** Сводка дня в «Месяце»: сколько тренировок без отменённых и до четырёх точек самых частых. */
data class MonthDay(val count: Int, val dots: List<MonthDot>) {
    companion object {
        val EMPTY = MonthDay(0, emptyList())
    }
}

/**
 * «Месяц» и мини-календарь (ТЗ 5.3, шаг 1.12а): страницы листания по месяцам, недели месяца с понедельника,
 * сводка дня — число тренировок и точки.
 */
object MonthRules {
    /** Точек под числом — не больше четырёх: больше в клетку 58 dp не помещается и не различается. */
    const val DOTS_MAX = 4

    private const val FIRST_YEAR = 1970

    /** Месяцы листаются с января 1970 до декабря 2099 — как дни в «Дне». */
    const val PAGE_COUNT = (2100 - FIRST_YEAR) * 12

    /** Страница листания — месяц от января 1970: так номер страницы не нужно хранить отдельно от даты. */
    fun page(date: LocalDate): Int = (date.year - FIRST_YEAR) * 12 + date.month.number - 1

    /** Первое число месяца страницы [page]. */
    fun firstOf(page: Int): LocalDate = LocalDate(FIRST_YEAR + page / 12, page % 12 + 1, 1)

    /** Последнее число месяца, в который входит [date]. */
    fun lastOf(date: LocalDate): LocalDate =
        LocalDate(date.year, date.month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    /**
     * Понедельники строк месяца: первая строка — неделя 1-го числа, последняя — неделя последнего. Строк 4–6;
     * дни соседних месяцев в первой и последней строке видны, но приглушены.
     */
    fun weeks(date: LocalDate): List<LocalDate> {
        val last = lastOf(date)
        return generateSequence(DateFormat.weekStart(LocalDate(date.year, date.month, 1))) { it.plus(7, DateTimeUnit.DAY) }
            .takeWhile { it <= last }
            .toList()
    }

    /** Дни страницы месяца — с понедельника первой строки по воскресенье последней; [Span.to] не входит. */
    fun span(date: LocalDate): Span {
        val weeks = weeks(date)
        return Span(weeks.first(), weeks.last().plus(7, DateTimeUnit.DAY))
    }

    /**
     * День, который выбран после листания месяца на [page]: сегодня, если оно в этом месяце; иначе то же число,
     * что было выбрано, — 31.10 → 30.11, как строка недели листает на тот же день недели.
     */
    fun sameDay(selected: LocalDate, page: Int, today: LocalDate): LocalDate {
        val first = firstOf(page)
        if (page(today) == page) return today
        return LocalDate(first.year, first.month, minOf(selected.day, lastOf(first).day))
    }

    /**
     * Сводка дня (ТЗ 5.3): число — тренировки без отменённых; точки — самые частые пары «цвет места × оттенок типа»
     * среди них, не больше [DOTS_MAX]. Чаще — левее; поровну — в порядке дня, по первой тренировке.
     */
    fun day(items: List<MonthItem>): MonthDay {
        val held = items.filter { !it.cancelled }
        if (held.isEmpty()) return MonthDay.EMPTY
        val dots = held.groupBy { MonthDot(it.color, it.shade) }
            .entries
            .sortedWith(compareByDescending<Map.Entry<MonthDot, List<MonthItem>>> { it.value.size }.thenBy { e -> e.value.minOf { it.startMinute } })
            .take(DOTS_MAX)
            .map { it.key }
        return MonthDay(held.size, dots)
    }

    /** Дни с [from] по [to] не включая. */
    data class Span(val from: LocalDate, val to: LocalDate)
}
