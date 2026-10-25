package com.akenory.personalka.ui.salary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.domain.money.SalaryYear
import com.akenory.personalka.domain.money.YearMonthRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.YearMonth

internal const val SALARY_YEAR_EARNED_TAG = "salary-year-earned"
internal const val SALARY_YEAR_CHART_TAG = "salary-year-chart"
internal fun salaryYearRowTag(month: YearMonth) = "salary-year-row-$month"
internal const val SALARY_SIDE_TAG = "salary-side"
internal const val SALARY_SIDE_PREV_TAG = "salary-side-prev"
internal const val SALARY_SIDE_NEXT_TAG = "salary-side-next"
internal fun salarySideRowTag(month: YearMonth) = "salary-side-row-$month"

/**
 * «Год» (ТЗ 5.12, шаг 3.8б, макет PayYear): заработано за год, на руки, налог и ПТ; столбцы заработка по месяцам
 * с отметками плана (у текущего — сделано и прогноз); ниже месяцы от свежего к раннему. Нажатие на месяц — его
 * вкладка «Месяц».
 */
@Composable
internal fun YearTab(year: SalaryYear, onMonth: (YearMonth) -> Unit) {
    val colors = PersonalkaTheme.colors
    if (year.rows.isEmpty()) {
        SalaryCard { Muted(SalaryFormat.YEAR_EMPTY) }
        return
    }
    SalaryCard {
        Muted(SalaryFormat.yearTitle(year.year))
        Text(
            MoneyFormat.rub(year.earned),
            modifier = Modifier.testTag(SALARY_YEAR_EARNED_TAG),
            style = MaterialTheme.typography.displaySmall,
            color = colors.text,
        )
        SalaryFormat.yearPlan(year)?.let { Muted(it) }
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            Tile(MoneyFormat.rub(year.onHand), SalaryFormat.YEAR_ON_HAND, Modifier.weight(1f))
            Tile(MoneyFormat.rub(year.ndfl), SalaryFormat.YEAR_TAX, Modifier.weight(1f))
            Tile(RuText.digits(year.ptCount.toLong()), SalaryFormat.YEAR_PT, Modifier.weight(1f))
        }
        SalaryFormat.yearCounts(year)?.let { Muted(it) }
        YearChart(year)
    }
    SalaryCard {
        year.rows.asReversed().forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            YearRow(row) { onMonth(row.month) }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    val colors = PersonalkaTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = Dimens.space3, vertical = Dimens.space2)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Muted(label)
    }
}

/**
 * Столбцы января–декабря: заработано графитом, у текущего сверху серым — прогноз; план — короткая черта над
 * столбцом. Подпись текущего месяца — жирная. Будущие месяцы — пустые места.
 */
@Composable
private fun YearChart(year: SalaryYear) {
    val colors = PersonalkaTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted)
    val currentStyle = labelStyle.copy(color = colors.text, fontWeight = FontWeight.SemiBold)
    val rows = year.rows.associateBy { it.month.month.ordinal }
    val description = year.rows.joinToString("; ") { SalaryFormat.yearBarDescription(it) }
    val max = year.chartMax.coerceAtLeast(1).toFloat()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(top = Dimens.space3)
            .semantics { contentDescription = description }
            .testTag(SALARY_YEAR_CHART_TAG),
    ) {
        val bottomBand = 22.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - bottomBand
        val slot = size.width / 12f
        val bar = slot * 0.72f
        fun py(amount: Long) = bottom - amount / max * (bottom - top)
        for (i in 0 until 12) {
            val x = i * slot + (slot - bar) / 2f
            val row = rows[i]
            if (row != null) {
                val forecast = row.forecast
                if (forecast != null && forecast > row.earned) {
                    drawRect(colors.outline, Offset(x, py(forecast)), Size(bar, py(row.earned) - py(forecast)))
                }
                if (row.earned > 0) drawRect(colors.primary, Offset(x, py(row.earned)), Size(bar, bottom - py(row.earned)))
                row.plan?.let { plan ->
                    val y = py(plan)
                    drawLine(colors.text, Offset(x - slot * 0.06f, y), Offset(x + bar + slot * 0.06f, y), strokeWidth = 1.5.dp.toPx())
                }
            }
            val month = YearMonth(year.year, i + 1)
            val label = measurer.measure(SalaryFormat.monthShort(month), if (month == year.current) currentStyle else labelStyle)
            drawText(label, topLeft = Offset(i * slot + (slot - label.size.width) / 2f, bottom + 4.dp.toPx()))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        BoxLegend(colors.primary, SalaryFormat.CHART_EARNED)
        if (year.rows.any { it.forecast != null }) BoxLegend(colors.outline, SalaryFormat.CHART_FORECAST)
        if (year.rows.any { it.plan != null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
                    drawLine(colors.text, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1.5.dp.toPx())
                }
                Muted(SalaryFormat.YEAR_PLAN, Modifier.padding(start = Dimens.space1))
            }
        }
    }
}

