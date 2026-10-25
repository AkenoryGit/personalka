package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate

/**
 * График накопления по дням (ТЗ 5.12, шаг 3.7б). Точки — на конец дня, индекс — число месяца, точка 0 — начало
 * месяца (всегда 0 ₽). [earned] — заработано с 0 по [factDay] (сегодня; у прошедшего — последний день, у будущего
 * только точка 0), последняя точка — ровно «Заработано». [forecast] — прогноз пунктиром с [factDay] по [days]:
 * первая точка — заработано на сегодня, последняя — ровно прогноз месяца; у прошедшего месяца пуст. Темп плана —
 * прямая от 0 до [plan] к последнему дню. [step] — шаг линий сетки.
 */
data class SalaryChart(
    val days: Int,
    val factDay: Int,
    val earned: List<Long>,
    val forecast: List<Long>,
    val plan: Long?,
    val step: Long,
) {
    /** Верх графика: самая высокая точка или план. */
    val top: Long get() = maxOf(earned.max(), forecast.maxOrNull() ?: 0, plan ?: 0, 1)

    /** Темп плана на конец дня [day]. */
    fun pace(day: Int): Long? = plan?.let { it * day / days }

    /** Линии сетки снизу вверх, без нуля. */
    val ticks: List<Long> get() = generateSequence(step) { it + step }.takeWhile { it <= top }.toList()
}

object SalaryChartRules {

    /**
     * График месяца. Заработано по дням — тренировки по дню начала (по ступени месяца, как в «Заработано»: переход
     * ступени поднимает и прошлые дни), доплаты СПТ — по дню покупки, доплаты месяца — по дню внесения; всё, что
     * вне [1, factDay], ложится в крайний день. Прогноз от сегодняшней точки растёт по запланированным тренировкам
     * их днями: разница «прогноз − заработано» делится между днями пропорционально их запланированным деньгам
     * (так и переход ступени по прогнозу, и ожидаемые дети групп ложатся туда, где тренировки). Запланированные
     * на сегодня и раньше (не отмеченные) идут в завтрашний день; запланированных нет — разница ровно по дням.
     * [days] — та же раскладка по дням, что у «Заработка за день» (3.7в), поэтому точка дня и сумма дня сходятся.
     */
    fun build(
        input: MonthInput,
        pay: MonthPay,
        period: MonthPeriod,
        today: LocalDate,
        plan: Long?,
        days: List<DayItem> = SalaryDaysRules.items(input, pay, SalaryDaysRules.lastDay(input.month, today)),
    ): SalaryChart {
        val month = input.month
        val monthDays = month.lastDay.day
        val factDay = when (period) {
            MonthPeriod.PAST -> monthDays
            MonthPeriod.CURRENT -> today.day
            MonthPeriod.FUTURE -> 0
        }
        val ofMonth = PayRules.inMonth(input.trainings, month)
        val daily = LongArray(monthDays + 1)
        if (factDay > 0) days.forEach { daily[it.date.day.coerceIn(1, factDay)] += it.amount }
        val earned = ArrayList<Long>(factDay + 1)
        var sum = 0L
        for (d in 0..factDay) {
            sum += daily[d]
            earned += sum
        }
        val forecast = if (period == MonthPeriod.PAST || factDay == monthDays) {
            emptyList()
        } else {
            forecastLine(ofMonth, pay, factDay, monthDays, earned.last())
        }
        return SalaryChart(monthDays, factDay, earned, forecast, plan, step(maxOf(earned.last(), forecast.maxOrNull() ?: 0, plan ?: 0)))
    }

    private fun forecastLine(ofMonth: List<PayTraining>, pay: MonthPay, factDay: Int, days: Int, anchor: Long): List<Long> {
        val weights = LongArray(days + 1)
        ofMonth.zip(pay.forecastTrainings).forEach { (t, p) ->
            if (t.status == TrainingStatus.PLANNED) weights[t.start.date.day.coerceAtLeast(factDay + 1)] += p.earned
        }
        val total = weights.sum()
        val delta = pay.forecast - anchor
        val line = ArrayList<Long>(days - factDay + 1)
        var acc = 0L
        for (d in factDay..days) {
            if (d > factDay) acc += if (total > 0) weights[d] else 1
            val of = if (total > 0) total else (days - factDay).toLong()
            line += anchor + delta * acc / of
        }
        return line
    }

    /** Шаг сетки: 1, 2 или 5 × 10ⁿ рублей, чтобы до [max] было не больше четырёх линий. */
    fun step(max: Long): Long {
        var base = 100_000L // 1 000 ₽ в копейках
        while (true) {
            for (k in longArrayOf(1, 2, 5)) {
                if (max <= base * k * MAX_TICKS) return base * k
            }
            base *= 10
        }
    }

    private const val MAX_TICKS = 4
}
