package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// Экран «Зарплата» (ТЗ 5.12): вкладки «Месяц» и «Выплаты» из расчёта 4.6–4.12. Здесь только сборка того, что
// показать: сами правила — в PayRules, Payouts, SalaryPlan, SptRules и SalaryChartRules.

/** Месяц относительно сегодняшнего дня: у прошедшего нет прогноза и «не хватает», у будущего — только прогноз. */
enum class MonthPeriod { PAST, CURRENT, FUTURE }

/** Раздел разбивки месяца (4.10) в порядке экрана: в счёт ступени, стартовые, остальные, группы, дежурства. */
enum class BreakdownSection { PT, SPT, OTHER, GROUPS, DUTY }

/** Случай группы из таблицы 4.8. Группа коллеги, проведённая коллегой, в разбивку не попадает: там всё по нулям. */
enum class GroupCase { MINE_ALONE, MINE_TOGETHER, MINE_REPLACED, THEIRS_TOGETHER, THEIRS_BY_ME }

/**
 * Строка разбивки месяца (4.10). [count] — тренировок, [unit] — оплата одной (у дежурств — ставка за час),
 * [amount] — заработано по строке. У групп [children] — оплаченных детей, [gross] — G до деления, [colleagueId] —
 * с кем делится. У дежурств [minutes]; дежурство за коллегу — отдельной строкой с [date] и [coverFor].
 * [missing] — тренировки без ставки (0 ₽). Строка с [sptClosed] — доплаты за СПТ, закрытые покупкой в этом месяце.
 */
data class BreakdownLine(
    val section: BreakdownSection,
    val typeId: String? = null,
    val locationId: String? = null,
    val count: Int = 0,
    val unit: Long? = null,
    val amount: Long = 0,
    val splitMark: SplitMark? = null,
    val groupCase: GroupCase? = null,
    val colleagueId: String? = null,
    val children: Int = 0,
    val gross: Long = 0,
    val minutes: Int = 0,
    val date: LocalDate? = null,
    val coverFor: String? = null,
    val missing: Boolean = false,
    val sptClosed: Int = 0,
)

/**
 * Ещё [pts] ПТ сверх расписания переводят месяц на ступень [tier] с [from]-й ПТ: прибавка [gain] — [own] за сами
 * ПТ и остальное за то, что новая ступень идёт на весь месяц (5.12).
 */
data class TierBeyond(val pts: Int, val from: Int, val tier: Int, val gain: Long, val own: Long)

/**
 * Ступень месяца (5.12): проведено [count] и по расписанию [forecastCount] при порогах [t2From] и [t3From].
 * [toNext] — сколько ещё ПТ до порога [nextFrom] по проведённым, [perPt] — прибавка к каждой ПТ месяца на нём
 * (по ставке ПТ основного места); [beyond] — что дадут ПТ сверх расписания, если до следующего порога не больше
 * [SalaryMonthRules.MAX_BEYOND]. У прошедшего месяца подсказок нет.
 */
data class TierProgress(
    val count: Int,
    val forecastCount: Int,
    val tier: Int,
    val forecastTier: Int,
    val t2From: Int,
    val t3From: Int,
    val ptTypeIds: Set<String>,
    val nextFrom: Int? = null,
    val toNext: Int? = null,
    val perPt: Long? = null,
    val beyond: TierBeyond? = null,
)

/**
 * Всё для вкладок «Месяц» и «Выплаты» месяца [month]. [plan] — свой план месяца или план последнего месяца до него
 * (4.12), [planOwn] — план задан именно в этом месяце. [today] и [todayAhead] — заработано сегодня и ожидается ещё
 * сегодня (только у текущего месяца). [payout] — выплаты по проведённым, [forecastPayout] — если всё пройдёт по
 * расписанию. [advanceActual] — введённый аванс. [chart] — график накопления по дням, [closed] — «Месяц сверен»
 * (правки его тренировок спрашивают подтверждение, 4.1). [days] — заработанное по дням (3.7в): нажатие на день графика.
 * [colleagues] — взаиморасчёт по каждому коллеге для вкладки «Коллеги» (3.8). [todayPt] и [todayPtAhead] — ПТ
 * сегодня: проведено и ещё запланировано (счёт как у ступени, 4.6), для «Денег» на «Главной».
 */
