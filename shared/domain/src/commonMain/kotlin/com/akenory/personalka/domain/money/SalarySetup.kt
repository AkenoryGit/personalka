package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Мастер «Настроить зарплату» и зарплатная часть «Профиля» (ТЗ 5.18, 5.16, 4.11): ставки (пример или свои), схема
// выплат, ступени, дни выплат. Что пишется версиями — здесь; запись — SalarySetupRepository.

/** Тип, для которого в мастере есть ставка-пример (ТЗ 3.3): какой тип тренера она заполняет. */
enum class ExampleService {
    /** ПТ — тип, в который превращается сплит. */
    PT,

    /** Сплит — первый действующий тип вида «сплит». */
    SPLIT,

    /** Грудничковое — тип с названием «Грудничковое…». */
    INFANT,
}

/**
 * Ставки-пример для мастера (решение Дмитрия 28.10.2026, шаг 5.3а): у приложения нет ставок какого-то клуба и
 * категорий тренера — тренер вписывает свои или берёт пример и правит. Пример одинаков для всех мест тренера и
 * помечен «сверьте со своим расчётным листком»; ступени 1–3 (101 и 151 ПТ — тоже пример).
 */
object ExampleRates {
    private fun rub(t1: Long, t2: Long, t3: Long) = RateAmounts(t1 * 100, t2 * 100, t3 * 100)

    /** Пример ставки для услуги, одинаковый для любого места. */
    fun amounts(service: ExampleService): RateAmounts = when (service) {
        ExampleService.PT -> rub(920, 960, 1000)
        ExampleService.SPLIT -> rub(1550, 1600, 1650)
        ExampleService.INFANT -> rub(670, 700, 730)
    }

    /** Для какой услуги есть пример у типа: ПТ — тип [ptTypeId], сплит — вид «сплит», грудничковое — по названию. */
    fun service(typeId: String, name: String, isSplit: Boolean, ptTypeId: String?): ExampleService? = when {
        typeId == ptTypeId -> ExampleService.PT
        isSplit -> ExampleService.SPLIT
        name.trim().lowercase().startsWith("грудничк") -> ExampleService.INFANT
        else -> null
    }
}

/** Версия схемы выплат из базы с её id. */
data class SavedScheme(val id: String, val scheme: PayScheme)

/** Что делать с версией после «Сохранить»: ничего, править версию той же даты или добавить новую. */
enum class VersionEdit { NONE, REPLACE, ADD }

/** Что выбрано в мастере: всё пишется одной записью после «Готово». */
data class SalarySetupDraft(
    val scheme: PaySchemeKind,
    /** Ожидаемый аванс, копейки; только у схемы с официальной частью. */
    val advance: Long?,
    /** С какого месяца действует [scheme]; до него — «Вся сумма, без аванса» (4.11). */
    val schemeFrom: YearMonth,
    /** День аванса — только у схемы с официальной частью (4.11). */
    val advanceDay: Int? = null,
    /** Какого числа следующего месяца приходит зарплата за месяц (4.11). */
    val salaryDay: Int = PayScheme.DEFAULT_SALARY_DAY,
    /** Ставки по типу и месту (тип → место → суммы); пустые поля не пишутся. */
    val rates: Map<String, Map<String, RateAmounts>>,
    val tiers: TierDraft,
)

/** Правила мастера «Настроить зарплату» и зарплатной части «Профиля» (ТЗ 5.18, 5.16, 4.11). */
object SalarySetupRules {
    /** Пример дня аванса в подсказке поля (4.11): своего числа у приложения нет — его вписывает тренер. */
    const val ADVANCE_DAY_EXAMPLE = 25

    /** Пример дня зарплаты в подсказке поля (4.11). */
    const val SALARY_DAY_EXAMPLE = PayScheme.DEFAULT_SALARY_DAY

    /**
     * Мастер виден, пока у тренера нет схемы выплат: «Готово» пишет её последней, прерванный мастер начнётся заново
     * (шаг 5.3а; до него признаком была категория тренера, а категорий больше нет).
     */
    fun needed(schemes: List<*>): Boolean = schemes.isEmpty()

    /** День месяца из поля: число 1–31, иначе `null` (31-е в коротком месяце — последний день, 4.11). */
    fun day(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..31 }

    /** Ошибка поля дня: пусто или не число 1–31. */
    fun dayProblem(text: String): String? = if (day(text) == null) "Число месяца от 1 до 31" else null

    /**
     * Схемы в выборе (решение Дмитрия 25.10.2026, 4.11): у всех — «Вся сумма, без аванса» (`NONE`); «Официально
     * с налогом 6,5 %» (`HALF_OFFICIAL`) — только если возможность открыта у аккаунта на сегодня (1.5). «Полностью
     * официально» и «ИП» новой версией не выбираются: прежние версии с ними считаются как раньше.
     */
    fun schemes(open: Set<Feature>): List<PaySchemeKind> =
        listOfNotNull(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL.takeIf { Feature.HALF_OFFICIAL in open })

    /** Шаг «Схема выплат» в мастере — только когда есть из чего выбирать (10.7); иначе схема — `NONE`. */
    fun schemeStep(open: Set<Feature>): Boolean = schemes(open).size > 1

