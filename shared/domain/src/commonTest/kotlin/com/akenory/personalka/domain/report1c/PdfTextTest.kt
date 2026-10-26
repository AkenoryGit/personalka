package com.akenory.personalka.domain.report1c

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfTextTest {
    /** PDF из двух страниц без сжатия: простой шрифт и шрифт с `ToUnicode` (как у 1С, коды по два байта). */
    private fun pdf(): ByteArray {
        val cmap = """
            /CIDInit /ProcSet findresource begin
            1 begincodespacerange <0000> <FFFF> endcodespacerange
            2 beginbfchar <0001> <0414> <0002> <0430> endbfchar
            1 beginbfrange <0003> <0005> <0442> endbfrange
            1 beginbfrange <0006> <0007> [<0020> <0031>] endbfrange
            endcmap
        """.trimIndent()
        val page1 = "BT /F1 10 Tf 30 800 Td (Hello \\(1\\)) Tj 0 -12 Td [(Wor) -20 (ld)] TJ ET"
        val page2 = "q 1 0 0 1 10 20 cm BT /F2 8 Tf 1 0 0 1 100 700 Tm [<00010002>-11<0003000400050006>] TJ T* <0007> Tj ET Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [ 3 0 R 4 0 R ] /Count 2 /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>",
            "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Contents [ 8 0 R ] >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Font /Subtype /Type0 /Encoding /Identity-H /ToUnicode 9 0 R >>",
            "<< /Length ${page1.length} >>\nstream\n$page1\nendstream",
            "<< /Length 10 0 R >>\nstream\n$page2\nendstream",
            "<< /Length ${cmap.encodeToByteArray().size} >>\nstream\n$cmap\nendstream",
            "${page2.length}",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        objects.forEachIndexed { i, o -> sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun runsWithPositionsAndUnicode() {
        val pages = PdfText.pages(pdf())
        assertEquals(2, pages.size)
        assertEquals(listOf(PdfTextRun(30.0, 800.0, "Hello (1)"), PdfTextRun(30.0, 788.0, "World")), pages[0])
        // Сдвиг cm (10, 20) складывается с Tm; T* без TL — та же строка.
        assertEquals(PdfTextRun(110.0, 720.0, "Датуф "), pages[1][0])
        assertEquals("1", pages[1][1].text)
    }

    @Test
    fun notPdf() {
        assertFailsWith<PdfText.NotPdf> { PdfText.pages("hello".encodeToByteArray()) }
        assertFailsWith<PdfText.NotPdf> { PdfText.pages("%PDF-1.4\n".encodeToByteArray()) }
    }
}
