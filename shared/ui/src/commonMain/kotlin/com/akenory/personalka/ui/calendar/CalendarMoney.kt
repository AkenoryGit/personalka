package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SalaryRepository
import com.akenory.personalka.domain.format.CalendarMoneyFormat
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.money.CalendarMoney
import com.akenory.personalka.domain.money.CalendarMoneyRules
import com.akenory.personalka.domain.money.DayMoney
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaColors
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Деньги в календаре (ТЗ 5.3, шаг 5.10г): строка месяца над сеткой «Месяца», окно «Деньги по месяцам», фон и цвет
// суммы дня. Цифры — `CalendarMoneyRules` из того же расчёта, что «Зарплата» и «Главная».

const val CALENDAR_MONEY_LINE_TAG = "calendar-money-line"
const val CALENDAR_MONTHS_PANEL_TAG = "calendar-months-panel"
fun monthsRowTag(month: YearMonth) = "calendar-months-$month"

/**
 * Фон клетки по сумме — ступени 1–4: графит на 5, 9, 13 и 17 % поверх сетки. Больше 17 % бледная сумма «будет»
 * опускается ниже 4,5 : 1 (ТЗ 10.4): на 17 % в светлой теме 5,2 : 1, в тёмной 5,0 : 1.
 */
private val HEAT = floatArrayOf(0f, 0.05f, 0.09f, 0.13f, 0.17f)

/** Бледная сумма «будет» — цвет текста на 68 % поверх фона клетки: не ниже 4,9 : 1 на любой ступени фона. */
private const val PALE = 0.68f

/** Фон клетки дня по ступени суммы. */
internal fun PersonalkaColors.heat(level: Int): Color =
    if (level <= 0) Color.Transparent else text.copy(alpha = HEAT[level.coerceAtMost(HEAT.size - 1)])

/** Цвет суммы дня: заработано — текст, «будет» — бледный текст. */
internal fun PersonalkaColors.moneyText(day: DayMoney): Color = if (day.forecast) text.copy(alpha = PALE) else text

/** Деньги месяцев [months] для календаря; пустой список или нет репозитория — пусто. */
internal fun calendarMoney(
    salary: SalaryRepository?,
    months: List<YearMonth>,
    today: LocalDate,
    placeIds: Set<String>?,
): Flow<Map<YearMonth, CalendarMoney>> {
    if (salary == null || months.isEmpty()) return flowOf(emptyMap())
    return salary.monthsData(months.distinct().sorted(), today).map { list ->
        list.mapNotNull { data -> data.input?.let { data.view.month to CalendarMoneyRules.month(data.view, it, today, placeIds) } }.toMap()
    }
}

/**
 * Строка месяца над сеткой «Месяца» (макет «Месяц: сумма в каждом дне»): «91 400 ₽ заработано · будет ≈ 146 740 ₽ ›»;
 * нажатие — «Деньги по месяцам».
 */
