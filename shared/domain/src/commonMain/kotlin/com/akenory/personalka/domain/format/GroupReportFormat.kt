package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.backup.XlsxCell
import com.akenory.personalka.domain.backup.XlsxSheet
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.report.GroupMark
import com.akenory.personalka.domain.report.GroupReport
import com.akenory.personalka.domain.report.GroupReportBlock
import com.akenory.personalka.domain.report.GroupReportKid
import com.akenory.personalka.domain.report.GroupReportRules
import kotlinx.datetime.LocalDate

/** Строки вкладки «Группы» в «Отчётах» (ТЗ 5.15, шаг 5.3о): экран, «Поделиться» текстом и файл Excel. */
object GroupReportFormat {
    private const val DOT = " · "

    const val TAB = "Группы"
    const val PICK_TITLE = "Какие группы"
    const val ALL = "Все"
    const val LEFT = "Уже не в группе"
    const val EMPTY = "За этот период тренировок групп нет"
    const val SHARE_TEXT = "Текстом"
    const val SHARE_EXCEL = "Файлом Excel"
    const val SHARE_TITLE = "Поделиться отчётом"
    const val DONE = "Готово"

    /** Строка выбора под периодом: «Группы: все · 4», «Группы: 2 из 4». */
    fun picked(chosen: Int, total: Int): String =
        if (chosen >= total) "Группы: все$DOT${RuText.digits(total.toLong())}" else "Группы: ${RuText.digits(chosen.toLong())} из ${RuText.digits(total.toLong())}"

    /** «проведено 8 · в среднем пришли 6,4 из 12». */
    fun summary(b: GroupReportBlock): String {
        val head = "проведено ${RuText.digits(b.conducted.toLong())}"
        if (b.conducted == 0) return head
        return "$head${DOT}в среднем пришли ${ClientFormat.tenths(b.averageTenths)} из ${RuText.digits(b.size.toLong())}"
    }

    /** «не отмечено 1 · отменены целиком 1» — только ненулевые; ничего — `null`. */
    fun extra(b: GroupReportBlock): String? = listOfNotNull(
        b.notMarked.takeIf { it > 0 }?.let { "не отмечено ${RuText.digits(it.toLong())}" },
        b.cancelledWhole.takeIf { it > 0 }?.let { "отменены целиком ${RuText.digits(it.toLong())}" },
    ).joinToString(DOT).ifEmpty { null }

    /** «Белова Ева». */
    fun name(n: ClientName): String = "${n.lastName} ${n.firstName}"

    /** Справа у ребёнка: «6 из 8 · 75 %». */
    fun share(k: GroupReportKid): String =
        "${RuText.digits(k.came.toLong())} из ${RuText.digits(k.total.toLong())}$DOT${k.percent ?: 0}${RuText.NBSP}%"

    /** «не пришёл 1 · отменено 1 · со списанием 1» — только ненулевые; «последний раз 24.10» — отдельно. */
    fun counts(k: GroupReportKid): String? = listOfNotNull(
        k.noShow.takeIf { it > 0 }?.let { "не пришёл ${RuText.digits(it.toLong())}" },
        k.cancelled.takeIf { it > 0 }?.let { "отменено ${RuText.digits(it.toLong())}" },
        k.charged.takeIf { it > 0 }?.let { "со списанием ${RuText.digits(it.toLong())}" },
    ).joinToString(DOT).ifEmpty { null }

    fun last(date: LocalDate): String = "последний раз ${DateFormat.dayMonth(date)}"

    /** «пропустил 3 подряд» — с трёх; меньше — `null`. Пол ребёнка не важен: форма без рода не звучит, а «пропустил» привычно. */
    fun missed(k: GroupReportKid): String? =
        k.missedInRow.takeIf { it >= GroupReportRules.MISSED_IN_ROW }?.let { "пропустил ${RuText.digits(it.toLong())} подряд" }

