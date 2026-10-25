package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// Счётчик ПТ, ступень месяца и оплата одной тренировки (ТЗ 4.6, 4.7). Деньги — копейки, длительности — минуты.
// Деление групп с коллегами (4.8) и доплата за закрытие СПТ (4.9) — шаги 3.3 и 3.4: здесь группа — p × ставка,
// СПТ — базовая ставка.

/** Что расчёту нужно знать о типе тренировки (ТЗ 3.3). */
data class PayType(
    val id: String,
    val kind: TrainingKind,
    val payUnit: PayUnit,
    val countsTowardPt: Boolean,
    val tierDependent: Boolean,
    val splitFallbackTypeId: String? = null,
)

/** Версия ставки тренера (ТЗ 3.3). [locationId] пусто — для любого места. */
data class PayRate(
    val typeId: String,
    val locationId: String?,
    val validFrom: LocalDate,
    val amountT1: Long,
    val amountT2: Long? = null,
    val amountT3: Long? = null,
) {
    /** Ставка ступени [tier]; у ступени нет своей суммы — сумма ступени ниже. */
    fun amount(tier: Int): Long = when (tier) {
        3 -> amountT3 ?: amountT2 ?: amountT1
        2 -> amountT2 ?: amountT1
        else -> amountT1
    }
}

/** Версия ступеней (ТЗ 3.3, TierSchemeVersion): с 1-го числа месяца. */
data class TierScheme(
    val validFrom: LocalDate,
    val enabled: Boolean = true,
    val t2From: Int = 101,
    val t3From: Int = 151,
    val ptTypeIds: Set<String>,
)

/** Тренировка для расчёта: [start] — локальное время клуба, [clients] — статусы участия её клиентов. */
data class PayTraining(
    val id: String,
    val typeId: String,
    val locationId: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val status: TrainingStatus,
    val clients: List<ParticipationStatus>,
    val splitHalf: Boolean = false,
)

/** Пометка сплита с одним клиентом (ТЗ 4.7): в календаре он остаётся сплитом. */
enum class SplitMark { AS_PT, HALF }

/** Оплата одной тренировки: сумма, пометка сплита и «нет ставки» (0 ₽ и пункт в «Нужно сделать»). */
data class TrainingPay(
    val trainingId: String,
    val amount: Long,
    val paidClients: Int,
    val splitMark: SplitMark? = null,
    val rateMissing: Boolean = false,
)

/** Деньги месяца по 4.6: счётчик и ступень по проведённым и прогнозные, заработок по текущей ступени, прогноз — по прогнозной. */
data class MonthPay(
    val month: YearMonth,
    val ptCount: Int,
    val tier: Int,
    val forecastPtCount: Int,
    val forecastTier: Int,
    val earned: Long,
    val forecast: Long,
    val trainings: List<TrainingPay>,
) {
    /** Тренировки без ставки — пункты «Нужно сделать». */
    val missingRate: List<String> get() = trainings.filter { it.rateMissing }.map { it.trainingId }
}

object PayRules {

    /** Оплаченный клиент (ТЗ 4.6): пришёл или отменил со списанием. */
    fun isPaid(status: ParticipationStatus): Boolean =
        status == ParticipationStatus.ATTENDED || status == ParticipationStatus.CANCELLED_CHARGED

    /**
     * p — число оплаченных клиентов. В прогнозе ([forecast]) запланированная тренировка считается состоявшейся:
     * её ожидаемые клиенты — тоже оплаченные (4.6, 4.12).
     */
    fun paidClients(training: PayTraining, forecast: Boolean = false): Int = training.clients.count {
        isPaid(it) || (forecast && training.status == TrainingStatus.PLANNED && it == ParticipationStatus.EXPECTED)
    }

    /** Тренировки месяца [month] — по дате начала в поясе клуба. */
    fun inMonth(trainings: List<PayTraining>, month: YearMonth): List<PayTraining> =
        trainings.filter { it.start.date.yearMonth == month }

    /**
     * Какие типы считаются как ПТ в месяце: список версии ступеней; версии ещё нет (ступени появляются с первым
     * открытием «Зарплаты», 3.3) — по отметкам «Считать как ПТ» типов.
     */
    fun ptTypeIds(scheme: TierScheme?, types: List<PayType>): Set<String> =
        scheme?.ptTypeIds ?: types.filter { it.countsTowardPt }.map { it.id }.toSet()

    /**
     * Счётчик N (ТЗ 4.6): тренировки из [trainings] с типом из [ptTypeIds] и хотя бы одним оплаченным клиентом.
     * Сплит с одним или двумя клиентами — 1, место не важно. В прогнозе — ещё запланированные с ожидаемыми клиентами.
     */
    fun ptCount(trainings: List<PayTraining>, ptTypeIds: Set<String>, forecast: Boolean = false): Int =
        trainings.count { it.typeId in ptTypeIds && paidClients(it, forecast) > 0 }