data class SalaryMonth(
    val month: YearMonth,
    val period: MonthPeriod,
    val pay: MonthPay,
    val totals: MonthTotals,
    val forecastPayout: MonthPayout,
    val scheme: PayScheme?,
    val plan: Long?,
    val planOwn: Boolean,
    val shortfall: Shortfall?,
    val today: Long?,
    val todayAhead: Long?,
    val tiers: TierProgress?,
    val breakdown: List<BreakdownLine>,
    val advanceActual: Long?,
    val chart: SalaryChart,
    val closed: Boolean = false,
    val days: List<DayItem> = emptyList(),
    val colleagues: List<ColleagueSettlement> = emptyList(),
    val todayPt: Int? = null,
    val todayPtAhead: Int? = null,
) {
    /** «Месяц сверен» — у прошедшего и текущего: сверять будущий не с чем. */
    val canClose: Boolean get() = period != MonthPeriod.FUTURE

    val payout: MonthPayout get() = totals.payout

    /** Выполнение плана, проценты вниз до целого. */
    val percent: Int? get() = SalaryPlan.percent(pay.earned, plan)

    /** Прогноз в процентах плана. */
    val forecastPercent: Int? get() = SalaryPlan.percent(pay.forecast, plan)

    /** Прогноз есть у текущего и будущих месяцев. */
    val hasForecast: Boolean get() = period != MonthPeriod.PAST

    /** На руки, если всё пройдёт по расписанию: выплаты клуба по прогнозу и взаиморасчёт по прогнозу. */
    val forecastOnHand: Long get() = forecastPayout.card + forecastPayout.cash + pay.forecastSettlement
}

object SalaryMonthRules {

    fun period(month: YearMonth, today: LocalDate): MonthPeriod = when {
        month < today.yearMonth -> MonthPeriod.PAST
        month > today.yearMonth -> MonthPeriod.FUTURE
        else -> MonthPeriod.CURRENT
    }

    /** День, по ставкам которого считаются добавочные ПТ: сегодня, а у прошедшего и будущего месяца — его край. */
    fun calcDay(month: YearMonth, today: LocalDate): LocalDate = today.coerceIn(month.firstDay, month.lastDay)

    /**
     * Основное место для «это K ПТ» и «+40 ₽ к каждой ПТ» (4.12): где в месяце больше всего ПТ типа [ptTypeId];
     * ПТ нет — первое из [places] (порядок тренера, обычно большой бассейн).
     */
    fun ptPlace(trainings: List<PayTraining>, ptTypeId: String?, places: List<String>): String? {
        val counts = trainings.filter { it.typeId == ptTypeId && it.locationId in places }.groupingBy { it.locationId }.eachCount()
        return counts.maxByOrNull { it.value }?.key ?: places.firstOrNull()
    }

