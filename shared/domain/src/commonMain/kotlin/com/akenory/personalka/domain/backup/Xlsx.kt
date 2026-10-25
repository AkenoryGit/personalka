package com.akenory.personalka.domain.backup

import kotlinx.datetime.LocalDate

/**
 * Ячейка листа Excel. Числа, деньги и даты пишутся числами, а не текстом: в Excel и Google Таблицах их можно
 * складывать, сортировать и строить по ним графики.
 */
sealed interface XlsxCell {
    data class Text(val value: String) : XlsxCell

    data class Whole(val value: Long) : XlsxCell

    /** Деньги в копейках — в ячейке рубли с копейками, формат «124 800,00». */
    data class Money(val kopecks: Long) : XlsxCell

    /** Сотые доли — в ячейке «63,17» (секунды результата). */
    data class Hundredths(val value: Long) : XlsxCell

    /** Дата — число дней Excel с форматом «22.10.2026». */
    data class Day(val date: LocalDate) : XlsxCell
}

/**
 * Лист: [name] — название вкладки (до 31 знака), [headers] — первая строка, она закреплена при прокрутке, [widths] —
 * ширина столбцов в знаках, [rows] — строки (`null` — пустая ячейка), [boldRows] — номера строк [rows] жирным (итоги).
 */
class XlsxSheet(
    val name: String,
    val headers: List<String>,
    val widths: List<Int>,
    val rows: List<List<XlsxCell?>>,
    val boldRows: Set<Int> = emptySet(),
)

/**
 * Книга Excel (.xlsx, Office Open XML) на чистом Kotlin: файлы архива «путь → XML». Упаковывает их в zip слой данных.
 * Строки — прямо в ячейках (`inlineStr`): Excel, Google Таблицы и LibreOffice читают их без таблицы общих строк.
 */
object Xlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Файлы книги по порядку: `[Content_Types].xml` — первым. */
    fun files(sheets: List<XlsxSheet>): Map<String, String> {
        require(sheets.isNotEmpty()) { "В книге нужен хотя бы один лист" }
        val out = LinkedHashMap<String, String>()
        out["[Content_Types].xml"] = contentTypes(sheets.size)
        out["_rels/.rels"] = HEAD + """<Relationships xmlns="$RELS_NS">""" +
            """<Relationship Id="rId1" Type="$DOC_REL/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        out["xl/workbook.xml"] = workbook(sheets)
        out["xl/_rels/workbook.xml.rels"] = workbookRels(sheets.size)
        out["xl/styles.xml"] = STYLES
        sheets.forEachIndexed { i, sheet -> out["xl/worksheets/sheet${i + 1}.xml"] = sheet(sheet) }
        return out
    }

    /** «A», «Z», «AA» — буквы столбца по номеру с нуля. */
    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Число дней Excel: 1 — 1 января 1900 года, 25569 — 1 января 1970 года. */
    fun serial(date: LocalDate): Long = date.toEpochDays() + EPOCH_1970

    /** «-1234.05» из копеек или сотых — без дробей с плавающей точкой: в ячейке ровно то, что посчитано. */
    fun decimal(hundredths: Long): String {
        val sign = if (hundredths < 0) "-" else ""
        val abs = if (hundredths < 0) -hundredths else hundredths
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    private fun contentTypes(count: Int): String = HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="$ML_TYPE.sheet.main+xml"/>""" +
        """<Override PartName="/xl/styles.xml" ContentType="$ML_TYPE.styles+xml"/>""" +
        (1..count).joinToString("") { """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="$ML_TYPE.worksheet+xml"/>""" } +
        "</Types>"

    private fun workbook(sheets: List<XlsxSheet>): String = HEAD + """<workbook xmlns="$MAIN_NS" xmlns:r="$DOC_REL"><sheets>""" +
        sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${escape(sheetName(s.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" } +
        "</sheets></workbook>"

    private fun workbookRels(count: Int): String = HEAD + """<Relationships xmlns="$RELS_NS">""" +
        (1..count).joinToString("") { """<Relationship Id="rId$it" Type="$DOC_REL/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
        """<Relationship Id="rId${count + 1}" Type="$DOC_REL/styles" Target="styles.xml"/></Relationships>"""

    private fun sheet(sheet: XlsxSheet): String {
        val sb = StringBuilder(HEAD)
        sb.append("""<worksheet xmlns="$MAIN_NS"><sheetViews><sheetView workbookViewId="0">""")
        sb.append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (sheet.widths.isNotEmpty()) {
            sb.append("<cols>")
            sheet.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        row(sb, 1, sheet.headers.map { XlsxCell.Text(it) }, bold = true)
        sheet.rows.forEachIndexed { i, cells -> row(sb, i + 2, cells, bold = i in sheet.boldRows) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun row(sb: StringBuilder, number: Int, cells: List<XlsxCell?>, bold: Boolean) {
        sb.append("""<row r="$number">""")
        cells.forEachIndexed { i, cell ->
            if (cell == null) return@forEachIndexed
            val ref = "${column(i)}$number"
            when (cell) {
                is XlsxCell.Text -> {
                    if (cell.value.isEmpty()) return@forEachIndexed
                    val style = if (bold) """ s="$BOLD"""" else ""
                    sb.append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                }
                is XlsxCell.Whole -> number(sb, ref, if (bold) BOLD else 0, cell.value.toString())
                is XlsxCell.Money -> number(sb, ref, if (bold) MONEY_BOLD else MONEY, decimal(cell.kopecks))
                is XlsxCell.Hundredths -> number(sb, ref, HUNDREDTHS, decimal(cell.value))
                is XlsxCell.Day -> number(sb, ref, DATE, serial(cell.date).toString())
            }
        }
        sb.append("</row>")
    }

    private fun number(sb: StringBuilder, ref: String, style: Int, value: String) {
        val s = if (style != 0) """ s="$style"""" else ""
        sb.append("""<c r="$ref"$s><v>$value</v></c>""")
    }

    /** Название листа: без `[]:*?/\` и не длиннее 31 знака — иначе Excel не откроет файл. */
    private fun sheetName(name: String): String = name.filterNot { it in "[]:*?/\\" }.take(31)

    /** Спецзнаки XML экранируются, управляющие знаки (кроме табуляции и перевода строки) выбрасываются: XML их не допускает. */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        text.forEach { c ->
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                c == '￾' || c == '￿' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DOC_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val ML_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml"
    private const val EPOCH_1970 = 25569L

    // Номера стилей ячеек в styles.xml (cellXfs).
    private const val BOLD = 1
    private const val DATE = 2
    private const val MONEY = 3
    private const val HUNDREDTHS = 4
    private const val MONEY_BOLD = 5

    /** Шрифты: обычный и жирный; форматы: дата «22.10.2026» (свой, 164), «# ##0,00» (4), «0,00» (2). */
    private const val STYLES = HEAD + """<styleSheet xmlns="$MAIN_NS">""" +
        """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd.mm.yyyy"/></numFmts>""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="6">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="4" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""" +
        """</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
