package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.money.CalendarMoney
import com.akenory.personalka.domain.money.DayMoney
import com.akenory.personalka.domain.money.MonthPeriod

/** Деньги в календаре (ТЗ 5.3, шаг 5.10г): клетка дня, заголовок дня, строка месяца и «Деньги по месяцам». */
object CalendarMoneyFormat {
    private const val DOT = " · "
    private const val KOPECKS_IN_RUB = 100L

    /** Флажок в меню вида. */
    const val TOGGLE = "Деньги по дням"

    /** Заголовок окна и подписи полосы. */
    const val MONTHS_TITLE = "Деньги по месяцам"
    const val EARNED = "заработано"
    const val SCHEDULED = "по расписанию"
    const val PLAN = "план"
    const val MONTHS_HINT = "Нажмите на месяц — календарь откроется на нём."
    const val PREVIOUS_YEAR = "Предыдущий год"
    const val NEXT_YEAR = "Следующий год"

    /**
     * Сумма в клетке дня и в строке недели, без «₽» (клетка 58 dp): заработано — до рубля, «будет» — до 10 ₽, как
     * прогноз везде: «7 400», «6 440».
     */
    fun cell(day: DayMoney): String =
        if (day.forecast) MoneyFormat.amount(MoneyFormat.roundTo10Rub(day.amount)) else MoneyFormat.amount(roundToRub(day.amount))

    /**
     * Строка под заголовком дня: «заработано 5 500 ₽»; сегодня — «заработано 3 200 ₽ · будет ≈ 6 440 ₽» (будет — за
     * весь день); будущий — «будет ≈ 5 300 ₽». Денег нет — `null`.
     */
    fun dayLine(day: DayMoney?, todayEarned: Long? = null): String? {
        if (day == null) return null
        return when {
            todayEarned != null -> "$EARNED ${MoneyFormat.rub(roundToRub(todayEarned))}${DOT}будет ${MoneyFormat.approxRub(day.amount)}"
            day.forecast -> "будет ${MoneyFormat.approxRub(day.amount)}"
            else -> "$EARNED ${MoneyFormat.rub(roundToRub(day.amount))}"
        }
    }

    /**
     * Строка месяца над сеткой: «91 400 ₽ заработано · будет ≈ 146 740 ₽»; прошедший — «91 400 ₽ заработано»;
     * будущий — «будет ≈ 61 700 ₽». Первая часть — [lead] (крупнее), вторая — [rest].
     */
    fun monthLead(money: CalendarMoney): String = when (money.period) {
        MonthPeriod.FUTURE -> "будет ${MoneyFormat.approxRub(money.forecast ?: 0)}"
        else -> MoneyFormat.rub(roundToRub(money.earned))
    }

    fun monthRest(money: CalendarMoney): String = when (money.period) {
        MonthPeriod.PAST -> EARNED
        MonthPeriod.CURRENT -> "$EARNED${DOT}будет ${MoneyFormat.approxRub(money.forecast ?: money.earned)}"
        MonthPeriod.FUTURE -> SCHEDULED
    }

    /** TalkBack строки месяца: «Октябрь: 91 400 ₽ заработано · будет ≈ 146 740 ₽. Деньги по месяцам». */
    fun monthDescription(money: CalendarMoney): String =
        "${SalaryFormat.monthName(money.month)}: ${monthLead(money)} ${monthRest(money)}. $MONTHS_TITLE"

    /** Клетка дня для TalkBack: «, 7 400 ₽» или «, будет ≈ 6 440 ₽». */
    fun cellDescription(day: DayMoney?): String = when {
        day == null -> ""
        day.forecast -> ", будет ${MoneyFormat.approxRub(day.amount)}"
        else -> ", ${MoneyFormat.rub(roundToRub(day.amount))}"
    }

    /** Сумма строки месяца в «Деньгах по месяцам»: заработанное, будущий — «≈ 61 700». */
    fun rowAmount(money: CalendarMoney): String = when (money.period) {
        MonthPeriod.FUTURE -> "≈$NBSP${MoneyFormat.amount(MoneyFormat.roundTo10Rub(money.forecast ?: 0))}"
        else -> MoneyFormat.amount(roundToRub(money.earned))
    }

    /** Подпись под суммой: «будет ≈ 146 740», «по расписанию», «93 % плана» или ничего. */
    fun rowNote(money: CalendarMoney): String? = when (money.period) {
        MonthPeriod.CURRENT -> "будет ${MoneyFormat.approxRub(money.forecast ?: money.earned).removeSuffix("$NBSP₽")}"
        MonthPeriod.FUTURE -> SCHEDULED
        MonthPeriod.PAST -> money.plan?.takeIf { it > 0 }?.let { "${money.earned * 100 / it}$NBSP% плана" }
    }

    /** «Итого за 2026: 1 102 350 ₽». */
    fun yearTotal(year: Int): String = "Итого за $year"

    /** До рубля, половина — от нуля. */
    fun roundToRub(kopecks: Long): Long {
        val negative = kopecks < 0
        val abs = if (negative) -kopecks else kopecks
        val rest = abs % KOPECKS_IN_RUB
        val rounded = abs - rest + if (rest * 2 >= KOPECKS_IN_RUB) KOPECKS_IN_RUB else 0
        return if (negative) -rounded else rounded
    }
}
