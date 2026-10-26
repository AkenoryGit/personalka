package com.akenory.personalka.domain.result

import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Результаты (шаг 2.3, ТЗ 5.11): примеры — ТЗ и макет «Карточка · результаты» (ClCardResults). Имена условные. */
class ResultRulesTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val today = LocalDate(2026, 10, 20)

    private val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 25)
    private val free25 = SwimEvent(SwimStyle.FREESTYLE, 25, 25)
    private val back25 = SwimEvent(SwimStyle.BACKSTROKE, 25, 25)
    private val back50 = SwimEvent(SwimStyle.BACKSTROKE, 50, 25)

    private fun r(id: String, day: LocalDate, event: SwimEvent, cs: Int, competition: Boolean = false, name: String? = null) =
        ResultItem(id, day, event, cs, if (competition) ResultKind.COMPETITION else ResultKind.TRAINING, name)

    private fun d(day: Int, month: Int = 10) = LocalDate(2026, month, day)

    // Результаты Ксении из макета: рекорды 17,84 / 39,62 / 21,90 / 47,15, график 50 м в/с с сентября по октябрь.
    private val ksenia = listOf(
        r("01", d(8, 9), free50, 4410),
        r("02", d(29, 9), back50, 4715),
        r("03", d(19, 9), free50, 4280),
        r("04", d(26, 9), free50, 4200, competition = true),
        r("05", d(6), back25, 2190),
        r("06", d(6), free50, 4120),
        r("07", d(10), free25, 1784, competition = true),
        r("08", d(10), free50, 3962, competition = true),
        r("09", d(15), free50, 4030),
    )

    @Test
    fun timeIsTypedAsDigits() {
        // 5.11: «5216» → 52,16; «10317» → 1:03,17.
        assertEquals(5216, ResultTime.parse("5216"))
        assertEquals(6317, ResultTime.parse("10317"))
        assertEquals("52,16", ResultTime.format(5216))
        assertEquals("1:03,17", ResultTime.format(6317))
        // Критерий шага: «1:03,17» и «52,16» читаются так же, как цифры подряд.
        assertEquals(ResultTime.parse("10317"), ResultTime.parse("1:03,17"))
        assertEquals(ResultTime.parse("5216"), ResultTime.parse("52,16"))
        assertEquals(5216, ResultTime.parse("52.16"))
        assertEquals(5210, ResultTime.parse("52,1"))
        assertEquals("10317", ResultTime.digits("1:03,17"))
        assertEquals("5216", ResultTime.digits("52,16"))
        // Длинные дистанции: «172530» → 17:25,30.
        assertEquals((17 * 60 + 25) * 100 + 30, ResultTime.parse("172530"))
        assertEquals("17:25,30", ResultTime.format(104530))
        assertEquals("0,52", ResultTime.format(52))
    }

    @Test
    fun timeAsTyped() {
        // Поле показывает набранное сразу в виде времени.
        assertEquals("", ResultTime.typed(""))
        assertEquals("52", ResultTime.typed("52"))
        assertEquals("5,21", ResultTime.typed("521"))
        assertEquals("52,16", ResultTime.typed("5216"))
        assertEquals("1:03,17", ResultTime.typed("10317"))
        assertEquals("17:25,30", ResultTime.typed("172530"))
        assertEquals("172530", ResultTime.digits("1725301"))
    }

    @Test
    fun badTime() {
        // Меньше трёх цифр — ещё набирается; секунд больше 59 — не читается; ноль — не время.
        assertNull(ResultTime.parse("52"))
        assertNull(ResultTime.parse("17517"))
        assertNull(ResultTime.parse("7516"))
        assertNull(ResultTime.parse("000"))
        assertNull(ResultTime.parse("1:3,17"))
        assertNull(ResultTime.parse("abc"))
        assertNull(ResultFormat.timeProblem("52"))
        assertEquals(ResultFormat.BAD_TIME, ResultFormat.timeProblem("7516"))
        assertNull(ResultFormat.timeProblem("10317"))
    }

    @Test
    fun recordsByPool() {
        // Макет: 25 м в/с 17,84 (соревнования · 10.10), 50 м в/с 39,62, 25 м на спине 21,90, 50 м на спине 47,15.
        val records = ResultRules.records(ksenia, 25)
        assertEquals(listOf("07", "08", "05", "02"), records.map { it.id })
        assertEquals(listOf(1784, 3962, 2190, 4715), records.map { it.timeCs })
        assertEquals("соревнования · 10.10", ResultFormat.recordLine(records[0], today))
        assertEquals("тренировка · 29.09", ResultFormat.recordLine(records[3], today))
        assertEquals(s("25_м вольный стиль"), ResultFormat.event(records[0].event))
        assertEquals(s("50_м на спине"), ResultFormat.event(records[3].event))
        // В 50-метровом бассейне результатов нет — переключателя бассейнов нет.
        assertEquals(listOf(25), ResultRules.pools(ksenia))
        assertEquals(emptyList(), ResultRules.records(ksenia, 50))
        val long = r("10", d(18), SwimEvent(SwimStyle.FREESTYLE, 50, 50), 4100)
        assertEquals(listOf(25, 50), ResultRules.pools(ksenia + long))
        assertEquals(listOf(long), ResultRules.records(ksenia + long, 50))
        // Одинаковое время — рекорд за тем, кто показал его раньше.
        val again = r("11", d(19), free25, 1784)
        assertEquals("07", ResultRules.records(ksenia + again, 25).first().id)
    }

    @Test
    fun newRecord() {
        // 5.11: «Личный рекорд, − 7,00» — на сколько быстрее прежнего лучшего.
        val list = listOf(r("1", d(1), free50, 4662))
        assertEquals(700, ResultRules.recordGain(list, free50, 3962))
        assertEquals(s("Личный рекорд, −_7,00"), ResultFormat.record(700))
        // Не быстрее — не рекорд; первый на дистанции — тоже не рекорд: сравнивать не с чем.
        assertNull(ResultRules.recordGain(list, free50, 4662))
        assertNull(ResultRules.recordGain(list, free50, 4700))
        assertNull(ResultRules.recordGain(list, back50, 5000))
        // Бассейн — другая дистанция.
        assertNull(ResultRules.recordGain(list, free50.copy(poolM = 50), 3000))
        // Изменяемый результат с собой не сравнивается.
        assertNull(ResultRules.recordGain(list, free50, 4000, exceptId = "1"))
    }

    @Test
    fun distancesByStyle() {
        assertEquals(ResultRules.DISTANCES, ResultRules.distances(SwimStyle.FREESTYLE))
        assertEquals(listOf(25, 50, 100, 200), ResultRules.distances(SwimStyle.BUTTERFLY))
        assertEquals(listOf(100, 200, 400), ResultRules.distances(SwimStyle.MEDLEY))
        // Смена стиля: 50 м комплексом нет — 100; 800 м на спине нет — 200.
        assertEquals(100, ResultRules.fitDistance(SwimStyle.MEDLEY, 50))
        assertEquals(200, ResultRules.fitDistance(SwimStyle.BACKSTROKE, 800))
        assertEquals(50, ResultRules.fitDistance(SwimStyle.BREASTSTROKE, 50))
    }

    @Test
    fun defaults() {
        // Новый результат — на дистанции последнего внесённого; результатов нет — 50 м вольным в 25-метровом.
        assertEquals(free50, ResultRules.defaultEvent(ksenia))
        assertEquals(back50, ResultRules.defaultEvent(ksenia + r("10", d(1, 9), back50, 4800)))
        assertEquals(free50, ResultRules.defaultEvent(emptyList()))
    }

    @Test
    fun chart() {
        // Макет: график 50 м в/с — дистанция, где результатов больше всего; на спине по одному — в выборе их нет.
        assertEquals(listOf(free50), ResultRules.chartEvents(ksenia, 25))
        assertEquals(free50, ResultRules.defaultChartEvent(ksenia, 25))
        assertNull(ResultRules.defaultChartEvent(ksenia, 50))
        val chart = ResultRules.chart(ksenia, free50)!!
        assertEquals(listOf(4410, 4280, 4200, 4120, 3962, 4030), chart.points.map { it.timeCs })
        assertEquals(listOf(false, false, true, false, true, false), chart.points.map { it.competition })
        // Ось с запасом 10 %: 39,18–44,54; подписи — через 2 секунды, как в макете: 40, 42, 44.
        assertEquals(3918, chart.lowCs)
        assertEquals(4454, chart.highCs)
        assertEquals(listOf(4000, 4200, 4400), chart.ticks)
        assertEquals(listOf("40", "42", "44"), chart.ticks.map(ResultFormat::tick))
        // Месяцы — сентябрь и октябрь, первая точка слева, последняя справа.
        assertEquals(listOf(d(1, 9), d(1)), chart.months)
        assertEquals(0f, chart.x(d(8, 9)))
        assertEquals(1f, chart.x(d(15)))
        assertEquals(0f, chart.y(chart.lowCs))
        // Все точки одного дня — посередине; одинаковое время — ось ±0,5 с.
        val same = ResultRules.chart(listOf(r("1", d(1), back25, 2500), r("2", d(1), back25, 2500)), back25)!!
        assertEquals(0.5f, same.x(d(1)))
        assertEquals(2450 to 2550, same.lowCs to same.highCs)
        assertEquals(listOf(2450, 2500, 2550), same.ticks)
        assertEquals(listOf("24,5", "25", "25,5"), same.ticks.map(ResultFormat::tick))
        assertEquals("1:05", ResultFormat.tick(6500))
    }

    @Test
    fun lines() {
        val start = r("1", d(10), free25, 1784, competition = true, name = "Кубок города")
        assertEquals(s("Кубок города · сб_10.10"), ResultFormat.listLine(start, today))
        assertEquals(s("тренировка · чт_15.10"), ResultFormat.listLine(ksenia.last(), today))
        assertEquals("тренировка · 15.10.2025", ResultFormat.recordLine(ksenia.last().copy(date = LocalDate(2025, 10, 15)), today))
        assertEquals(s("Бассейн 25_м"), ResultFormat.pool(25))
        assertEquals(s("25_м вольный стиль · 17,84 · 10.10.2026"), ResultFormat.deleteLine(start))
        assertEquals("сентябрь", com.akenory.personalka.domain.format.DateFormat.month(d(1, 9)))
    }
}
