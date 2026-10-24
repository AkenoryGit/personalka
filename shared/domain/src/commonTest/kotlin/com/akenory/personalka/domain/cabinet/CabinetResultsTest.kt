package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Результаты в кабинете (ТЗ 6.3, п. 6) на данных макета `KbCabinet`: «сейчас» — вт 20.10.2026. */
class CabinetResultsTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val today = LocalDate(2026, 10, 20)
    private var next = 0

    private fun r(date: String, distance: Int, style: SwimStyle, cs: Int, competition: Boolean = false, pool: Int = 25) = ResultItem(
        id = "r-" + (++next).toString().padStart(3, '0'),
        date = LocalDate.parse(date),
        event = SwimEvent(style, distance, pool),
        timeCs = cs,
        kind = if (competition) ResultKind.COMPETITION else ResultKind.TRAINING,
    )

    /** Результаты Ксении из макета: 50 м в/с — 6 точек с сентября, остальные дистанции — по одной-две. */
    private val mockup = listOf(
        r("2026-09-03", 50, SwimStyle.FREESTYLE, 4410),
        r("2026-09-17", 50, SwimStyle.FREESTYLE, 4280),
        r("2026-09-19", 50, SwimStyle.FREESTYLE, 4190, competition = true),
        r("2026-10-01", 50, SwimStyle.FREESTYLE, 4110),
        r("2026-10-10", 50, SwimStyle.FREESTYLE, 3962, competition = true),
        r("2026-10-15", 50, SwimStyle.FREESTYLE, 4030),
        r("2026-10-10", 25, SwimStyle.FREESTYLE, 1784, competition = true),
        r("2026-10-06", 25, SwimStyle.BACKSTROKE, 2190),
        r("2026-09-29", 50, SwimStyle.BACKSTROKE, 4715),
    )

    @Test
    fun noResultsNoSection() {
        assertNull(CabinetResultRules.results(emptyList(), today))
        val page = CabinetRules.page(CabinetInput("Орлова Ксения", "Дмитрий Дудник", null, LocalDateTime(2026, 10, 20, 15, 41), 4, 60, emptyList()))
        assertNull(page.results)
    }

    @Test
    fun recordsAndAllAsInMockup() {
        val results = assertNotNull(CabinetResultRules.results(mockup, today))
        assertEquals(1, results.records.size, "только бассейн 25 м")
        assertEquals(s("Личные рекорды · бассейн 25_м"), results.records[0].title)
        assertEquals(
            listOf(
                s("25_м вольный стиль|соревнования · 10.10|17,84"),
                s("50_м вольный стиль|соревнования · 10.10|39,62"),
                s("25_м на спине|тренировка · 06.10|21,90"),
                s("50_м на спине|тренировка · 29.09|47,15"),
            ),
            results.records[0].lines.map { "${it.event}|${it.line}|${it.time}" },
        )
        assertEquals("9", results.allCount)
        assertEquals(s("тренировка · чт_15.10"), results.all.first().line, "новые сверху, бассейн не пишется — он один")
    }

    @Test
    fun chartDefaultsToMostResultsAndChipsLinkEveryDistance() {
        val chart = assertNotNull(CabinetResultRules.results(mockup, today)?.chart)
        assertEquals(listOf(s("50_м вольный стиль")), chart.chips.map { it.label }, "график — только где результатов два и больше")
        assertTrue(chart.chips.single().selected)
        assertEquals(6, chart.dots.size)
        assertEquals(listOf(false, false, true, false, true, false), chart.dots.map { it.competition })
        assertEquals(0f, chart.dots.first().x)
        assertEquals(1f, chart.dots.last().x)
        assertTrue(chart.dots[4].y < chart.dots[0].y, "быстрее — ниже")
        assertEquals("39,62", chart.best.text)
        assertEquals(chart.dots[4].x, chart.best.x)
        assertEquals(listOf("сентябрь", "октябрь"), chart.months.map { it.text })
        assertEquals(1, chart.borders.size, "пунктир — начало октября")
        assertTrue(chart.legendCompetition && chart.legendTraining)
        assertEquals("Чем ниже точка, тем быстрее.", chart.hint)
        assertEquals(s("50_м вольный стиль: 6_результатов, с 44,10 03.09.2026 до 40,30 15.10.2026, лучшее 39,62"), chart.summary)
    }

    @Test
    fun chipChoosesDistanceUnknownFallsBack() {
        val more = mockup + r("2026-10-13", 25, SwimStyle.BACKSTROKE, 2240) + r("2026-10-14", 50, SwimStyle.FREESTYLE, 3500, pool = 50) +
            r("2026-10-18", 50, SwimStyle.FREESTYLE, 3480, pool = 50)
        val default = assertNotNull(CabinetResultRules.chart(more))
        assertEquals(
            listOf(s("50_м вольный стиль · бассейн 25_м"), s("25_м на спине · бассейн 25_м"), s("50_м вольный стиль · бассейн 50_м")),
            default.chips.map { it.label },
        )
        assertEquals(listOf("50-freestyle-25", "25-backstroke-25", "50-freestyle-50"), default.chips.map { it.key })
        assertEquals("50-freestyle-25", default.chips.single { it.selected }.key)

        val picked = assertNotNull(CabinetResultRules.chart(more, "50-freestyle-50"))
        assertEquals("50-freestyle-50", picked.chips.single { it.selected }.key)
        assertEquals(2, picked.dots.size)
        assertEquals("34,80", picked.best.text)
        assertEquals(ChartAnchor.END, picked.best.anchor, "лучшее у правого края — подпись влево")

        val unknown = assertNotNull(CabinetResultRules.chart(more, "<script>"))
        assertEquals("50-freestyle-25", unknown.chips.single { it.selected }.key)

        val results = assertNotNull(CabinetResultRules.results(more, today))
        assertEquals(listOf(s("Личные рекорды · бассейн 25_м"), s("Личные рекорды · бассейн 50_м")), results.records.map { it.title })
        assertEquals(s("тренировка · вс_18.10 · бассейн 50_м"), results.all.first().line, "два бассейна — бассейн в строке")
    }

    @Test
    fun oneResultPerDistanceNoChart() {
        val results = assertNotNull(CabinetResultRules.results(listOf(r("2026-10-10", 25, SwimStyle.FREESTYLE, 1784)), today))
        assertNull(results.chart)
        assertEquals(1, results.records.single().lines.size)
    }

    @Test
    fun manyMonthsShortLabelsThatDoNotOverlap() {
        val year = (1..12).map { m -> r("2026-${m.toString().padStart(2, '0')}-15", 50, SwimStyle.FREESTYLE, 4500 - m * 30) }
        val chart = assertNotNull(CabinetResultRules.chart(year))
        assertTrue(chart.months.isNotEmpty())
        assertTrue(chart.months.all { it.text.length == 3 }, chart.months.toString())
        assertTrue(chart.months.size < 12, "на узком графике все 12 не помещаются")
        assertEquals(11, chart.borders.size)
        // Соседние подписи не налезают: по 9 px на букву на графике шириной 190 px.
        chart.months.zipWithNext().forEach { (a, b) -> assertTrue(b.x - a.x >= 3 * 9f / 190f, "$a $b") }
    }
}
