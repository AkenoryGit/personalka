package com.akenory.personalka.domain.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Раскладка наложений (ТЗ 5.3) на примерах макета «Блоки, значки, наложения» (шаг 0.3). */
class DayLayoutTest {
    private fun at(id: String, from: String, minutes: Int): DaySpan {
        val (h, m) = from.split(':').map { it.toInt() }
        return DaySpan(id, h * 60 + m, h * 60 + m + minutes)
    }

    private fun DayLayout.of(id: String): BlockPlace = requireNotNull(place(id)) { "Блок $id скрыт" }

    @Test
    fun threeAtOneTimeShareWidth() {
        // Проверка шага: три тренировки на одно время делят ширину — каждая на треть.
        val layout = DayLayoutRules.layout(listOf(at("a", "10:00", 50), at("b", "10:00", 50), at("c", "10:00", 50)))
        assertEquals(listOf(0, 1, 2), listOf("a", "b", "c").map { layout.of(it).column })
        assertTrue(layout.blocks.all { it.columns == 3 && it.span == 1 })
        assertTrue(layout.more.isEmpty())
    }

    @Test
    fun threeLikeMockupLongerFirst() {
        // «Три на одно время»: группа 18:00–19:00 левее Ильина 18:00–18:50, Новикова с 18:15 — третья.
        val layout = DayLayoutRules.layout(listOf(at("ilin", "18:00", 50), at("novikova", "18:15", 50), at("group", "18:00", 60)))
        assertEquals(0, layout.of("group").column)
        assertEquals(1, layout.of("ilin").column)
        assertEquals(2, layout.of("novikova").column)
        assertEquals(3, layout.of("novikova").columns)
    }

    @Test
    fun twoOverlapThirdAloneFullWidth() {
        // «Две пересекающиеся, третья — отдельно и на всю ширину».
        val layout = DayLayoutRules.layout(listOf(at("sokolova", "16:00", 50), at("egorova", "16:30", 50), at("nosova", "17:30", 50)))
        assertEquals(BlockPlace("sokolova", 0, 1, 2), layout.of("sokolova"))
        assertEquals(BlockPlace("egorova", 1, 1, 2), layout.of("egorova"))
        assertEquals(BlockPlace("nosova", 0, 1, 1), layout.of("nosova"))
    }

    @Test
    fun sameStartLongerFirstLikeDayMockup() {
        // Вт 20.10: отмена Зайцевой 10:00–10:50 и Белова 10:00–10:30 — длинная слева, хоть и внесена позже.
        val layout = DayLayoutRules.layout(listOf(at("belova", "10:00", 30), at("zaitseva", "10:00", 50)))
        assertEquals(0, layout.of("zaitseva").column)
        assertEquals(1, layout.of("belova").column)
    }

    @Test
    fun touchingIsNotOverlap() {
        val layout = DayLayoutRules.layout(listOf(at("a", "09:00", 60), at("b", "10:00", 60)))
        assertEquals(BlockPlace("a", 0, 1, 1), layout.of("a"))
        assertEquals(BlockPlace("b", 0, 1, 1), layout.of("b"))
    }

    @Test
    fun chainMakesOneCluster() {
        // a пересекается с b, b — с c, a с c — нет: один кластер, c встаёт в первую освободившуюся колонку.
        val layout = DayLayoutRules.layout(listOf(at("a", "09:00", 60), at("b", "09:30", 60), at("c", "10:00", 60)))
        assertEquals(BlockPlace("a", 0, 1, 2), layout.of("a"))
        assertEquals(BlockPlace("b", 1, 1, 2), layout.of("b"))
        assertEquals(BlockPlace("c", 0, 1, 2), layout.of("c"))
    }

    @Test
    fun blockStretchesOverFreeColumns() {
        // Три колонки в 10:00; d с 10:30 встаёт во вторую, а третья к этому времени свободна — d шириной в две.
        val layout = DayLayoutRules.layout(
            listOf(at("a", "10:00", 60), at("b", "10:00", 30), at("c", "10:00", 30), at("d", "10:30", 30)),
        )
        assertEquals(BlockPlace("d", 1, 2, 3), layout.of("d"))
        // b упирается в c — растягиваться некуда.
        assertEquals(BlockPlace("b", 1, 1, 3), layout.of("b"))
    }

    @Test
    fun fourVisibleFiveAsThreeAndMore() {
        val four = DayLayoutRules.layout((1..4).map { at("t$it", "10:00", 50) })
        assertEquals(4, four.blocks.size)
        assertTrue(four.more.isEmpty())

        // «Пять — видны три и «+2»».
        val five = DayLayoutRules.layout((1..5).map { at("t$it", "10:00", 50) })
        assertEquals(listOf("t1", "t2", "t3"), five.blocks.map { it.id })
        assertTrue(five.blocks.all { it.columns == 4 })
        assertNull(five.place("t4"))
        assertEquals(MorePlace(column = 3, columns = 4, start = 600, end = 650, ids = listOf("t4", "t5")), five.more.single())
    }

    @Test
    fun hiddenAtDifferentTimesGetOwnMarks() {
        // Длинная цепочка: пять наложений в 10:00 и ещё пять в 12:00 — две метки «+2», каждая на своё время.
        val items = listOf(at("long", "09:00", 300)) +
            (1..4).map { at("m$it", "10:00", 50) } + (1..4).map { at("n$it", "12:00", 50) }
        val layout = DayLayoutRules.layout(items)
        assertEquals(2, layout.more.size)
        assertEquals(listOf(600, 720), layout.more.map { it.start })
        assertEquals(listOf(2, 2), layout.more.map { it.ids.size })
    }

    @Test
    fun narrowDayFitsFewerColumns() {
        // День 348 dp (412 − 48 − 2 × 8) — четыре колонки, 3 дня на сложенном (≈102 dp) — две, не уже 36 dp.
        assertEquals(4, DayLayoutRules.maxColumns(348f))
        assertEquals(2, DayLayoutRules.maxColumns(102f))
        assertEquals(3, DayLayoutRules.maxColumns(110f))
        assertEquals(2, DayLayoutRules.maxColumns(20f))

        val three = DayLayoutRules.layout((1..3).map { at("t$it", "10:00", 50) }, maxColumns = 2)
        assertEquals(listOf("t1"), three.blocks.map { it.id })
        assertEquals(listOf("t2", "t3"), three.more.single().ids)
        assertEquals(1, three.more.single().column)
    }

    @Test
    fun emptyDay() {
        val layout = DayLayoutRules.layout(emptyList())
        assertTrue(layout.blocks.isEmpty() && layout.more.isEmpty())
    }
}