    /** «На раз: Гусев Борис · 2, Сидоров Ян · 1». */
    fun guests(b: GroupReportBlock): String? =
        b.guests.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "На раз: ") { "${name(it.name)}$DOT${RuText.digits(it.came.toLong())}" }

    /**
     * «Поделиться» текстом: по группе — название и строки детей «Белова Ева — 6 из 8 (75 %), последний раз 24.10».
     * Ни телефонов, ни дневника.
     */
    fun shareText(title: String, report: GroupReport): String = buildString {
        append("Посещаемость групп · $title")
        report.blocks.forEach { b ->
            appendLine()
            appendLine()
            appendLine(b.group.name)
            append(summary(b))
            (b.kids + b.left).forEach { k ->
                appendLine()
                append(kidLine(k))
            }
            guests(b)?.let {
                appendLine()
                append(it)
            }
        }
    }

    private fun kidLine(k: GroupReportKid): String {
        val base = "${name(k.name)} — ${RuText.digits(k.came.toLong())} из ${RuText.digits(k.total.toLong())} (${k.percent ?: 0}${RuText.NBSP}%)"
        return listOfNotNull(base, k.last?.let(::last), missed(k)).joinToString(", ")
    }

    /** `otchet-gruppy-20261031.xlsx`. */
    fun fileName(today: LocalDate): String =
        "otchet-gruppy-${today.year}${two(today.month.ordinal + 1)}${two(today.day)}.xlsx"

    private fun two(n: Int) = n.toString().padStart(2, '0')

    val TOTAL_HEADERS = listOf("Группа", "Ребёнок", "Пришёл", "Не пришёл", "Отменено", "Со списанием", "Из", "%", "Последний раз")

    /** Буква журнала: «+» пришёл, «н» не пришёл, «о» отменено, «с» со списанием. */
    fun mark(m: GroupMark): String = when (m) {
        GroupMark.CAME -> "+"
        GroupMark.NO_SHOW -> "н"
        GroupMark.CANCELLED -> "о"
        GroupMark.CHARGED -> "с"
    }

    /**
     * Книга отчёта (5.15): лист «Итоги» и по листу-журналу на группу — строки дети, столбцы даты проведённых, справа
     * итоги. Имя листа — название группы без недопустимых символов, до 31 знака, повтор — с номером.
     */
    fun sheets(report: GroupReport): List<XlsxSheet> {
        val totals = report.blocks.flatMap { b -> (b.kids + b.left).map { k -> totalRow(b.group.name, k) } }
        val names = HashSet<String>()
        names += "итоги"
        val journals = report.blocks.map { b ->
            val base = b.group.name.filterNot { it in "[]:*?/\\" }.trim().ifEmpty { "Группа" }.take(31)
            var sheetName = base
            var n = 2
            while (!names.add(sheetName.lowercase())) {
                val suffix = " ($n)"
                sheetName = base.take(31 - suffix.length) + suffix
                n++
            }
            val kids = b.kids + b.left
            XlsxSheet(
                name = sheetName,
                headers = listOf("Ребёнок") + b.dates.map { DateFormat.dayMonth(it.second.date) } + listOf("Пришёл", "Из", "%"),
                widths = listOf(24) + b.dates.map { 7 } + listOf(9, 6, 6),
                rows = kids.map { k ->
                    listOf<XlsxCell?>(XlsxCell.Text(name(k.name))) +
                        b.dates.map { (id, _) -> k.marks[id]?.let { XlsxCell.Text(mark(it)) } } +
                        listOf(XlsxCell.Whole(k.came.toLong()), XlsxCell.Whole(k.total.toLong()), XlsxCell.Whole((k.percent ?: 0).toLong()))
                },
            )
        }
        return listOf(XlsxSheet("Итоги", TOTAL_HEADERS, listOf(22, 24, 8, 10, 10, 13, 6, 6, 14), totals)) + journals
    }

    private fun totalRow(group: String, k: GroupReportKid): List<XlsxCell?> = listOf(
        XlsxCell.Text(group),
        XlsxCell.Text(name(k.name)),
        XlsxCell.Whole(k.came.toLong()),
        XlsxCell.Whole(k.noShow.toLong()),
        XlsxCell.Whole(k.cancelled.toLong()),
        XlsxCell.Whole(k.charged.toLong()),
        XlsxCell.Whole(k.total.toLong()),
        XlsxCell.Whole((k.percent ?: 0).toLong()),
        k.last?.let { XlsxCell.Day(it) },
    )
}
