package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

// Выплаты месяца (ТЗ 4.11), «на руки» (4.10), план и «до плана не хватает K ПТ» (4.12). Деньги — копейки.

/** Версия схемы выплат тренера (ТЗ 3.2, PaySchemeVersion): действует с 1-го числа месяца. */
data class PayScheme(
    val validFrom: LocalDate,
    val kind: PaySchemeKind,
    val officialShareBp: Int = Versions.officialShareBp(kind),
    val ndflBp: Int = Versions.NDFL_BP,
    val advanceDay: Int? = null,
    val advanceDefault: Long? = null,
    val salaryDay: Int = DEFAULT_SALARY_DAY,
) {
    /** Та же версия как схема владельца группы — для доли налога w (4.8). */
    fun owner(): OwnerScheme = OwnerScheme(validFrom, officialShareBp, ndflBp)

    companion object {
        const val DEFAULT_SALARY_DAY = 10
    }
}

/**
 * Выплата месяца (ТЗ 4.11): [ADVANCE] — аванс 25-го числа месяца, [CARD_REST] — остаток на карту и [CASH] — наличные
 * 10-го следующего; [WHOLE] — вся сумма 10-го следующего (ИП, без схемы и любая схема без официальной части).
 */
enum class PayoutKind { ADVANCE, CARD_REST, CASH, WHOLE }

data class Payout(val kind: PayoutKind, val date: LocalDate, val amount: Long)

/**
 * Выплаты месяца [month] по начислению клуба [accrued] (ТЗ 4.11): официальная часть, НДФЛ (оценка — точная сумма в
 * расчётном листке), на карту, наличные, аванс и остаток на карту. [card] + [cash] — всё, что выплатит клуб; при
 * [PayoutKind.WHOLE] вся сумма в [cash]. [payouts] — по датам, для экрана и календаря выплат.
 */
data class MonthPayout(
    val month: YearMonth,
    val kind: PaySchemeKind,
    val accrued: Long,
    val official: Long,
    val ndfl: Long,
    val card: Long,
    val cash: Long,
    val advance: Long,
    val payouts: List<Payout>,
) {
    /** Остаток на карту 10-го: на карту минус аванс. */
    val cardRest: Long get() = card - advance

    /** Аванс больше суммы на карту — остаток отрицательный, с предупреждением. */
    val advanceExceedsCard: Boolean get() = cardRest < 0
}

/**
 * Аванс месяца [month] (4.11): приходит [date] (25-го), ожидается [expected] (`advance_default`), введён [actual].
 * Пока сумма не введена, с дня аванса «Главная» просит её ввести, а 25-го в 19:00 приходит уведомление (5.2, 5.19).
 */
data class AdvanceState(val month: YearMonth, val date: LocalDate, val expected: Long?, val actual: Long?) {
    /** Пора ввести фактическую сумму: сегодня день аванса или позже, а суммы нет. */
    fun ask(today: LocalDate): Boolean = actual == null && today >= date
}

/** Итоги месяца (ТЗ 4.10): заработано E, начислено клубом A, взаиморасчёт S, на руки R = карта + наличные + S. */
data class MonthTotals(
    val earned: Long,
    val accrued: Long,
    val settlement: Long,
    val onHand: Long,
    val payout: MonthPayout,
)

object Payouts {

    /** Схема выплат месяца — версия, действующая 1-го числа (4.11). Версий нет — `null`: считается как `NONE`. */
    fun scheme(schemes: List<PayScheme>, month: YearMonth): PayScheme? = Versions.activeIn(schemes, month) { it.validFrom }

    /** День аванса месяца [month] (25-го), если у схемы есть аванс; 31-го в коротком месяце — последний день. */
    fun advanceDate(month: YearMonth, scheme: PayScheme?): LocalDate? {
        val day = scheme?.advanceDay ?: return null
        if (scheme.officialShareBp == 0) return null
        return dayOf(month, day)
    }

