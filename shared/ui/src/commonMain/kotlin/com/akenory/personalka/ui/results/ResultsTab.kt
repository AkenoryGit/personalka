package com.akenory.personalka.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.result.ResultChart
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.SwimEvent
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

const val RESULT_CHART_TAG = "result-chart"
const val CHART_EVENT_TAG = "chart-event"
fun resultRowTag(id: String) = "result-$id"

/**
 * Вкладка «Результаты» карточки клиента (5.9, макет ClCardResults): бассейн 25 / 50 м — если результаты есть
 * в обоих; личные рекорды; график по дистанции с выбором; все результаты от новых к старым — нажатие открывает
 * форму с «Удалить».
 */
@Composable
fun ResultsTab(clientId: String, list: List<ResultItem>, today: LocalDate, onOpen: (ResultItem) -> Unit) {
    val pools = ResultRules.pools(list)
    var poolChoice by rememberSaveable(clientId) { mutableStateOf(pools.firstOrNull() ?: 25) }
    val pool = poolChoice.takeIf { it in pools } ?: pools.firstOrNull() ?: return
    val records = ResultRules.records(list, pool)
    val events = ResultRules.chartEvents(list, pool)
    var eventKey by rememberSaveable(clientId, pool) { mutableStateOf<String?>(null) }
    val event = events.firstOrNull { key(it) == eventKey } ?: ResultRules.defaultChartEvent(list, pool)
    val all = list.filter { it.event.poolM == pool }.sortedWith(ResultRules.NEWEST_FIRST)

    Column(Modifier.padding(horizontal = Dimens.space4).padding(top = Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        if (pools.size > 1) {
            Segmented(pools, pool, { ResultFormat.pool(it) }, { poolChoice = it })
        }
        SectionTitle(ResultFormat.RECORDS)
        ListCard {
            records.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                ResultLine(item, ResultFormat.recordLine(item, today), today, onClick = null)
            }
        }
        if (event != null) {
            val chart = ResultRules.chart(list, event)
            if (chart != null) ChartCard(events, event, chart, onPick = { eventKey = key(it) })
        }
        SectionTitle(ResultFormat.ALL)
        ListCard {
            all.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                ResultLine(item, ResultFormat.listLine(item, today), today, onClick = { onOpen(item) })
            }
        }
    }
}

private fun key(event: SwimEvent) = "${event.style.name}-${event.distanceM}-${event.poolM}"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Dimens.space2),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/** Строка результата: дистанция и «соревнования · 10.10» слева, время справа. */
@Composable
private fun ResultLine(item: ResultItem, line: String, today: LocalDate, onClick: (() -> Unit)?) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.row)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).testTag(resultRowTag(item.id)) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = ResultFormat.spoken(item, today) }
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ResultFormat.event(item.event), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(line, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
        }
        Spacer(Modifier.width(Dimens.space3))
        Text(ResultFormat.time(item.timeCs), style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text)
    }
}

/**
 * График времени по дистанции (5.9): выбор дистанции, условные обозначения, точки по дням — соревнования залитые,
 * тренировки пустые; подписи времени слева, месяцы снизу; у лучшего — его время. Оранжевого нет (10.5).
 */
@Composable
private fun ChartCard(events: List<SwimEvent>, event: SwimEvent, chart: ResultChart, onPick: (SwimEvent) -> Unit) {
    val colors = PersonalkaTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(colors.surface, MaterialTheme.shapes.large).padding(Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Box {
            Row(
                Modifier
                    .heightIn(min = Dimens.touch)
                    .background(colors.surfaceAlt, MaterialTheme.shapes.small)
                    .clickable(enabled = events.size > 1) { menu = true }
                    .semantics { contentDescription = ResultFormat.CHART_EVENT + ": " + ResultFormat.event(event) }
                    .testTag(CHART_EVENT_TAG)
                    .padding(horizontal = Dimens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ResultFormat.event(event), style = MaterialTheme.typography.labelMedium, color = colors.text)
                if (events.size > 1) Icon(PIcons.ArrowDropDown, contentDescription = null, tint = colors.text)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                events.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(ResultFormat.event(option), style = MaterialTheme.typography.bodyLarge, color = colors.text) },
                        onClick = {
                            menu = false
                            onPick(option)
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4), verticalAlignment = Alignment.CenterVertically) {
            if (chart.points.any { it.competition }) Legend(true, ResultFormat.LEGEND_COMPETITION)
            if (chart.points.any { !it.competition }) Legend(false, ResultFormat.LEGEND_TRAINING)
        }
        Chart(chart)
        Text(ResultFormat.CHART_HINT, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun Legend(filled: Boolean, text: String) {
    val colors = PersonalkaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (filled) colors.text else colors.surface, CircleShape)
                .border(2.dp, colors.text, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.text)
    }
}

@Composable
private fun Chart(chart: ResultChart) {
    val colors = PersonalkaTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.tabular().copy(color = colors.textMuted)
    val bestStyle = MaterialTheme.typography.labelMedium.tabular().copy(color = colors.text)
    val best = chart.points.minBy { it.timeCs }
    val summary = ResultFormat.CHART_HINT + ". " + ResultFormat.RECORDS + ": " + ResultFormat.time(best.timeCs)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = summary }
            .testTag(RESULT_CHART_TAG),
    ) {
        val gutter = 40.dp.toPx()
        val bottomBand = 28.dp.toPx()
        val pad = 10.dp.toPx()
        val left = gutter + pad
        val right = size.width - pad
        val top = pad
        val bottom = size.height - bottomBand
        fun px(date: LocalDate) = left + chart.x(date) * (right - left)
        fun py(cs: Int) = bottom - chart.y(cs) * (bottom - top)

        chart.ticks.forEach { tick ->
            val y = py(tick)
            drawLine(colors.divider, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(ResultFormat.tick(tick), labelStyle)
            drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
        }

        // Месяцы: пунктир на границе, подпись — посередине своего куска.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        chart.months.forEachIndexed { i, start ->
            val from = maxOf(start, chart.from)
            val next = chart.months.getOrNull(i + 1)
            val to = if (next == null) chart.to else minOf(next, chart.to)
            if (i > 0 && chart.from.daysUntil(chart.to) > 0) {
                val x = px(start)
                drawLine(colors.outline, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
            val label = measurer.measure(DateFormat.month(start), labelStyle)
            val center = (px(from) + px(to)) / 2f
            val x = (center - label.size.width / 2f).coerceIn(gutter, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, bottom + 8.dp.toPx()))
        }

        val points = chart.points.map { Offset(px(it.date), py(it.timeCs)) }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, colors.textMuted, style = Stroke(width = 1.5.dp.toPx()))
        }
        val radius = 5.dp.toPx()
        chart.points.forEachIndexed { i, point ->
            val c = points[i]
            if (point.competition) {
                drawCircle(colors.text, radius, c)
            } else {
                drawCircle(colors.surface, radius, c)
                drawCircle(colors.text, radius, c, style = Stroke(width = 2.dp.toPx()))
            }
        }
        // Время лучшего — под его точкой, а если там не помещается — над ней.
        val bestAt = points[chart.points.indexOf(best)]
        val label = measurer.measure(ResultFormat.time(best.timeCs), bestStyle)
        val x = (bestAt.x - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
        val below = bestAt.y + radius + 2.dp.toPx()
        val y = if (below + label.size.height <= bottom) below else bestAt.y - radius - 2.dp.toPx() - label.size.height
        drawText(label, topLeft = Offset(x, y))
    }
}
