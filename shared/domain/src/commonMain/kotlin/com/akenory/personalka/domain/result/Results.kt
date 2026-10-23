package com.akenory.personalka.domain.result

import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number

// ТЗ 5.11. Результаты: ввод времени, личные рекорды, график по дистанции. Время — сотые доли секунды (3.5).

/** Дистанция: стиль, метры и бассейн. Рекорды и график считаются отдельно для каждой. */
data class SwimEvent(val style: SwimStyle, val distanceM: Int, val poolM: Int)

/** Результат клиента. [eventName] — название старта у соревнований. */
data class ResultItem(
    val id: String,
    val date: LocalDate,
    val event: SwimEvent,
    val timeCs: Int,
    val kind: ResultKind,
    val eventName: String? = null,
)

/**
 * Время результата: «52,16» — секунды и сотые, «1:03,17» — минуты, секунды и сотые. Тренер набирает цифры подряд
 * (5.11): «5216» → 52,16, «10317» → 1:03,17; набранное с запятой и двоеточием читается так же.
 */
object ResultTime {
    /** Больше шести цифр не набрать: 99:59,99. */
    const val MAX_DIGITS = 6

    /** Цифры из набранного: «1:03,17» → «10317». Лишние в конце отбрасываются. */
    fun digits(text: String): String = text.filter { it in '0'..'9' }.take(MAX_DIGITS)

    /** Как набранные цифры видны в поле: «5» → «5», «521» → «5,21», «5216» → «52,16», «10317» → «1:03,17». */
    fun typed(digits: String): String {
        val n = digits.length
        return when {
            n <= 2 -> digits
            n <= 4 -> digits.substring(0, n - 2) + "," + digits.substring(n - 2)
            else -> digits.substring(0, n - 4) + ":" + digits.substring(n - 4, n - 2) + "," + digits.substring(n - 2)
        }
    }

    /**
     * Время в сотых или `null`, если так время не читается: меньше трёх цифр, секунд больше 59, ноль.
     * Понимает и цифры подряд, и «1:03,17», «52,16», «52.16».
     */
    fun parse(text: String): Int? {
        val clean = text.trim()
        val digits = if (clean.all { it in '0'..'9' }) clean else formattedDigits(clean) ?: return null
        if (digits.length !in 3..MAX_DIGITS) return null
        val n = digits.length
        val hundredths = digits.substring(n - 2).toInt()
        val seconds = digits.substring((n - 4).coerceAtLeast(0), n - 2).toInt()
        val minutes = if (n > 4) digits.substring(0, n - 4).toInt() else 0
        if (seconds > 59) return null
        val cs = (minutes * 60 + seconds) * 100 + hundredths
        return cs.takeIf { it > 0 }
    }

    /** «1:03,17» → «10317», «52,16» → «5216», «52,1» → «5210»; иначе `null`. */
    private fun formattedDigits(text: String): String? {
        val match = Regex("^(?:(\\d{1,2}):)?(\\d{1,2})[,.](\\d{1,2})$").matchEntire(text) ?: return null
        val (minutes, seconds, hundredths) = match.destructured
        // После минут секунды — двумя цифрами: «1:3,17» — опечатка, а не 1:03,17.
        if (minutes.isNotEmpty() && seconds.length != 2) return null
        val cc = if (hundredths.length == 1) hundredths + "0" else hundredths
        return if (minutes.isEmpty()) seconds + cc else minutes + seconds.padStart(2, '0') + cc
    }

    /** «52,16», «1:03,17», «0,52». */
    fun format(cs: Int): String {
        val minutes = cs / 6000
        val seconds = cs / 100 % 60
        val hundredths = cs % 100
        val tail = "," + two(hundredths)
        return if (minutes > 0) "$minutes:${two(seconds)}$tail" else "$seconds$tail"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}

/** Точка графика: день, время и соревнования ли — у них точка залитая. */
data class ChartPoint(val date: LocalDate, val timeCs: Int, val competition: Boolean)

/**
 * График времени по дистанции (5.9, 6.3): точки по дням слева направо, ось времени снизу вверх от [lowCs] до [highCs]
 * (чем ниже точка, тем быстрее), подписи времени [ticks] и начала месяцев [months] — первый месяц тоже, даже если
 * его начало левее первой точки.
 */
data class ResultChart(
    val points: List<ChartPoint>,
    val lowCs: Int,
    val highCs: Int,
    val ticks: List<Int>,
    val months: List<LocalDate>,
    val from: LocalDate,
    val to: LocalDate,
) {
    /** Доля ширины от первого дня до [date]: 0 — левый край, 1 — правый. Все точки в один день — посередине. */
    fun x(date: LocalDate): Float {
        val days = from.daysUntil(to)
        return if (days == 0) 0.5f else from.daysUntil(date).toFloat() / days
    }

    /** Доля высоты снизу: [lowCs] — 0, [highCs] — 1. */
    fun y(cs: Int): Float = (cs - lowCs).toFloat() / (highCs - lowCs)
}

object ResultRules {
    /** Бассейн 25 или 50 м. */
    val POOLS = listOf(25, 50)

    /** Дистанции (3.5). */
    val DISTANCES = listOf(25, 50, 100, 200, 400, 800, 1500)

    /** Шаги подписей графика в сотых: от 0,1 с до 10 мин. */
    private val TICK_STEPS = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 3000, 6000, 12000, 30000, 60000)

