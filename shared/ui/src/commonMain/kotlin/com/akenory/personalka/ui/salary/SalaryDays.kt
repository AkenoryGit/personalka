package com.akenory.personalka.ui.salary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SalaryMonthData
import com.akenory.personalka.data.money.SalaryRepository
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.money.BreakdownSection
import com.akenory.personalka.domain.money.DayItemKind
import com.akenory.personalka.domain.money.SalaryDaysRules
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate

internal const val SALARY_DAY_TAG = "salary-day"
internal const val SALARY_PERIOD_TAG = "salary-period"
internal const val PERIOD_FROM_TAG = "period-from"
internal const val PERIOD_TO_TAG = "period-to"
internal const val PERIOD_TOTAL_TAG = "period-total"

/** Какую дату периода выбирают в календаре. */
internal enum class PeriodEnd { FROM, TO }

/** Самый длинный период: год. */
private const val MAX_PERIOD_DAYS = 366

/**
 * Заработанное в день [date] (5.12, 3.7в) — под графиком «По дням»: сумма дня и за что — тренировки со временем,
 * типом, местом и клиентами (или группой), доплаты СПТ и доплаты месяца. Сумма дня — шаг графика в этот день.
 */
@Composable
internal fun DayDetails(data: SalaryMonthData, date: LocalDate) {
    val items = SalaryDaysRules.day(data.view.days, date)
    val colors = PersonalkaTheme.colors
    Text(
        SalaryFormat.dayTitle(date, items.sumOf { it.amount }),
        Modifier.padding(top = Dimens.space2).testTag(SALARY_DAY_TAG),
        style = MaterialTheme.typography.titleMedium,
        color = colors.text,
    )
    if (items.isEmpty()) Muted(SalaryFormat.DAY_EMPTY)
    items.forEachIndexed { i, item ->
        if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
        when (item.kind) {
            DayItemKind.TRAINING -> {
                val t = data.trainings[item.trainingId]
                val type = t?.let { data.types[it.typeId] }
                val place = t?.let { data.places[it.locationId] }
                val title = if (t == null) {
                    ""
                } else {
                    SalaryFormat.dayTraining(
                        t.start.time,
                        type?.shortName?.ifBlank { type.name }.orEmpty(),
                        place?.shortName?.ifBlank { place.name }.orEmpty(),
                    )
                }
                MoneyRow(title, MoneyFormat.rub(item.amount), note = data.whom[item.trainingId]?.ifBlank { null })
            }
            DayItemKind.SPT -> MoneyRow(SalaryFormat.DAY_SPT, MoneyFormat.rub(item.amount))
            DayItemKind.ADJUSTMENT -> {
                val a = item.adjustment?.let { data.adjustments.getOrNull(it) }
                MoneyRow(a?.comment.orEmpty(), MoneyFormat.signedRub(item.amount), note = a?.let { SalaryFormat.adjustmentNote(it.inClubAccrual) })
            }
        }
    }
}

/** Период по умолчанию для месяца [month]: месяц целиком, у текущего — с 1-го по сегодня. */
internal fun defaultPeriod(first: LocalDate, last: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> =
    first to (if (today in first..last) today else last)

/**
 * «Заработок за период» (5.12, 3.7в): даты «С» и «По» (календарь), сумма за период, по месяцам — каждый по своей
 * ступени, разбивка тренировок и доплаты, попавшие в эти дни. Ступень заново не считается.
 */
@Composable
internal fun PeriodPanel(
    repo: SalaryRepository,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    picking: PeriodEnd?,
    onPick: (PeriodEnd?) -> Unit,
    onChange: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val days = to.toEpochDays() - from.toEpochDays() + 1
    val problem = when {
        days < 1 -> SalaryFormat.PERIOD_REVERSED
        days > MAX_PERIOD_DAYS -> SalaryFormat.PERIOD_TOO_LONG
        else -> null
    }
    val data by remember(repo, from, to, today, problem) {
        if (problem == null) repo.period(from, to, today) else emptyFlow()
    }.collectAsState(initial = null)
    val colors = PersonalkaTheme.colors
    Box {
        Panel(SalaryFormat.PERIOD_TITLE, onDismiss) {
            DateRow(SalaryFormat.PERIOD_FROM, from, Modifier.testTag(PERIOD_FROM_TAG)) { onPick(PeriodEnd.FROM) }
            DateRow(SalaryFormat.PERIOD_TO, to, Modifier.testTag(PERIOD_TO_TAG)) { onPick(PeriodEnd.TO) }
            if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = colors.error)
            } else {
                val loaded = data?.takeIf { it.view.from == from && it.view.to == to }
                if (loaded != null) {
                    val view = loaded.view
                    Muted(SalaryFormat.periodRange(from, to))
                    Muted(SalaryFormat.PERIOD_EARNED)
                    Text(
                        MoneyFormat.rub(view.total),
                        modifier = Modifier.testTag(PERIOD_TOTAL_TAG),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.text,
                    )
                    Muted(SalaryFormat.PERIOD_NOTE)
                    if (view.total == 0L && view.breakdown.isEmpty()) Muted(SalaryFormat.PERIOD_EMPTY)
                    if (view.parts.size > 1) {
                        SectionTitle(SalaryFormat.PERIOD_MONTHS)
                        view.parts.forEach { part ->
                            MoneyRow(SalaryFormat.periodPart(part), MoneyFormat.rub(part.earned), note = SalaryFormat.byTier(part.tier))
                        }
                    }
                    val names = loaded.months.last()
                    val ptCount = view.breakdown.filter { it.section == BreakdownSection.PT }.sumOf { it.count }
                    view.breakdown.groupBy { it.section }.forEach { (section, lines) ->
                        SectionTitle(SalaryFormat.section(section, ptCount))
                        lines.forEachIndexed { i, line ->
                            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                            BreakdownRow(line, names)
                        }
                    }
                    if (view.adjustments.isNotEmpty()) {
                        SectionTitle(SalaryFormat.ADJUSTMENTS)
                        view.adjustments.forEach { (month, item) ->
                            val a = loaded.months.firstOrNull { it.view.month == month }?.adjustments?.getOrNull(item.adjustment ?: -1)
                            MoneyRow(
                                a?.comment.orEmpty(),
                                MoneyFormat.signedRub(item.amount),
                                note = DateFormat.dayMonth(item.date),
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                Text(SalaryFormat.CLOSE, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
        if (picking != null) {
            DatePickerPanel(
                initial = if (picking == PeriodEnd.FROM) from else to,
                onPick = { date ->
                    onPick(null)
                    if (picking == PeriodEnd.FROM) onChange(date, to) else onChange(from, date)
                },
                onDismiss = { onPick(null) },
            )
        }
    }
}

/** Строка даты периода: «С» слева, дата справа; нажатие — календарь. */
@Composable
private fun DateRow(title: String, date: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        Text(DateFormat.full(date), style = MaterialTheme.typography.bodyLarge, color = colors.text)
    }
}
