package com.akenory.personalka.domain.report1c

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Настоящий отчёт 1С за 1–25 октября 2026 (ТЗ 8.2): 9 страниц, 289 строк. ФИО тренера и клиентов в файле заменены на
 * придуманные, всё остальное — как выгрузила 1С.
 */
class Report1cFileTest {
    private val pdf = assertNotNull(javaClass.getResourceAsStream("/report1c/otchet-oktyabr-2026.pdf")).readBytes()

    private fun report(): Report1c = assertIs<Report1cRead.Read>(Report1cParser.parse(pdf)).report

    @Test
    fun allRowsMatchTotal() {
        val r = report()
        assertEquals(289, r.rows.size)
        assertEquals(289, r.totalRows)
        assertEquals(154, r.sessions)
        assertEquals(9, PdfText.pages(pdf).size)
    }

    @Test
    fun headerAndPeriod() {
        val r = report()
        assertEquals("Тренеров Иван Петрович", r.coach)
        assertEquals(LocalDate(2026, 10, 1), r.from)
        assertEquals(LocalDate(2026, 10, 31), r.to)
    }

    @Test
    fun countsMatchReportTotals() {
        val rows = report().rows
        // «Итого клиентов: Не пришло - 1, Пришло - 281, Отменило - 7» и «Итого: 289 289 406 510».
        assertEquals(281, rows.count { it.status == Status1c.CAME })
        assertEquals(7, rows.count { it.status == Status1c.CANCELLED })
        assertEquals(1, rows.count { it.status == Status1c.NO_SHOW })
        assertEquals(40_651_000L, rows.sumOf { it.price })
        assertEquals(289, rows.sumOf { it.services })
        assertEquals(156, rows.count { it.service == Service1c.GROUP })
        assertEquals(133, rows.count { it.service == Service1c.PERSONAL })
    }

    @Test
    fun multilineCellsAreJoined() {
        val rows = report().rows
        assertEquals(
            setOf(
                "Персональная тренировка. ББ",
                "Персональная тренировка. Детский бассейн 45 минут",
                "Детская групповая тренировка в бассейне 50 минут",
                "Мини-группа ББ",
                "Сплит. ББ",
                "Стартовая тренировка бассейн",
            ),
            rows.map { it.serviceName }.toSet(),
        )
        val first = rows.first()
        assertEquals(LocalDateTime(2026, 10, 1, 8, 0), first.at)
        assertEquals("Персональная тренировка. ББ", first.serviceName)
        assertEquals(220_000L, first.price)
        // Ячейка ФИО в две строчки: «Ганеева Кира» над датой, «Олеговна» под ней.
        assertEquals("Ганеева Кира Олеговна", rows[1].client)
        assertEquals("Родионов Фёдор Андреевич", rows.single { it.at == LocalDateTime(2026, 10, 25, 15, 0) }.client)
    }

    @Test
    fun cancelsAndNoShow() {
        val rows = report().rows
        val cancel = rows.single { it.client == "Пестов Егор Романович" && it.at == LocalDateTime(2026, 10, 8, 9, 0) }
        assertEquals(Status1c.CANCELLED, cancel.status)
        assertEquals(0L, cancel.price)
        val noShow = rows.single { it.status == Status1c.NO_SHOW }
        assertEquals("Волкова Варвара Денисовна", noShow.client)
        assertEquals(LocalDateTime(2026, 10, 24, 14, 0), noShow.at)
        assertEquals("Мини-группа ББ", noShow.serviceName)
    }

    @Test
    fun lastPageWithoutHeaderUsesColumnsOfPreviousPage() {
        val last = report().rows.takeLast(2)
        assertEquals(listOf("Пестов Максим Ильич", "Карпов Глеб Андреевич"), last.map { it.client })
        assertTrue(last.all { it.status == Status1c.CAME && it.service == Service1c.PERSONAL })
    }

    @Test
    fun coachCheck() {
        val r = report()
        assertEquals(false, Report1cParser.otherCoach(r, "тренеров  иван"))
        assertEquals(true, Report1cParser.otherCoach(r, "Другой Тренер"))
    }

    @Test
    fun cutFileIsNotAReport() {
        assertEquals(Report1cRead.NotAReport, Report1cParser.parse(pdf.copyOf(pdf.size / 3)))
    }

    @Test
    fun inflateMatchesJavaZip() {
        val random = Random(42)
        val samples = listOf(
            ByteArray(0),
            "Итого: 289 289 406 510".encodeToByteArray(),
            ByteArray(70_000) { (it % 251).toByte() },
            ByteArray(50_000) { random.nextInt(256).toByte() },
            ByteArray(100_000) { "абвгд ".encodeToByteArray()[random.nextInt(11)].toByte() },
        )
        for (sample in samples) for (level in listOf(0, 1, 6, 9)) {
            val d = Deflater(level)
            d.setInput(sample)
            d.finish()
            val buf = ByteArray(sample.size * 2 + 64)
            val n = d.deflate(buf)
            assertContentEquals(sample, Inflate.inflate(buf.copyOf(n)), "уровень $level, ${sample.size} байт")
        }
    }
}
