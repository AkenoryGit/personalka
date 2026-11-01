package com.akenory.personalka.domain.money

import kotlinx.datetime.YearMonth

// Тренировки с оплатой напрямую от клиента (решение Дмитрия 01.11.2026, шаг 5.3с): выезды к клиенту в его бассейн и
// всё, за что клиент платит тренеру сам, сразу после тренировки. Это не касса клуба: такие тренировки не входят в
// счётчик ПТ и ступень, в «Начислено клубом», официальную часть и НДФЛ, в аванс, взаиморасчёты с коллегами и сверку
// с 1С. Считаются отдельно — по своей цене из «Ставок» (тип не зависит от ступени), тем же PayRules.pay.

/**
 * Месяц тренировок с оплатой напрямую: [input] — только такие тренировки, [pay] — их расчёт. [earned] — уже получено
 * (проведённые и отмены со списанием), [ahead] — сколько ещё ожидается по расписанию, [held] — сколько тренировок
 * оплачено, [missing] — сколько без цены.
 */
data class DirectMonth(
    val input: MonthInput,
    val pay: MonthPay,
) {
    val earned: Long get() = pay.earned
    val ahead: Long get() = (pay.scheduled - pay.earned).coerceAtLeast(0)
    val held: Int get() = pay.trainings.count { it.amount > 0 }
    val missing: Int get() = pay.missingRate.size

    /** Пусто — в этом месяце таких тренировок нет: карточка не показывается (10.7). */
    val isEmpty: Boolean get() = held == 0 && ahead == 0L && missing == 0
}

object DirectPayRules {
    /**
     * Деньги месяца [month] по тренировкам с оплатой напрямую [trainings]: без ступеней (всегда ставка 1-й ступени),
     * без схемы выплат, доплат, СПТ и доли отмен. В прогнозе запланированные считаются состоявшимися.
     */
    fun month(month: YearMonth, trainings: List<PayTraining>, types: List<PayType>, rates: List<PayRate>): DirectMonth {
        val input = MonthInput(month = month, trainings = trainings, types = types, rates = rates, schemes = emptyList())
        return DirectMonth(input, input.pay())
    }
}
