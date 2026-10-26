package com.akenory.personalka.domain.report1c

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** «Услуга» отчёта 1С (ТЗ 8.1): персональное или групповое занятие; другое название — [OTHER]. */
enum class Service1c { PERSONAL, GROUP, OTHER }

/** «Статус» отчёта 1С (ТЗ 8.1): «Пришел», «Не пришел», «Отменил». */
enum class Status1c { CAME, NO_SHOW, CANCELLED }

/**
 * Строка отчёта 1С — один клиент на одном занятии (ТЗ 8.1). [at] — местное время клуба, [serviceName] — «Наименование
 * услуги» одной строкой, [client] — ФИО как в 1С, [price] — «Стоимость» в копейках (цена для клиента, не оплата тренера).
 */
data class Row1c(
    val at: LocalDateTime,
    val service: Service1c,
    val serviceName: String,
    val client: String,
    val status: Status1c,
    val services: Int,
    val clients: Int,
    val price: Long,
)

/**
 * Прочитанный отчёт 1С: [coach] — ФИО тренера из шапки, период [from]–[to], [rows] по порядку, [totalRows] — первое число
 * после «Итого:», [sessions] — «Итого занятий».
 */
data class Report1c(
    val coach: String?,
    val from: LocalDate,
    val to: LocalDate,
    val rows: List<Row1c>,
    val totalRows: Int?,
    val sessions: Int?,
)

/** Итог чтения (ТЗ 8.2, пп. 4, 6). */
sealed interface Report1cRead {
    data class Read(val report: Report1c) : Report1cRead

    /** Строк меньше или больше, чем в «Итого», — «Отчёт прочитан не полностью». */
    data class Incomplete(val report: Report1c, val expected: Int) : Report1cRead

    /** Нет таблицы — «Это не похоже на отчёт 1С по тренировкам». */
    data object NotAReport : Report1cRead
}

/**
 * Разбор PDF-отчёта 1С «Отчет по тренировкам» (ТЗ 8.2). Работает на телефоне без сервера: текст с координатами —
 * [PdfText], дальше — колонки по строке заголовка и строки по якорю-дате.
 */
object Report1cParser {
    /** Колонки слева направо — по словам строки заголовка. */
    private val HEADERS = listOf("Дата", "Услуга", "Наименование услуги", "Клиент", "Статус", "Количество услуг", "Клиентов", "Стоимость")
    private const val DATE = 0
    private const val SERVICE = 1
    private const val SERVICE_NAME = 2
    private const val CLIENT = 3
    private const val STATUS = 4
    private const val SERVICES = 5
    private const val CLIENTS = 6
    private const val PRICE = 7

    /** Текст ячейки стоит левее заголовка не больше чем на столько (у 1С — на 0,12 пт). */
    private const val LEFT_SLACK = 3.0

    /** Строчка ячейки дальше этого от своей даты по вертикали — уже не ячейка (у 1С самая высокая ячейка — ±8,3 пт). */
    private const val ROW_REACH = 12.0

    private val ANCHOR = Regex("""^(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2})$""")
    private val PERIOD = Regex("""за период:\s*(\d{2})\.(\d{2})\.(\d{4})\s*-\s*(\d{2})\.(\d{2})\.(\d{4})""")
    private val FILE_TIME = Regex("""report-(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""")

    /** Файл PDF целиком. Не PDF — [Report1cRead.NotAReport]. */
    fun parse(pdf: ByteArray): Report1cRead =
        try {
            parse(PdfText.pages(pdf))
        } catch (_: PdfText.NotPdf) {
            Report1cRead.NotAReport
        }

    /** Страницы текста (ТЗ 8.2, пп. 2–4, 6). */
    fun parse(pages: List<List<PdfTextRun>>): Report1cRead {
        val all = pages.flatten()
        val period = all.firstNotNullOfOrNull { PERIOD.find(it.text) }
        var columns: List<Double>? = null
        var coach: String? = null
        var totalRows: Int? = null
        var sessions: Int? = null
        val rows = ArrayList<Row1c>()

        for ((index, page) in pages.withIndex()) {
            val header = header(page)
            // Заголовок таблицы 1С повторяет не на каждой странице, а где придётся — и вверху, и внизу. Колонки те же.
            if (header != null) columns = header.second
            val cols = columns ?: continue
            val headerY = header?.first
            if (index == 0 && coach == null) coach = coachName(page, headerY)

            val totals = page.firstOrNull { it.text.trim().startsWith("Итого:") }
            val stopY = totals?.y
            if (totals != null) {
                totalRows = page.filter { abs(it.y - totals.y) < 1.0 && it.x > totals.x }
                    .sortedBy { it.x }
                    .firstNotNullOfOrNull { it.text.trim().toIntOrNull() }
            }
            page.firstOrNull { it.text.trim().startsWith("Итого занятий:") }?.let {
                sessions = it.text.substringAfter(':').trim().toIntOrNull()
            }

            val body = page.filter { run ->
                (headerY == null || abs(run.y - headerY) >= 1.0) && (stopY == null || run.y > stopY + 1.0) &&
                    !(header != null && header.third.any { h -> abs(h - run.y) < 1.0 })
            }
            val anchors = body.filter { column(it.x, cols) == DATE && ANCHOR.matches(it.text.trim()) }.sortedByDescending { it.y }
            if (anchors.isEmpty()) {
                if (totals != null) break
                continue
            }
            val cells = anchors.associateWith { Array(HEADERS.size) { ArrayList<PdfTextRun>() } }
            for (run in body) {
                if (run in cells) continue
                val anchor = anchors.minBy { abs(it.y - run.y) }
                if (abs(anchor.y - run.y) > ROW_REACH) continue
                cells.getValue(anchor)[column(run.x, cols)].add(run)
            }
            for (anchor in anchors) {
                row(anchor, cells.getValue(anchor))?.let(rows::add)
            }
            if (totals != null) break
        }

        if (columns == null || period == null) return Report1cRead.NotAReport
        val (d1, m1, y1, d2, m2, y2) = period.destructured
        val report = Report1c(
            coach = coach,
            from = LocalDate(y1.toInt(), m1.toInt(), d1.toInt()),
            to = LocalDate(y2.toInt(), m2.toInt(), d2.toInt()),
            rows = rows,
            totalRows = totalRows,
            sessions = sessions,
        )
        return if (totalRows == null || totalRows != rows.size) {
            Report1cRead.Incomplete(report, totalRows ?: 0)
        } else {
            Report1cRead.Read(report)
        }
    }

