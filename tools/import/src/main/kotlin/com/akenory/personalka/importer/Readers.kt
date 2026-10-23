package com.akenory.personalka.importer

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.importing.GroupCode
import com.akenory.personalka.domain.importing.ImportRules
import com.akenory.personalka.domain.importing.ImportedStatus
import com.akenory.personalka.domain.importing.ResultTime
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month

// ТЗ 9.1. Что берётся из каждого листа. Всё, что прочитать не удалось, — в [Issues]: строка с проблемой
// не переносится, пока её не исправят в таблице или в файле сопоставления.

/** Место в таблицах для отчёта: «Смирнова Ольга.xlsx · Тренировки · строка 14». */
fun where(book: Book, sheet: Sheet? = null, row: Row? = null): String =
    listOfNotNull(book.file.name, sheet?.name, row?.let { "строка ${it.number}" }).joinToString(" · ")

/** Проблемы (мешают импорту) и исправленное само (только для сведения). */
class Issues {
    val problems = mutableListOf<String>()
    val fixed = mutableListOf<String>()

    fun problem(where: String, text: String) {
        problems += "$where: $text"
    }

    fun fixed(where: String, text: String) {
        fixed += "$where: $text"
    }
}

data class RawTraining(
    val where: String,
    val date: LocalDate,
    val time: LocalTime,
    val status: ImportedStatus,
    val plan: String?,
    val fact: String?,
    val behavior: String?,
)

data class RawResult(
    val where: String,
    val date: LocalDate,
    val distanceM: Int,
    val style: SwimStyle,
    val time: ResultTime,
    val kind: ResultKind,
    val eventName: String?,
)

/** Таблица одного клиента. */
data class ClientTable(
    val where: String,
    val name: ClientName,
    val birth: LocalDate?,
    val photo: String?,
    val note: String?,
    val trainings: List<RawTraining>,
    val results: List<RawResult>,
)

/** Групповая тренировка из листа «<Месяц> группы»: дети — как написаны в таблице. */
data class GroupRow(val where: String, val date: LocalDate, val time: LocalTime, val code: GroupCode, val codeText: String, val children: List<String>)

/** Сумма столбца месячного листа по строкам дней и то, что стоит в строке «ИТОГО». */
data class ColumnTotal(val title: String, val days: Double, val itogo: Double?)

/** Месячный лист зарплаты: итоги посчитаны по строкам дней (ТЗ 9.2), подписанные значения — как есть. */
data class MonthSheet(
    val where: String,
    val month: Month,
    val year: Int?,
    val days: Int,
    val totals: List<ColumnTotal>,
    val values: List<Pair<String, String>>,
)

/** Таблица зарплаты: групповые тренировки, месяцы и ставки. */
data class SalaryTable(val groups: List<GroupRow>, val months: List<MonthSheet>, val rates: List<Pair<String, String>>)

object ClientReader {
    private const val INFO = "Общая информация"
    private const val TRAININGS = "Тренировки"
    private const val RESULTS = "Результаты"

    private val INFO_LABELS = listOf("фио|фамилия|клиент|имя", "дата рожд|д.р.|д/р", "фото", "заметк|примечан|комментар|о клиенте")
    private val TRAINING_COLUMNS = listOf("дата", "время", "статус", "план", "факт", "поведен")
    private val RESULT_COLUMNS = listOf("дата", "дистанц", "время|результат", "тип|стиль|вид", "соревн|старт|где")

    fun isClientBook(book: Book): Boolean = book.sheet(TRAININGS) != null || book.sheet(INFO) != null

