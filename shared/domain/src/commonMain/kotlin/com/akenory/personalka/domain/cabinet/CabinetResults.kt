package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** Строка результата: «50 м вольный стиль», «соревнования · 10.10», «39,62». */
data class CabinetResultLine(val event: String, val line: String, val time: String)

/** Личные рекорды одного бассейна: «Личные рекорды · бассейн 25 м» и строки. */
data class CabinetRecords(val title: String, val lines: List<CabinetResultLine>)

/** Чип-ссылка выбора дистанции графика: подпись, значение `?d=` и выбран ли. */
data class CabinetChip(val label: String, val key: String, val selected: Boolean)

/** Где выровнять подпись по точке: по началу, середине или концу — у краёв подпись не выходит за график. */
enum class ChartAnchor { START, MIDDLE, END }

/** Подпись на графике: [x] и [y] — доли ширины и высоты (0 — слева и снизу), [anchor] — выравнивание по [x]. */
data class ChartLabel(val text: String, val x: Float, val y: Float = 0f, val anchor: ChartAnchor = ChartAnchor.MIDDLE)

/** Точка графика в долях ширины и высоты; соревнования — залитая. */
data class ChartDot(val x: Float, val y: Float, val competition: Boolean)

/**
 * График времени по дистанции для кабинета (6.3): всё в долях ширины и высоты — сервер рисует SVG любой ширины, а текст
 * остаётся 16 px. [ticks] — подписи времени по высоте, [borders] — пунктир начала месяца, [months] — подписи месяцев
 * (не налезают друг на друга даже на 320 px), [best] — время лучшего у его точки, [summary] — для чтения с экрана.
 */
data class CabinetChart(
    val title: String,
    val chips: List<CabinetChip>,
    val ticks: List<ChartLabel>,
    val borders: List<Float>,
    val months: List<ChartLabel>,
    val dots: List<ChartDot>,
    val best: ChartLabel,
    val legendCompetition: Boolean,
    val legendTraining: Boolean,
    val hint: String,
    val summary: String,
)

/** Раздел «Результаты» (6.3, п. 6): рекорды по бассейнам, график, все результаты свёрнутыми. */
data class CabinetResults(
    val records: List<CabinetRecords>,
    val chart: CabinetChart?,
    val allCount: String,
    val all: List<CabinetResultLine>,
)

/**
 * Результаты в кабинете родителя (ТЗ 6.3, п. 6; макет `KbCabinet`): те же рекорды и график, что во вкладке приложения
 * (`ResultRules.records`, `chart`, 5.9). Нет результатов — нет раздела.
 */
object CabinetResultRules {
    /** Самый узкий график: 320 px экрана без полей страницы, карточки и подписей времени (ТЗ 2.3). */
    private const val MIN_PLOT_PX = 190f

    /** Ширина буквы подписи в 16 px — с запасом, чтобы подписи месяцев не слипались. */
    private const val CHAR_PX = 9f

    /** Месяцев больше — подписи короткие: «сен», «окт». */
    private const val FULL_MONTHS = 3

    /** Значение `?d=` для дистанции: «50-freestyle-25». */
    fun key(event: SwimEvent): String = "${event.distanceM}-${event.style.name.lowercase()}-${event.poolM}"

    /**
     * Раздел результатов: рекорды бассейна 25 м, затем 50 м; график по дистанции [chartKey] из адреса, иначе — где
     * результатов больше всего (сначала в 25-метровом); все результаты от новых к старым. `null` — результатов нет.
     */
    fun results(results: List<ResultItem>, today: LocalDate, chartKey: String? = null): CabinetResults? {
        if (results.isEmpty()) return null
        val pools = ResultRules.pools(results)
        val twoPools = pools.size > 1
        val records = pools.map { pool ->
            CabinetRecords(
                CabinetFormat.recordsTitle(pool),
                ResultRules.records(results, pool).map { line(it, ResultFormat.recordLine(it, today), pool = false) },
            )
        }
        val all = results.sortedWith(ResultRules.NEWEST_FIRST).map { line(it, ResultFormat.listLine(it, today), twoPools) }
        return CabinetResults(
            records = records,
            chart = chart(results, chartKey),
            allCount = all.size.toString(),
            all = all,
        )
    }

