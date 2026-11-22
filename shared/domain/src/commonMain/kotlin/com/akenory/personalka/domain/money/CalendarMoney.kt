package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Деньги в календаре (ТЗ 5.3, шаг 5.10г, решение Дмитрия 18.11.2026): сумма каждого дня в «Месяце» и в строке недели,
// строка месяца над сеткой и «Деньги по месяцам». Считается из того же расчёта месяца, что «Зарплата» и «Главная»:
// здесь только раскладка по дням и фильтр места. Будущий день — «если все придут» (шаг 5.34, решение Дмитрия
// 22.11.2026), «будет» месяца — со средними: обычной долей отмен и посещаемостью групп.

/**
 * Деньги дня: [amount] — копейки. [forecast] — сегодня и дальше («будет» по расписанию), иначе — заработано.
 * [tone] — день против среднего дня месяца (шаг 5.10з): слабый, обычный или сильный.
 */
data class DayMoney(val amount: Long, val forecast: Boolean, val tone: DayTone)

/**
 * Какой день по деньгам (шаг 5.10з, вариант Б, решение Дмитрия 18.11.2026): ниже 80 % среднего дня месяца — слабый
 * (серым), выше 120 % — сильный (зелёным), между — обычный. Средний — по дням месяца с деньгами, поэтому раскраска
 * сама подстраивается под заработок, без порога в рублях.
 */
enum class DayTone { WEAK, NORMAL, STRONG }

/**
 * Деньги месяца в календаре: [days] — по числу месяца (дней без денег нет), [earned] — заработано, [forecast] —
 * «будет» за месяц (у прошедшего месяца `null`), [plan] — план месяца (с фильтром места `null`: план общий).
 * [today] — заработано сегодня, [todayAhead] — сколько ещё будет сегодня (только у текущего месяца).
 */
data class CalendarMoney(
    val month: YearMonth,
    val period: MonthPeriod,
    val days: Map<Int, DayMoney>,
    val earned: Long,
    val forecast: Long?,
    val plan: Long?,
    val today: Long? = null,
    val todayAhead: Long? = null,
) {
    fun day(date: LocalDate): DayMoney? = if (date.year == month.year && date.month == month.month) days[date.day] else null

    /** Доля полосы «Деньги по месяцам» от [max]: заработано и сверху «по расписанию». */
    val total: Long get() = forecast ?: earned
}

object CalendarMoneyRules {

    /** Слабый день — ниже стольких процентов среднего, сильный — выше. */
    const val WEAK_BELOW = 80
    const val STRONG_ABOVE = 120

