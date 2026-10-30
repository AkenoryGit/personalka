package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.report.ClientReport
import com.akenory.personalka.domain.report.ClientReportRow
import com.akenory.personalka.domain.report.ClientVisit
import com.akenory.personalka.domain.report.ReportCounts
import com.akenory.personalka.domain.report.ReportKey
import com.akenory.personalka.domain.training.ClientOutcome

/**
 * «Отчёты → Клиент» (ТЗ 5.15, шаг 5.3к): строки «ПТ · ББ» с «проведено · со списанием · отменено», у групп —
 * «пришёл · не пришёл · отменено · со списанием»; ниже тренировки по датам, итог «В зарплату: N из M». Денег нет.
 */
object ClientReportFormat {
    private const val DOT = " · "

    const val TAB_TRAININGS = "Тренировки"
    const val TAB_CLIENT = "Клиент"
    const val MENU = "Отчёт"
    const val PICK = "Выберите клиента"
    const val SEARCH = "Фамилия или имя"
    const val NOT_FOUND = "Никого не нашли"
    const val CHANGE = "Другой клиент"
    const val ARCHIVED = "в архиве"
    const val TRAININGS = "Тренировки по датам"
    const val ABSENT = "Не пришёл"
    const val THEIRS_BY_COLLEAGUE = "группа коллеги, вёл коллега"

    /** Название строки: «ПТ · ББ», у групп с коллегой — случай 4.8, у группы коллеги, которую вёл он, — своя пометка. */
    fun rowTitle(row: ClientReportRow, type: String, place: String): String =
        if (row.theirsByColleague) listOf(type, place, THEIRS_BY_COLLEAGUE).joinToString(DOT) else ReportFormat.rowTitle(row.key, type, place)

    fun rowTitle(visit: ClientVisit, type: String, place: String): String =
        if (visit.theirsByColleague) listOf(type, place, THEIRS_BY_COLLEAGUE).joinToString(DOT) else ReportFormat.rowTitle(visit.key, type, place)

    /** Под строкой: у групп — «пришёл 5 · не пришёл 1 · отменено 1 · со списанием 1», у остальных — как в «Тренировках». */
    fun rowNote(row: ClientReportRow): String {
        if (!row.attendance) return ReportFormat.counts(row.counts).joinToString(DOT)
        val c = row.counts
        return listOfNotNull(
            "пришёл ${c.done}".takeIf { c.done > 0 },
            "не пришёл ${row.absent}".takeIf { row.absent > 0 },
            "отменено ${c.cancelled - row.absent}".takeIf { c.cancelled - row.absent > 0 },
            "со списанием ${c.charged}".takeIf { c.charged > 0 },
        ).joinToString(DOT)
    }

    /** Тренировка в списке: «вт 20.10 · 13:30 · ПТ · ББ». */
    fun visitLine(v: ClientVisit, typePlace: String): String =
        listOf(DateFormat.short(v.start.date), DateFormat.time(v.start.time), typePlace).joinToString(DOT)

    /** Статус клиента: «Проведено», «Отменено», «Отмена со списанием», у группы — ещё «Не пришёл». */
    fun visitStatus(v: ClientVisit): String = if (v.absent) ABSENT else v.outcome.label

    /** Под отменённой: причина и «предупредили за 40 мин» — как в кабинете (6.3). Нечего сказать — `null`. */
    fun visitNote(v: ClientVisit): String? {
        if (v.absent || (v.outcome != ClientOutcome.CANCELLED && v.outcome != ClientOutcome.CANCELLED_CHARGED)) return null
        return CabinetFormat.cancelNote(v.cancelReason, v.noticeMinutes)
    }

    /** Итог: «В зарплату: 9 из 12». */
    fun salaryLine(total: ReportCounts): String = "${ReportFormat.IN_SALARY}: ${total.inSalary} из ${total.all}"

    /** Заголовок для «Поделиться»: «Отчёт: Орлова Ксения · октябрь 2026». */
    fun shareTitle(name: String, periodTitle: String): String = "Отчёт: $name$DOT${periodTitle.replaceFirstChar { it.lowercaseChar() }}"

    /**
     * «Поделиться» (5.15): заголовок, по строке на пару, итог «В зарплату: N из M», затем тренировки по датам.
     * [rowName] — название строки, [visitName] — «тип · место» тренировки.
     */
    fun shareText(
        name: String,
        periodTitle: String,
        report: ClientReport,
        rowName: (ClientReportRow) -> String,
        visitName: (ClientVisit) -> String,
    ): String = buildString {
        append(shareTitle(name, periodTitle))
        if (report.counted < report.to) append(" (по ").append(DateFormat.dayMonth(report.counted)).append(")")
        append('\n')
        if (report.isEmpty) {
            append(ReportFormat.EMPTY)
            return@buildString
        }
        report.rows.forEach { row ->
            append(rowName(row)).append(" ${RuText.DASH} ")
            append((rowNote(row).split(DOT).filter { it.isNotBlank() } + "в зарплату ${row.counts.inSalary}").joinToString(", ")).append('\n')
        }
        append(salaryLine(report.total)).append('\n')
        report.visits.forEach { v ->
            append('\n').append(visitLine(v, visitName(v))).append(" ${RuText.DASH} ").append(visitStatus(v).replaceFirstChar { it.lowercaseChar() })
            visitNote(v)?.let { append(" (").append(it.replaceFirstChar { c -> c.lowercaseChar() }).append(")") }
        }
    }
}
