package com.akenory.personalka.importer

import com.akenory.personalka.domain.importing.ImportRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import kotlinx.datetime.toKotlinLocalDateTime
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.util.Locale
import org.apache.poi.ss.usermodel.Cell as PoiCell

// Чтение xlsx: книга → листы → строки → ячейки, уже без Apache POI. Дальше код работает только с этим видом,
// поэтому тесты собирают таблицы и через POI, и прямо в памяти.

/** Ячейка: показанный текст, число, дата (если у ячейки формат даты) и ссылка. */
data class Cell(val text: String = "", val number: Double? = null, val date: LocalDateTime? = null, val link: String? = null) {
    val isEmpty: Boolean get() = text.isBlank() && number == null && date == null
    val key: String get() = ImportRules.key(text)

    /** Дата: из формата ячейки или из текста «22.10.2026». */
    fun asDate(): LocalDate? = date?.date ?: ImportRules.date(text)

    /**
     * Время тренировки. Число 9.3 — это «9.30», а Google в русской таблице превращает «10.05» в дату 10 мая —
     * её день и месяц и есть часы и минуты. Время с форматом времени берётся как есть.
     */
    fun asTime(): LocalTime? {
        if (date != null) {
            val serial = number ?: 0.0
            return when {
                serial < 1.0 || date.time != LocalTime(0, 0) -> date.time
                date.day <= 23 -> LocalTime(date.day, date.month.number)
                else -> null
            }
        }
        if (number != null) return ImportRules.time(String.format(Locale.ROOT, "%.2f", number))
        return ImportRules.time(text)
    }
}

/** Строка листа с номером, как его видно в Excel (с 1). */
data class Row(val number: Int, val cells: List<Cell>) {
    operator fun get(column: Int?): Cell = column?.let { cells.getOrNull(it) } ?: EMPTY
    val isEmpty: Boolean get() = cells.all { it.isEmpty }

    companion object {
        val EMPTY = Cell()
    }
}

/** Заголовок таблицы на листе: номер строки в [Sheet.rows] и столбцы по ключевым словам. */
data class Header(val index: Int, val columns: Map<String, Int>) {
    operator fun get(word: String): Int? = columns[word]
}

data class Sheet(val name: String, val rows: List<Row>) {
    val key: String get() = ImportRules.key(name)

    /**
     * Строка заголовка в первых 20 строках: та, где больше всего ячеек начинаются с одного из [words]. Нужно,
     * чтобы нашлось хотя бы [need]. Ключ столбца — само слово из [words]; синонимы — через «|»: «дет|состав».
     */
    fun header(words: List<String>, need: Int): Header? = rows.take(HEADER_ROWS).withIndex()
        .map { (index, row) ->
            val columns = buildMap {
                for (word in words) {
                    val variants = word.split('|')
                    val column = row.cells.indexOfFirst { cell -> variants.any { cell.key.startsWith(it) } }
                    if (column >= 0) put(word, column)
                }
            }
            Header(index, columns)
        }
        .filter { it.columns.size >= need }
        .maxByOrNull { it.columns.size }

    /**
     * Значение по подписи — для листов вида «ФИО: …»: ячейка справа от подписи, а если подписи стоят в одной
     * строке как заголовок — ячейка под ней. Подпись — начало текста ячейки, синонимы через «|».
     */
    fun labeled(label: String, all: List<String>): Cell? {
        val variants = label.split('|')
        for ((index, row) in rows.withIndex()) {
            val column = row.cells.indexOfFirst { cell -> variants.any { cell.key.startsWith(it) } }
            if (column < 0) continue
            val labelsInRow = row.cells.count { cell -> all.flatMap { it.split('|') }.any { cell.key.startsWith(it) } }
            if (labelsInRow < 2) {
                row.cells.drop(column + 1).firstOrNull { !it.isEmpty }?.let { return it }
            }
            return rows.getOrNull(index + 1)?.get(column)?.takeIf { !it.isEmpty }
        }
        return null
    }

    /** Первые строки листа текстом — в отчёт, когда лист не понят: по ним видно, как он устроен. */
    fun preview(lines: Int = 3): String = rows.filter { !it.isEmpty }.take(lines).joinToString(" / ") { row ->
        row.cells.filter { !it.isEmpty }.take(8).joinToString(" | ") { it.text.take(20) }
    }

    companion object {
        const val HEADER_ROWS = 20
    }
}

data class Book(val file: File, val sheets: List<Sheet>) {
    fun sheet(name: String): Sheet? = sheets.firstOrNull { it.key == ImportRules.key(name) }
}

object Workbooks {
    private val formatter = DataFormatter(Locale.forLanguageTag("ru"))

    /** Вся книга в память: 40 таблиц клиентов — это мегабайты, не больше. Пароль или битый файл — исключение. */
    fun read(file: File): Book = WorkbookFactory.create(file, null, true).use { workbook ->
        val sheets = (0 until workbook.numberOfSheets).map { index ->
            val sheet = workbook.getSheetAt(index)
            val rows = sheet.mapNotNull { row ->
                val last = row.lastCellNum.toInt()
                if (last <= 0) return@mapNotNull null
                Row(row.rowNum + 1, (0 until last).map { cell(row.getCell(it)) })
            }
            Sheet(workbook.getSheetName(index), rows)
        }
        Book(file, sheets)
    }

    private fun cell(c: PoiCell?): Cell {
        if (c == null) return Cell()
        val link = c.hyperlink?.address ?: if (c.cellType == CellType.FORMULA) hyperlinkOf(c.cellFormula) else null
        val type = if (c.cellType == CellType.FORMULA) c.cachedFormulaResultType else c.cellType
        return when (type) {
            CellType.STRING -> Cell(c.richStringCellValue.string.trim(), link = link)
            CellType.NUMERIC -> {
                val value = c.numericCellValue
                val style = c.cellStyle
                val shown = formatter.formatRawCellContents(value, style.dataFormat.toInt(), style.dataFormatString).trim()
                val date = if (DateUtil.isCellDateFormatted(c)) DateUtil.getLocalDateTime(value).toKotlinLocalDateTime() else null
                Cell(shown, value, date, link)
            }
            CellType.BOOLEAN -> Cell(if (c.booleanCellValue) "да" else "нет", link = link)
            else -> Cell(link = link)
        }
    }

    private fun hyperlinkOf(formula: String): String? =
        Regex("HYPERLINK\\(\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(formula)?.groupValues?.get(1)
}
