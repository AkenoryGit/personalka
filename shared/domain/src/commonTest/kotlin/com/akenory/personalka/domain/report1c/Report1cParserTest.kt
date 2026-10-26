package com.akenory.personalka.domain.report1c

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class Report1cParserTest {
    private val header = listOf(
        PdfTextRun(49.2, 783.4, "Дата"), PdfTextRun(89.6, 783.4, "Услуга"),
        PdfTextRun(173.2, 783.4, "Наименование услуги"), PdfTextRun(265.3, 783.4, "Клиент"),
        PdfTextRun(368.4, 783.4, "Статус"), PdfTextRun(411.2, 783.4, "Количество услуг"),
        PdfTextRun(480.4, 783.4, "Клиентов"), PdfTextRun(520.9, 783.4, "Стоимость"),
    )
    private val top = listOf(
        PdfTextRun(30.0, 805.6, "Тренеров Иван Петрович"),
        PdfTextRun(180.7, 794.3, "Отчет по тренировкам за период: 01.10.2026 - 31.10.2026"),
    )

    private fun row(y: Double, date: String, client: List<String>, status: String = "Пришел", price: String = "2 200,00") =
        listOf(
            PdfTextRun(29.9, y, date), PdfTextRun(89.5, y, "Персональное занятие"),
            PdfTextRun(173.0, y + 4.1, "Персональная"), PdfTextRun(173.0, y - 4.1, "тренировка. ББ "),
            PdfTextRun(368.3, y, status), PdfTextRun(473.0, y, "1"), PdfTextRun(513.6, y, "1"), PdfTextRun(535.1, y, price),
        ) + when (client.size) {
            1 -> listOf(PdfTextRun(265.2, y, client[0]))
            else -> client.mapIndexed { i, s -> PdfTextRun(265.2, y + 4.1 - 8.2 * i, s) }
        }

    private fun totals(y: Double, n: Int) = listOf(
        PdfTextRun(390.0, y, "Итого:"), PdfTextRun(473.0, y, "$n"), PdfTextRun(513.6, y, "$n"), PdfTextRun(530.0, y, "4 400"),
    )

    @Test
    fun readsRowsAcrossPagesWithHeaderOnlyOnFirst() {
        val page1 = top + header + row(769.4, "01.10.2026 08:00", listOf("Орехов Марк Сергеевич"))
        val page2 = row(802.2, "02.10.2026 10:00", listOf("Ганеева Кира ", "Олеговна"), "Отменил", "0.00") + totals(780.0, 2)
        val r = assertIs<Report1cRead.Read>(Report1cParser.parse(listOf(page1, page2))).report
        assertEquals("Тренеров Иван Петрович", r.coach)
        assertEquals(2, r.rows.size)
        assertEquals("Ганеева Кира Олеговна", r.rows[1].client)
        assertEquals(Status1c.CANCELLED, r.rows[1].status)
        assertEquals(0L, r.rows[1].price)
        assertEquals(LocalDateTime(2026, 10, 2, 10, 0), r.rows[1].at)
        assertEquals("Персональная тренировка. ББ", r.rows[0].serviceName)
    }

    @Test
    fun headerAtPageBottomIsNotPartOfLastRow() {
        val page = top + header + row(769.4, "01.10.2026 08:00", listOf("Орехов Марк Сергеевич")) +
            header.map { it.copy(y = 760.0) } + totals(740.0, 1)
        val r = assertIs<Report1cRead.Read>(Report1cParser.parse(listOf(page))).report
        assertEquals("Орехов Марк Сергеевич", r.rows.single().client)
        assertEquals(Status1c.CAME, r.rows.single().status)
    }

    @Test
    fun countDifferentFromTotalIsIncomplete() {
        val page = top + header + row(769.4, "01.10.2026 08:00", listOf("Орехов Марк Сергеевич")) + totals(740.0, 3)
        val read = assertIs<Report1cRead.Incomplete>(Report1cParser.parse(listOf(page)))
        assertEquals(3, read.expected)
        assertEquals(1, read.report.rows.size)
    }

    @Test
    fun withoutTotalIsIncomplete() {
        val page = top + header + row(769.4, "01.10.2026 08:00", listOf("Орехов Марк Сергеевич"))
        assertIs<Report1cRead.Incomplete>(Report1cParser.parse(listOf(page)))
    }

    @Test
    fun withoutTableIsNotAReport() {
        assertEquals(Report1cRead.NotAReport, Report1cParser.parse(listOf(top)))
        assertEquals(Report1cRead.NotAReport, Report1cParser.parse(emptyList()))
        assertEquals(Report1cRead.NotAReport, Report1cParser.parse("не PDF".encodeToByteArray()))
    }

    @Test
    fun exportTimeFromFileName() {
        assertEquals(LocalDateTime(2026, 10, 18, 10, 48, 41), Report1cParser.exportedAt("trener-report-20261018T104841.pdf"))
        assertNull(Report1cParser.exportedAt("otchet.pdf"))
    }

    @Test
    fun moneyInKopecks() {
        assertEquals(220_000L, Report1cParser.kopecks("2 200,00"))
        assertEquals(75_000L, Report1cParser.kopecks("750,00"))
        assertEquals(0L, Report1cParser.kopecks("0.00"))
        assertEquals(40_651_000L, Report1cParser.kopecks("406 510"))
        assertNull(Report1cParser.kopecks(""))
    }

    @Test
    fun nameKeyIgnoresCaseYoAndPatronymic() {
        assertEquals("родионов федор", Names1c.key("  Родионов   Фёдор Андреевич"))
        assertEquals(Names1c.key("ГАНЕЕВА Кира"), Names1c.key("Ганеева кира Олеговна"))
    }
}
