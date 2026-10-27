package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Мастер «Настроить зарплату» и зарплатная часть «Профиля» (ТЗ 5.18, 5.16, 4.11): категория, схема выплат,
// ставки из сетки клуба, первая версия ступеней. Что пишется версиями — здесь; запись — SalarySetupRepository.

/** Услуга сетки клуба (`ClubRateGrid.service`, ТЗ 3.3): какой тип тренера она заполняет. */
enum class ClubService(val title: String) {
    /** Персональная — тип «ПТ» (тот, в который превращается сплит). */
    PT("Персональная"),

    /** Сплит — первый действующий тип вида «сплит». */
    SPLIT("Сплит"),

    /** Грудничковое плавание — тип с названием «Грудничковое…». */
    INFANT("Грудничковое плавание"),
}

/** Строка сетки ставок клуба: категория, услуга, короткое имя места (пусто — любое место тренера), суммы по ступеням. */
data class ClubRate(val category: CoachCategory, val service: ClubService, val placeShort: String?, val amounts: RateAmounts)

/**
 * Сетка ставок клуба (ТЗ 3.3, `scope = CLUB`) — шаблон для мастера: таблица «Мотивация тренеров» (прислана Дмитрием
 * 25.10.2026), колонки «50–100 ПТ», «101–150 ПТ», «151+ ПТ» — ступени 1–3. «Малый басс» — ДБ; ПТ и сплит в зале —
 * по ставкам ББ (3.3); у грудничкового место в таблице не указано — одна ставка для любого места.
 */
object ClubRateGrid {
    private fun r(category: CoachCategory, service: ClubService, place: String?, t1: Long, t2: Long, t3: Long) =
        ClubRate(category, service, place, RateAmounts(t1 * 100, t2 * 100, t3 * 100))

    private val T = CoachCategory.TRAINER
    private val M = CoachCategory.MASTER
    private val E = CoachCategory.ELITE
    private val PT = ClubService.PT
    private val SPLIT = ClubService.SPLIT

    private fun big(category: CoachCategory, service: ClubService, t1: Long, t2: Long, t3: Long) =
        listOf(r(category, service, "ББ", t1, t2, t3), r(category, service, "Зал", t1, t2, t3))

    val rows: List<ClubRate> =
        big(T, PT, 830, 860, 900) + big(M, PT, 920, 960, 1000) + big(E, PT, 1050, 1100, 1150) +
            big(T, SPLIT, 1400, 1450, 1500) + big(M, SPLIT, 1550, 1600, 1650) + big(E, SPLIT, 1650, 1700, 1750) +
            listOf(
                r(T, PT, "ДБ", 730, 760, 800),
                r(M, PT, "ДБ", 830, 860, 900),
                r(E, PT, "ДБ", 920, 960, 1000),
                r(T, SPLIT, "ДБ", 1200, 1250, 1320),
                r(M, SPLIT, "ДБ", 1400, 1450, 1520),
                r(E, SPLIT, "ДБ", 1550, 1600, 1620),
                r(T, ClubService.INFANT, null, 620, 640, 660),
                r(M, ClubService.INFANT, null, 670, 700, 730),
                r(E, ClubService.INFANT, null, 770, 800, 830),
            )

    /** Ставка сетки для категории, услуги и места: своя строка места, нет — строка любого места, нет и её — `null`. */
    fun amounts(category: CoachCategory, service: ClubService, placeShort: String): RateAmounts? {
        val own = rows.filter { it.category == category && it.service == service }
        return (own.firstOrNull { it.placeShort == placeShort } ?: own.firstOrNull { it.placeShort == null })?.amounts
    }

    /** У категории есть хоть одна строка сетки. */
    fun has(category: CoachCategory): Boolean = rows.any { it.category == category }

    /** Услуга сетки для типа: ПТ — тип [ptTypeId], сплит — вид «сплит», грудничковое — по названию; иначе `null`. */
    fun service(typeId: String, name: String, isSplit: Boolean, ptTypeId: String?): ClubService? = when {
        typeId == ptTypeId -> PT
        isSplit -> SPLIT
        name.trim().lowercase().startsWith("грудничк") -> ClubService.INFANT
        else -> null
    }
}

/** Версия категории из базы с её id. */
data class CategoryVersion(val id: String, val validFrom: LocalDate, val category: CoachCategory)

/** Версия схемы выплат из базы с её id. */
data class SavedScheme(val id: String, val scheme: PayScheme)