    /**
     * Месяц для экрана: деньги (4.6–4.10), выплаты (4.11), план и «не хватает» (4.12), ступень и разбивка.
     * [ptTypeId] и [ptPlaceId] — ПТ и место для добавочных ПТ; нет — строка «не хватает» без числа ПТ.
     * [settled] — отметки «Рассчитались» месяца: id коллеги → сумма, на которой отметили.
     */
    fun build(
        input: MonthInput,
        paySchemes: List<PayScheme>,
        plans: Map<YearMonth, Long>,
        advanceActual: Long?,
        today: LocalDate,
        ptTypeId: String?,
        ptPlaceId: String?,
        closed: Boolean = false,
        settled: Map<String, Long> = emptyMap(),
    ): SalaryMonth {
        val month = input.month
        val period = period(month, today)
        val pay = input.pay()
        val plan = SalaryPlan.planFor(month, plans)
        val on = calcDay(month, today)
        val shortfall = when {
            period == MonthPeriod.PAST || plan == null -> null
            ptTypeId != null && ptPlaceId != null -> SalaryPlan.shortfall(input, plan, ptTypeId, ptPlaceId, on)
            pay.forecast < plan -> Shortfall(plan - pay.forecast, null)
            else -> null
        }
        val ofToday = if (period == MonthPeriod.CURRENT) {
            PayRules.inMonth(input.trainings, month).filter { it.start.date == today }
        } else {
            null
        }
        val days = SalaryDaysRules.items(input, pay, SalaryDaysRules.lastDay(month, today))
        val todayIds = ofToday?.map { it.id }?.toSet()
        val plannedToday = ofToday?.filter { it.status == TrainingStatus.PLANNED }?.map { it.id }?.toSet()
        val ptIds = PayRules.ptTypeIds(Versions.activeIn(input.schemes, month) { it.validFrom }, input.types)
        return SalaryMonth(
            month = month,
            period = period,
            pay = pay,
            totals = Payouts.totals(pay, paySchemes, advanceActual),
            forecastPayout = Payouts.of(month, pay.forecastAccrued, paySchemes, advanceActual),
            scheme = Payouts.scheme(paySchemes, month),
            plan = plan,
            planOwn = plans.containsKey(month),
            shortfall = shortfall,
            today = todayIds?.let { ids -> pay.trainings.filter { it.trainingId in ids }.sumOf { it.earned } },
            todayAhead = plannedToday?.let { ids -> pay.forecastTrainings.filter { it.trainingId in ids }.sumOf { it.earned } },
            tiers = tierProgress(input, pay, period, ptTypeId, ptPlaceId, on),
            breakdown = breakdown(input, pay),
            advanceActual = advanceActual,
            chart = SalaryChartRules.build(input, pay, period, today, plan, days),
            closed = closed,
            days = days,
            colleagues = SettlementRules.colleagues(input, pay, settled),
            todayPt = ofToday?.let { PayRules.ptCount(it, ptIds) },
            todayPtAhead = ofToday?.let { list -> PayRules.ptCount(list.filter { it.status == TrainingStatus.PLANNED }, ptIds, forecast = true) },
        )
    }

    /** Ступень месяца; ступени выключены — `null` (вся оплата по ступени 1, полоса не нужна). */
    fun tierProgress(input: MonthInput, pay: MonthPay, period: MonthPeriod, ptTypeId: String?, placeId: String?, on: LocalDate): TierProgress? {
        val scheme = Versions.activeIn(input.schemes, input.month) { it.validFrom }
        if (scheme != null && !scheme.enabled) return null
        val t2 = scheme?.t2From ?: DEFAULT_T2
        val t3 = scheme?.t3From ?: DEFAULT_T3
        val base = TierProgress(
            pay.ptCount, pay.forecastPtCount, pay.tier, pay.forecastTier, t2, t3, PayRules.ptTypeIds(scheme, input.types),
        )
        // Без версии ступеней всё идёт по ступени 1: подсказывать переход не о чем.
        if (period == MonthPeriod.PAST || scheme == null) return base
        val rate = if (ptTypeId != null && placeId != null) {
            Versions.rateFor(input.rates, ptTypeId, placeId, on, { it.typeId }, { it.locationId }, { it.validFrom })
        } else {
            null
        }
        val tierDependent = input.types.firstOrNull { it.id == ptTypeId }?.tierDependent == true
        val nextFrom = when (pay.tier) {
            1 -> t2
            2 -> t3
            else -> null
        }
        val perPt = if (nextFrom != null && rate != null && tierDependent) rate.amount(pay.tier + 1) - rate.amount(pay.tier) else null
        val target = when (pay.forecastTier) {
            1 -> t2
            2 -> t3
            else -> null
        }
        val beyond = if (target != null && rate != null && ptTypeId != null && placeId != null) {
            val k = target - pay.forecastPtCount
            val extra = SalaryPlan.withExtra(input, k, ptTypeId, placeId, on).pay()
            val newTier = extra.forecastTier
            TierBeyond(k, target, newTier, extra.forecast - pay.forecast, k * rate.amount(if (tierDependent) newTier else 1))
                .takeIf { k in 1..MAX_BEYOND && newTier > pay.forecastTier }
        } else {
            null
        }
        return base.copy(nextFrom = nextFrom, toNext = nextFrom?.let { it - pay.ptCount }, perPt = perPt, beyond = beyond)
    }

