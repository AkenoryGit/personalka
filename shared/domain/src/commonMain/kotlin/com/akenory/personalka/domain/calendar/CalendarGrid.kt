package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.LocalTime

// ТЗ 5.3, «Сетка» и «Блок тренировки». Расстояния — в dp, время — минуты суток по часам клуба.

/** Часы сетки дня: с [startHour] до [endHour]; 24 — полночь в конце дня. */
data class GridHours(val startHour: Int, val endHour: Int) {
    init {
        require(startHour in 0 until endHour && endHour <= 24) { "Часы сетки вне суток: $startHour–$endHour" }
    }

    val startMinute: Int get() = startHour * 60
    val endMinute: Int get() = endHour * 60
    val count: Int get() = endHour - startHour
}

/** Сколько строк текста помещается в блок (ТЗ 5.3, «Содержимое по высоте блока»). */
enum class BlockRows {
    /** Меньше 40 dp: имя и начало одной строкой — «Белова Е., 10:00». */
    ONE,

    /** 40–56 dp: имя и «начало · место» — «08:00 · ББ». */
    TWO,

    /** От 56 dp: имя, время, «тип · место». */
    THREE,
}

/**
 * Что видно в сетке: [top] — минута суток у верхнего края, [hourDp] — высота часа. Прокрутка и щипок меняют
 * только это; в минутах положение не зависит от масштаба и переживает сложение Fold.
 */
data class GridView(val top: Float, val hourDp: Float) {
    val dpPerMinute: Float get() = hourDp / 60f

    /** Сколько минут помещается в высоту [heightDp]. */
    fun minutesIn(heightDp: Float): Float = heightDp * 60f / hourDp

    /** Та же картина в пределах сетки [hours] при высоте экрана [heightDp]. */
    fun clamped(hours: GridHours, heightDp: Float): GridView =
        copy(top = CalendarGrid.clampTop(top, hours, minutesIn(heightDp)))

    /** Прокрутка на [dyDp]: положительное — вниз, к вечеру. */
    fun scrolled(dyDp: Float, hours: GridHours, heightDp: Float): GridView =
        copy(top = top + minutesIn(dyDp)).clamped(hours, heightDp)

    /**
     * Щипок: высота часа умножается на [factor] в пределах 40–160 dp, а минута под пальцами — в [focusDp]
     * от верха сетки — остаётся под ними.
     */
    fun zoomed(factor: Float, focusDp: Float, hours: GridHours, heightDp: Float): GridView {
        val hour = CalendarGrid.clampHour(hourDp * factor)
        val focusMinute = top + minutesIn(focusDp)
        return GridView(top = focusMinute - focusDp * 60f / hour, hourDp = hour).clamped(hours, heightDp)
    }
}

/** Сетка календаря (ТЗ 5.3). */
object CalendarGrid {
    /** Высота часа: по умолчанию 64 dp, щипком — от 40 до 160 dp. */
    const val HOUR_DEFAULT_DP = 64f
    const val HOUR_MIN_DP = 40f
    const val HOUR_MAX_DP = 160f

    /** От этой высоты блок показывает три строки, от [TWO_ROWS_DP] — две. */
    const val THREE_ROWS_DP = 56f
    const val TWO_ROWS_DP = 40f

    /** При открытии видно время с «сейчас минус 1 час». */
    const val OPEN_BEFORE_NOW_MIN = 60

    fun clampHour(dp: Float): Float = dp.coerceIn(HOUR_MIN_DP, HOUR_MAX_DP)

    /**
     * Часы сетки: часы работы из настроек, расширенные до полного часа тренировок видимых дней. Тренировка в 06:30
     * даёт сетку с 06:00, до 23:40 — до 24:00; ночь без тренировок не показывается. Конец работы 00:00 — это 24:00.
     */
    fun hours(workStart: LocalTime, workEnd: LocalTime, spans: List<DaySpan> = emptyList()): GridHours {
        val workFrom = workStart.hour * 60 + workStart.minute
        val workTo = (workEnd.hour * 60 + workEnd.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
        val from = minOf(workFrom, spans.minOfOrNull { it.start } ?: workFrom).coerceAtLeast(0)
        val to = maxOf(workTo, spans.maxOfOrNull { it.end } ?: workTo).coerceAtMost(ClubTime.MINUTES_PER_DAY)
        val startHour = from / 60
        val endHour = ((to + 59) / 60).coerceIn(startHour + 1, 24)
        return GridHours(startHour.coerceAtMost(endHour - 1), endHour)
    }

    /** Высота отрезка в [minutes] минут при часе [hourDp]. */
    fun heightDp(minutes: Int, hourDp: Float): Float = minutes * hourDp / 60f

    /** При часе 64 dp тренировка на 60 минут — три строки, на 45–50 — две, на 30 — одна. */
    fun rows(heightDp: Float): BlockRows = when {
        heightDp >= THREE_ROWS_DP -> BlockRows.THREE
        heightDp >= TWO_ROWS_DP -> BlockRows.TWO
        else -> BlockRows.ONE
    }

    /**
     * Верхняя минута в пределах сетки: не выше её начала и не так низко, чтобы под её концом было пусто.
     * [viewportMinutes] — сколько минут видно на экране; сетка короче экрана стоит от начала.
     */
    fun clampTop(top: Float, hours: GridHours, viewportMinutes: Float): Float {
        val last = maxOf(hours.startMinute.toFloat(), hours.endMinute - viewportMinutes)
        return top.coerceIn(hours.startMinute.toFloat(), last)
    }

    /**
     * Вид при открытии: «сейчас минус 1 час», но не дальше конца сетки — в 15:40 при сетке 07:00–23:00
     * на сложенном Fold (768 dp, 12 часов) виден 11:00–23:00.
     */
    fun open(now: LocalTime, hours: GridHours, heightDp: Float, hourDp: Float = HOUR_DEFAULT_DP): GridView =
        GridView(top = openTop(now), hourDp = clampHour(hourDp)).clamped(hours, heightDp)

    /** «Сейчас минус 1 час» минутой суток — до проверки на края сетки: её делает [GridView.clamped]. */
    fun openTop(now: LocalTime): Float = (now.hour * 60 + now.minute - OPEN_BEFORE_NOW_MIN).toFloat()
}