    /** Ступень по счётчику [n] (ТЗ 4.6): выключенная схема или её нет — всегда 1. */
    fun tier(scheme: TierScheme?, n: Int): Int = when {
        scheme == null || !scheme.enabled -> 1
        n >= scheme.t3From -> 3
        n >= scheme.t2From -> 2
        else -> 1
    }

    /**
     * Оплата одной тренировки по ступени месяца [tier] (ТЗ 4.7). Ставка — по типу, месту и дню тренировки (3.3),
     * ступень — только у типов с `tier_dependent`. Округление — до копейки по каждой тренировке, половина вверх.
     * Дежурство и `OTHER` без клиентов оплачиваются, когда проведены (в прогнозе — когда не отменены).
     */
    fun pay(
        training: PayTraining,
        types: Map<String, PayType>,
        rates: List<PayRate>,
        tier: Int,
        forecast: Boolean = false,
    ): TrainingPay {
        val type = types[training.typeId]
        val p = paidClients(training, forecast)
        val happened = when (training.status) {
            TrainingStatus.DONE -> true
            TrainingStatus.PLANNED -> forecast
            TrainingStatus.CANCELLED -> false
        }
        fun result(amount: Long, mark: SplitMark? = null) = TrainingPay(training.id, amount, p, mark)
        val missing = TrainingPay(training.id, 0, p, rateMissing = true)
        if (type == null) return missing

        fun amountOf(of: PayType): Long? =
            rateOf(of.id, training, rates)?.amount(if (of.tierDependent) tier else 1)

        return when (type.kind) {
            TrainingKind.PERSONAL, TrainingKind.SPT ->
                if (p == 0) result(0) else amountOf(type)?.let { result(it) } ?: missing
            TrainingKind.SPLIT -> when {
                p == 0 -> result(0)
                p >= 2 -> amountOf(type)?.let { result(it) } ?: missing
                training.splitHalf -> amountOf(type)?.let { result(half(it), SplitMark.HALF) } ?: missing
                else -> type.splitFallbackTypeId?.let { types[it] }?.let(::amountOf)
                    ?.let { result(it, SplitMark.AS_PT) } ?: missing
            }
            TrainingKind.GROUP -> if (p == 0) result(0) else amountOf(type)?.let { result(p * it) } ?: missing
            TrainingKind.DUTY -> if (!happened) result(0) else amountOf(type)?.let { result(perHour(it, training.durationMin)) } ?: missing
            TrainingKind.OTHER -> {
                val due = if (training.clients.isEmpty()) happened else p > 0
                if (!due) return result(0)
                val rate = amountOf(type) ?: return missing
                result(
                    when (type.payUnit) {
                        PayUnit.PER_SESSION -> rate
                        PayUnit.PER_CLIENT -> p * rate
                        PayUnit.PER_HOUR -> perHour(rate, training.durationMin)
                    },
                )
            }
        }
    }

    /**
     * Деньги месяца [month] (ТЗ 4.6): ступени берутся из версии, действующей 1-го числа; итоговая ступень идёт на все
     * тренировки месяца с `tier_dependent`, а не только на те, что сверх порога.
     */
    fun month(
        month: YearMonth,
        trainings: List<PayTraining>,
        types: List<PayType>,
        rates: List<PayRate>,
        schemes: List<TierScheme>,
    ): MonthPay {
        val ofMonth = inMonth(trainings, month)
        val scheme = Versions.activeIn(schemes, month) { it.validFrom }
        val ptIds = ptTypeIds(scheme, types)
        val n = ptCount(ofMonth, ptIds)
        val nForecast = ptCount(ofMonth, ptIds, forecast = true)
        val tier = tier(scheme, n)
        val forecastTier = tier(scheme, nForecast)
        val byId = types.associateBy { it.id }
        val pays = ofMonth.map { pay(it, byId, rates, tier) }
        val forecast = ofMonth.sumOf { pay(it, byId, rates, forecastTier, forecast = true).amount }
        return MonthPay(month, n, tier, nForecast, forecastTier, pays.sumOf { it.amount }, forecast, pays)
    }

    private fun rateOf(typeId: String, training: PayTraining, rates: List<PayRate>): PayRate? =
        Versions.rateFor(rates, typeId, training.locationId, training.start.date, { it.typeId }, { it.locationId }, { it.validFrom })

    /** Половина ставки, половина копейки — вверх: 1 550 ₽ → 775 ₽, 1 ₽ 01 коп. → 51 коп. */
    fun half(amount: Long): Long = roundDiv(amount, 2)

    /** Оплата по часам: минуты / 60 × ставка за час, до копейки, половина вверх. */
    fun perHour(ratePerHour: Long, minutes: Int): Long = roundDiv(ratePerHour * minutes, 60)

    private fun roundDiv(a: Long, b: Long): Long = (a * 2 + b) / (b * 2)
}