/** Что делать с версией после «Сохранить»: ничего, править версию той же даты или добавить новую. */
enum class VersionEdit { NONE, REPLACE, ADD }

/** Что выбрано в мастере: всё пишется одной записью после «Готово». */
data class SalarySetupDraft(
    val category: CoachCategory,
    val scheme: PaySchemeKind,
    /** Ожидаемый аванс, копейки; только у схемы с официальной частью. */
    val advance: Long?,
    /** С какого месяца действует [scheme]; до него — «Вся сумма, без аванса» (4.11). */
    val schemeFrom: YearMonth,
    /** Ставки по типу и месту (тип → место → суммы); пустые поля не пишутся. */
    val rates: Map<String, Map<String, RateAmounts>>,
    /** Услуга сетки по типу и месту, если сумма взята из сетки без правки (`club_service`, 3.3). */
    val services: Map<String, Map<String, String>>,
    val tiers: TierDraft,
)

/** Правила мастера «Настроить зарплату» и зарплатной части «Профиля» (ТЗ 5.18, 5.16, 4.11). */
object SalarySetupRules {
    /** День аванса у схемы с официальной частью (4.11). */
    const val ADVANCE_DAY = 25

    val CATEGORIES: List<CoachCategory> = listOf(CoachCategory.TRAINER, CoachCategory.MASTER, CoachCategory.ELITE)

    /** Мастер виден, пока у тренера нет категории: её пишет «Готово» последней, прерванный мастер начнётся заново. */
    fun needed(categories: List<CategoryVersion>): Boolean = categories.isEmpty()

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
    fun plain(scheme: PayScheme): PayScheme = PayScheme(scheme.validFrom, PaySchemeKind.NONE)

    /** Строка схемы в «Профиле» — только с открытыми видами оплаты (27.10.2026; до этого — и со старой схемой). */
    fun schemeRow(open: Set<Feature>): Boolean = payTypes(open)

    /**
     * Версии схемы, которые пишет «Готово» мастера: схема [kind] с месяца [from]; если он позже месяца [start],
     * с [start] до него — «Вся сумма, без аванса»: так задаётся переход на новую систему оплаты. «Вся сумма» —
     * всегда с [start].
     */
    fun wizardSchemes(start: LocalDate, kind: PaySchemeKind, advance: Long?, from: YearMonth): List<Pair<YearMonth, PayScheme>> {
        val first = YearMonth(start.year, start.month)
        val none = first to scheme(start, PaySchemeKind.NONE, null)
        return when {
            kind == PaySchemeKind.NONE -> listOf(none)
            from > first -> listOf(none, from to scheme(from.firstDay, kind, advance))
            else -> listOf(from to scheme(from.firstDay, kind, advance))
        }
    }

    /** У схемы есть официальная часть — значит, аванс 25-го и поле «Ожидаемый аванс». */
    fun hasAdvance(kind: PaySchemeKind): Boolean = Versions.officialShareBp(kind) > 0

    /** Версия схемы из выбора: доля по виду, аванс 25-го у официальной, у ИП и без схемы — без аванса. */
    fun scheme(from: LocalDate, kind: PaySchemeKind, advance: Long?): PayScheme {
        val official = hasAdvance(kind)
        return PayScheme(
            validFrom = from,
            kind = kind,
            advanceDay = if (official) ADVANCE_DAY else null,
            advanceDefault = if (official) advance else null,
        )
    }

    /**
     * С какого дня действует всё, что пишет мастер: с 1 января текущего года, как первая ставка (5.16), — тогда
     * и тренировки, записанные до настройки, считаются по категории, схеме, ставкам и ступеням.
     */
    fun start(today: LocalDate): LocalDate = LocalDate(today.year, 1, 1)

    /** Месяц первой версии ступеней из мастера — январь текущего года; ступени уже есть — текущий месяц. */
    fun tierMonth(hasTiers: Boolean, today: LocalDate): YearMonth =
        if (hasTiers) RateRules.defaultTierMonth(today) else YearMonth(today.year, 1)

    /**
     * Сумма в форме ставок мастера: уже внесённая ставка на сегодня главнее сетки — мастер не затирает то, что
     * тренер внёс в «Ставках»; нет ни той, ни другой — поле пустое.
     */
    fun prefill(own: RateAmounts?, grid: RateAmounts?): RateAmounts? = own ?: grid

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