    /**
     * Дистанции, которые плавают этим стилем: вольным — все, комплексом — 100, 200 и 400, остальными — от 25 до 200.
     * Лишних чипов в форме нет.
     */
    fun distances(style: SwimStyle): List<Int> = when (style) {
        SwimStyle.FREESTYLE -> DISTANCES
        SwimStyle.MEDLEY -> listOf(100, 200, 400)
        else -> listOf(25, 50, 100, 200)
    }

    /** Дистанция после смены стиля: прежняя, если ею плавают, иначе ближайшая длиннее, иначе самая длинная. */
    fun fitDistance(style: SwimStyle, distanceM: Int): Int {
        val list = distances(style)
        return if (distanceM in list) distanceM else list.firstOrNull { it > distanceM } ?: list.last()
    }

    /** Порядок дистанций в рекордах и выборе графика: по стилю (вольный, спина, брасс, баттерфляй, комплекс), затем по метрам. */
    val ORDER: Comparator<SwimEvent> = compareBy<SwimEvent>({ it.poolM }, { it.style.ordinal }, { it.distanceM })

    /** Новые сверху: по дню, в один день — последний внесённый выше (id растут со временем, 3.1). */
    val NEWEST_FIRST: Comparator<ResultItem> = compareByDescending<ResultItem> { it.date }.thenByDescending { it.id }

    /** Бассейны, в которых есть результаты: 25, затем 50. Переключатель в карточке — только если их два. */
    fun pools(results: List<ResultItem>): List<Int> = POOLS.filter { pool -> results.any { it.event.poolM == pool } }

    /**
     * Личные рекорды в бассейне [poolM]: лучшее время на каждой дистанции, по порядку [ORDER]. Одинаковое время —
     * рекорд за тем, кто показал его раньше.
     */
    fun records(results: List<ResultItem>, poolM: Int): List<ResultItem> =
        results.filter { it.event.poolM == poolM }
            .groupBy { it.event }
            .map { (_, list) -> list.minWith(compareBy<ResultItem>({ it.timeCs }, { it.date }, { it.id })) }
            .sortedWith(compareBy(ORDER) { it.event })

    /**
     * На сколько сотых новый результат лучше прежнего рекорда на этой дистанции (5.11: «Личный рекорд, − 7,00»).
     * `null` — рекорда нет: результат не быстрее или он первый на дистанции. [exceptId] — изменяемый результат,
     * с самим собой он не сравнивается.
     */
    fun recordGain(results: List<ResultItem>, event: SwimEvent, timeCs: Int, exceptId: String? = null): Int? {
        val best = results.filter { it.event == event && it.id != exceptId }.minOfOrNull { it.timeCs } ?: return null
        return (best - timeCs).takeIf { it > 0 }
    }

    /** Дистанции для графика в бассейне [poolM]: те, где результатов два и больше, по порядку [ORDER]. */
    fun chartEvents(results: List<ResultItem>, poolM: Int): List<SwimEvent> =
        results.filter { it.event.poolM == poolM }
            .groupingBy { it.event }.eachCount()
            .filterValues { it >= 2 }.keys
            .sortedWith(ORDER)

    /** Дистанция графика по умолчанию: где результатов больше всего, при равенстве — первая по порядку. */
    fun defaultChartEvent(results: List<ResultItem>, poolM: Int): SwimEvent? {
        // При равенстве maxByOrNull оставляет первую.
        return chartEvents(results, poolM).maxByOrNull { event -> results.count { it.event == event } }
    }

    /**
     * Дистанция новой записи (5.11): та же, что у последнего внесённого результата клиента, — чаще всего плавают
     * одно и то же; результатов нет — 50 м вольным стилем в 25-метровом бассейне.
     */
    fun defaultEvent(results: List<ResultItem>): SwimEvent =
        results.maxWithOrNull(compareBy { it.id })?.event ?: SwimEvent(SwimStyle.FREESTYLE, 50, 25)

    /** График дистанции [event]: точки по дням, ось с запасом 10 % сверху и снизу, подписи круглым шагом — не больше пяти. */
    fun chart(results: List<ResultItem>, event: SwimEvent): ResultChart? {
        val list = results.filter { it.event == event }.sortedWith(compareBy<ResultItem>({ it.date }, { it.id }))
        if (list.isEmpty()) return null
        val min = list.minOf { it.timeCs }
        val max = list.maxOf { it.timeCs }
        val pad = if (max == min) 50 else ((max - min) / 10).coerceAtLeast(1)
        val low = (min - pad).coerceAtLeast(0)
        val high = max + pad
        val step = TICK_STEPS.firstOrNull { (high - low) / it <= 4 } ?: TICK_STEPS.last()
        val ticks = generateSequence(((low + step - 1) / step) * step) { it + step }.takeWhile { it <= high }.toList()
        val from = list.first().date
        val to = list.last().date
        val months = generateSequence(LocalDate(from.year, from.month, 1)) { next(it) }
            .takeWhile { it <= to }
            .toList()
        return ResultChart(
            points = list.map { ChartPoint(it.date, it.timeCs, it.kind == ResultKind.COMPETITION) },
            lowCs = low,
            highCs = high,
            ticks = ticks,
            months = months,
            from = from,
            to = to,
        )
    }

    private fun next(monthStart: LocalDate): LocalDate =
        if (monthStart.month.number == 12) LocalDate(monthStart.year + 1, 1, 1) else LocalDate(monthStart.year, monthStart.month.number + 1, 1)
}
