package com.akenory.personalka.domain.backup

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.domain.money.SalaryYear
import com.akenory.personalka.domain.money.YearMonthRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Выгрузка в Excel (ТЗ 7.6, шаг 3.8в): числа ячеек, листы и суммы зарплаты. */
class ExcelExportTest {

    @Test
    fun cellNumbers() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA"), listOf(0, 25, 26, 51, 52).map { Xlsx.column(it) })
        assertEquals("124800.00", Xlsx.decimal(12_480_000))
        assertEquals("-4165.43", Xlsx.decimal(-416_543))
        assertEquals("0.05", Xlsx.decimal(5))
        assertEquals(25569, Xlsx.serial(LocalDate(1970, 1, 1)))
        assertEquals(46315, Xlsx.serial(LocalDate(2026, 10, 20)))
        assertEquals("A &amp; B &lt;c&gt; &quot;d&quot;\nстрока", Xlsx.escape("A & B <c> \"d\"\nстрока\u0001"))
        assertEquals("trener-data-20261020.xlsx", BackupRules.excelFileName(LocalDate(2026, 10, 20)))
    }

    @Test
    fun workbookFiles() {
        val sheets = ExcelExport.sheets(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("Клиенты", "Тренировки", "Результаты", "Зарплата по месяцам"), sheets.map { it.name })
        val files = Xlsx.files(sheets)
        assertEquals("[Content_Types].xml", files.keys.first())
        assertTrue((1..4).all { "xl/worksheets/sheet$it.xml" in files })
        assertTrue(files.getValue("xl/workbook.xml").contains("""<sheet name="Зарплата по месяцам" sheetId="4" r:id="rId4"/>"""))
        assertTrue(files.getValue("xl/_rels/workbook.xml.rels").contains("""Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles""""))
        // Пустой лист — только строка заголовков, закреплённая при прокрутке.
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>"""))
        assertTrue(sheet.contains("""<c r="A1" t="inlineStr" s="1"><is><t xml:space="preserve">Фамилия</t></is></c>"""))
    }

    @Test
    fun trainingRow() {
        val sheet = ExcelExport.trainingSheet(
            listOf(
                ExcelTraining(
                    start = LocalDateTime(2026, 10, 20, 15, 0), durationMin = 45, type = "Группа", place = "Большой бассейн", group = "Сб 9:00",
                    people = listOf(ClientName("Петрова", "Аня") to ParticipationStatus.CANCELLED_CHARGED, ClientName("Орлов", "Миша") to ParticipationStatus.ATTENDED),
                    status = TrainingStatus.DONE, conductedBy = ConductedBy.SELF_AND_COLLEAGUE, colleague = "Лена",
                    cancelReason = null, plan = "", fact = "Кроль",
                ),
            ),
        )
        val row = sheet.rows.single()
        assertEquals(XlsxCell.Day(LocalDate(2026, 10, 20)), row[0])
        assertEquals(XlsxCell.Text("вт"), row[1])
        assertEquals(XlsxCell.Text("15:00"), row[2])
        assertEquals(XlsxCell.Text("Орлов Миша, Петрова Аня — отмена со списанием"), row[7])
        assertEquals(XlsxCell.Text("я и Лена"), row[9])
        assertEquals(null, row[11])
        assertEquals(XlsxCell.Text("Кроль"), row[12])
    }

    @Test
    fun salaryMonthsAndTotals() {
        fun row(month: Int, earned: Long, period: MonthPeriod, plan: Long?, forecast: Long? = null) =
            YearMonthRow(YearMonth(2026, month), period, earned, forecast, plan, earned * 13 / 100, earned - earned * 13 / 100, 3, 10, 90)
        val year = SalaryYear(
            2026,
            listOf(row(9, 9_000_000, MonthPeriod.PAST, 8_000_000), row(10, 4_165_43, MonthPeriod.CURRENT, 10_000_000, forecast = 9_500_000)),
            YearMonth(2026, 10),
        )
        val sheet = ExcelExport.salarySheet(listOf(year, SalaryYear(2025, emptyList(), null)))
        assertEquals(3, sheet.rows.size)
        val sep = sheet.rows[0]
        assertEquals(XlsxCell.Text("Сентябрь"), sep[1])
        assertEquals(XlsxCell.Money(9_000_000), sep[2])
        assertEquals(null, sep[3])
        assertEquals(XlsxCell.Whole(112), sep[5])
        assertEquals(XlsxCell.Hundredths(150), sep[10])
        assertEquals(XlsxCell.Money(9_500_000), sheet.rows[1][3])
        // «Итого 2026» — жирным, ровно суммы «Года».
        val total = sheet.rows[2]
        assertEquals(setOf(2), sheet.boldRows)
        assertEquals(XlsxCell.Text("Итого 2026"), total[1])
        assertEquals(XlsxCell.Money(year.earned), total[2])
        assertEquals(XlsxCell.Money(9_000_000 + 4_165_43), total[2])
        assertEquals(XlsxCell.Money(year.onHand), total[7])
        assertEquals(XlsxCell.Whole(6), total[8])
        assertEquals(XlsxCell.Hundredths(300), total[10])
        val xml = Xlsx.files(listOf(sheet)).getValue("xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("""<c r="C4" s="5"><v>94165.43</v></c>"""))
    }
}
