package com.akenory.personalka.ui.widget

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.watch.WatchSlot
import com.akenory.personalka.domain.widget.WidgetDay
import com.akenory.personalka.domain.widget.WidgetRules
import com.akenory.personalka.domain.widget.WidgetSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

/**
 * Что получают виджеты (шаги 5.9, 5.10) на Android и iPhone одинаково: расписание на [DAYS] дня и «Деньги» — теми же
 * расчётами, что «Главная»; для «До получки» — выплаты прошлого месяца (по проведённым) и текущего (по расписанию).
 */
object WidgetSource {
    const val DAYS: Int = 3
    private const val MAX_SLOTS = 60

    fun day(calendar: CalendarRepository, settings: SettingsRepository, today: LocalDate, todo: Flow<Int>): Flow<WidgetDay> = combine(
        calendar.homeTrainings(today),
        settings.salary.schemes,
        settings.months.month(previous(today), today),
        settings.months.month(today.yearMonth, today),
        todo,
    ) { trainings, schemes, last, current, todoCount ->
        val set = !SalarySetupRules.needed(schemes)
        val view = current.view.takeIf { set && it.month == today.yearMonth }
        val payouts = if (set) last.view.payout.payouts + current.view.forecastPayout.payouts else emptyList()
        val until = today.plus(DAYS, DateTimeUnit.DAY)
        WidgetDay(
            date = today,
            slots = trainings
                .filter { !it.cancelled && it.start.date >= today && it.start.date < until }
                .sortedWith(compareBy({ it.start }, { it.id }))
                .take(MAX_SLOTS)
                .map { it.widget() },
            money = view?.let { WidgetRules.money(it, today, payouts) },
            todo = todoCount,
        )
    }

    private fun previous(today: LocalDate) = LocalDate(today.year, today.month, 1).minus(1, DateTimeUnit.MONTH).yearMonth

    /** Имена в виджете есть всегда (решение Дмитрия 08.11.2026): выключатель «Без имён» — только для часов. */
    private fun CalendarTraining.widget() = WidgetSlot(
        WatchSlot(id, start, durationMin, typeShort, placeShort, title, group = group, marked = marked),
        color = color,
    )
}