    /**
     * Разбивка месяца (4.10) по проведённым и отменённым со списанием: строки «тип · место» с числом и ставкой,
     * группы по случаям 4.8 (только непустые), дежурства с часами, за коллег — по одному. Сумма строк, доплат СПТ
     * и доплат месяца — «Заработано». С [only] — только тренировки и доплаты СПТ из этих дней (период, 3.7в).
     */
    fun breakdown(input: MonthInput, pay: MonthPay, only: List<DayItem>? = null): List<BreakdownLine> {
        val types = input.types.associateBy { it.id }
        val typeOrder = input.types.withIndex().associate { it.value.id to it.index }
        val scheme = Versions.activeIn(input.schemes, input.month) { it.validFrom }
        val ptIds = PayRules.ptTypeIds(scheme, input.types)
        val byId = PayRules.inMonth(input.trainings, input.month).associateBy { it.id }
        val lines = LinkedHashMap<Any, BreakdownLine>()
        val single = mutableListOf<BreakdownLine>()
        val onlyTrainings = only?.mapNotNull { it.trainingId }?.toSet()
        pay.trainings.forEach { p ->
            if (onlyTrainings != null && p.trainingId !in onlyTrainings) return@forEach
            val t = byId[p.trainingId] ?: return@forEach
            val type = types[t.typeId]
            when (type?.kind) {
                TrainingKind.GROUP -> {
                    if (p.paidClients == 0) return@forEach
                    val case = groupCase(t) ?: return@forEach
                    val colleague = if (t.owner == Owner.SELF) t.colleagueId else t.ownerColleagueId
                    val key = listOf(BreakdownSection.GROUPS, case, t.typeId, t.locationId, colleague, p.rateMissing)
                    val line = lines[key] ?: BreakdownLine(
                        BreakdownSection.GROUPS, t.typeId, t.locationId, groupCase = case,
                        colleagueId = colleague.takeIf { case != GroupCase.MINE_ALONE }, missing = p.rateMissing,
                        unit = unitRate(t, type, pay.tier, input.rates),
                    )
                    lines[key] = line.copy(count = line.count + 1, children = line.children + p.paidClients, gross = line.gross + p.amount, amount = line.amount + p.earned)
                }
                TrainingKind.DUTY -> {
                    if (t.status != TrainingStatus.DONE) return@forEach
                    val unit = unitRate(t, type, pay.tier, input.rates)
                    if (t.coverFor != null) {
                        single += BreakdownLine(
                            BreakdownSection.DUTY, t.typeId, t.locationId, 1, unit, p.amount, minutes = t.durationMin,
                            date = t.start.date, coverFor = t.coverFor, missing = p.rateMissing,
                        )
                        return@forEach
                    }
                    val key = listOf(BreakdownSection.DUTY, t.typeId, t.locationId, unit, p.rateMissing)
                    val line = lines[key] ?: BreakdownLine(BreakdownSection.DUTY, t.typeId, t.locationId, unit = unit, missing = p.rateMissing)
                    lines[key] = line.copy(count = line.count + 1, minutes = line.minutes + t.durationMin, amount = line.amount + p.amount)
                }
                else -> {
                    if (p.amount == 0L && !p.rateMissing) return@forEach
                    val section = when {
                        type?.kind == TrainingKind.SPT -> BreakdownSection.SPT
                        t.typeId in ptIds -> BreakdownSection.PT
                        else -> BreakdownSection.OTHER
                    }
                    val unit = p.amount.takeIf { !p.rateMissing }
                    val key = listOf(section, t.typeId, t.locationId, unit, p.splitMark, p.rateMissing)
                    val line = lines[key] ?: BreakdownLine(section, t.typeId, t.locationId, unit = unit, splitMark = p.splitMark, missing = p.rateMissing)
                    lines[key] = line.copy(count = line.count + 1, amount = line.amount + p.earned)
                }
            }
        }
        val onlySpt = only?.mapNotNull { it.sptId }?.toSet()
        val closed = input.spt.filter {
            it.purchaseDate?.yearMonth == input.month && SptRules.bonus(it, input.rates) > 0 && (onlySpt == null || it.id in onlySpt)
        }
        val sptBonus = if (onlySpt == null) pay.sptBonus else closed.sumOf { SptRules.bonus(it, input.rates) }
        if (sptBonus != 0L) single += BreakdownLine(BreakdownSection.SPT, amount = sptBonus, sptClosed = closed.size)
        val all = lines.values + single
        return all.sortedWith(breakdownOrder(typeOrder))
    }