@Composable
internal fun MonthMoneyLine(money: CalendarMoney?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = money?.let(CalendarMoneyFormat::monthDescription) ?: CalendarMoneyFormat.MONTHS_TITLE
            }
            .testTag(CALENDAR_MONEY_LINE_TAG)
            .padding(start = Dimens.space4, end = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (money != null) {
            Text(CalendarMoneyFormat.monthLead(money), style = MaterialTheme.typography.titleSmall.tabular(), color = colors.text, maxLines = 1)
            Text(
                CalendarMoneyFormat.monthRest(money),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(Modifier.weight(1f))
        }
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

/**
 * «Деньги по месяцам» (шаг 5.10г): месяцы года — полоса (заработано графитом, «по расписанию» серым, отметка
 * плана), сумма и подпись. Нажатие на месяц — календарь на нём. Окно по центру, как окна календаря: переживает
 * сложение Fold.
 */
@Composable
internal fun MonthsPanel(
    year: Int,
    months: Map<YearMonth, CalendarMoney>,
    current: YearMonth,
    onYear: (Int) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val rows = (1..12).map { YearMonth(year, it) }
    val top = months.values.maxOfOrNull { maxOf(it.total, it.plan ?: 0) }?.coerceAtLeast(1) ?: 1
    Panel(CalendarMoneyFormat.MONTHS_TITLE, onDismiss, Modifier.testTag(CALENDAR_MONTHS_PANEL_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYear(year - 1) }) {
                Icon(PIcons.ChevronRight, contentDescription = CalendarMoneyFormat.PREVIOUS_YEAR, tint = colors.text, modifier = Modifier.rotate(180f))
            }
            Text(year.toString(), style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text)
            IconButton(onClick = { onYear(year + 1) }) {
                Icon(PIcons.ChevronRight, contentDescription = CalendarMoneyFormat.NEXT_YEAR, tint = colors.text)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Legend(colors.text, CalendarMoneyFormat.EARNED)
            Legend(colors.outline, CalendarMoneyFormat.SCHEDULED)
            Legend(colors.done, CalendarMoneyFormat.PLAN, mark = true)
        }
        Column {
            rows.forEach { month ->
                MonthRow(month, months[month], top, month == current, onClick = { onMonth(month) })
            }
        }
        HorizontalDivider(color = colors.divider)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CalendarMoneyFormat.yearTotal(year), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(
                MoneyFormat.rub(CalendarMoneyFormat.roundToRub(months.values.sumOf { it.earned })),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = colors.text,
            )
        }
        Text(CalendarMoneyFormat.MONTHS_HINT, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun Legend(color: Color, text: String, mark: Boolean = false) {
    val colors = PersonalkaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .width(if (mark) 2.dp else 12.dp)
                .height(if (mark) 12.dp else 8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun MonthRow(month: YearMonth, money: CalendarMoney?, top: Long, isCurrent: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val total = money?.total ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .clip(MaterialTheme.shapes.small)
            .background(if (isCurrent) colors.surfaceAlt else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                selected = isCurrent
                contentDescription = SalaryFormat.monthName(month) + (
                    if (money == null || total <= 0) "" else ": " + CalendarMoneyFormat.rowAmount(money) +
                        (CalendarMoneyFormat.rowNote(money)?.let { ", $it" } ?: "")
                    )
            }
            .testTag(monthsRowTag(month))
            .padding(horizontal = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Text(
            SalaryFormat.monthName(month),
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = colors.text,
            maxLines = 1,
        )
        // Полоса: заработано графитом, «по расписанию» сверху серым, отметка плана зелёным.
        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceAlt))
            if (money != null && total > 0) {
                val earned = (money.earned.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f)
                val all = (total.toFloat() / top).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(all).height(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.outline))
                if (earned > 0f) Box(Modifier.fillMaxWidth(earned).height(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.text))
            }
            val plan = money?.plan
            if (plan != null && plan > 0) {
                Box(Modifier.fillMaxWidth((plan.toFloat() / top).coerceIn(0f, 1f)).fillMaxHeight()) {
                    Box(Modifier.align(Alignment.CenterEnd).offset(x = 1.dp).width(2.dp).fillMaxHeight().background(colors.done))
                }
            }
        }
        Column(Modifier.width(104.dp), horizontalAlignment = Alignment.End) {
            if (money != null && total > 0) {
                Text(
                    CalendarMoneyFormat.rowAmount(money),
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    fontWeight = if (money.period == MonthPeriod.FUTURE) FontWeight.Normal else FontWeight.Medium,
                    color = if (money.period == MonthPeriod.FUTURE) colors.textMuted else colors.text,
                    maxLines = 1,
                )
                CalendarMoneyFormat.rowNote(money)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** Деньги месяцев года [year] для окна. */
@Composable
internal fun rememberYearMoney(salary: SalaryRepository?, year: Int, today: LocalDate, placeIds: Set<String>?): Map<YearMonth, CalendarMoney> {
    val flow = remember(salary, year, today, placeIds) { calendarMoney(salary, (1..12).map { YearMonth(year, it) }, today, placeIds) }
    val value by flow.collectAsState(initial = emptyMap())
    return value
}

/** Деньги нескольких месяцев сразу (строка недели может задеть два месяца): у каждого — своё окно истории. */
@Composable
internal fun rememberMonthsMoney(salary: SalaryRepository?, months: List<YearMonth>, today: LocalDate, placeIds: Set<String>?): Map<YearMonth, CalendarMoney> {
    val flow = remember(salary, months, today, placeIds) { calendarMoney(salary, months, today, placeIds) }
    val value by flow.collectAsState(initial = emptyMap())
    return value
}
