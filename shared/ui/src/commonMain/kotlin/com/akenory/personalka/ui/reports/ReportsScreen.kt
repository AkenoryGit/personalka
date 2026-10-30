package com.akenory.personalka.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.report.ReportNames
import com.akenory.personalka.data.report.ReportRepository
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.report.ReportKey
import com.akenory.personalka.domain.report.ReportPeriod
import com.akenory.personalka.domain.report.ReportRules
import com.akenory.personalka.domain.report.ReportTable
import com.akenory.personalka.domain.report.ReportYear
import com.akenory.personalka.ui.calendar.COPIED
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.CardHeader
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.TrainingCardHost
import com.akenory.personalka.ui.training.TrainingCardKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

internal const val REPORTS_TITLE_TAG = "reports-title"
internal const val REPORTS_PREV_TAG = "reports-prev"
internal const val REPORTS_NEXT_TAG = "reports-next"
internal const val REPORTS_SHARE_TAG = "reports-share"
internal const val REPORTS_EMPTY_TAG = "reports-empty"
internal const val REPORTS_TOTAL_TAG = "reports-total"
internal const val REPORTS_FROM_TAG = "reports-from"
internal const val REPORTS_TO_TAG = "reports-to"
internal const val REPORTS_GRID_TAG = "reports-grid"
internal fun reportPeriodTag(p: ReportPeriod) = "reports-period-${p.name}"
internal fun reportRowTag(key: ReportKey) = "reports-row-${keyText(key)}"
internal fun reportItemTag(id: String) = "reports-item-$id"
internal fun reportMonthTag(month: YearMonth) = "reports-month-$month"
internal fun reportWhatTag(key: ReportKey?) = "reports-what-${key?.let(::keyText) ?: "all"}"

/** Ключ строки — строкой, чтобы открытый список и выбор «Что считать» пережили пересоздание окна. */
internal fun keyText(key: ReportKey): String = "${key.typeId}|${key.locationId}|${key.groupCase?.name.orEmpty()}"

private fun keyOf(text: String): ReportKey {
    val (type, place, case) = text.split('|')
    return ReportKey(type, place, case.takeIf { it.isNotEmpty() }?.let(GroupCase::valueOf))
}

private enum class PickEnd { FROM, TO }

/**
 * «Отчёты» (ТЗ 5.15, шаг 5.3и) — пункт «Ещё → Работа». Сверху период: «Месяц» и «Год» со стрелками, «Свой период»
 * с датами «С» и «По». Месяц и свой период — строки «тип · место» с числом «В зарплату» справа и «проведено · со
 * списанием · отменено» под названием; нажатие на строку — список её тренировок, нажатие на тренировку — её карточка.
 * Год — сверка месяцев по выбранной паре; на разложенном Fold — полная таблица «пары × месяцы». «Поделиться» —
 * отчёт текстом. Всё выбранное объявлено над разметкой: сложение Fold его не сбрасывает.
 */
