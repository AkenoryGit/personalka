package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// Счётчик ПТ, ступень месяца, оплата одной тренировки (ТЗ 4.6, 4.7) и деление групп с коллегами (4.8).
// Деньги — копейки, длительности — минуты. СПТ здесь — базовая ставка, доплата за закрытие (4.9) — SptRules.
// Итоги месяца (4.10) — здесь же, выплаты (4.11) — Payouts, план и «не хватает K ПТ» (4.12) — SalaryPlan.

/** Что расчёту нужно знать о типе тренировки (ТЗ 3.3). */
data class PayType(
    val id: String,
    val kind: TrainingKind,
    val payUnit: PayUnit,
    val countsTowardPt: Boolean,
    val tierDependent: Boolean,
    val splitFallbackTypeId: String? = null,
)

/** Версия ставки тренера (ТЗ 3.3). [locationId] пусто — для любого места; [sptCloseBonus] — только у СПТ. */
data class PayRate(
    val typeId: String,
    val locationId: String?,
    val validFrom: LocalDate,
    val amountT1: Long,
    val amountT2: Long? = null,
    val amountT3: Long? = null,
    val sptCloseBonus: Long? = null,
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

/**
 * Версия схемы выплат владельца группы (ТЗ 3.2, 3.6) — для его доли налога w (4.8). У коллеги хранится только вид
 * схемы — [of]; у тренера доля и НДФЛ берутся из его PaySchemeVersion.
 */
data class OwnerScheme(
    val validFrom: LocalDate,
    val officialShareBp: Int,
    val ndflBp: Int = Versions.NDFL_BP,
) {
    companion object {
        fun of(validFrom: LocalDate, kind: PaySchemeKind) = OwnerScheme(validFrom, Versions.officialShareBp(kind))
    }
}

/** Схемы владельцев групп: [self] — тренера, [colleagues] — по id коллеги. Нет версии на дату — w = 0. */
data class OwnerSchemes(
    val self: List<OwnerScheme> = emptyList(),
    val colleagues: Map<String, List<OwnerScheme>> = emptyMap(),
)

/**
 * Тренировка для расчёта: [start] — локальное время клуба, [clients] — статусы участия её клиентов.
 * [owner] / [ownerColleagueId] — на ком числится группа, [conductedBy] / [colleagueId] — кто провёл (ТЗ 3.4, 4.8);
 * учитываются только у групп. [groupId] — группа тренировки: по ней прогноз берёт ожидаемое число детей (4.12).
 * [coverFor] — за кого дежурство (3.5): в разбивке месяца такие дежурства идут отдельными строками (4.10).
 */
data class PayTraining(
    val id: String,
    val typeId: String,
    val locationId: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val status: TrainingStatus,
    val clients: List<ParticipationStatus>,
    val splitHalf: Boolean = false,
    val owner: Owner = Owner.SELF,
    val ownerColleagueId: String? = null,
    val conductedBy: ConductedBy = ConductedBy.SELF,
    val colleagueId: String? = null,
    val groupId: String? = null,
    val coverFor: String? = null,
)

/** Доплата ([amount] больше нуля) или удержание месяца (ТЗ 3.6); [inClubAccrual] — входит в начисление клуба. */
data class PayAdjustment(val amount: Long, val inClubAccrual: Boolean = true)

/** Пометка сплита с одним клиентом (ТЗ 4.7): в календаре он остаётся сплитом. */
enum class SplitMark { AS_PT, HALF }

/**
 * Оплата одной тренировки: [amount] — G по 4.7, пометка сплита и «нет ставки» (0 ₽ и пункт в «Нужно сделать»).
 * По 4.8: [earned] — заработано мной, [accrued] — начислено мне клубом, [settlement] — взаиморасчёт с коллегой
 * [colleagueId]: больше нуля — коллега отдаёт мне, меньше — я коллеге. Не у групп — всё моё: G, G, 0.
 */
data class TrainingPay(
    val trainingId: String,
    val amount: Long,
    val paidClients: Int,
    val splitMark: SplitMark? = null,
    val rateMissing: Boolean = false,
    val earned: Long = amount,
    val accrued: Long = amount,
    val colleagueId: String? = null,
    val settlement: Long = 0,
)

/**
 * Деньги месяца по 4.6: счётчик и ступень по проведённым и прогнозные, заработок по текущей ступени, прогноз — по
 * прогнозной. [accrued] — начислено клубом (группы коллег — 0, мои группы — целиком, 4.8), [settlements] — итог
 * взаиморасчёта по каждому коллеге: больше нуля — коллега должен мне. [groupChildren] — оплаченные дети в группах,
 * которые я вёл (один или вместе), [dutyMinutes] — проведённые дежурства. [sptBonus] — доплаты за СПТ, закрытые
 * покупкой в этом месяце (4.9): входят в заработано, начислено и прогноз. [adjustments] — доплаты и удержания месяца
 * (4.10): все — в заработано и прогноз, с `in_club_accrual` — ещё и в начислено. [forecastTrainings],
 * [forecastAccrued] и [forecastSettlements] — то же по прогнозной ступени, будто все запланированные состоялись:
 * из них «Если всё пройдёт по расписанию — ≈ …» на руки (5.12).
 */
data class MonthPay(
    val month: YearMonth,
    val ptCount: Int,
    val tier: Int,
    val forecastPtCount: Int,
    val forecastTier: Int,
    val earned: Long,
    val forecast: Long,
    val trainings: List<TrainingPay>,
    val accrued: Long = earned,
    val settlements: Map<String, Long> = emptyMap(),
    val groupChildren: Int = 0,
    val dutyMinutes: Int = 0,
    val sptBonus: Long = 0,
    val adjustments: Long = 0,
    val forecastTrainings: List<TrainingPay> = trainings,
    val forecastAccrued: Long = accrued,
    val forecastSettlements: Map<String, Long> = settlements,
) {
    /** Взаиморасчёт S (4.10): мне должны минус я должен, по всем коллегам. */
    val settlement: Long get() = settlements.values.sum()

    /** Взаиморасчёт по прогнозу. */
    val forecastSettlement: Long get() = forecastSettlements.values.sum()

    /** Тренировки без ставки — пункты «Нужно сделать». */
    val missingRate: List<String> get() = trainings.filter { it.rateMissing }.map { it.trainingId }
}

object PayRules {

    /** Оплаченный клиент (ТЗ 4.6): пришёл или отменил со списанием. */
    fun isPaid(status: ParticipationStatus): Boolean =
        status == ParticipationStatus.ATTENDED || status == ParticipationStatus.CANCELLED_CHARGED

    /**
     * p — число оплаченных клиентов. В прогнозе ([forecast]) запланированная тренировка считается состоявшейся:
     * её ожидаемые клиенты — тоже оплаченные (4.6, 4.12). [expected] — ожидаемое число детей запланированной группы
     * ([expectedChildren]): с ним в прогнозе p — оно, а не состав.
     */
    fun paidClients(training: PayTraining, forecast: Boolean = false, expected: Int? = null): Int {
        val planned = forecast && training.status == TrainingStatus.PLANNED
        if (planned && expected != null) return expected
        return training.clients.count { isPaid(it) || (planned && it == ParticipationStatus.EXPECTED) }
    }

    /**
     * Ожидаемое число детей запланированной группы (ТЗ 4.12): среднее оплаченных по последним 4 проведённым
     * тренировкам этой группы до неё, до целого, половина вверх. Истории нет — `null`, и прогноз берёт состав.
     */
    fun expectedChildren(training: PayTraining, history: List<PayTraining>): Int? {
        val groupId = training.groupId ?: return null
        val last = history
            .filter { it.groupId == groupId && it.status == TrainingStatus.DONE && it.start < training.start }
            .sortedByDescending { it.start }
            .take(EXPECTED_FROM_LAST)
        if (last.isEmpty()) return null
        return roundDiv(last.sumOf { paidClients(it).toLong() }, last.size.toLong()).toInt()
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
     * Группа делится с коллегами по 4.8 ([share]); G считается по ставкам тренера.
     */
    fun pay(
        training: PayTraining,
        types: Map<String, PayType>,
        rates: List<PayRate>,
        tier: Int,
        forecast: Boolean = false,
        owners: OwnerSchemes = OwnerSchemes(),
        expected: Int? = null,
    ): TrainingPay {
        val type = types[training.typeId]
        val p = paidClients(training, forecast, expected)
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
            TrainingKind.GROUP -> if (p == 0) result(0) else amountOf(type)?.let { share(result(p * it), training, owners) } ?: missing
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
     * Доля налога владельца группы w (ТЗ 4.8) в стомиллионных: `official_share × ndfl_rate` по его схеме на день
     * тренировки. Половина официально — 6 500 000 (0,065), полностью — 13 000 000, ИП и нет схемы — 0.
     */
    fun ownerTax(training: PayTraining, owners: OwnerSchemes): Long {
        val versions = when (training.owner) {
            Owner.SELF -> owners.self
            Owner.COLLEAGUE -> training.ownerColleagueId?.let { owners.colleagues[it] }.orEmpty()
        }
        val scheme = Versions.activeOn(versions, training.start.date) { it.validFrom } ?: return 0
        return scheme.officialShareBp.toLong() * scheme.ndflBp
    }

    /**
     * Деление группы с коллегами (ТЗ 4.8). Доля каждого проводившего на руки — G × (1 − w(O)) / |C|, до копейки,
     * половина вверх. Заработано — G делённое на число проводивших, если я среди них; начислено клубом — G, если
     * группа моя. Коллега для взаиморасчёта — владелец, если группа его, иначе проводивший со мной или вместо меня.
     */
    fun share(pay: TrainingPay, training: PayTraining, owners: OwnerSchemes): TrainingPay {
        val g = pay.amount
        val mine = training.owner == Owner.SELF
        val conductors = if (training.conductedBy == ConductedBy.SELF_AND_COLLEAGUE) 2L else 1L
        val iConducted = training.conductedBy != ConductedBy.COLLEAGUE
        val handShare = roundDiv(g * (TAX_ONE - ownerTax(training, owners)), TAX_ONE * conductors)
        val earned = if (iConducted) roundDiv(g, conductors) else 0
        val settlement = when {
            mine && training.conductedBy == ConductedBy.SELF -> 0
            mine -> -handShare
            iConducted -> handShare
            else -> 0
        }
        val colleague = if (mine) training.colleagueId else training.ownerColleagueId
        return pay.copy(
            earned = earned,
            accrued = if (mine) g else 0,
            colleagueId = colleague.takeIf { settlement != 0L },
            settlement = settlement,
        )
    }

    /**
     * Деньги месяца [month] (ТЗ 4.6): ступени берутся из версии, действующей 1-го числа; итоговая ступень идёт на все
     * тренировки месяца с `tier_dependent`, а не только на те, что сверх порога. Группы делятся по 4.8 со схемами
     * владельцев [owners]; взаиморасчёт по коллеге — всё, что он должен мне, минус всё, что я должен ему.
     * Доплаты за закрытие СПТ [spt] идут в месяц покупки (4.9), доплаты и удержания [adjustments] — этого месяца (4.10).
     * В прогнозе запланированная группа ждёт столько детей, сколько в среднем было на её последних 4 проведённых
     * (4.12): поэтому в [trainings] можно передавать и прошлые месяцы — считаются только тренировки [month].
     */
    fun month(
        month: YearMonth,
        trainings: List<PayTraining>,
        types: List<PayType>,
        rates: List<PayRate>,
        schemes: List<TierScheme>,
        owners: OwnerSchemes = OwnerSchemes(),
        spt: List<SptCase> = emptyList(),
        adjustments: List<PayAdjustment> = emptyList(),
    ): MonthPay {
        val ofMonth = inMonth(trainings, month)
        val scheme = Versions.activeIn(schemes, month) { it.validFrom }
        val ptIds = ptTypeIds(scheme, types)
        val n = ptCount(ofMonth, ptIds)
        val nForecast = ptCount(ofMonth, ptIds, forecast = true)
        val tier = tier(scheme, n)
        val forecastTier = tier(scheme, nForecast)
        val byId = types.associateBy { it.id }
        val pays = ofMonth.map { pay(it, byId, rates, tier, owners = owners) }
        val sptBonus = SptRules.bonusIn(month, spt, rates)
        val adjusted = adjustments.sumOf { it.amount }
        val adjustedInClub = adjustments.filter { it.inClubAccrual }.sumOf { it.amount }
        val kindOf = { t: PayTraining -> byId[t.typeId]?.kind }
        val forecastPays = ofMonth.map {
            val expected = if (it.status == TrainingStatus.PLANNED && kindOf(it) == TrainingKind.GROUP) expectedChildren(it, trainings) else null
            pay(it, byId, rates, forecastTier, forecast = true, owners = owners, expected = expected)
        }
        val forecast = sptBonus + adjusted + forecastPays.sumOf { it.earned }
        val groupChildren = ofMonth.zip(pays)
            .filter { (t, _) -> kindOf(t) == TrainingKind.GROUP && t.conductedBy != ConductedBy.COLLEAGUE }
            .sumOf { (_, pay) -> pay.paidClients }
        val dutyMinutes = ofMonth
            .filter { kindOf(it) == TrainingKind.DUTY && it.status == TrainingStatus.DONE }
            .sumOf { it.durationMin }
        return MonthPay(
            month, n, tier, nForecast, forecastTier, pays.sumOf { it.earned } + sptBonus + adjusted, forecast, pays,
            accrued = pays.sumOf { it.accrued } + sptBonus + adjustedInClub,
            settlements = settlementsOf(pays),
            groupChildren = groupChildren,
            dutyMinutes = dutyMinutes,
            sptBonus = sptBonus,
            adjustments = adjusted,
            forecastTrainings = forecastPays,
            forecastAccrued = forecastPays.sumOf { it.accrued } + sptBonus + adjustedInClub,
            forecastSettlements = settlementsOf(forecastPays),
        )
    }

    /** Взаиморасчёт по каждому коллеге: всё, что он должен мне, минус всё, что я должен ему. */
    private fun settlementsOf(pays: List<TrainingPay>): Map<String, Long> = pays.filter { it.colleagueId != null }
        .groupBy { it.colleagueId!! }
        .mapValues { (_, list) -> list.sumOf { it.settlement } }

    private fun rateOf(typeId: String, training: PayTraining, rates: List<PayRate>): PayRate? =
        Versions.rateFor(rates, typeId, training.locationId, training.start.date, { it.typeId }, { it.locationId }, { it.validFrom })

    /** Половина ставки, половина копейки — вверх: 1 550 ₽ → 775 ₽, 1 ₽ 01 коп. → 51 коп. */
    fun half(amount: Long): Long = roundDiv(amount, 2)

    /** Оплата по часам: минуты / 60 × ставка за час, до копейки, половина вверх. */
    fun perHour(ratePerHour: Long, minutes: Int): Long = roundDiv(ratePerHour * minutes, 60)

    /** Единица доли налога [ownerTax]: десятитысячные доли × десятитысячные НДФЛ. */
    private const val TAX_ONE = 100_000_000L

    /** По скольким последним проведённым тренировкам группы ждать детей в прогнозе (4.12). */
    private const val EXPECTED_FROM_LAST = 4

    /** Деление до целого, половина — от нуля (для положительных — вверх): так округляются деньги до копейки. */
    internal fun roundDiv(a: Long, b: Long): Long = if (a < 0) -roundDiv(-a, b) else (a * 2 + b) / (b * 2)
}
