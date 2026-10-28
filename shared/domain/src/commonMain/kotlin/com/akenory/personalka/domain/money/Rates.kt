package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// «Ставки и ступени» в настройках (ТЗ 5.16, 3.3): что тренер вводит и что из этого пишется версиями.
// Расчёт по ставкам — PayRules; здесь — только правила формы. Деньги — копейки.

/** Суммы одной ставки: ступени 1–3 и доплата за закрытие СПТ. Пустая ступень 2 или 3 — берётся ступень ниже (3.2). */
data class RateAmounts(val t1: Long, val t2: Long? = null, val t3: Long? = null, val sptBonus: Long? = null)

/** Версия ставки из базы с её id: форма сравнивает с ней введённое. */
data class SavedRate(val id: String, val rate: PayRate) {
    val amounts: RateAmounts get() = RateAmounts(rate.amountT1, rate.amountT2, rate.amountT3, rate.sptCloseBonus)
}

/** Что записать после «Сохранить» в форме ставок типа. */
sealed interface RateChange {
    /** Новая версия места [placeId] (пусто — любое место) с даты формы. */
    data class Add(val placeId: String?, val amounts: RateAmounts) : RateChange

    /** Версия с той же датой уже есть — её суммы правятся, второй версии с одной датой не появляется. */
    data class Replace(val id: String, val amounts: RateAmounts) : RateChange

    /** Ставку, которая начинается в день формы, стёрли — версия удаляется, снова действует прежняя. */
    data class Remove(val id: String) : RateChange
}

/** Ступени (бонусы) в форме: включены, пороги, типы «Считать как ПТ» (ТЗ 3.3, 4.6). */
data class TierDraft(val enabled: Boolean, val t2From: Int, val t3From: Int, val ptTypeIds: Set<String>)

/** Правила формы «Ставки и ступени» (ТЗ 5.16, 3.3). */
object RateRules {
    /** Больше 1 000 000 ₽ за одну тренировку не бывает — значит, лишний ноль. */
    const val MAX_AMOUNT = 100_000_000L

    /** Порог ступени — не больше 1 000 ПТ в месяц. */
    const val MAX_TIER_FROM = 1000

    /** Пороги-пример (5.3а): подставляются как пример в подсказке поля и в «ставках-примере» мастера. */
    const val T2_FROM = 101
    const val T3_FROM = 151

    private val SPACES = Regex("[\\s   ₽]")
    private val MONEY = Regex("^(\\d+)(?:[.,](\\d{1,2}))?$")

    /** Сумма из поля: «920», «1 550», «13 546,50», «200 ₽» — в копейках. Пусто или непонятно — `null`. */
    fun money(text: String): Long? {
        val m = MONEY.find(text.replace(SPACES, "")) ?: return null
        val (rub, kop) = m.destructured
        if (rub.length > 9) return null
        val value = rub.toLong() * 100 + kop.padEnd(2, '0').toLong()
        return value.takeIf { it <= MAX_AMOUNT }
    }

    /** Поле суммы заполнено неверно: пустое — можно, остальное — только числом. */
    fun moneyProblem(text: String): String? = when {
        text.isBlank() -> null
        money(text) == null -> "Сумма — числом: 920 или 13 546,50"
        else -> null
    }

    /** Сумма — в поле формы: «920», «13546,50» (без пробелов: так её проще править). */
    fun moneyText(kopecks: Long?): String = when {
        kopecks == null -> ""
        kopecks % 100 == 0L -> (kopecks / 100).toString()
        else -> "${kopecks / 100},${(kopecks % 100).toString().padStart(2, '0')}"
    }

    /** Ставка дежурства — одна на любое место (оно идёт по часам, ТЗ 4.7); у остальных типов ставка — по местам. */
    fun perPlace(kind: TrainingKind): Boolean = kind != TrainingKind.DUTY

    /**
     * Дата, с которой действуют ставки в форме. Первая ставка типа — с 1 января: тогда и тренировки, записанные
     * до настройки зарплаты, получают ставку. Новая ставка — с сегодняшнего дня; тренер меняет дату сам.
     */
    fun defaultFrom(hasRates: Boolean, today: LocalDate): LocalDate = if (hasRates) today else LocalDate(today.year, 1, 1)

    /** Версия места [placeId] (пусто — любое место), действующая в день [date], — без замены на «любое место». */
    fun activeAt(saved: List<SavedRate>, placeId: String?, date: LocalDate): SavedRate? =
        Versions.activeOn(saved.filter { it.rate.locationId == placeId }, date) { it.rate.validFrom }