@Composable
internal fun ReportsScreen(
    reports: ReportRepository,
    calendar: CalendarRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
    shareText: ((String) -> Unit)? = null,
    onEdit: (String) -> Unit = {},
    onClient: (String) -> Unit = {},
    onGroup: (String) -> Unit = {},
    onDiary: (String) -> Unit = {},
) {
    val today = now.date
    var periodText by rememberSaveable { mutableStateOf(ReportPeriod.MONTH.name) }
    val period = ReportPeriod.valueOf(periodText)
    var monthText by rememberSaveable { mutableStateOf(today.yearMonth.toString()) }
    val month = YearMonth.parse(monthText)
    var year by rememberSaveable { mutableStateOf(today.year) }
    var fromText by rememberSaveable { mutableStateOf(today.yearMonth.firstDay.toString()) }
    var toText by rememberSaveable { mutableStateOf(today.toString()) }
    val from = LocalDate.parse(fromText)
    val to = LocalDate.parse(toText)
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }
    var whatText by rememberSaveable { mutableStateOf<String?>(null) }
    var card by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val wide = LocalWide.current
    val colors = PersonalkaTheme.colors
    val clipboard = LocalClipboardManager.current

    val days = to.toEpochDays() - from.toEpochDays() + 1
    val problem = when {
        period != ReportPeriod.CUSTOM -> null
        days < 1 -> ReportFormat.REVERSED
        days > ReportRules.MAX_DAYS -> ReportFormat.TOO_LONG
        else -> null
    }
    // «Сейчас» — с точностью до часа: отчёт не перечитывает базу каждую минуту.
    val hour = LocalDateTime(today, LocalTime(now.hour, 0))
    val data by remember(reports, period, month, year, from, to, hour, problem) {
        when {
            problem != null -> emptyFlow()
            period == ReportPeriod.MONTH -> reports.table(month.firstDay, month.lastDay, now)
            period == ReportPeriod.YEAR -> reports.year(year, now)
            else -> reports.table(from, to, now)
        }
    }.collectAsState(initial = null)
    val title = when (period) {
        ReportPeriod.MONTH -> ReportFormat.month(month)
        ReportPeriod.YEAR -> year.toString()
        ReportPeriod.CUSTOM -> ReportFormat.range(from, to)
    }
    // Данные другого периода, пока новый считается, не показываются.
    val table = data?.table?.takeIf {
        when (period) {
            ReportPeriod.MONTH -> it.from == month.firstDay && it.to == month.lastDay
            ReportPeriod.CUSTOM -> it.from == from && it.to == to
            ReportPeriod.YEAR -> false
        }
    }
    val yearView = data?.year?.takeIf { period == ReportPeriod.YEAR && it.year == year }
    val names = data?.names ?: ReportNames()
    val what = whatText?.let(::keyOf)?.takeIf { yearView == null || it in yearView.keys }

    fun share() {
        val text = when {
            yearView != null -> ReportFormat.shareYear(yearView, what, what?.let(names::row))
            table != null -> ReportFormat.shareText(title, table, names::row)
            else -> return
        }
        if (shareText != null) {
            shareText(text)
        } else {
            clipboard.setText(AnnotatedString(text))
            messages.show(ReportFormat.COPIED)
        }
    }

    TrainingCardHost(
        key = card?.let(TrainingCardKey::card),
        wide = wide,
        cards = calendar.cards,
        results = calendar.results,
        now = now,
        messages = messages,
        scope = scope,
        onClose = { card = null },
        onEdit = onEdit,
        onClient = { id ->
            card = null
            onClient(id)
        },
        onGroup = { id ->
            card = null
            onGroup(id)
        },
        onCopy = { id -> scope.launch { if (calendar.copy(id)) messages.show(COPIED) } },
        onDiary = onDiary,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth()) {
                    TopBar(ReportFormat.TITLE, onBack = onBack)
                    if (table != null || yearView != null) {
                        IconButton(
                            onClick = ::share,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.space1).testTag(REPORTS_SHARE_TAG),
                        ) {
                            Icon(PIcons.Share, contentDescription = ReportFormat.SHARE, tint = colors.text)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.space4),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    ReportPeriod.entries.forEach { p ->
                        ChoiceChip(
                            ReportFormat.period(p),
                            selected = p == period,
                            onClick = {
                                periodText = p.name
                                openRow = null
                            },
                            modifier = Modifier.testTag(reportPeriodTag(p)),
                        )
                    }
                }
                when (period) {
                    ReportPeriod.MONTH -> StepRow(
                        title,
                        onPrev = { monthText = month.firstDay.minus(1, DateTimeUnit.MONTH).yearMonth.toString() },
                        onNext = { monthText = month.firstDay.plus(1, DateTimeUnit.MONTH).yearMonth.toString() },
                    )
                    ReportPeriod.YEAR -> StepRow(title, onPrev = { year -= 1 }, onNext = { year += 1 })
                    ReportPeriod.CUSTOM -> Column(Modifier.padding(horizontal = Dimens.space4)) {
                        DateRow(ReportFormat.FROM, from, Modifier.testTag(REPORTS_FROM_TAG)) { picking = PickEnd.FROM.name }
                        DateRow(ReportFormat.TO, to, Modifier.testTag(REPORTS_TO_TAG)) { picking = PickEnd.TO.name }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.bg)
                        .verticalScroll(scroll)
                        .padding(Dimens.space4)
                        .padding(bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                ) {
                    when {
                        problem != null -> Text(problem, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        table != null -> TableContent(table, names) { openRow = keyText(it) }
                        yearView != null -> YearContent(yearView, names, what, wide, onWhat = { whatText = it?.let(::keyText) }) { m ->
                            monthText = m.toString()
                            periodText = ReportPeriod.MONTH.name
                        }
                    }
                }
            }
            val shownRow = openRow?.let(::keyOf)
            if (table != null && shownRow != null && table.row(shownRow) != null) {
                Panel(ReportFormat.itemsTitle(names.row(shownRow), title), onDismiss = { openRow = null }) {
                    table.itemsOf(shownRow).forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                        MoneyRow(
                            ReportFormat.itemLine(item, names.whom[item.trainingId]),
                            ReportFormat.status(item.status),
                            modifier = Modifier.testTag(reportItemTag(item.trainingId)),
                            onClick = { card = item.trainingId },
                        )
                    }
                    TextButton(onClick = { openRow = null }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                        Text(ReportFormat.CLOSE, style = MaterialTheme.typography.labelLarge, color = colors.text)
                    }
                }
            }
            picking?.let { end ->
                val isFrom = end == PickEnd.FROM.name
                DatePickerPanel(
                    initial = if (isFrom) from else to,
                    onPick = { date ->
                        picking = null
                        if (isFrom) fromText = date.toString() else toText = date.toString()
                    },
                    onDismiss = { picking = null },
                )
            }
        }
    }
}