    /**
     * Проверка ФИО тренера (ТЗ 8.2, п. 5): `true` — в шапке другое имя, чем «Имя в 1С» тренера. Имени в 1С нет или
     * шапки нет — сравнивать не с чем, `false`.
     */
    fun otherCoach(report: Report1c, name1c: String?): Boolean {
        val mine = name1c?.let(Names1c::key)?.takeIf { it.isNotEmpty() } ?: return false
        val theirs = report.coach?.let(Names1c::key)?.takeIf { it.isNotEmpty() } ?: return false
        return mine != theirs
    }

    /** Время выгрузки из имени файла (ТЗ 8.1): `…report-20261018T104841.pdf` → 18.10.2026 10:48:41; нет — `null`. */
    fun exportedAt(fileName: String): LocalDateTime? {
        val m = FILE_TIME.find(fileName) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return runCatching { LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull()
    }

    /** Строка заголовка: её высота, левые края колонок и высоты всех строк заголовка на странице. */
    private fun header(page: List<PdfTextRun>): Triple<Double, List<Double>, List<Double>>? {
        val lines = page.filter { it.text.trim() == "Клиент" }.mapNotNull { client ->
            val line = page.filter { abs(it.y - client.y) < 1.0 }
            val xs = HEADERS.map { h -> line.firstOrNull { it.text.trim() == h }?.x }
            if (xs.any { it == null }) null else client.y to xs.map { it!! }
        }
        if (lines.isEmpty()) return null
        // Строк заголовка на странице бывает две (вверху и внизу) — колонки у них одни.
        return Triple(lines.first().first, lines.first().second, lines.map { it.first })
    }

    private fun column(x: Double, cols: List<Double>): Int {
        var c = 0
        for (i in 1 until cols.size) if (x >= cols[i] - LEFT_SLACK) c = i
        return c
    }

    /** ФИО тренера — первая строка шапки над названием отчёта. */
    private fun coachName(page: List<PdfTextRun>, headerY: Double?): String? =
        page.filter { headerY == null || it.y > headerY + 1.0 }
            .filterNot { PERIOD.containsMatchIn(it.text) || it.text.contains("Отчет") }
            .maxByOrNull { it.y }
            ?.text?.let(::clean)?.takeIf { it.isNotEmpty() }

    private fun row(anchor: PdfTextRun, cells: Array<ArrayList<PdfTextRun>>): Row1c? {
        val m = ANCHOR.matchEntire(anchor.text.trim()) ?: return null
        val (d, mo, y, h, mi) = m.destructured
        val at = runCatching { LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull() ?: return null
        fun text(c: Int) = clean(cells[c].sortedByDescending { it.y }.joinToString(" ") { it.text })
        val status = when (text(STATUS).lowercase()) {
            "пришел", "пришёл" -> Status1c.CAME
            "не пришел", "не пришёл" -> Status1c.NO_SHOW
            "отменил" -> Status1c.CANCELLED
            else -> return null
        }
        val service = when (text(SERVICE)) {
            "Персональное занятие" -> Service1c.PERSONAL
            "Групповое занятие" -> Service1c.GROUP
            else -> Service1c.OTHER
        }
        return Row1c(
            at = at,
            service = service,
            serviceName = text(SERVICE_NAME),
            client = text(CLIENT),
            status = status,
            services = text(SERVICES).toIntOrNull() ?: 1,
            clients = text(CLIENTS).toIntOrNull() ?: 1,
            price = kopecks(text(PRICE)) ?: 0,
        )
    }

    /** «2 200,00», «750,00», «0.00» → копейки. */
    internal fun kopecks(s: String): Long? {
        val t = s.filterNot { it.isWhitespace() }.replace(',', '.')
        if (t.isEmpty()) return null
        val rub = t.substringBefore('.')
        val kop = t.substringAfter('.', "").padEnd(2, '0').take(2)
        val r = rub.toLongOrNull() ?: return null
        val k = kop.toLongOrNull() ?: return null
        return r * 100 + k
    }

    /** Пробелы (в том числе неразрывные) схлопнуты, по краям убраны. */
    private fun clean(s: String) = s.split(' ', ' ', ' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")

    private fun abs(v: Double) = kotlin.math.abs(v)
}

/** Имена из 1С (ТЗ 8.3): ключ — фамилия и имя, нижний регистр, ё → е, лишние пробелы убраны; отчество не учитывается. */
object Names1c {
    fun key(fullName: String): String =
        fullName.lowercase().replace('ё', 'е').split(' ', ' ', '\t').filter { it.isNotEmpty() }.take(2).joinToString(" ")
}