    /**
     * Что записать: [entered] — введённое по местам (`null` — поля пустые). Совпадает с действующей версией — ничего;
     * у действующей та же дата — правка её; иначе — новая версия с [date]. Пустые поля стирают только версию,
     * которая начинается в [date]; если до неё действует более ранняя — это ошибка формы ([emptyProblem]).
     */
    fun changes(saved: List<SavedRate>, date: LocalDate, entered: Map<String?, RateAmounts?>): List<RateChange> =
        entered.mapNotNull { (placeId, amounts) ->
            val active = activeAt(saved, placeId, date)
            when {
                amounts == null -> active?.takeIf { it.rate.validFrom == date }?.let { RateChange.Remove(it.id) }
                active?.amounts == amounts -> null
                active != null && active.rate.validFrom == date -> RateChange.Replace(active.id, amounts)
                else -> RateChange.Add(placeId, amounts)
            }
        }

    /**
     * Пустые поля места, где до [date] уже действует ставка: «убрать ставку с даты» нельзя — тренировки остались бы
     * без ставки молча. `null` — можно.
     */
    fun emptyProblem(saved: List<SavedRate>, placeId: String?, date: LocalDate): String? {
        val before = Versions.activeOn(saved.filter { it.rate.locationId == placeId && it.rate.validFrom < date }, date) { it.rate.validFrom }
        return if (before != null) "До этой даты ставка есть — впишите новую сумму" else null
    }

    /** Ступени 2–3 заполнены, а ступень 1 пустая. */
    fun needsT1(t1: Long?, t2: Long?, t3: Long?, bonus: Long?): Boolean = t1 == null && (t2 != null || t3 != null || bonus != null)

    /**
     * «Надбавки как у ПТ» (ТЗ 3.3): ступени 2 и 3 = своя ступень 1 + та же прибавка, что у ПТ в этом месте.
     * У ПТ нет ступени — `null` на её месте; нет ставки ПТ — `null`.
     */
    fun likePt(ownT1: Long, pt: RateAmounts?): Pair<Long?, Long?>? {
        if (pt == null) return null
        val t2 = pt.t2?.let { ownT1 + it - pt.t1 }
        val t3 = pt.t3?.let { ownT1 + it - pt.t1 }
        return t2 to t3
    }

    /** У типа есть кнопка «Надбавки как у ПТ»: персональный со ступенями, но не сама ПТ. */
    fun canCopyPtBonus(kind: TrainingKind, tierDependent: Boolean, isPt: Boolean): Boolean =
        kind == TrainingKind.PERSONAL && tierDependent && !isPt

    /** Ступени начинаются с 1-го числа месяца (ТЗ 3.3). */
    fun tierFrom(month: YearMonth): LocalDate = month.firstDay

    /** Месяц, с которого форма ступеней предлагает новую версию: текущий. */
    fun defaultTierMonth(today: LocalDate): YearMonth = today.yearMonth

    /**
     * Ступени в форме: действующие в месяце [month] или, пока версий нет, — без ступеней (решение 28.10.2026, шаг 5.3а:
     * пороги одного клуба не подставляются, 101 и 151 — только пример в подсказке поля), как ПТ — типы с отметкой
     * «Считать как ПТ» (ТЗ 3.3).
     */
    fun tierDraft(versions: List<TierScheme>, month: YearMonth, defaultPtTypes: Set<String>): TierDraft {
        val active = Versions.activeIn(versions, month) { it.validFrom }
            ?: return TierDraft(enabled = false, t2From = T2_FROM, t3From = T3_FROM, ptTypeIds = defaultPtTypes)
        return TierDraft(active.enabled, active.t2From, active.t3From, active.ptTypeIds)
    }

    /** Текст поля порога: есть версия ступеней в месяце — её порог, нет — пусто (пример — в подсказке поля). */
    fun thresholdText(versions: List<TierScheme>, month: YearMonth, value: Int): String =
        if (Versions.activeIn(versions, month) { it.validFrom } == null) "" else value.toString()

    /** Порог из поля: целое от 1 до [MAX_TIER_FROM]. */
    fun threshold(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_TIER_FROM }

    /** Ошибка порогов: ступень 3 — позже ступени 2. */
    fun tierProblem(t2From: Int?, t3From: Int?): String? = when {
        t2From == null || t3From == null -> "Порог — число ПТ от 1 до $MAX_TIER_FROM"
        t3From <= t2From -> "Ступень 3 начинается позже ступени 2"
        else -> null
    }
}
