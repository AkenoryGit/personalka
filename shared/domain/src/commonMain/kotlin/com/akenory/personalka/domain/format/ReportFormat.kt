package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.report.ReportCounts
import com.akenory.personalka.domain.report.ReportItem
import com.akenory.personalka.domain.report.ReportKey
import com.akenory.personalka.domain.report.ReportPeriod
import com.akenory.personalka.domain.report.ReportRow
import com.akenory.personalka.domain.report.ReportStatus
import com.akenory.personalka.domain.report.ReportTable
import com.akenory.personalka.domain.report.ReportYear
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * «Отчёты» (ТЗ 5.15, шаг 5.3и). Строка — «ПТ · ББ», под ней «проведено 61 · со списанием 2 · отменено 5», справа —
 * сколько из них в зарплату. Нули не пишутся (10.7). Денег здесь нет.
 */
object ReportFormat {
    private const val DOT = " · "

    const val TITLE = "Отчёты"
    const val MORE_NOTE = "Сколько тренировок проведено, отменено и списано"

    const val MONTH = "Месяц"
    const val YEAR = "Год"
    const val CUSTOM = "Свой период"
    const val FROM = "С"
    const val TO = "По"

    const val IN_SALARY = "В зарплату"
    const val EMPTY = "За этот период тренировок нет"
    const val WHAT = "Что считать"
    const val ALL = "Все"
    const val TOTAL = "Всего"
    const val YEAR_TOTAL = "Итог за год"
    const val CLOSED = "сверен"
    const val SHARE = "Поделиться"
    const val COPIED = "Отчёт скопирован"
    const val CLOSE = "Закрыть"
    const val REVERSED = "«По» раньше, чем «С»"
    const val TOO_LONG = "Период длиннее года — выберите короче"

    const val DONE = "Проведено"
    const val CHARGED = "Со списанием"
    const val CANCELLED = "Отменено"

    fun period(p: ReportPeriod): String = when (p) {
        ReportPeriod.MONTH -> MONTH
        ReportPeriod.YEAR -> YEAR
        ReportPeriod.CUSTOM -> CUSTOM
    }

    /** «Октябрь 2026». */
    fun month(month: YearMonth): String = SalaryFormat.month(month)

    /** Под заголовком текущего периода: «по 30.10» — дальше будущее. */
    fun countedTo(table: ReportTable): String? =
        if (table.counted < table.to) "по ${DateFormat.dayMonth(table.counted)}" else null

    /** «01.10.2026 – 30.10.2026». */
    fun range(from: LocalDate, to: LocalDate): String = "${DateFormat.full(from)} ${RuText.DASH} ${DateFormat.full(to)}"

    /** Название строки: «ПТ · ББ», у групп с коллегой — ещё случай. */
    fun rowTitle(key: ReportKey, type: String, place: String): String =
        listOfNotNull(type, place, groupCase(key.groupCase)).joinToString(DOT)

    /** Случай 4.8 словами; своя группа, которую вёл один, — без пометки. */
    fun groupCase(case: GroupCase?): String? = when (case) {
        null, GroupCase.MINE_ALONE -> null
        GroupCase.MINE_TOGETHER -> "вдвоём с коллегой"
        GroupCase.MINE_REPLACED -> "вёл коллега"
        GroupCase.THEIRS_TOGETHER -> "группа коллеги, вдвоём"
        GroupCase.THEIRS_BY_ME -> "группа коллеги, вёл я"
    }

    /** «проведено 61 · со списанием 2 · отменено 5»; у дежурств — часами; у СПТ — ещё «закрыто 1». */
    fun rowNote(row: ReportRow): String {
        val parts = if (row.perHour) {
            listOfNotNull(
                SalaryFormat.hours(row.doneMinutes).takeIf { row.doneMinutes > 0 }?.let { "проведено $it" },
                SalaryFormat.hours(row.cancelledMinutes).takeIf { row.cancelledMinutes > 0 }?.let { "отменено $it" },
            )
        } else {
            counts(row.counts)
        }
        val closed = if (row.sptClosed > 0) listOf("закрыто ${row.sptClosed}") else emptyList()
        return (parts + closed).joinToString(DOT)
    }

    /** Части «проведено N», «со списанием N», «отменено N» — только ненулевые. */
    fun counts(c: ReportCounts): List<String> = listOfNotNull(
        "проведено ${c.done}".takeIf { c.done > 0 },
        "со списанием ${c.charged}".takeIf { c.charged > 0 },
        "отменено ${c.cancelled}".takeIf { c.cancelled > 0 },
    )

