package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.ColleagueSettlement
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.SettlementCase
import com.akenory.personalka.domain.money.SettlementTraining
import kotlinx.datetime.YearMonth

/**
 * Строки вкладки «Коллеги» (ТЗ 5.12, 4.8) и текст «Поделиться расчётом». Пол коллеги приложение не знает,
 * поэтому глаголы — без рода («отдаёт», «провели»), а имя склоняется только в дательном (`RuText.dative`).
 */
object SettlementFormat {
    private const val DOT = " · "

    const val TAB = "Коллеги"
    const val SETTLED = "Рассчитались"
    const val UNSETTLE = "Снять отметку"
    const val SHARE = "Поделиться"
    const val SHARE_NOTE = "В тексте — даты, число детей и суммы, без имён детей"
    const val SETTLED_MESSAGE = "Отмечено: рассчитались"
    const val UNSETTLED_MESSAGE = "Отметка снята"
    const val EVEN = "В расчёте"
    const val COPIED = "Расчёт скопирован — вставьте его в сообщение"
    const val EMPTY = "В этом месяце взаиморасчётов нет. Они появятся, когда группу проведёте вместе с коллегой " +
        "или с заменой."

    /** Заголовок случая: «Моя группа · провели вместе», «Группа коллеги · провели вы». */
    fun caseTitle(case: GroupCase): String = when (case) {
        GroupCase.MINE_ALONE -> "Моя группа"
        GroupCase.MINE_TOGETHER -> "Моя группа${DOT}провели вместе"
        GroupCase.MINE_REPLACED -> "Моя группа${DOT}провели без вас"
        GroupCase.THEIRS_TOGETHER -> "Группа коллеги${DOT}провели вместе"
        GroupCase.THEIRS_BY_ME -> "Группа коллеги${DOT}провели вы"
    }

    /** Под заголовком случая: «Сб 9:00 ББ · 3 трен. · 33 ребёнка»; одна тренировка — с датой. */
    fun caseNote(c: SettlementCase, name: String): String {
        val count = if (c.trainings.size == 1) {
            DateFormat.short(c.trainings.single().start.date)
        } else {
            "${RuText.digits(c.trainings.size.toLong())}${NBSP}трен."
        }
        return listOf(name, count, children(c.children)).filter { it.isNotBlank() }.joinToString(DOT)
    }

    /** Кто кому отдаёт по строке: «вы отдаёте», «Лена отдаёт». */
    fun direction(amount: Long, colleague: String): String = if (amount < 0) "вы отдаёте" else "$colleague отдаёт"

    /** Строка тренировки: «сб 03.10 · 11 детей · 2 970 ₽». */
    fun training(t: SettlementTraining): String =
        listOf(DateFormat.short(t.start.date), children(t.children), MoneyFormat.rub(t.gross)).joinToString(DOT)

    /**
     * Как считается доля: «½ × (1 − 0,065) от каждой», замена — «(1 − 0,065) от каждой»; без налога владельца —
     * «½ от каждой» и «вся сумма». Налог разный у тренировок случая (схема сменилась) — `null`.
     */
    fun formula(c: SettlementCase): String? {
        val tax = c.tax ?: return null
        val rest = if (tax == 0L) null else "(1 − ${taxShare(tax)})"
        return when {
            c.together && rest != null -> "½ × $rest от каждой"
            c.together -> "½ от каждой"
            rest != null -> "$rest от каждой"
            else -> "вся сумма группы"
        }
    }