@Composable
private fun BoxLegend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Muted(text, Modifier.padding(start = Dimens.space1))
    }
}

/** Месяц года: название, «идёт» у текущего, заработано и процент плана; под ним ПТ, дети, дежурства, налог, на руки. */
@Composable
private fun YearRow(row: YearMonthRow, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.touch)
            .padding(vertical = Dimens.space2)
            .testTag(salaryYearRowTag(row.month)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(SalaryFormat.monthName(row.month), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.text)
            if (row.period == MonthPeriod.CURRENT) {
                Text(
                    SalaryFormat.MONTH_GOING,
                    modifier = Modifier
                        .padding(end = Dimens.space2)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceAlt)
                        .padding(horizontal = Dimens.space2, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.text,
                )
            }
            Text(MoneyFormat.rub(row.earned), style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text, fontWeight = FontWeight.SemiBold)
            row.percent?.let {
                Muted("$it${RuText.NBSP}%", Modifier.padding(start = Dimens.space2))
            }
        }
        Muted(SalaryFormat.yearRow(row))
    }
}

/**
 * Левая колонка «Зарплаты» на разложенном Fold (ТЗ 5.12, шаг 3.8а, макет PayUnfolded): год со стрелками,
 * «Заработано за год» и месяцы от свежего к раннему с полосками выполнения плана. Выбранный месяц [selected]
 * подсвечен, нажатие — [onMonth]. [year] ещё не прочитан — только шапка.
 */
@Composable
internal fun YearSide(
    yearNumber: Int,
    year: SalaryYear?,
    selected: YearMonth,
    scroll: ScrollState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Column(modifier.background(colors.bg).testTag(SALARY_SIDE_TAG)) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space4, end = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                yearNumber.toString(),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
            )
            IconButton(onClick = onPrev, modifier = Modifier.testTag(SALARY_SIDE_PREV_TAG)) {
                Icon(PIcons.ChevronRight, contentDescription = "Прошлый год", tint = colors.text, modifier = Modifier.rotate(180f))
            }
            IconButton(onClick = onNext, modifier = Modifier.testTag(SALARY_SIDE_NEXT_TAG)) {
                Icon(PIcons.ChevronRight, contentDescription = "Следующий год", tint = colors.text)
            }
        }
        if (year == null || year.year != yearNumber) return@Column
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = Dimens.space2)
                .padding(bottom = Dimens.space4),
        ) {
            Column(Modifier.padding(horizontal = Dimens.space2, vertical = Dimens.space2)) {
                if (year.rows.isEmpty()) {
                    Muted(SalaryFormat.YEAR_EMPTY)
                    return@Column
                }
                Muted(SalaryFormat.yearTitle(year.year))
                Text(
                    MoneyFormat.rub(year.earned),
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                SalaryFormat.yearPlan(year)?.let { Muted(it) }
            }
            year.rows.asReversed().forEach { row ->
                SideRow(row, row.month == selected) { onMonth(row.month) }
            }
        }
    }
}

/** Месяц слева: название и процент плана над полоской, справа заработано; выбранный — на карточке. */
@Composable
private fun SideRow(row: YearMonthRow, selected: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.surface else Color.Transparent)
            .selectable(selected = selected, onClick = onClick)
            .heightIn(min = Dimens.touch)
            .padding(horizontal = Dimens.space3, vertical = Dimens.space3)
            .semantics(mergeDescendants = true) { contentDescription = SalaryFormat.yearSideDescription(row) }
            .testTag(salarySideRowTag(row.month)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SalaryFormat.monthName(row.month),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    maxLines = 1,
                )
                Muted(row.percent?.let { "$it${RuText.NBSP}%" } ?: SalaryFormat.YEAR_NO_PLAN)
            }
            TwoPartBar(row.planShareDone, row.planShareForecast, 4.dp)
        }
        Text(
            MoneyFormat.rub(row.earned),
            modifier = Modifier.padding(start = Dimens.space3),
            style = MaterialTheme.typography.titleSmall.tabular(),
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