    fun read(book: Book, issues: Issues): ClientTable? {
        val here = where(book)
        val info = book.sheet(INFO)
        if (info == null) issues.problem(here, "нет листа «$INFO»")
        val nameText = info?.labeled(INFO_LABELS[0], INFO_LABELS)?.text.orEmpty()
        val fromFile = book.file.nameWithoutExtension
        val name = ImportRules.name(nameText) ?: ImportRules.name(fromFile)
        if (name == null) {
            issues.problem(here, "не понять ФИО: в листе «${nameText.ifEmpty { "пусто" }}», в имени файла «$fromFile»")
            return null
        }
        val birthCell = info?.labeled(INFO_LABELS[1], INFO_LABELS)
        val birth = birthCell?.asDate()
        if (birthCell != null && birth == null) issues.problem(here, "не понять дату рождения «${birthCell.text}»")
        val photo = info?.labeled(INFO_LABELS[2], INFO_LABELS)?.let { it.link ?: it.text }?.ifBlank { null }
        val note = info?.labeled(INFO_LABELS[3], INFO_LABELS)?.text?.ifBlank { null }
        return ClientTable(here, name, birth, photo, note, trainings(book, issues), results(book, issues))
    }

    private fun trainings(book: Book, issues: Issues): List<RawTraining> {
        val sheet = book.sheet(TRAININGS) ?: return emptyList<RawTraining>().also { issues.problem(where(book), "нет листа «$TRAININGS»") }
        val header = sheet.header(TRAINING_COLUMNS, need = 3)
        if (header == null || header["дата"] == null || header["статус"] == null) {
            if (sheet.rows.all { it.isEmpty }) return emptyList()
            issues.problem(where(book, sheet), "не найден заголовок «Дата · Время · Статус · План · Факт · Поведение»; начало листа: ${sheet.preview()}")
            return emptyList()
        }
        // Строки без даты и времени — заготовки таблицы, не тренировки.
        val rows = dataRows(sheet, header).filter { !it[header["дата"]].isEmpty || !it[header["время"]].isEmpty }
        return rows.mapNotNull { row ->
            val here = where(book, sheet, row)
            val date = row[header["дата"]].asDate()
            val time = row[header["время"]].asTime()
            val status = ImportRules.status(row[header["статус"]].text)
            when {
                date == null -> issues.problem(here, "не понять дату «${row[header["дата"]].text}»")
                time == null -> issues.problem(here, if (row[header["время"]].isEmpty) "пустое время" else "не понять время «${row[header["время"]].text}»")
                status == null -> issues.problem(
                    here,
                    if (row[header["статус"]].isEmpty) "пустой статус" else "неизвестный статус «${row[header["статус"]].text}»",
                )
                else -> return@mapNotNull RawTraining(
                    here, date, time, status,
                    text(row[header["план"]]), text(row[header["факт"]]), text(row[header["поведен"]]),
                )
            }
            null
        }
    }

    private fun results(book: Book, issues: Issues): List<RawResult> {
        val sheet = book.sheet(RESULTS) ?: return emptyList()
        val header = sheet.header(RESULT_COLUMNS, need = 3)
        if (header == null || header["дата"] == null || header["время|результат"] == null) {
            if (sheet.rows.all { it.isEmpty }) return emptyList()
            issues.problem(where(book, sheet), "не найден заголовок «Дата · Дистанция · Время · Тип»; начало листа: ${sheet.preview()}")
            return emptyList()
        }
        val rows = dataRows(sheet, header).filter { !it[header["дата"]].isEmpty || !it[header["время|результат"]].isEmpty }
        return rows.mapNotNull { row ->
            val here = where(book, sheet, row)
            val distanceText = row[header["дистанц"]].text
            val typeText = row[header["тип|стиль|вид"]].text
            val timeText = row[header["время|результат"]].let { if (it.number != null && it.date == null) String.format(java.util.Locale.ROOT, "%.2f", it.number) else it.text }
            val date = row[header["дата"]].asDate()
            val distance = ImportRules.distance(distanceText)
            val style = ImportRules.style(distanceText) ?: ImportRules.style(typeText)
            val time = ImportRules.resultTime(timeText)
            val event = text(row[header["соревн|старт|где"]])
            when {
                date == null -> issues.problem(here, "результат: не понять дату «${row[header["дата"]].text}»")
                distance == null -> issues.problem(here, "результат: не понять дистанцию «$distanceText»")
                style == null -> issues.problem(here, "результат: не понять стиль в «$distanceText» / «$typeText»")
                time == null -> issues.problem(here, "результат: не понять время «$timeText»")
                else -> {
                    val competition = event != null || "сорев" in ImportRules.key(typeText)
                    return@mapNotNull RawResult(
                        here, date, distance, style, time,
                        if (competition) ResultKind.COMPETITION else ResultKind.TRAINING, event,
                    )
                }
            }
            null
        }
    }
}