    /** День выплаты за месяц [month] — 10-е число следующего месяца. */
    fun salaryDate(month: YearMonth, scheme: PayScheme?): LocalDate =
        dayOf(next(month), scheme?.salaryDay ?: PayScheme.DEFAULT_SALARY_DAY)

    /**
     * Выплаты месяца (4.11): официальная часть = A × доля, НДФЛ = официальная × 0,13, на карту = официальная − НДФЛ,
     * наличные = A − официальная — всё до копейки, половина вверх. Аванс — [advanceActual], а до его ввода —
     * `advance_default`; без официальной части аванса нет и вся сумма идёт 10-го одной выплатой.
     */
    fun of(month: YearMonth, accrued: Long, schemes: List<PayScheme>, advanceActual: Long? = null): MonthPayout {
        val scheme = scheme(schemes, month)
        val kind = scheme?.kind ?: PaySchemeKind.NONE
        val salary = salaryDate(month, scheme)
        val share = scheme?.officialShareBp ?: 0
        val official = PayRules.roundDiv(accrued * share, BP_ONE)
        if (official == 0L) {
            return MonthPayout(month, kind, accrued, 0, 0, 0, accrued, 0, listOf(Payout(PayoutKind.WHOLE, salary, accrued)))
        }
        val ndfl = PayRules.roundDiv(official * scheme!!.ndflBp, BP_ONE)
        val card = official - ndfl
        val cash = accrued - official
        val advanceDate = advanceDate(month, scheme)
        val advance = if (advanceDate == null) 0 else advanceActual ?: scheme.advanceDefault ?: 0
        val payouts = buildList {
            if (advanceDate != null) add(Payout(PayoutKind.ADVANCE, advanceDate, advance))
            add(Payout(PayoutKind.CARD_REST, salary, card - advance))
            if (cash != 0L) add(Payout(PayoutKind.CASH, salary, cash))
        }
        return MonthPayout(month, kind, accrued, official, ndfl, card, cash, advance, payouts)
    }

    /** Итоги месяца (4.10) по деньгам месяца [pay]: выплаты — по начисленному, на руки — выплаты клуба и взаиморасчёт. */
    fun totals(pay: MonthPay, schemes: List<PayScheme>, advanceActual: Long? = null): MonthTotals {
        val payout = of(pay.month, pay.accrued, schemes, advanceActual)
        return MonthTotals(pay.earned, pay.accrued, pay.settlement, payout.card + payout.cash + pay.settlement, payout)
    }

    /**
     * Аванс месяца [month] (4.11, 5.2, 5.19): день, ожидаемая сумма и введённая [advanceActual]. У схемы без аванса —
     * `null`: ни пункта «Нужно сделать», ни уведомления 25-го.
     */
    fun advance(month: YearMonth, schemes: List<PayScheme>, advanceActual: Long?): AdvanceState? {
        val scheme = scheme(schemes, month)
        val date = advanceDate(month, scheme) ?: return null
        return AdvanceState(month, date, scheme?.advanceDefault, advanceActual)
    }

    /** Спросить фактический аванс (4.11): сегодня день аванса месяца или позже, а сумма ещё не введена. */
    fun askAdvance(today: LocalDate, schemes: List<PayScheme>, advanceActual: Long?): Boolean {
        val month = today.yearMonth
        val date = advanceDate(month, scheme(schemes, month)) ?: return false
        return advanceActual == null && today >= date
    }

    private fun dayOf(month: YearMonth, day: Int): LocalDate = LocalDate(month.year, month.month, day.coerceIn(1, month.lastDay.day))

    private fun next(month: YearMonth): YearMonth = month.firstDay.plus(1, DateTimeUnit.MONTH).yearMonth

    private const val BP_ONE = 10_000L
}

/** Всё, из чего считаются деньги месяца ([PayRules.month]): прогноз «не хватает K ПТ» пересчитывает его с добавкой. */
data class MonthInput(
    val month: YearMonth,
    val trainings: List<PayTraining>,
    val types: List<PayType>,
    val rates: List<PayRate>,
    val schemes: List<TierScheme>,
    val owners: OwnerSchemes = OwnerSchemes(),
    val spt: List<SptCase> = emptyList(),
    val adjustments: List<PayAdjustment> = emptyList(),
) {
    fun pay(): MonthPay = PayRules.month(month, trainings, types, rates, schemes, owners, spt, adjustments)
}

