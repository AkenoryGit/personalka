package com.akenory.personalka.domain.money

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import kotlinx.datetime.yearMonth

// Вкладка «Год» (ТЗ 5.12, шаг 3.8б): итоги месяцев из того же расчёта, что и вкладка «Месяц», — поэтому октябрь
// в «Годе» совпадает с октябрём в «Месяце» до копейки, а итог года — ровно сумма строк.

/**
 * Месяц года: заработано [earned] (у текущего — по проведённым), [forecast] — прогноз (только у текущего), план
 * [plan] (свой или перенесённый, 4.12), налог [ndfl] (оценка 4.11), на руки [onHand] (карта, наличные и взаиморасчёт
 * по проведённым), ПТ в счёт ступени [ptCount], оплаченных детей в группах [groupChildren], дежурств [dutyMinutes].
 */
data class YearMonthRow(
    val month: YearMonth,
    val period: MonthPeriod,
    val earned: Long,
    val forecast: Long?,
    val plan: Long?,
    val ndfl: Long,
    val onHand: Long,
    val ptCount: Int,
    val groupChildren: Int,
    val dutyMinutes: Int,
) {
    /** Выполнение плана, проценты вниз до целого. */
    val percent: Int? get() = SalaryPlan.percent(earned, plan)

    /**
     * Полоска выполнения плана слева на разложенном Fold (шаг 3.8а): доля плана, которую дают заработано
     * ([planShareDone]) и прогноз ([planShareForecast], только у текущего), от 0 до 1; без плана — 0.
     */
    val planShareDone: Float get() = share(earned)
    val planShareForecast: Float get() = forecast?.let { share(maxOf(it, earned)) } ?: 0f

    private fun share(amount: Long): Float =
        if (plan == null || plan <= 0) 0f else (amount.coerceAtLeast(0).toDouble() / plan).coerceAtMost(1.0).toFloat()

    /** План выполнен — только у закончившегося месяца с планом. */
    val planMet: Boolean get() = period == MonthPeriod.PAST && plan != null && earned >= plan
}

/**
 * Год [year]: [rows] — месяцы по возрастанию, от первого месяца с заработком или тренировками до текущего (будущих
 * нет: у них заработка нет, а прогноз — во вкладке «Месяц»). [current] — идущий месяц, если он в этом году.
 */
data class SalaryYear(val year: Int, val rows: List<YearMonthRow>, val current: YearMonth?) {
    val earned: Long get() = rows.sumOf { it.earned }
    val ndfl: Long get() = rows.sumOf { it.ndfl }
    val onHand: Long get() = rows.sumOf { it.onHand }
    val ptCount: Int get() = rows.sumOf { it.ptCount }
    val groupChildren: Int get() = rows.sumOf { it.groupChildren }
    val dutyMinutes: Int get() = rows.sumOf { it.dutyMinutes }

    /** Закончившиеся месяцы с планом — «план выполнен в M из N месяцев». */
    val planMonths: Int get() = rows.count { it.period == MonthPeriod.PAST && it.plan != null }
    val planMetMonths: Int get() = rows.count { it.planMet }

    /** Самый высокий столбец графика: заработано, прогноз или план. */
    val chartMax: Long get() = rows.maxOfOrNull { maxOf(it.earned, it.forecast ?: 0, it.plan ?: 0) } ?: 0
}

object SalaryYearRules {

    /** Месяцы года [year], которые нужно посчитать на день [today]: весь прошедший год, у текущего — до сегодня. */
    fun months(year: Int, today: LocalDate): List<YearMonth> {
        val last = if (year < today.year) 12 else if (year == today.year) today.month.number else 0
        return (1..last).map { YearMonth(year, it) }
    }

    /**
     * Год из посчитанных месяцев [months] (как во вкладке «Месяц»). Месяцы до первого с заработком, тренировками или
     * прогнозом не показываются: год начинается с месяца перехода (ТЗ 3.6, импорта нет).
     */
    fun build(year: Int, months: List<SalaryMonth>, today: LocalDate): SalaryYear {
        val sorted = months.filter { it.month.year == year }.sortedBy { it.month }
        val first = sorted.indexOfFirst { hasData(it) }
        val rows = if (first < 0) emptyList() else sorted.drop(first).map { row(it) }
        val current = today.yearMonth.takeIf { it.year == year }
        return SalaryYear(year, rows, current)
    }

    fun row(m: SalaryMonth): YearMonthRow = YearMonthRow(
        month = m.month,
        period = m.period,
        earned = m.pay.earned,
        forecast = m.pay.forecast.takeIf { m.hasForecast },
        plan = m.plan,
        ndfl = m.payout.ndfl,
        onHand = m.totals.onHand,
        ptCount = m.pay.ptCount,
        groupChildren = m.pay.groupChildren,
        dutyMinutes = m.pay.dutyMinutes,
    )

    private fun hasData(m: SalaryMonth): Boolean =
        m.pay.earned != 0L || m.pay.trainings.isNotEmpty() || (m.hasForecast && m.pay.forecast != 0L)
}