object SalaryReader {
    private const val SETTINGS = "Настройки"
    private val GROUP_COLUMNS = listOf("дата", "время", "тип", "дет|состав|ученик|кто")
    private val MONTH_LABELS = listOf("заработ", "налог", "получ", "план", "аванс", "бонус", "преми")

    fun isSalaryBook(book: Book): Boolean = book.sheets.any { ImportRules.isGroupsSheet(it.name) } || book.sheet(SETTINGS) != null

    fun read(book: Book, issues: Issues): SalaryTable {
        val groups = book.sheets.filter { ImportRules.isGroupsSheet(it.name) }.flatMap { groups(book, it, issues) }
        val months = book.sheets.filter { ImportRules.sheetMonth(it.name) != null && !ImportRules.isGroupsSheet(it.name) }
            .mapNotNull { month(book, it, issues) }
        val rates = book.sheet(SETTINGS)?.let { pairs(it) }.orEmpty()
        if (book.sheet(SETTINGS) == null) issues.problem(where(book), "нет листа «$SETTINGS» со ставками")
        return SalaryTable(groups, months, rates)
    }

    private fun groups(book: Book, sheet: Sheet, issues: Issues): List<GroupRow> {
        val header = sheet.header(GROUP_COLUMNS, need = 3)
        if (header == null || header["дата"] == null || header["время"] == null || header["тип"] == null) {
            if (sheet.rows.all { it.isEmpty }) return emptyList()
            issues.problem(where(book, sheet), "не найден заголовок «Дата · Время · Тип · Дети»; начало листа: ${sheet.preview()}")
            return emptyList()
        }
        val firstChild = header["дет|состав|ученик|кто"] ?: (maxOf(header["дата"]!!, header["время"]!!, header["тип"]!!) + 1)
        val rows = dataRows(sheet, header)
        val dates = rows.mapNotNull { it[header["дата"]].asDate() }
        val year = ImportRules.sheetYear(sheet.name) ?: ImportRules.usualYear(dates)
        val result = mutableListOf<GroupRow>()
        var lastDate: LocalDate? = null
        for (row in rows) {
            val here = where(book, sheet, row)
            val children = row.cells.drop(firstChild).flatMap { names(it.text) }
            val dateCell = row[header["дата"]]
            val timeCell = row[header["время"]]
            val codeCell = row[header["тип"]]
            // Дети на следующей строке без даты, времени и типа — продолжение той же тренировки.
            if (dateCell.isEmpty && timeCell.isEmpty && codeCell.isEmpty) {
                val previous = result.lastOrNull()
                if (previous != null && children.isNotEmpty()) result[result.lastIndex] = previous.copy(children = previous.children + children)
                continue
            }
            // Дата в объединённой ячейке стоит только у первой тренировки дня.
            var date = dateCell.asDate() ?: if (dateCell.isEmpty) lastDate else null
            if (date != null && year != null && date.year != year) {
                val fixedDate = ImportRules.fixYear(date, year)
                issues.fixed(here, "год ${date.year} исправлен на $year: ${fmt(fixedDate)}")
                date = fixedDate
            }
            val time = timeCell.asTime()
            val code = ImportRules.groupCode(codeCell.text)
            when {
                date == null -> issues.problem(here, "группа: не понять дату «${dateCell.text}»")
                time == null -> issues.problem(here, if (timeCell.isEmpty) "группа: пустое время" else "группа: не понять время «${timeCell.text}»")
                code == null -> issues.problem(here, "группа: тип «${codeCell.text}» — ждём Д, Д+Л или Л+Д")
                children.isEmpty() -> issues.problem(here, "группа: нет детей")
                else -> result += GroupRow(here, date, time, code, codeCell.text.replace(" ", ""), children)
            }
            if (date != null) lastDate = date
        }
        return result
    }

