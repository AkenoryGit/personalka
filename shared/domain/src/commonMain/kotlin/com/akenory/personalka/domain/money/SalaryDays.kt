package com.akenory.personalka.domain.money

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// Заработок за день и за период (ТЗ 5.12, шаг 3.7в): заработанное месяца раскладывается по дням так же, как на
// графике «По дням», поэтому сумма дней месяца — ровно «Заработано», а период через месяцы — сумма их частей.
// Ступень заново не считается: каждая тренировка — по ступени своего месяца, как в «Заработано».

/** Что легло в день: тренировка, доплата за СПТ, закрытую покупкой блока, или доплата (удержание) месяца. */
enum class DayItemKind { TRAINING, SPT, ADJUSTMENT }

/**
 * Заработанное одного дня. У тренировки — [trainingId], у доплаты СПТ — [sptId], у доплаты месяца — [adjustment]
 * (номер в `MonthInput.adjustments`, в том же порядке — записи доплат месяца).
 */
data class DayItem(
    val date: LocalDate,
    val kind: DayItemKind,
    val amount: Long,
    val trainingId: String? = null,
    val sptId: String? = null,
    val adjustment: Int? = null,
)

/**
 * Часть периода в месяце [month]: дни с [from] по [to], заработано [earned] по ступени месяца [tier].
 * [whole] — период берёт месяц целиком (до его последнего дня с заработком).
 */
data class PeriodPart(val month: YearMonth, val from: LocalDate, val to: LocalDate, val earned: Long, val tier: Int, val whole: Boolean)

/**
 * Заработок с [from] по [to] включительно: [total] — сумма частей месяцев [parts]; [breakdown] — разбивка 4.10
 * по тренировкам периода (одинаковые строки разных месяцев сложены, при разной ступени — разные строки);
 * [adjustments] — доплаты и удержания, попавшие в период.
 */
data class SalaryPeriod(
    val from: LocalDate,
    val to: LocalDate,
    val parts: List<PeriodPart>,
    val breakdown: List<BreakdownLine>,
    val adjustments: List<Pair<YearMonth, DayItem>>,
) {
    val total: Long get() = parts.sumOf { it.earned }
}

object SalaryDaysRules {

    /**
     * Заработанное месяца по дням: тренировки — днём начала, доплаты СПТ — днём покупки, доплаты месяца — днём
     * внесения (внесённая раньше месяца — в 1-е, позже или без даты — в [lastDay]). Всё ложится в дни с 1-го по
     * [lastDay] (у текущего месяца — сегодня, у остальных — последний день). Сумма — ровно «Заработано».
     */
    fun items(input: MonthInput, pay: MonthPay, lastDay: Int): List<DayItem> {
        val month = input.month
        val days = month.lastDay.day
        val last = lastDay.coerceIn(1, days)
        fun on(day: Int) = LocalDate(month.year, month.month, day.coerceIn(1, last))
        val out = ArrayList<DayItem>()
        PayRules.inMonth(input.trainings, month).zip(pay.trainings).forEach { (t, p) ->
            if (p.earned != 0L) out += DayItem(on(t.start.date.day), DayItemKind.TRAINING, p.earned, trainingId = t.id)
        }
        input.spt.filter { it.purchaseDate?.yearMonth == month }.forEach { case ->
            val bonus = SptRules.bonus(case, input.rates)
            if (bonus != 0L) out += DayItem(on(case.purchaseDate!!.day), DayItemKind.SPT, bonus, sptId = case.id)
        }
        input.adjustments.forEachIndexed { i, a ->
            val d = a.date
            val day = when {
                d != null && d.yearMonth == month -> d.day
                d != null && d.yearMonth < month -> 1
                else -> last
            }
            out += DayItem(on(day), DayItemKind.ADJUSTMENT, a.amount, adjustment = i)
        }
        return out.sortedBy { it.date }
    }

    /** Последний день с заработком: у текущего месяца — сегодня, у прошедшего и будущего — последний день месяца. */
    fun lastDay(month: YearMonth, today: LocalDate): Int =
        if (month == today.yearMonth) today.day else month.lastDay.day

    /** Заработанное дня [date] из раскладки месяца. */
    fun day(items: List<DayItem>, date: LocalDate): List<DayItem> = items.filter { it.date == date }

    /**
     * Период с [from] по [to] из месяцев [months] (вход и деньги каждого месяца, который период задевает): части
     * месяцев, разбивка и доплаты. Месяцы вне периода пропускаются.
     */
    fun period(months: List<Pair<MonthInput, MonthPay>>, from: LocalDate, to: LocalDate, today: LocalDate): SalaryPeriod {
        val parts = ArrayList<PeriodPart>()
        val lines = ArrayList<BreakdownLine>()
        val adjustments = ArrayList<Pair<YearMonth, DayItem>>()
        var typeOrder: Map<String, Int> = emptyMap()
        months.sortedBy { it.first.month }.forEach { (input, pay) ->
            val month = input.month
            if (month < from.yearMonth || month > to.yearMonth) return@forEach
            val start = maxOf(from, month.firstDay)
            val end = minOf(to, month.lastDay)
            val inside = items(input, pay, lastDay(month, today)).filter { it.date in start..end }
            parts += PeriodPart(month, start, end, inside.sumOf { it.amount }, pay.tier, start == month.firstDay && end == month.lastDay)
            lines += SalaryMonthRules.breakdown(input, pay, inside)
            adjustments += inside.filter { it.kind == DayItemKind.ADJUSTMENT }.map { month to it }
            typeOrder = input.types.withIndex().associate { it.value.id to it.index }
        }
        return SalaryPeriod(from, to, parts, merge(lines, typeOrder), adjustments)
    }

    /** Одинаковые строки разбивки разных месяцев складываются; порядок — как в разбивке месяца. */
    private fun merge(lines: List<BreakdownLine>, typeOrder: Map<String, Int>): List<BreakdownLine> {
        val merged = LinkedHashMap<BreakdownLine, BreakdownLine>()
        lines.forEach { line ->
            val key = line.copy(count = 0, amount = 0, children = 0, gross = 0, minutes = 0, sptClosed = if (line.sptClosed > 0) 1 else 0)
            val old = merged[key]
            merged[key] = if (old == null) {
                line
            } else {
                old.copy(
                    count = old.count + line.count,
                    amount = old.amount + line.amount,
                    children = old.children + line.children,
                    gross = old.gross + line.gross,
                    minutes = old.minutes + line.minutes,
                    sptClosed = old.sptClosed + line.sptClosed,
                )
            }
        }
        return merged.values.sortedWith(SalaryMonthRules.breakdownOrder(typeOrder))
    }
}
