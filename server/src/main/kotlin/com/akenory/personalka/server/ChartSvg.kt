package com.akenory.personalka.server

import com.akenory.personalka.domain.cabinet.CabinetChart
import com.akenory.personalka.domain.cabinet.ChartAnchor
import com.akenory.personalka.domain.cabinet.ChartLabel
import java.util.Locale

/**
 * График результатов кабинета SVG-разметкой (ТЗ 6.3, п. 6): две картинки рядом — подписи времени шириной [AXIS_W]
 * и сам график на всю оставшуюся ширину. По ширине точки стоят в процентах, по высоте — в пикселях, без `viewBox`:
 * график растягивается под экран, а текст остаётся 16 px и на 320 px, и на планшете. Положения и подписи считает
 * `CabinetResultRules`; здесь только разметка. Цвета — классами из `cabinet.css`, обе темы.
 */
object ChartSvg {
    const val HEIGHT = 214
    const val AXIS_W = 44

    /** Поле графика по высоте: сверху запас под точку, снизу — под подписи месяцев. */
    private const val TOP = 12f
    private const val BOTTOM = 150f
    private const val MONTH_Y = 206
    private const val DOT = 6
    private const val OPEN_DOT = 5

    /** Подписи времени слева, по одной высоте с линиями сетки графика. */
    fun axis(chart: CabinetChart): String = buildString {
        append("""<svg class="ax-col" width="$AXIS_W" height="$HEIGHT" aria-hidden="true">""")
        chart.ticks.forEach { tick ->
            append("""<text class="ax" x="${AXIS_W - 6}" y="${n(py(tick.y) + 6)}" text-anchor="end">${esc(tick.text)}</text>""")
        }
        append("</svg>")
    }

    /** Сетка, пунктир месяцев, линия по точкам, точки (соревнования залитые), время лучшего и подписи месяцев. */
    fun plot(chart: CabinetChart): String = buildString {
        append("""<svg class="pl" width="100%" height="$HEIGHT" aria-hidden="true">""")
        chart.ticks.forEach { tick ->
            val y = n(py(tick.y))
            append("""<line class="gl" x1="0" y1="$y" x2="100%" y2="$y"/>""")
        }
        chart.borders.forEach { x ->
            append("""<line class="gl" x1="${pct(x)}" y1="${n(TOP - 4)}" x2="${pct(x)}" y2="${n(BOTTOM + 4)}" stroke-dasharray="3 3"/>""")
        }
        chart.dots.zipWithNext().forEach { (a, b) ->
            append("""<line class="ln" x1="${pct(a.x)}" y1="${n(py(a.y))}" x2="${pct(b.x)}" y2="${n(py(b.y))}"/>""")
        }
        chart.dots.forEach { dot ->
            val r = if (dot.competition) DOT else OPEN_DOT
            append("""<circle class="${if (dot.competition) "pf" else "po"}" cx="${pct(dot.x)}" cy="${n(py(dot.y))}" r="$r"/>""")
        }
        // Время лучшего — под его точкой, а если там не помещается — над ней.
        val bestY = py(chart.best.y)
        val below = bestY + DOT + 18
        val y = if (below <= BOTTOM + 28) below else bestY - DOT - 6
        append(text("vl", chart.best, y))
        chart.months.forEach { append(text("ax", it, MONTH_Y.toFloat())) }
        append("</svg>")
    }

    private fun text(cls: String, label: ChartLabel, y: Float): String {
        val anchor = when (label.anchor) {
            ChartAnchor.START -> "start"
            ChartAnchor.MIDDLE -> "middle"
            ChartAnchor.END -> "end"
        }
        return """<text class="$cls" x="${pct(label.x)}" y="${n(y)}" text-anchor="$anchor">${esc(label.text)}</text>"""
    }

    /** Доля высоты снизу → пиксели сверху. */
    private fun py(y: Float): Float = BOTTOM - y * (BOTTOM - TOP)

    private fun pct(x: Float): String = String.format(Locale.ROOT, "%.2f%%", x * 100)

    private fun n(v: Float): String = String.format(Locale.ROOT, "%.1f", v)

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
