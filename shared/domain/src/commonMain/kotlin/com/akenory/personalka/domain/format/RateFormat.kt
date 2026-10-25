package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.TierDraft
import kotlinx.datetime.LocalDate

/** Строки «Ставок и ступеней» в настройках (ТЗ 5.16, 3.3). */
object RateFormat {
    private const val DOT = " · "

    const val TITLE = "Ставки и ступени"
    const val RATES = "Ставки"
    const val TIERS = "Ступени"
    const val NO_RATE = "Нет ставки"
    const val ANY_PLACE = "Любое место"
    const val FROM = "Действуют с"
    const val FROM_NOTE = "Тренировки раньше этой даты считаются по прежним ставкам."
    const val FIRST_NOTE = "Первая ставка действует с 1 января — тогда и уже записанные тренировки получат её."
    const val TIER_1 = "Ступень 1"
    const val TIER_2 = "Ступень 2"
    const val TIER_3 = "Ступень 3"
    const val ONLY = "Ставка"
    const val SPT_BONUS = "Доплата за покупку блока"
    const val LIKE_PT = "Надбавки как у ПТ"
    const val HISTORY = "Прежние ставки"
    const val TIERS_ON = "Ставка растёт со ступенью"
    const val TIERS_ON_NOTE = "Выключено — весь месяц по ступени 1."
    const val T2_FROM = "Ступень 2 — с какой ПТ"
    const val T3_FROM = "Ступень 3 — с какой ПТ"
    const val AS_PT = "Считать как ПТ"
    const val AS_PT_NOTE = "Эти тренировки идут в счётчик ПТ месяца, по нему выбирается ступень."
    const val TIERS_MONTH = "С месяца"
    const val TIERS_MONTH_NOTE = "Ступени меняются с 1-го числа: прошлые месяцы не пересчитываются."

    /** «Действуют с 01.11.2026» — подпись даты формы. */
    fun date(date: LocalDate): String = DateFormat.full(date)

    /** За что ставка: «за клиента», «за час»; за тренировку — ничего. */
    fun unit(unit: PayUnit): String = when (unit) {
        PayUnit.PER_SESSION -> ""
        PayUnit.PER_CLIENT -> " за${NBSP}клиента"
        PayUnit.PER_HOUR -> " за${NBSP}час"
    }

    /** Суммы ставки: «920 / 960 / 1 000», «200 + 140 за блок». Пустые ступени не пишутся. */
    fun amounts(a: RateAmounts): String {
        val tiers = listOfNotNull(a.t1, a.t2, a.t3).joinToString(" / ") { MoneyFormat.amount(it) }
        return if (a.sptBonus != null) "$tiers + ${MoneyFormat.amount(a.sptBonus)} за блок" else tiers
    }

    /**
     * Строка типа в списке ставок: «ББ 920 / 960 / 1 000 · ДБ 830 · Зал —», у дежурства — «200 ₽ за час»;
     * ни одной ставки — «Нет ставки».
     */
    fun summary(cells: List<Pair<String?, RateAmounts?>>, unit: PayUnit): String {
        if (cells.all { it.second == null }) return NO_RATE
        if (cells.size == 1 && cells[0].first == null) return "${amounts(cells[0].second!!)}$NBSP₽${unit(unit)}"
        return cells.joinToString(DOT) { (place, a) -> "${place ?: ANY_PLACE}$NBSP${a?.let(::amounts) ?: "—"}" } + unit(unit)
    }

    /** Заголовок версии в «Прежних ставках»: «с 01.10.2026». */
    fun since(date: LocalDate): String = "с$NBSP${DateFormat.full(date)}"

    /** Строка ступеней: «Ступень 2 — от 101 ПТ, 3 — от 151», выключены — «Без ступеней»; [from] — с какого числа. */
    fun tiers(draft: TierDraft, from: LocalDate?): String {
        val head = if (draft.enabled) "Ступень 2 — от ${draft.t2From}${NBSP}ПТ, 3 — от ${draft.t3From}" else "Без ступеней"
        return if (from != null) "$head$DOT${since(from)}" else head
    }

    /** Строка раздела в «Настройках»: «Нет ставки у 3 типов» или ступени. */
    fun settingsRow(missing: Int, tiers: TierDraft): String =
        if (missing > 0) "Нет ставки у ${RuText.count(missing.toLong(), "типа", "типов", "типов")}" else tiers(tiers, null)

    /** Сообщение после «Сохранить»: «Ставки «ПТ» с 01.11.2026 сохранены». */
    fun saved(type: String, from: LocalDate): String = "Ставки «$type» ${since(from)} сохранены"

    /** Сообщение после ступеней: «Ступени с 01.11.2026 сохранены». */
    fun tiersSaved(from: LocalDate): String = "Ступени ${since(from)} сохранены"

    /** «Ноябрь 2026» — месяц формы ступеней. */
    fun month(from: LocalDate): String = DateFormat.monthYear(from)
}