/**
 * Прогноз ниже плана (4.12): не хватает [missing]; [pts] — сколько добавочных ПТ · ББ его закрывают (`null` — нет
 * ставки ПТ · ББ или не закрыть и за [SalaryPlan.MAX_EXTRA]). Если они переводят месяц на новую ступень —
 * [newTier] и [tierFrom]: с какой по счёту ПТ весь месяц пойдёт по ней.
 */
data class Shortfall(val missing: Long, val pts: Int?, val newTier: Int? = null, val tierFrom: Int? = null)

object SalaryPlan {

    /** План месяца [month]: свой, а нет — план последнего месяца до него (новый месяц предлагает план прошлого). */
    fun planFor(month: YearMonth, plans: Map<YearMonth, Long>): Long? =
        plans[month] ?: plans.filterKeys { it < month }.maxByOrNull { it.key }?.value

    /** Выполнение плана E / план в процентах, вниз до целого: 100 % — только когда план набран. Плана нет — `null`. */
    fun percent(earned: Long, plan: Long?): Int? =
        if (plan == null || plan <= 0) null else (maxOf(earned, 0) * 100 / plan).toInt()

    /**
     * «До плана не хватает X ₽ — это K ПТ» (4.12): прогноз ниже плана — X и наименьшее K добавочных ПТ типа
     * [ptTypeId] в месте [locationId], при котором прогноз с пересчётом ступени не ниже плана. Переход порога поднимает
     * весь месяц, поэтому K ищется пересчётом, а не делением X на ставку (пример 26). Добавочные ПТ считаются в день
     * [on] этого месяца — по ставке, действующей в него. Прогноз не ниже плана или плана нет — `null`.
     */
    fun shortfall(input: MonthInput, plan: Long?, ptTypeId: String, locationId: String, on: LocalDate): Shortfall? {
        require(on.yearMonth == input.month) { "День добавочных ПТ — в месяце расчёта" }
        if (plan == null) return null
        val base = input.pay()
        if (base.forecast >= plan) return null
        val missing = plan - base.forecast
        fun withExtra(k: Int): MonthPay = withExtra(input, k, ptTypeId, locationId, on).pay()
        // Добавочная ПТ прогноз не уменьшает: ступень только растёт, оплата каждой — не меньше нуля. Поэтому — бинарный поиск.
        if (withExtra(MAX_EXTRA).forecast < plan) return Shortfall(missing, null)
        var low = 0
        var high = MAX_EXTRA
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (withExtra(mid).forecast >= plan) high = mid else low = mid
        }
        val reached = withExtra(high)
        if (reached.forecastTier <= base.forecastTier) return Shortfall(missing, high)
        val scheme = Versions.activeIn(input.schemes, input.month) { it.validFrom }
        val from = scheme?.let { if (reached.forecastTier >= 3) it.t3From else it.t2From }
        return Shortfall(missing, high, reached.forecastTier, from)
    }

    /** Месяц [input] с [k] добавочными запланированными ПТ типа [ptTypeId] в месте [locationId] в день [on]. */
    fun withExtra(input: MonthInput, k: Int, ptTypeId: String, locationId: String, on: LocalDate): MonthInput = input.copy(
        trainings = input.trainings + List(k) {
            PayTraining("$EXTRA_ID$it", ptTypeId, locationId, on.atTime(LocalTime(0, 0)), EXTRA_MINUTES, TrainingStatus.PLANNED, listOf(ParticipationStatus.EXPECTED))
        },
    )

    /** Больше добавочных ПТ в месяц не ищется: столько не провести. */
    const val MAX_EXTRA = 500

    private const val EXTRA_ID = "plan-extra-"
    private const val EXTRA_MINUTES = 50
}
