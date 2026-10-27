package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.yearMonth

// Прогноз месяца с отменами (ТЗ 4.12, шаг 3.9щ) и часы работы в неделю (5.12).

/**
 * Обычная доля отмен ПТ и сплитов (4.12, шаг 3.9щ): из [total] прошедших за последние 8 недель [cancelled] не
 * принесли денег — отменены без списания. Отмены «По инициативе тренера» и «По инициативе клуба» не считаются вовсе.
 */
data class CancelShare(val cancelled: Int, val total: Int) {
    init {
        require(cancelled in 0..total) { "Отмен больше, чем тренировок" }
    }

    /** Доля в процентах, до целого, половина вверх: 8 из 40 — 20 %. */
    val percent: Int get() = if (total == 0) 0 else PayRules.roundDiv(cancelled * 100L, total.toLong()).toInt()

    /** Сколько из [n] запланированных обычно состоится: до целого, половина вверх. */
    fun keep(n: Int): Int = if (total == 0) n else PayRules.roundDiv(n.toLong() * (total - cancelled), total.toLong()).toInt()

    /** Сколько из [amount] копеек запланированных обычно будет заработано: до копейки, половина вверх. */
    fun keep(amount: Long): Long = if (total == 0) amount else PayRules.roundDiv(amount * (total - cancelled), total.toLong())
}

object CancelRules {

    /** Виды, которые отменяются клиентами по одному и поэтому идут в прогноз со скидкой: ПТ и сплиты. */
    val KINDS: Set<TrainingKind> = setOf(TrainingKind.PERSONAL, TrainingKind.SPLIT)

    /** За сколько недель до сегодня считать долю отмен. */
    const val WEEKS = 8

    /** Меньше стольких прошедших ПТ и сплитов — доли нет: по двум-трём тренировкам её не угадать. */
    const val MIN_TOTAL = 10

    /**
     * Доля отмен ПТ и сплитов тренера за [WEEKS] недель до [today] (сам день не входит: его тренировки ещё идут).
     * Прошедшая — проведённая или отменённая; отменённая без денег — ни одного оплаченного клиента (отмена со
     * списанием оплачивается, это не потеря). Отмены по инициативе тренера или клуба ([PayTraining.byUs]) не входят
     * ни в отмены, ни в число. Меньше [MIN_TOTAL] прошедших или ни одной отмены — `null`: прогноз по расписанию.
     */
    fun share(trainings: List<PayTraining>, types: List<PayType>, today: LocalDate): CancelShare? {
        val kinds = types.filter { it.kind in KINDS }.map { it.id }.toSet()
        val from = today.minus(WEEKS * 7, DateTimeUnit.DAY)
        val past = trainings.filter {
            it.typeId in kinds && it.start.date >= from && it.start.date < today &&
                it.status != TrainingStatus.PLANNED && !it.byUs
        }
        val cancelled = past.count { PayRules.paidClients(it) == 0 }
        if (past.size < MIN_TOTAL || cancelled == 0) return null
        return CancelShare(cancelled, past.size)
    }

    /**
     * Часы работы месяца (5.12, шаг 3.9щ): проведённые тренировки, которые вёл тренер (сам или вместе с коллегой),
     * дежурства тоже; пересекающиеся по времени — один раз. В неделю — на дни месяца по сегодня включительно
     * (у прошедшего — на весь месяц). У будущего месяца и без проведённых — `null`.
     */
    fun work(trainings: List<PayTraining>, month: YearMonth, today: LocalDate, earned: Long): WorkHours? {
        if (month > today.yearMonth) return null
        val days = if (month < today.yearMonth) month.lastDay.day else today.day
        val intervals = PayRules.inMonth(trainings, month)
            .filter { it.status == TrainingStatus.DONE && it.conductedBy != ConductedBy.COLLEAGUE && it.durationMin > 0 }
            .map { minuteOf(it) to minuteOf(it) + it.durationMin }
            .sortedBy { it.first }
        var minutes = 0L
        var end = Long.MIN_VALUE
        intervals.forEach { (a, b) ->
            if (b > end) {
                minutes += b - maxOf(a, end)
                end = b
            }
        }
        if (minutes == 0L) return null
        return WorkHours(
            perWeek = PayRules.roundDiv(minutes * 7, days * 60L).toInt(),
            perHour = PayRules.roundDiv(earned * 60, minutes),
        )
    }

    private fun minuteOf(t: PayTraining): Long =
        t.start.date.toEpochDays().toLong() * MINUTES_IN_DAY + t.start.hour * 60L + t.start.minute

    private const val MINUTES_IN_DAY = 24 * 60L
}

/** «Работа: 40 ч в неделю, ≈ 1 100 ₽ за час» (5.12): часов в неделю до целого и заработано за час работы в копейках. */
data class WorkHours(val perWeek: Int, val perHour: Long)