/** Отчёт месяца или своего периода: строки, итог, «впереди по расписанию». */
@Composable
private fun TableContent(table: ReportTable, names: ReportNames, onRow: (ReportKey) -> Unit) {
    val colors = PersonalkaTheme.colors
    ReportFormat.countedTo(table)?.let { Muted(it) }
    if (table.isEmpty) {
        Muted(ReportFormat.EMPTY, Modifier.testTag(REPORTS_EMPTY_TAG))
    } else {
        SalaryCard {
            CardHeader(" ", right = ReportFormat.IN_SALARY)
            table.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                val note = listOfNotNull(ReportFormat.rowNote(row).ifEmpty { null }, ReportFormat.children(row)).joinToString("\n")
                MoneyRow(
                    names.row(row.key),
                    ReportFormat.salaryValue(row),
                    modifier = Modifier.testTag(reportRowTag(row.key)),
                    note = note.ifEmpty { null },
                    onClick = if (row.trainingIds.isNotEmpty()) ({ onRow(row.key) }) else null,
                )
            }
        }
        SalaryCard {
            MoneyRow(
                ReportFormat.TOTAL,
                table.total.inSalary.toString(),
                modifier = Modifier.testTag(REPORTS_TOTAL_TAG),
                note = ReportFormat.countsLine(table.total),
                bold = true,
            )
            if (table.ptCount > 0) Muted(ReportFormat.totalLine(table))
        }
    }
    if (table.waiting > 0) Muted(ReportFormat.waiting(table.waiting))
    if (table.ahead > 0) Muted(ReportFormat.ahead(table.ahead))
}

/**
 * «Год»: на сложенном — выбор «Что считать» и месяцы строками, нажатие на месяц открывает его; на разложенном —
 * полная таблица «пары × месяцы».
 */
