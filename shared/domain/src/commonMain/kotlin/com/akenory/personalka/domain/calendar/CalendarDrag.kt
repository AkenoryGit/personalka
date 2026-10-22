package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.StatusRules
import kotlinx.datetime.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// ТЗ 5.3, «Жесты». Минуты — от начала суток по часам клуба, расстояния — dp, время пальца — миллисекунды.

/** Почему тренировку не перенести. */
enum class MoveProblem {
    /** Отменённая: сначала «Вернуть» (5.4). */
    CANCELLED,

    /** Проведённая или с отметками «пришёл» — в то время, когда её ещё нельзя было бы отметить. */
    MARKED_IN_FUTURE,
}

/**
 * Жесты календаря (ТЗ 5.3): длительное нажатие, перетаскивание с привязкой к шагу, автопрокрутка у верхнего
 * и нижнего края, листание дня у бокового. Экран сверяет с этим палец, а правила — то, куда тренировку ставят.
 */
object CalendarDrag {
    /** Длительное нажатие. */
    const val LONG_PRESS_MS: Long = 400

    /** Сдвиг пальца после длительного нажатия, с которого блок тащится; меньше — при отпускании меню. */
    const val MENU_SLOP_DP: Float = 8f

    /** В виде «День» палец у бокового края столько держится — листается день. */
    const val EDGE_FLIP_MS: Long = 600

    /** Боковой край колонки дня, где палец листает день. */
    const val SIDE_EDGE_DP: Float = 32f

    /** Полоса у верхнего и нижнего края сетки, где она прокручивается сама. */
    const val SCROLL_EDGE_DP: Float = 48f

    /** Скорость автопрокрутки, когда палец у самого края: за секунду — два часа при 64 dp. */
    const val SCROLL_MAX_DP_PER_SECOND: Float = 480f

    /** Шаг перетаскивания по умолчанию (`drag_step_minutes`, 3.2); в настройках — 5, 10, 15 или 30. */
    const val STEP_DEFAULT: Int = 15

    /** Начало быстрого создания: минута под пальцем, вниз до шага. В 17:23 при шаге 15 — 17:15. */
    fun createMinute(minute: Float, step: Int): Int {
        val s = safeStep(step)
        val m = floor(minute).toInt().coerceIn(0, ClubTime.MINUTES_PER_DAY - 1)
        return m / s * s
    }

    /**
     * Новое начало перетаскиваемой тренировки. [raw] — минута суток, где сейчас верх блока. Начало встаёт
     * на ближайшую кратную шагу минуту: 12:07 тянут на 12:16 — 12:15. Пока верх ближе к старому началу, чем
     * на полшага, начало прежнее: отпустить блок на месте — ничего не изменить. Через полночь тренировку
     * не перенести (4.14): последнее начало — сутки минус длительность.
     */
    fun moveMinute(start: Int, raw: Float, durationMin: Int, step: Int): Int {
        val s = safeStep(step)
        val latest = (ClubTime.MINUTES_PER_DAY - durationMin).coerceAtLeast(0)
        if (abs(raw - start) < s / 2f) return start.coerceIn(0, latest)
        return ((raw / s).roundToInt() * s).coerceIn(0, latest)
    }

    /**
     * Автопрокрутка, пока блок тащат: dp в секунду, вниз — больше нуля. [y] — палец от верха сетки высотой
     * [height]. Чем ближе к краю, тем быстрее; вне полос у краёв — 0.
     */
    fun autoScroll(y: Float, height: Float): Float {
        val edge = minOf(SCROLL_EDGE_DP, height / 4f)
        if (edge <= 0f) return 0f
        return when {
            y < edge -> -SCROLL_MAX_DP_PER_SECOND * ((edge - y) / edge).coerceAtMost(1f)
            y > height - edge -> SCROLL_MAX_DP_PER_SECOND * ((y - (height - edge)) / edge).coerceAtMost(1f)
            else -> 0f
        }
    }

    /** Палец у бокового края колонки шириной [width]: −1 — у левого (вчера), 1 — у правого (завтра), 0 — нет. */
    fun side(x: Float, width: Float): Int {
        val edge = minOf(SIDE_EDGE_DP, width / 4f)
        return when {
            x < edge -> -1
            x > width - edge -> 1
            else -> 0
        }
    }

    /**
     * Можно ли поставить тренировку на [start]. Отменённая не переносится: сначала её возвращают. Проведённая
     * или с отметками «пришёл» ([marked]) не уходит туда, где её ещё нельзя было бы отметить — позже, чем
     * через 15 минут от [now] (4.1): иначе в будущем стояла бы проведённая тренировка.
     */
    fun problem(status: TrainingStatus, marked: Boolean, start: LocalDateTime, now: LocalDateTime): MoveProblem? = when {
        status == TrainingStatus.CANCELLED -> MoveProblem.CANCELLED
        (marked || status == TrainingStatus.DONE) &&
            ClubTime.plusMinutes(start, -StatusRules.MARK_BEFORE_START_MINUTES) > now -> MoveProblem.MARKED_IN_FUTURE
        else -> null
    }

    private fun safeStep(step: Int): Int = if (step in 1..60) step else STEP_DEFAULT
}