    /** Доля налога w десятичной дробью: 6 500 000 стомиллионных → «0,065». */
    fun taxShare(tax: Long): String {
        val whole = tax / TAX_ONE
        val fraction = (tax % TAX_ONE).toString().padStart(8, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole" else "$whole,$fraction"
    }

    /** Итог крупно: «Лена отдаёт вам 883,58 ₽», «Вы отдаёте Лене 950 ₽», ноль — «В расчёте». */
    fun total(name: String, amount: Long): String =
        if (amount == 0L) EVEN else "${SalaryFormat.settlement(name, amount)} ${MoneyFormat.rub(kotlin.math.abs(amount))}"

    /** «Итог за октябрь». */
    fun totalTitle(month: YearMonth): String = "Итог за ${DateFormat.month(month.firstDay)}"

    /** «По расписанию к 31.10 — вы отдаёте Лене ≈ 950 ₽». */
    fun forecast(month: YearMonth, name: String, amount: Long): String {
        val what = if (amount == 0L) "в расчёте" else {
            val text = SalaryFormat.settlement(name, amount)
            val lower = if (amount < 0) text.replaceFirstChar { it.lowercaseChar() } else text
            "$lower ${MoneyFormat.approxRub(kotlin.math.abs(amount))}"
        }
        return "По расписанию к ${DateFormat.dayMonth(month.lastDay)} — $what"
    }

    /** «Рассчитались · 883,58 ₽»; расчёт потом изменился — на сколько. */
    fun settledNote(s: ColleagueSettlement): String? {
        val amount = s.settled ?: return null
        val changed = s.changedSince ?: return "$SETTLED$DOT${MoneyFormat.rub(kotlin.math.abs(amount))}"
        return "Рассчитались на ${MoneyFormat.rub(kotlin.math.abs(amount))}, потом расчёт изменился на ${MoneyFormat.signedRub(changed)}"
    }

    /** Схема коллеги под именем: «официально с налогом 6,5 %, как у вас». */
    fun scheme(kind: PaySchemeKind?, mine: PaySchemeKind?): String? {
        kind ?: return null
        val text = SalarySetupFormat.scheme(kind).replaceFirstChar { it.lowercaseChar() }
        return if (kind == mine) "$text, как у вас" else text
    }

    /**
     * «Поделиться расчётом» (5.12): текст для коллеги — от первого лица тренера, на «ты»: даты, время, число детей
     * и суммы, без имён детей. [place] — подпись случая (тип и место): название группы может быть любым.
     */
    fun shareText(month: YearMonth, s: ColleagueSettlement, place: (SettlementCase) -> String): String = buildString {
        appendLine("Взаиморасчёт за ${DateFormat.month(month.firstDay)} ${month.year}")
        s.cases.forEach { c ->
            appendLine()
            val title = shareCase(c.case)
            val where = place(c).takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            appendLine("$title$where — ${shareDirection(c.amount)} ${MoneyFormat.rub(kotlin.math.abs(c.amount))}")
            c.trainings.forEach { t ->
                appendLine(
                    "${DateFormat.short(t.start.date)} ${DateFormat.time(t.start.time)}$DOT${children(t.children)}$DOT" +
                        "группа ${MoneyFormat.rub(t.gross)} → ${MoneyFormat.rub(kotlin.math.abs(t.amount))}",
                )
            }
            formula(c)?.let { appendLine("Доля: $it") }
        }
        appendLine()
        append(if (s.total == 0L) "Итого: в расчёте" else "Итого: ${shareDirection(s.total)} ${MoneyFormat.rub(kotlin.math.abs(s.total))}")
    }

    private fun shareCase(case: GroupCase): String = when (case) {
        GroupCase.MINE_ALONE -> "Моя группа"
        GroupCase.MINE_TOGETHER -> "Моя группа, вели вместе"
        GroupCase.MINE_REPLACED -> "Моя группа, замена"
        GroupCase.THEIRS_TOGETHER -> "Твоя группа, вели вместе"
        GroupCase.THEIRS_BY_ME -> "Твоя группа, замена"
    }

    private fun shareDirection(amount: Long): String = if (amount < 0) "я отдаю тебе" else "ты отдаёшь мне"

    private fun children(n: Int): String = RuText.count(n, "ребёнок", "ребёнка", "детей")

    /** Единица доли налога: стомиллионные (`PayRules.ownerTax`). */
    private const val TAX_ONE = 100_000_000L
}