@Composable
private fun YearContent(
    year: ReportYear,
    names: ReportNames,
    what: ReportKey?,
    wide: Boolean,
    onWhat: (ReportKey?) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    if (year.months.isEmpty() || year.total.isEmpty) {
        Muted(ReportFormat.EMPTY, Modifier.testTag(REPORTS_EMPTY_TAG))
        return
    }
    if (wide) {
        YearGrid(year, names, onMonth)
        return
    }
    Text(ReportFormat.WHAT, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ChoiceChip(ReportFormat.ALL, selected = what == null, onClick = { onWhat(null) }, modifier = Modifier.testTag(reportWhatTag(null)))
        year.keys.forEach { key ->
            ChoiceChip(names.row(key), selected = what == key, onClick = { onWhat(key) }, modifier = Modifier.testTag(reportWhatTag(key)))
        }
    }
    SalaryCard {
        CardHeader(" ", right = ReportFormat.IN_SALARY)
        year.months.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            val c = year.counts(m, what)
            MoneyRow(
                ReportFormat.yearMonth(m.month, m.closed),
                c.inSalary.toString(),
                modifier = Modifier.testTag(reportMonthTag(m.month)),
                note = ReportFormat.countsLine(c),
                onClick = { onMonth(m.month) },
            )
        }
    }
    SalaryCard {
        val t = year.totalCounts(what)
        MoneyRow(ReportFormat.YEAR_TOTAL, t.inSalary.toString(), modifier = Modifier.testTag(REPORTS_TOTAL_TAG), note = ReportFormat.countsLine(t), bold = true)
    }
}

/** Полная таблица года на разложенном Fold: строки — пары, столбцы — месяцы, в ячейке «проведено / со списанием / отменено». */
@Composable
private fun YearGrid(year: ReportYear, names: ReportNames, onMonth: (YearMonth) -> Unit) {
    val colors = PersonalkaTheme.colors
    val nameWidth = 168.dp
    val cellWidth = 88.dp
    Muted(ReportFormat.CELL_NOTE)
    SalaryCard(Modifier.testTag(REPORTS_GRID_TAG)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                    Text("", Modifier.width(nameWidth))
                    year.months.forEach { m ->
                        Text(
                            ReportFormat.monthShort(m.month) + if (m.closed) " ✓" else "",
                            Modifier.width(cellWidth).clickable(role = Role.Button) { onMonth(m.month) }.testTag(reportMonthTag(m.month)),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.text,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(ReportFormat.YEAR, Modifier.width(cellWidth), style = MaterialTheme.typography.labelMedium, color = colors.text, textAlign = TextAlign.Center)
                }
                (year.keys + listOf<ReportKey?>(null)).forEach { key ->
                    HorizontalDivider(thickness = 1.dp, color = colors.divider)
                    Row(Modifier.heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            key?.let(names::row) ?: ReportFormat.TOTAL,
                            Modifier.width(nameWidth).padding(end = Dimens.space2),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text,
                        )
                        year.months.forEach { m ->
                            Text(ReportFormat.cell(year.counts(m, key)), Modifier.width(cellWidth), style = MaterialTheme.typography.bodySmall, color = colors.text, textAlign = TextAlign.Center)
                        }
                        Text(ReportFormat.cell(year.totalCounts(key)), Modifier.width(cellWidth), style = MaterialTheme.typography.labelMedium, color = colors.text, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.space4, end = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag(REPORTS_TITLE_TAG),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        IconButton(onClick = onPrev, modifier = Modifier.testTag(REPORTS_PREV_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = "Назад", tint = colors.text, modifier = Modifier.rotate(180f))
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(REPORTS_NEXT_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = "Вперёд", tint = colors.text)
        }
    }
}

/** Дата периода: «С» слева, дата справа; нажатие — календарь. */
@Composable
private fun DateRow(title: String, date: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        Text(DateFormat.full(date), style = MaterialTheme.typography.bodyLarge, color = colors.text)
    }
}