    /** Порядок строк разбивки: разделы, доплаты СПТ после стартовых, случаи групп, типы в порядке тренера, даты. */
    fun breakdownOrder(typeOrder: Map<String, Int>): Comparator<BreakdownLine> =
        compareBy<BreakdownLine> { it.section.ordinal }
            .thenBy { it.sptClosed }
            .thenBy { it.groupCase?.ordinal ?: 0 }
            .thenBy { typeOrder[it.typeId] ?: Int.MAX_VALUE }
            .thenBy { it.date }

    /** Случай 4.8 группы; группа коллеги, проведённая коллегой, — `null`. */
    fun groupCase(t: PayTraining): GroupCase? = when (t.owner) {
        Owner.SELF -> when (t.conductedBy) {
            ConductedBy.SELF -> GroupCase.MINE_ALONE
            ConductedBy.SELF_AND_COLLEAGUE -> GroupCase.MINE_TOGETHER
            ConductedBy.COLLEAGUE -> GroupCase.MINE_REPLACED
        }
        Owner.COLLEAGUE -> when (t.conductedBy) {
            ConductedBy.SELF_AND_COLLEAGUE -> GroupCase.THEIRS_TOGETHER
            ConductedBy.SELF -> GroupCase.THEIRS_BY_ME
            ConductedBy.COLLEAGUE -> null
        }
    }

    /** Ставка тренировки по ступени месяца: за час у дежурств, за клиента у групп. Нет ставки — `null`. */
    private fun unitRate(t: PayTraining, type: PayType?, tier: Int, rates: List<PayRate>): Long? =
        Versions.rateFor(rates, t.typeId, t.locationId, t.start.date, { it.typeId }, { it.locationId }, { it.validFrom })
            ?.amount(if (type?.tierDependent == true) tier else 1)

    /**
     * Сверенные месяцы (4.1) из записей месяцев: у месяца с двумя записями (два телефона) решает последняя
     * изменённая — записи приходят по возрастанию изменения.
     */
    fun closedMonths(records: List<Pair<YearMonth, Boolean>>): Set<YearMonth> =
        records.associate { it }.filterValues { it }.keys

    /** Дальше подсказка «ещё N ПТ сверх расписания» не показывается: столько сверх расписания не набрать. */
    const val MAX_BEYOND = 20

    /** Пороги, пока версии ступеней нет (3.3): так их предлагает форма ступеней. */
    private const val DEFAULT_T2 = 101
    private const val DEFAULT_T3 = 151
}