    private fun month(book: Book, sheet: Sheet, issues: Issues): MonthSheet? {
        val month = ImportRules.sheetMonth(sheet.name) ?: return null
        val header = sheet.header(listOf("дата|число|день"), need = 1)
        if (header == null) {
            if (sheet.rows.all { it.isEmpty }) return null
            issues.problem(where(book, sheet), "не найден столбец «Дата» со строками дней; начало листа: ${sheet.preview()}")
            return null
        }
        val dateColumn = header["дата|число|день"]!!
        val titles = sheet.rows[header.index].cells.withIndex()
            .filter { (i, c) -> i != dateColumn && !c.isEmpty }
            .map { (i, c) -> i to c.text.replace('\n', ' ') }
        val below = sheet.rows.drop(header.index + 1)
        val dayRows = below.filter { isDay(it[dateColumn]) }
        val itogo = below.firstOrNull { row -> row.cells.any { it.key.startsWith("итог") || it.key.startsWith("всего") } }
        val totals = titles.mapNotNull { (column, title) ->
            val values = dayRows.mapNotNull { it[column].number }
            if (values.isEmpty()) null else ColumnTotal(title, values.sum(), itogo?.get(column)?.number)
        }
        val dates = dayRows.mapNotNull { it[dateColumn].asDate() }
        val year = ImportRules.sheetYear(sheet.name) ?: ImportRules.usualYear(dates)
        // Подписи вне таблицы дней: «План — 120 000», «Аванс — 30 000». Столбцы таблицы дней уже в totals.
        val values = MONTH_LABELS.mapNotNull { label ->
            sheet.rows.filterIndexed { i, _ -> i != header.index }.firstNotNullOfOrNull { row ->
                val column = row.cells.indexOfFirst { it.key.startsWith(label) }
                if (column < 0) return@firstNotNullOfOrNull null
                row.cells.drop(column + 1).firstOrNull { !it.isEmpty }?.let { row.cells[column].text to it.text }
            }
        }
        if (dayRows.isEmpty()) issues.problem(where(book, sheet), "нет строк дней под заголовком «${sheet.rows[header.index][dateColumn].text}»")
        return MonthSheet(where(book, sheet), month, year, dayRows.size, totals, values)
    }

    /** Строка дня: в столбце даты — дата или число 1–31. */
    private fun isDay(cell: Cell): Boolean =
        cell.asDate() != null || (cell.number?.let { it == Math.floor(it) && it in 1.0..31.0 } ?: false)

    /** Пары «подпись — число» на листе настроек: ставки. */
    private fun pairs(sheet: Sheet): List<Pair<String, String>> = sheet.rows.mapNotNull { row ->
        val label = row.cells.indexOfFirst { it.text.isNotBlank() && it.number == null }
        if (label < 0) return@mapNotNull null
        val value = row.cells.drop(label + 1).firstOrNull { it.number != null } ?: return@mapNotNull null
        row.cells[label].text to value.text
    }

    /** Дети в ячейке: по одному или списком через запятую, точку с запятой или с новой строки. */
    private fun names(text: String): List<String> = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Строки под заголовком до конца листа: пустые и «ИТОГО» пропускаются. */
private fun dataRows(sheet: Sheet, header: Header): List<Row> = sheet.rows.drop(header.index + 1).filter { row ->
    !row.isEmpty && row.cells.none { it.key.startsWith("итог") || it.key.startsWith("всего") }
}

private fun text(cell: Cell): String? = cell.text.trim().ifEmpty { null }

fun fmt(date: LocalDate): String = com.akenory.personalka.domain.format.DateFormat.full(date)