    /**
     * Деньги месяца [view] для календаря. [placeIds] — фильтр места (`null` — все места): тогда в днях только
     * тренировки этих мест, а доплаты СПТ и месяца, у которых места нет, не считаются.
     *
     * - Прошедший день — заработано (та же раскладка, что у графика «По дням», 3.7в).
     * - Сегодня — заработано + «будет» остатка дня, тем же расчётом, что «Сегодня» на «Главной» (`todayAhead`).
     * - Будущий день — «если все придут» (шаг 5.34): его запланированные тренировки по расписанию, по ступени
     *   «если всё пройдёт по расписанию» (`forecastTrainings`), без доли отмен; отменённые не входят, группы —
     *   средним пришедших, а тип, снятый в «Что считать в „будет“», — только отмеченными (как `shown`).
     *   Неотмеченные тренировки прошедших дней не входят ни в свой день, ни в будущие — они в «Нужно сделать».
     * - «Будет» месяца — показываемый прогноз со средними (`shown.forecast`, шаги 3.9щ, 5.10в), а не сумма дней: в днях
     *   нет доли отмен (и ступени, на которую месяц переведёт проведённые). С фильтром места — заработано и остаток
     *   сегодня этого места плюс его доля прогноза сверх них: по запланированным тренировкам (и неотмеченным прошлых
     *   дней) пропорционально их деньгам по расписанию.
     */
    fun month(view: SalaryMonth, input: MonthInput, today: LocalDate, placeIds: Set<String>? = null): CalendarMoney {
        val month = view.month
        val ofMonth = PayRules.inMonth(input.trainings, month)
        val placeOf = ofMonth.associate { it.id to it.locationId }
        fun counts(trainingId: String?): Boolean =
            placeIds == null || (trainingId != null && placeOf[trainingId] in placeIds)
        val sums = LongArray(month.lastDay.day + 1)
        view.days.forEach { item ->
            val ok = if (item.kind == DayItemKind.TRAINING) counts(item.trainingId) else placeIds == null
            if (ok) sums[item.date.day] += item.amount
        }
        val earned = sums.sum()
        val shown = view.shown
        var todayEarned: Long? = null
        var todayAhead: Long? = null
        var forecast: Long? = null
        val forecastFrom: Int // с какого числа — «будет»
        when (view.period) {
            MonthPeriod.PAST -> forecastFrom = Int.MAX_VALUE
            MonthPeriod.CURRENT, MonthPeriod.FUTURE -> {
                val current = view.period == MonthPeriod.CURRENT
                forecastFrom = if (current) today.day else 1
                val aheadAll: Long
                if (current) {
                    val planned = ofMonth.filter { it.start.date == today && it.status == TrainingStatus.PLANNED }.map { it.id }.toSet()
                    fun ahead(ids: Set<String>): Long {
                        val already = shown.trainings.filter { it.trainingId in ids }.sumOf { it.earned }
                        return (shown.plannedNow.filter { it.trainingId in ids }.sumOf { it.earned } - already).coerceAtLeast(0)
                    }
                    aheadAll = ahead(planned)
                    todayAhead = ahead(planned.filter { counts(it) }.toSet())
                    todayEarned = sums[today.day]
                    sums[today.day] += todayAhead
                } else {
                    aheadAll = 0
                }
                val scheduled = ofMonth.zip(shown.forecastTrainings).filter { (t, _) -> t.status == TrainingStatus.PLANNED }
                // Будущие дни — «если все придут»: деньги их запланированных тренировок по расписанию.
                scheduled.forEach { (t, p) ->
                    if (t.start.date.day > (if (current) today.day else 0) && counts(t.id)) sums[t.start.date.day] += p.earned
                }
                // «Будет» месяца — со средними, ровно как на «Главной» и в «Зарплате»; с фильтром — часть места.
                forecast = if (placeIds == null) {
                    shown.forecast
                } else {
                    val rest = (shown.forecast - view.pay.earned - aheadAll).coerceAtLeast(0)
                    earned + (todayAhead ?: 0) + placeShare(rest, scheduled.filter { (t, _) -> !current || t.start.date != today }, ::counts)
                }
            }
        }
        val positive = (1..month.lastDay.day).map { sums[it] }.filter { it > 0 }
        val average = if (positive.isEmpty()) 0L else positive.sum() / positive.size
        val days = (1..month.lastDay.day).mapNotNull { d ->
            val amount = sums[d]
            if (amount <= 0L) null else d to DayMoney(amount, d >= forecastFrom, tone(amount, average))
        }.toMap()
        return CalendarMoney(
            month = month,
            period = view.period,
            days = days,
            earned = earned,
            forecast = forecast,
            plan = if (placeIds == null) view.plan else null,
            today = todayEarned,
            todayAhead = todayAhead,
        )
    }

    /**
     * Доля места в [rest] — «будет» месяца сверх заработанного и остатка сегодня: [rest] делится между тренировками
     * [weights] пропорционально их деньгам по расписанию нарастающим итогом (доли всех мест вместе — ровно [rest]),
     * место берёт доли тренировок, которые [counts].
     */
    private fun placeShare(rest: Long, weights: List<Pair<PayTraining, TrainingPay>>, counts: (String?) -> Boolean): Long {
        val total = weights.sumOf { it.second.earned }
        if (total <= 0) return 0
        var acc = 0L
        var given = 0L
        var mine = 0L
        weights.sortedBy { it.first.start }.forEach { (t, p) ->
            acc += p.earned
            val upTo = rest * acc / total
            if (counts(t.id)) mine += upTo - given
            given = upTo
        }
        return mine
    }

    /** День против среднего [average]: ниже 80 % — слабый, выше 120 % — сильный. */
    fun tone(amount: Long, average: Long): DayTone = when {
        average <= 0 -> DayTone.NORMAL
        amount * 100 < average * WEAK_BELOW -> DayTone.WEAK
        amount * 100 > average * STRONG_ABOVE -> DayTone.STRONG
        else -> DayTone.NORMAL
    }
}
