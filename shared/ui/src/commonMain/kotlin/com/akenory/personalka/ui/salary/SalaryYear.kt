package com.akenory.personalka.ui.salary

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.YearMonth

internal const val SALARY_YEAR_EARNED_TAG = "salary-year-earned"
internal const val SALARY_YEAR_CHART_TAG = "salary-year-chart"
internal fun salaryYearRowTag(month: YearMonth) = "salary-year-row-$month"

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