    private fun line(item: ResultItem, where: String, pool: Boolean) = CabinetResultLine(
        event = ResultFormat.event(item.event),
        line = if (pool) where + " · " + CabinetFormat.poolLower(item.event.poolM) else where,
        time = ResultFormat.time(item.timeCs),
    )

    /** График (5.9, 6.3): дистанции, где результатов два и больше; ни одной — графика нет. */
    fun chart(results: List<ResultItem>, chartKey: String? = null): CabinetChart? {
        val pools = ResultRules.pools(results)
        val events = pools.flatMap { ResultRules.chartEvents(results, it) }
        if (events.isEmpty()) return null
        val event = events.firstOrNull { key(it) == chartKey }
            ?: pools.firstNotNullOfOrNull { ResultRules.defaultChartEvent(results, it) }
            ?: return null
        val chart = ResultRules.chart(results, event) ?: return null
        val chipPools = events.map { it.poolM }.distinct().size > 1
        val chips = events.map { e ->
            val label = ResultFormat.event(e) + if (chipPools) " · " + CabinetFormat.poolLower(e.poolM) else ""
            CabinetChip(label, key(e), e == event)
        }
        val dots = chart.points.map { ChartDot(chart.x(it.date), chart.y(it.timeCs), it.competition) }
        val best = chart.points.minWith(compareBy({ it.timeCs }, { it.date }))
        val bestX = chart.x(best.date)
        val bestText = ResultFormat.time(best.timeCs)
        val spread = chart.from.daysUntil(chart.to) > 0
        val first = chart.points.first()
        val last = chart.points.last()
        return CabinetChart(
            title = CabinetFormat.CHART_TITLE,
            chips = chips,
            ticks = chart.ticks.map { ChartLabel(ResultFormat.tick(it), 0f, chart.y(it), ChartAnchor.END) },
            borders = if (spread) chart.months.drop(1).map { chart.x(it) } else emptyList(),
            months = monthLabels(chart.months, chart.from, chart.to) { chart.x(it) },
            dots = dots,
            best = ChartLabel(bestText, bestX, chart.y(best.timeCs), anchor(bestX, bestText)),
            legendCompetition = chart.points.any { it.competition },
            legendTraining = chart.points.any { !it.competition },
            hint = CabinetFormat.CHART_HINT,
            summary = CabinetFormat.chartSummary(
                ResultFormat.event(event), chart.points.size,
                ResultFormat.time(first.timeCs), first.date, ResultFormat.time(last.timeCs), last.date, bestText,
            ),
        )
    }

    /**
     * Подписи месяцев — посередине своего куска графика; у краёв — по краю. Месяцев больше трёх — короткие названия;
     * подпись, которая налезла бы на предыдущую на самом узком графике, пропускается.
     */
    private fun monthLabels(months: List<LocalDate>, from: LocalDate, to: LocalDate, x: (LocalDate) -> Float): List<ChartLabel> {
        val full = months.size <= FULL_MONTHS
        val labels = mutableListOf<ChartLabel>()
        var lastRight = Float.NEGATIVE_INFINITY
        months.forEachIndexed { i, start ->
            val segmentFrom = maxOf(start, from)
            val next = months.getOrNull(i + 1)
            val segmentTo = if (next == null) to else minOf(next, to)
            val center = if (from == to) 0.5f else (x(segmentFrom) + x(segmentTo)) / 2f
            val text = if (full) DateFormat.month(start) else CabinetFormat.monthShort(start)
            val anchor = anchor(center, text)
            val width = width(text)
            val left = when (anchor) {
                ChartAnchor.START -> 0f
                ChartAnchor.MIDDLE -> center - width / 2f
                ChartAnchor.END -> 1f - width
            }
            if (left >= lastRight) {
                val at = when (anchor) {
                    ChartAnchor.START -> 0f
                    ChartAnchor.MIDDLE -> center
                    ChartAnchor.END -> 1f
                }
                labels += ChartLabel(text, at, 0f, anchor)
                lastRight = left + width + GAP
            }
        }
        return labels
    }

    private const val GAP = 0.04f

    private fun width(text: String) = text.length * CHAR_PX / MIN_PLOT_PX

    /** Подпись по [x] не выходит за края самого узкого графика. */
    private fun anchor(x: Float, text: String): ChartAnchor {
        val half = width(text) / 2f
        return when {
            x - half < 0f -> ChartAnchor.START
            x + half > 1f -> ChartAnchor.END
            else -> ChartAnchor.MIDDLE
        }
    }
}