    /** «проведено 61 · со списанием 2 · отменено 5»; ничего — «нет». */
    fun countsLine(c: ReportCounts): String = counts(c).joinToString(DOT).ifEmpty { "нет" }

    /** У групп: «детей 38 · в среднем 9,5»; детей нет — `null`. */
    fun children(row: ReportRow): String? {
        if (!row.perClient || row.children == 0) return null
        val avg = row.averageTenths?.let { "в среднем ${it / 10},${it % 10}" }
        return listOfNotNull("детей ${row.children}", avg).joinToString(DOT)
    }

    /** Число справа: в зарплату — тренировок, у дежурств — часов. */
    fun salaryValue(row: ReportRow): String =
        if (row.perHour) (if (row.salaryMinutes > 0) SalaryFormat.hours(row.salaryMinutes) else "0") else row.counts.inSalary.toString()

    /** Итог: «В зарплату 70 · в счёт ступени 94». */
    fun totalLine(table: ReportTable): String =
        listOfNotNull("в зарплату ${table.total.inSalary}", "в счёт ступени ${table.ptCount}".takeIf { table.ptCount > 0 }).joinToString(DOT)

    fun ahead(n: Int): String = "Впереди по расписанию: $n"

    fun waiting(n: Int): String = "Ещё не отмечены: $n"

    /** Строка месяца в «Годе»: «Октябрь», у сверенного — «Октябрь · сверен». */
    fun yearMonth(month: YearMonth, closed: Boolean): String =
        DateFormat.month(month.firstDay).replaceFirstChar { it.uppercaseChar() } + if (closed) DOT + CLOSED else ""

    /** Короткое название месяца для столбца полной таблицы: «окт». */
    fun monthShort(month: YearMonth): String = CabinetFormat.monthShort(month.firstDay)

    /** Ячейка полной таблицы: «61 / 2 / 5». */
    fun cell(c: ReportCounts): String = if (c.all == 0) "" else "${c.done} / ${c.charged} / ${c.cancelled}"

    const val CELL_NOTE = "В ячейке: проведено / со списанием / отменено"

    fun status(s: ReportStatus?): String = when (s) {
        ReportStatus.DONE -> DONE
        ReportStatus.CHARGED -> CHARGED
        ReportStatus.CANCELLED -> CANCELLED
        null -> "Запланировано"
    }

    /** Строка тренировки в списке: «вт 20.10 · 13:30 · Орлова К.». */
    fun itemLine(item: ReportItem, who: String?): String =
        listOfNotNull(DateFormat.short(item.start.date), DateFormat.time(item.start.time), who?.ifBlank { null }).joinToString(DOT)

    /** Заголовок списка тренировок строки: «ПТ · ББ — октябрь 2026». */
    fun itemsTitle(rowTitle: String, periodTitle: String): String = "$rowTitle ${RuText.DASH} ${periodTitle.replaceFirstChar { it.lowercaseChar() }}"

    /**
     * «Поделиться» текстом (5.15): заголовок с периодом, по строке на пару, итог.
     * [names] — название пары по ключу.
     */
    fun shareText(title: String, table: ReportTable, names: (ReportKey) -> String): String = buildString {
        append("Отчёт: ").append(title)
        countedTo(table)?.let { append(" (").append(it).append(")") }
        append('\n')
        if (table.isEmpty) {
            append(EMPTY)
            return@buildString
        }
        table.rows.forEach { row ->
            append(names(row.key)).append(" ${RuText.DASH} ")
            val parts = rowNote(row).split(DOT).filter { it.isNotBlank() }.toMutableList()
            if (!row.perHour) parts += "в зарплату ${row.counts.inSalary}"
            children(row)?.let { parts += it.split(DOT) }
            append(parts.joinToString(", ")).append('\n')
        }
        append(TOTAL).append(" ${RuText.DASH} ")
        append((counts(table.total) + totalLine(table).split(DOT)).joinToString(", "))
    }

    /** «Поделиться» для «Года»: по строке на месяц и итог. */
    fun shareYear(year: ReportYear, key: ReportKey?, keyName: String?): String = buildString {
        append("Отчёт: ").append(year.year)
        keyName?.let { append(DOT).append(it) }
        append('\n')
        year.months.forEach { m ->
            val c = year.counts(m, key)
            append(yearMonth(m.month, m.closed)).append(" ${RuText.DASH} ")
            append((counts(c) + "в зарплату ${c.inSalary}").joinToString(", ")).append('\n')
        }
        val t = year.totalCounts(key)
        append(YEAR_TOTAL).append(" ${RuText.DASH} ").append((counts(t) + "в зарплату ${t.inSalary}").joinToString(", "))
    }
}