    /**
     * Виды оплаты вообще (решение Дмитрия 27.10.2026): есть, только пока в админке открыта «С налогом 6,5 %». Закрыта —
     * в приложении ни одного упоминания схем (своей, коллег, налога и аванса), всё считается «100 % 10-го»
     * ([plain]); сохранённые версии не стираются и вернутся, если возможность снова откроют.
     */
    fun payTypes(open: Set<Feature>): Boolean = Feature.HALF_OFFICIAL in open

    /** Схема при закрытых видах оплаты: вся сумма по ставкам одной выплатой 10-го, без налога и аванса. */
    fun plain(scheme: PayScheme): PayScheme = PayScheme(scheme.validFrom, PaySchemeKind.NONE, salaryDay = scheme.salaryDay)

    /** Строка схемы в «Профиле» — только с открытыми видами оплаты (27.10.2026; до этого — и со старой схемой). */
    fun schemeRow(open: Set<Feature>): Boolean = payTypes(open)

    /**
     * Версии схемы, которые пишет «Готово» мастера: схема [kind] с месяца [from]; если он позже месяца [start],
     * с [start] до него — «Вся сумма, без аванса»: так задаётся переход на новую систему оплаты. «Вся сумма» —
     * всегда с [start].
     */
    fun wizardSchemes(
        start: LocalDate,
        kind: PaySchemeKind,
        advance: Long?,
        from: YearMonth,
        salaryDay: Int = PayScheme.DEFAULT_SALARY_DAY,
        advanceDay: Int? = null,
    ): List<Pair<YearMonth, PayScheme>> {
        val first = YearMonth(start.year, start.month)
        val none = first to scheme(start, PaySchemeKind.NONE, null, salaryDay = salaryDay)
        val chosen = from to scheme(from.firstDay, kind, advance, advanceDay, salaryDay)
        return when {
            kind == PaySchemeKind.NONE -> listOf(none)
            from > first -> listOf(none, chosen)
            else -> listOf(chosen)
        }
    }

    /** У схемы есть официальная часть — значит, аванс, поля «День аванса» и «Ожидаемый аванс». */
    fun hasAdvance(kind: PaySchemeKind): Boolean = Versions.officialShareBp(kind) > 0

    /**
     * Версия схемы из выбора: доля по виду; у официальной — аванс в день [advanceDay] (не вписан — пример, 25-е),
     * у ИП и без схемы — без аванса; зарплата — [salaryDay] числа следующего месяца.
     */
    fun scheme(
        from: LocalDate,
        kind: PaySchemeKind,
        advance: Long?,
        advanceDay: Int? = null,
        salaryDay: Int = PayScheme.DEFAULT_SALARY_DAY,
    ): PayScheme {
        val official = hasAdvance(kind)
        return PayScheme(
            validFrom = from,
            kind = kind,
            advanceDay = if (official) advanceDay ?: ADVANCE_DAY_EXAMPLE else null,
            advanceDefault = if (official) advance else null,
            salaryDay = salaryDay,
        )
    }

    /** Та же версия схемы: вид, аванс и дни выплат (5.16) — тогда «Сохранить» ничего не пишет. */
    fun sameScheme(a: PayScheme, b: PayScheme): Boolean =
        a.kind == b.kind && a.advanceDefault == b.advanceDefault && a.advanceDay == b.advanceDay && a.salaryDay == b.salaryDay

    /**
     * С какого дня действует всё, что пишет мастер: с 1 января текущего года, как первая ставка (5.16), — тогда
     * и тренировки, записанные до настройки, считаются по схеме, ставкам и ступеням.
     */
    fun start(today: LocalDate): LocalDate = LocalDate(today.year, 1, 1)

    /** Месяц первой версии ступеней из мастера — январь текущего года; ступени уже есть — текущий месяц. */
    fun tierMonth(hasTiers: Boolean, today: LocalDate): YearMonth =
        if (hasTiers) RateRules.defaultTierMonth(today) else YearMonth(today.year, 1)

    /**
     * Сумма в форме ставок мастера: уже внесённая ставка на сегодня главнее примера — мастер не затирает то, что
     * тренер внёс в «Ставках»; нет ни той, ни другой (или тренер выбрал «Вписать свои») — поле пустое.
     */
    fun prefill(own: RateAmounts?, example: RateAmounts?): RateAmounts? = own ?: example

    /**
     * Что сделать с версией по дате [date]: совпадает с действующей — ничего; у действующей та же дата — править её
     * (двух версий с одной датой нет); иначе — новая версия.
     */
    fun <T> edit(versions: List<T>, date: LocalDate, validFrom: (T) -> LocalDate, same: (T) -> Boolean): VersionEdit {
        val active = Versions.activeOn(versions, date, validFrom)
        return when {
            active != null && same(active) -> VersionEdit.NONE
            active != null && validFrom(active) == date -> VersionEdit.REPLACE
            else -> VersionEdit.ADD
        }
    }

    /** Схема выплат начинается с 1-го числа месяца (3.2). */
    fun schemeFrom(month: YearMonth): LocalDate = month.firstDay

    /**
     * Схему [kind] можно выбрать в «Профиле»: из списка [schemes]. Действующая «налог 6,5 %» с кончившимся
     * сроком остаётся в прошлых месяцах (4.11), но новой версией её не выбрать.
     */
    fun selectable(kind: PaySchemeKind, open: Set<Feature>): Boolean = kind in schemes(open)
}
