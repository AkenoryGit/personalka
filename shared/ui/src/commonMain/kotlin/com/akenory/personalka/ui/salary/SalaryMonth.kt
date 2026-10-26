package com.akenory.personalka.ui.salary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SalaryMonthData
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.format.SettlementFormat
import com.akenory.personalka.domain.format.SlotFormat
import com.akenory.personalka.domain.money.BreakdownLine
import com.akenory.personalka.domain.money.BreakdownSection
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.domain.money.PayoutKind
import com.akenory.personalka.domain.money.Payouts
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.money.TierProgress
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth
import kotlin.math.roundToInt

internal const val SALARY_EARNED_TAG = "salary-earned"
internal const val SALARY_PLAN_TAG = "salary-plan"
internal const val SALARY_SLOTS_TAG = "salary-slots"
internal const val SALARY_PLAN_FIELD_TAG = "salary-plan-field"
internal const val SALARY_TITLE_TAG = "salary-title"
internal const val SALARY_PREV_TAG = "salary-prev"
internal const val SALARY_NEXT_TAG = "salary-next"
internal const val SALARY_ADD_ADJUST_TAG = "salary-add-adjust"
internal const val ADJUST_AMOUNT_TAG = "adjust-amount"
internal const val ADJUST_FOR_TAG = "adjust-for"
internal const val SALARY_ON_HAND_TAG = "salary-on-hand"
internal const val SALARY_ADVANCE_TAG = "salary-advance"
internal const val ADVANCE_FIELD_TAG = "advance-field"
internal const val SALARY_TOTAL_TAG = "salary-total"
internal const val SALARY_CHART_TAG = "salary-chart"
internal const val SALARY_CLOSED_TAG = "salary-closed"
internal fun salaryTabTag(tab: SalaryTab) = "salary-tab-${tab.name}"

/** Вкладки «Зарплаты»: «Коллеги» — только с «Совместным ведением групп» (3.8); «Год» — шаг 3.8б. */
enum class SalaryTab(val title: String) {
    MONTH(SalaryFormat.TAB_MONTH),
    PAYOUTS(SalaryFormat.TAB_PAYOUTS),
    COLLEAGUES(SettlementFormat.TAB),
    YEAR(SalaryFormat.TAB_YEAR),
}

/** Новая доплата в окне «+ Доплата» — вместо id записи. */
private const val NEW_ADJUSTMENT = "new"

/**
 * «Зарплата» (ТЗ 5.12): сверху месяц со стрелками и «Ставки и ступени», вкладки «Месяц», «Выплаты», «Коллеги» и «Год».
 * «Месяц» — заработано, план и прогноз, ступень, разбивка, доплаты; «Выплаты» — начислено, налог, когда что придёт
 * и на руки; «Коллеги» — взаиморасчёт; «Год» — итоги месяцев (сверху тогда год, стрелки листают годы). Месяц,
 * вкладка и прокрутка переживают сложение Fold. [shareText] —
 * системное «Поделиться» текстом; нет его — текст расчёта копируется.
 */
@Composable
internal fun SalaryMonthScreen(
    settings: SettingsRepository,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onRates: () -> Unit,
    shareText: ((String) -> Unit)? = null,
    advanceAsk: Boolean = false,
    onAdvanceShown: () -> Unit = {},
    onSlots: (() -> Unit)? = null,
) {
    var monthText by rememberSaveable { mutableStateOf(today.yearMonth.toString()) }
    val month = YearMonth.parse(monthText)
    var tab by rememberSaveable { mutableStateOf(SalaryTab.MONTH) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    var adjusting by rememberSaveable { mutableStateOf<String?>(null) }
    var advanceOpen by rememberSaveable { mutableStateOf(false) }
    // День графика (3.7в) — свой у каждого месяца; переживает сложение Fold.
    var dayText by rememberSaveable(monthText) { mutableStateOf<String?>(null) }
    var periodFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var periodTo by rememberSaveable { mutableStateOf<String?>(null) }
    var periodPick by rememberSaveable { mutableStateOf<PeriodEnd?>(null) }
    // Раскрытые случаи «Коллег» — свои у месяца.
    var expanded by rememberSaveable(monthText) { mutableStateOf(listOf<String>()) }
    val monthScroll = rememberScrollState()
    val payoutScroll = rememberScrollState()
    val colleagueScroll = rememberScrollState()
    val yearScroll = rememberScrollState()
    // Левая колонка разложенного Fold (3.8а) — своя прокрутка, объявлена над веткой wide.
    val sideScroll = rememberScrollState()
    val wide = LocalWide.current
    val coCoaching by remember(settings) { settings.salary.coCoaching }.collectAsState(initial = false)
    // На разложенном «Год» — левая колонка, вкладки нет; выбранная вкладка помнится и вернётся при сложении.
    val tabs = SalaryTab.entries.filter { (it != SalaryTab.COLLEAGUES || coCoaching) && (it != SalaryTab.YEAR || !wide) }
    val shown = if (tab in tabs) tab else SalaryTab.MONTH
    val clipboard = LocalClipboardManager.current
    val repo = settings.months
    val data by remember(repo, month, today) { repo.month(month, today) }.collectAsState(initial = null)
    val needYear = shown == SalaryTab.YEAR || wide
    val yearData by remember(repo, month.year, today, needYear) {
        if (needYear) repo.year(month.year, today) else flowOf(null)
    }.collectAsState(initial = null)
    val colors = PersonalkaTheme.colors
    // «Введите сумму аванса» с «Главной» или из уведомления (шаг 3.9): этот месяц, «Выплаты» и окно аванса.
    LaunchedEffect(advanceAsk) {
        if (advanceAsk) {
            monthText = today.yearMonth.toString()
            tab = SalaryTab.PAYOUTS
            advanceOpen = true
            onAdvanceShown()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideWidth = (maxWidth * 0.45f).coerceIn(280.dp, 360.dp)
        // Правая часть всегда первой и на одном месте дерева: при сложении меняется только отступ слева (как в AppShell).
        Column(Modifier.fillMaxSize().padding(start = if (wide) sideWidth else 0.dp)) {
            // На «Годе» стрелки листают годы: тот же месяц прошлого или следующего года.
            val step = if (shown == SalaryTab.YEAR) 12 else 1
            MonthBar(
                // На разложенном год — слева, справа только месяц.
                title = when {
                    shown == SalaryTab.YEAR -> month.year.toString()
                    wide -> SalaryFormat.monthName(month)
                    else -> SalaryFormat.month(month)
                },
                year = shown == SalaryTab.YEAR,
                onPrev = { monthText = month.firstDay.minus(step, DateTimeUnit.MONTH).yearMonth.toString() },
                onNext = { monthText = month.firstDay.plus(step, DateTimeUnit.MONTH).yearMonth.toString() },
                onRates = onRates,
            )
            SalaryTabs(tabs, shown) { tab = it }
            val loaded = data?.takeIf { it.view.month == month }
            val year = yearData?.takeIf { it.year == month.year }
            if (loaded == null || (shown == SalaryTab.YEAR && year == null)) {
                Spacer(Modifier.weight(1f))
                return@Column
            }
            val scroll = when (shown) {
                SalaryTab.MONTH -> monthScroll
                SalaryTab.PAYOUTS -> payoutScroll
                SalaryTab.COLLEAGUES -> colleagueScroll
                SalaryTab.YEAR -> yearScroll
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
                when (shown) {
                    SalaryTab.MONTH -> MonthTab(
                        loaded,
                        day = dayText?.let { LocalDate.parse(it) },
                        onDay = { dayText = it.toString() },
                        onPeriod = {
                            val (from, to) = defaultPeriod(month.firstDay, month.lastDay, today)
                            periodFrom = from.toString()
                            periodTo = to.toString()
                        },
                        onPlan = { planOpen = true },
                        onAdjust = { adjusting = it },
                        onSlots = onSlots,
                    )
                    SalaryTab.PAYOUTS -> PayoutsTab(loaded, today, onAdvance = { advanceOpen = true })
                    SalaryTab.COLLEAGUES -> ColleaguesTab(
                        loaded,
                        expanded = expanded.toSet(),
                        onExpand = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
                        onSettle = { row, settled ->
                            scope.launch {
                                repo.settle(month, row.colleagueId, if (settled) row.total else null)
                                val text = if (settled) SettlementFormat.SETTLED_MESSAGE else SettlementFormat.UNSETTLED_MESSAGE
                                // «Отменить» возвращает прежнюю отметку, в том числе устаревшую сумму.
                                messages.showUndo(text) { scope.launch { repo.settle(month, row.colleagueId, row.settled) } }
                            }
                        },
                        onShare = { row ->
                            val text = SettlementFormat.shareText(month, row) { placeName(loaded, it) }
                            if (shareText != null) {
                                shareText(text)
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                messages.show(SettlementFormat.COPIED)
                            }
                        },
                    )
                    SalaryTab.YEAR -> YearTab(year!!) { picked ->
                        monthText = picked.toString()
                        tab = SalaryTab.MONTH
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.width(sideWidth).fillMaxHeight()) {
                YearSide(
                    yearNumber = month.year,
                    year = yearData,
                    selected = month,
                    scroll = sideScroll,
                    onPrev = { monthText = month.firstDay.minus(12, DateTimeUnit.MONTH).yearMonth.toString() },
                    onNext = { monthText = month.firstDay.plus(12, DateTimeUnit.MONTH).yearMonth.toString() },
                    onMonth = { monthText = it.toString() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider(thickness = 1.dp, color = colors.divider)
            }
        }
        val loaded = data?.takeIf { it.view.month == month } ?: return@BoxWithConstraints
        if (planOpen) {
            PlanPanel(loaded.view, onDismiss = { planOpen = false }) { amount ->
                planOpen = false
                scope.launch {
                    repo.savePlan(month, amount)
                    messages.show(SalaryFormat.PLAN_SAVED)
                }
            }
        }
        adjusting?.let { id ->
            AdjustmentPanel(
                data = loaded,
                id = id.takeIf { it != NEW_ADJUSTMENT },
                onDismiss = { adjusting = null },
                onSave = { amount, comment, inClub ->
                    adjusting = null
                    scope.launch {
                        if (id == NEW_ADJUSTMENT) {
                            repo.addAdjustment(month, amount, comment, inClub)
                            messages.show(SalaryFormat.adjustmentSaved(amount < 0))
                        } else {
                            repo.updateAdjustment(id, amount, comment, inClub)
                        }
                    }
                },
                onDelete = { deduction ->
                    adjusting = null
                    scope.launch {
                        repo.deleteAdjustment(id)
                        messages.showUndo(SalaryFormat.adjustmentDeleted(deduction)) { scope.launch { repo.deleteAdjustment(id, restore = true) } }
                    }
                },
            )
        }
        val from = periodFrom
        val to = periodTo
        if (from != null && to != null) {
            PeriodPanel(
                repo = repo,
                from = LocalDate.parse(from),
                to = LocalDate.parse(to),
                today = today,
                picking = periodPick,
                onPick = { periodPick = it },
                onChange = { f, t ->
                    periodFrom = f.toString()
                    periodTo = t.toString()
                },
                onDismiss = {
                    periodFrom = null
                    periodTo = null
                    periodPick = null
                },
            )
        }
        if (advanceOpen) {
            AdvancePanel(loaded.view, onDismiss = { advanceOpen = false }) { amount ->
                advanceOpen = false
                scope.launch {
                    repo.saveAdvance(month, amount)
                    messages.show(SalaryFormat.ADVANCE_SAVED)
                }
            }
        }
    }
}

@Composable
private fun MonthBar(title: String, year: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onRates: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space4, end = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag(SALARY_TITLE_TAG),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            maxLines = 1,
        )
        IconButton(onClick = onPrev, modifier = Modifier.testTag(SALARY_PREV_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = if (year) "Прошлый год" else "Прошлый месяц", tint = colors.text, modifier = Modifier.rotate(180f))
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(SALARY_NEXT_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = if (year) "Следующий год" else "Следующий месяц", tint = colors.text)
        }
        IconButton(onClick = onRates, modifier = Modifier.testTag(SALARY_RATES_TAG)) {
            Icon(PIcons.Tune, contentDescription = RateFormat.TITLE, tint = colors.text)
        }
    }
}

@Composable
private fun SalaryTabs(tabs: List<SalaryTab>, selected: SalaryTab, onSelect: (SalaryTab) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .height(Dimens.touch)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                        .testTag(salaryTabTag(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(tab.title, style = MaterialTheme.typography.labelLarge, color = if (isSelected) colors.text else colors.textMuted)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(64.dp).height(3.dp).background(if (isSelected) colors.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
    }
}

/** Карточка на surface со скруглением 12 и отступами 16. */
@Composable
internal fun SalaryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(PersonalkaTheme.colors.surface)
            .padding(Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        content = content,
    )
}

@Composable
internal fun CardHeader(title: String, right: String? = null) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (right != null) Text(right, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
}

/** Строка суммы: подпись и пояснение слева, сумма справа; [bold] — итог. */
@Composable
internal fun MoneyRow(
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    lead: String? = null,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .heightIn(min = Dimens.touch)
            .padding(vertical = Dimens.space2),
        verticalAlignment = Alignment.Top,
    ) {
        if (lead != null) {
            Text(lead, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = colors.text)
        }
        Column(Modifier.weight(1f).padding(end = Dimens.space3)) {
            Text(
                title,
                style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontWeight = if (bold) FontWeight.SemiBold else null,
            )
            if (note != null) Muted(note)
            extra?.invoke()
        }
        Text(
            amount,
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

// --- Месяц ---

@Composable
private fun MonthTab(
    data: SalaryMonthData,
    day: LocalDate?,
    onDay: (LocalDate) -> Unit,
    onPeriod: () -> Unit,
    onPlan: () -> Unit,
    onAdjust: (String) -> Unit,
    onSlots: (() -> Unit)? = null,
) {
    val view = data.view
    EarnedCard(view, onPlan, onSlots)
    view.tiers?.let { TierCard(it, view.period, data) }
    ChartCard(data, day, onDay, onPeriod)
    BreakdownCard(data, onAdjust)
    if (view.closed) ClosedMark(view)
}

/**
 * «По дням» (5.12, макет PayMonth): заработано сплошной линией до сегодняшней точки, прогноз пунктиром до конца
 * месяца, темп плана — тонкая прямая к плану; вертикальный пунктир — сегодня. Подписи сетки — слева над линиями.
 * Нажатие на день (до сегодня) — под графиком что заработано в этот день; «Период» — заработок за любые даты (3.7в).
 */
@Composable
private fun ChartCard(data: SalaryMonthData, day: LocalDate?, onDay: (LocalDate) -> Unit, onPeriod: () -> Unit) {
    val view = data.view
    val chart = view.chart
    val selected = day?.takeIf { it.yearMonth == view.month && it.day in 1..chart.factDay }
    val colors = PersonalkaTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.tabular().copy(color = colors.textMuted)
    val factDate = if (chart.factDay > 0) LocalDate(view.month.year, view.month.month, chart.factDay) else null
    val summary = SalaryFormat.chartDescription(chart.earned.last(), factDate, chart.forecast.lastOrNull(), chart.plan)
    SalaryCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                SalaryFormat.CHART,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            SecondaryButton(SalaryFormat.PERIOD, onPeriod, Modifier.testTag(SALARY_PERIOD_TAG), icon = PIcons.CalendarMonth)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = Dimens.space2)
                .semantics { contentDescription = summary }
                .pointerInput(view.month, chart.factDay, onDay) {
                    detectTapGestures { pos ->
                        val left = 44.dp.toPx()
                        val right = size.width - 4.dp.toPx()
                        val d = ((pos.x - left) / (right - left) * chart.days).roundToInt()
                        if (chart.factDay > 0) onDay(LocalDate(view.month.year, view.month.month, d.coerceIn(1, chart.factDay)))
                    }
                }
                .testTag(SALARY_CHART_TAG),
        ) {
            val gutter = 44.dp.toPx()
            val bottomBand = 22.dp.toPx()
            val left = gutter
            val right = size.width - 4.dp.toPx()
            val top = 4.dp.toPx()
            val bottom = size.height - bottomBand
            val max = chart.top.toFloat()
            fun px(day: Int) = left + day.toFloat() / chart.days * (right - left)
            fun py(amount: Long) = bottom - amount / max * (bottom - top)
            val thin = 1.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))

            (listOf(0L) + chart.ticks).forEach { tick ->
                val y = py(tick)
                drawLine(colors.divider, Offset(left, y), Offset(right, y), strokeWidth = thin)
                val label = measurer.measure(if (tick == 0L) "0" else SalaryFormat.axis(tick), labelStyle)
                drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
            }
            listOf(1, 10, 20, chart.days).forEach { day ->
                val label = measurer.measure("$day", labelStyle)
                val x = (px(day) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, bottom + 4.dp.toPx()))
            }
            chart.plan?.let { plan ->
                drawLine(colors.outline, Offset(px(0), py(0)), Offset(px(chart.days), py(plan)), strokeWidth = 1.5.dp.toPx())
            }
            fun line(points: List<Long>, from: Int): Path = Path().apply {
                points.forEachIndexed { i, v -> if (i == 0) moveTo(px(from), py(v)) else lineTo(px(from + i), py(v)) }
            }
            if (chart.forecast.size > 1) {
                drawPath(line(chart.forecast, chart.factDay), colors.text, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
            }
            if (chart.earned.size > 1) {
                drawPath(line(chart.earned, 0), colors.primary, style = Stroke(width = 2.5.dp.toPx()))
            }
            if (view.period == MonthPeriod.CURRENT) {
                val x = px(chart.factDay)
                drawLine(colors.outline, Offset(x, top), Offset(x, bottom), strokeWidth = thin, pathEffect = dash)
                drawCircle(colors.primary, 4.dp.toPx(), Offset(x, py(chart.earned.last())))
            }
            selected?.let { d ->
                val x = px(d.day)
                drawLine(colors.text, Offset(x, top), Offset(x, bottom), strokeWidth = thin)
                drawCircle(colors.text, 4.dp.toPx(), Offset(x, py(chart.earned[d.day])))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            if (view.period != MonthPeriod.FUTURE) LineLegend(colors.primary, SalaryFormat.CHART_EARNED)
            if (chart.forecast.size > 1) LineLegend(colors.text, SalaryFormat.CHART_FORECAST, dashed = true)
            if (chart.plan != null) LineLegend(colors.outline, SalaryFormat.CHART_PACE)
        }
        if (selected != null) DayDetails(data, selected) else if (chart.factDay > 0) Muted(SalaryFormat.DAY_HINT)
    }
}

@Composable
private fun LineLegend(color: Color, text: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
            val y = size.height / 2f
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())) else null
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = effect)
        }
        Muted(text, Modifier.padding(start = Dimens.space1))
    }
}

/**
 * Отметка «Сверен» под разбивкой (5.12, 4.1). Сверяют и снимают сверку в «Ещё → Сверки» (5.14, шаг 3.9в): здесь
 * только видно, что правки месяца спросят подтверждение.
 */
@Composable
private fun ClosedMark(view: SalaryMonth) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Row(Modifier.testTag(SALARY_CLOSED_TAG), verticalAlignment = Alignment.CenterVertically) {
            Icon(PIcons.CheckCircle, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            Text(SalaryFormat.MONTH_CLOSED, Modifier.padding(start = Dimens.space2), style = MaterialTheme.typography.titleMedium, color = colors.text)
        }
        Muted(SalaryFormat.closedNote(view.month))
        Muted(SalaryFormat.CLOSED_WHERE)
    }
}

@Composable
private fun EarnedCard(view: SalaryMonth, onPlan: () -> Unit, onSlots: (() -> Unit)? = null) {
    val colors = PersonalkaTheme.colors
    SalaryCard {
        Muted(SalaryFormat.EARNED)
        Text(
            MoneyFormat.rub(view.pay.earned),
            modifier = Modifier.testTag(SALARY_EARNED_TAG),
            style = MaterialTheme.typography.displaySmall,
            color = colors.text,
        )
        SalaryFormat.today(view.today, view.todayAhead)?.let { Muted(it) }
        Spacer(Modifier.height(Dimens.space2))
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClick = onPlan).testTag(SALARY_PLAN_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val plan = view.plan
            Text(
                if (plan == null) SalaryFormat.NO_PLAN else "${SalaryFormat.PLAN}  ${MoneyFormat.rub(plan)}",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
            )
            Icon(PIcons.Edit, contentDescription = SalaryFormat.PLAN_TITLE, tint = colors.textMuted, modifier = Modifier.padding(start = Dimens.space2).size(18.dp))
            Spacer(Modifier.weight(1f))
            view.percent?.let { Muted(SalaryFormat.done(it)) }
        }
        val plan = view.plan
        if (plan != null && plan > 0) {
            val done = (view.pay.earned.toFloat() / plan).coerceIn(0f, 1f)
            val forecast = if (view.hasForecast) (view.pay.forecast.toFloat() / plan).coerceIn(done, 1f) else done
            TwoPartBar(done, forecast)
        }
        if (view.hasForecast) {
            Row(Modifier.fillMaxWidth()) {
                Muted(SalaryFormat.forecast(view.pay.forecast), Modifier.weight(1f))
                view.forecastPercent?.let { Muted(SalaryFormat.ofPlan(it)) }
            }
        }
        if (view.plan != null && !view.planOwn) Muted(SalaryFormat.PLAN_CARRIED)
        view.shortfall?.let { Note(SalaryFormat.shortfall(it)) }
        // «Найти свободные слоты» (макет PayMonth, 5.13): куда поставить недостающие ПТ.
        if (view.shortfall != null && onSlots != null) {
            TextButton(onClick = onSlots, modifier = Modifier.testTag(SALARY_SLOTS_TAG)) {
                Icon(PIcons.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(SlotFormat.FIND_SLOTS, Modifier.padding(start = Dimens.space2), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Полоса из двух частей: проведено — графит, прогноз — серый; конец полосы — план (макет PayMonth). */
@Composable
internal fun TwoPartBar(done: Float, forecast: Float, height: Dp = 8.dp) {
    val colors = PersonalkaTheme.colors
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(colors.surfaceAlt)) {
        if (forecast > 0f) Box(Modifier.fillMaxWidth(forecast).fillMaxHeight().background(colors.outline))
        if (done > 0f) Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(colors.primary))
    }
}

@Composable
private fun TierCard(t: TierProgress, period: MonthPeriod, data: SalaryMonthData) {
    val colors = PersonalkaTheme.colors
    SalaryCard {
        CardHeader(SalaryFormat.TIER, SalaryFormat.tierHeader(t, period))
        val max = maxOf(t.t3From + 9, t.forecastCount, t.count).toFloat()
        val shownForecast = if (period == MonthPeriod.PAST) t.count else t.forecastCount
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
            val width = maxWidth
            Column {
                TwoPartBar(t.count / max, shownForecast / max, 12.dp)
                Box(Modifier.fillMaxWidth().height(20.dp)) {
                    listOf(t.t2From, t.t3From).forEach { from ->
                        Muted("$from", Modifier.offset(x = width * (from / max) - 12.dp).width(24.dp))
                    }
                }
            }
            listOf(t.t2From, t.t3From).forEach { from ->
                Box(Modifier.offset(x = width * (from / max) - 1.dp).width(2.dp).height(12.dp).background(colors.text))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Legend(colors.primary, SalaryFormat.tierDone(t.count))
            if (period != MonthPeriod.PAST && t.forecastCount > t.count) Legend(colors.outline, SalaryFormat.tierPlanned(t.forecastCount - t.count))
        }
        if (period != MonthPeriod.PAST) {
            val next = SalaryFormat.toNext(t)
            if (next == null) Text(SalaryFormat.TIER_TOP, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            else Text(next, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            t.beyond?.let { Text(SalaryFormat.beyond(t.forecastCount, it), style = MaterialTheme.typography.bodyLarge, color = colors.text) }
        }
        val names = data.types.values.filter { it.id in t.ptTypeIds }.map { it.name }
        SalaryFormat.counted(names)?.let { Muted(it) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Muted(text, Modifier.padding(start = Dimens.space1))
    }
}

@Composable
private fun BreakdownCard(data: SalaryMonthData, onAdjust: (String) -> Unit) {
    val view = data.view
    val colors = PersonalkaTheme.colors
    SalaryCard {
        CardHeader(SalaryFormat.BREAKDOWN, SalaryFormat.byTier(view.pay.tier))
        view.breakdown.groupBy { it.section }.forEach { (section, lines) ->
            SectionTitle(SalaryFormat.section(section, view.pay.ptCount))
            lines.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                BreakdownRow(line, data)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            Text(SalaryFormat.ADJUSTMENTS, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            SecondaryButton(SalaryFormat.ADD_ADJUSTMENT, { onAdjust(NEW_ADJUSTMENT) }, Modifier.testTag(SALARY_ADD_ADJUST_TAG), icon = PIcons.Add)
        }
        data.adjustments.forEach { a ->
            MoneyRow(
                a.comment,
                MoneyFormat.signedRub(a.amount),
                note = SalaryFormat.adjustmentNote(a.inClubAccrual),
                onClick = { onAdjust(a.id) },
            )
        }
        HorizontalDivider(thickness = 2.dp, color = colors.text)
        MoneyRow(SalaryFormat.EARNED, MoneyFormat.rub(view.pay.earned), Modifier.testTag(SALARY_TOTAL_TAG), bold = true)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = Dimens.space3), style = MaterialTheme.typography.labelMedium, color = PersonalkaTheme.colors.textMuted)
}

@Composable
internal fun BreakdownRow(line: BreakdownLine, data: SalaryMonthData) {
    val type = data.types[line.typeId]
    val place = data.places[line.locationId]
    val title = SalaryFormat.lineTitle(line, type?.name.orEmpty(), place?.shortName?.ifBlank { place.name } ?: "")
    val note = SalaryFormat.lineNote(line, line.colleagueId?.let { data.colleagues[it] })
    MoneyRow(title, MoneyFormat.rub(line.amount), note = note)
}

// --- Выплаты ---

@Composable
private fun PayoutsTab(data: SalaryMonthData, today: LocalDate, onAdvance: () -> Unit) {
    val view = data.view
    val payout = view.payout
    val colors = PersonalkaTheme.colors
    SalaryCard {
        Muted(SalaryFormat.ACCRUED)
        Text(MoneyFormat.rub(payout.accrued), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Muted(SalaryFormat.accruedNote(view.period, today, payout.kind))
        val scheme = view.scheme
        if (payout.official != 0L && scheme != null) {
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            MoneyRow(SalaryFormat.official(scheme.officialShareBp), MoneyFormat.rub(payout.official), note = SalaryFormat.officialNote(scheme.officialShareBp))
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            MoneyRow(SalaryFormat.ndfl(scheme.ndflBp), MoneyFormat.rub(-payout.ndfl), note = SalaryFormat.NDFL_NOTE)
        }
        SectionTitle(SalaryFormat.WHEN)
        payout.payouts.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            when (p.kind) {
                PayoutKind.ADVANCE -> MoneyRow(
                    SalaryFormat.payout(p.kind),
                    MoneyFormat.rub(p.amount),
                    Modifier.testTag(SALARY_ADVANCE_TAG),
                    lead = SalaryFormat.payoutDate(p.date),
                    onClick = onAdvance,
                    extra = { AdvanceChip(view.advanceActual != null) },
                )
                PayoutKind.CARD_REST -> MoneyRow(
                    SalaryFormat.payout(p.kind),
                    MoneyFormat.rub(p.amount),
                    lead = SalaryFormat.payoutDate(p.date),
                    note = if (payout.advanceExceedsCard) SalaryFormat.ADVANCE_EXCEEDS else SalaryFormat.cardRestNote(payout.card),
                )
                else -> MoneyRow(SalaryFormat.payout(p.kind), MoneyFormat.rub(p.amount), lead = SalaryFormat.payoutDate(p.date))
            }
        }
        view.pay.settlements.filterValues { it != 0L }.forEach { (id, amount) ->
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            MoneyRow(
                SalaryFormat.settlement(data.colleagues[id] ?: SalaryFormat.COLLEAGUE, amount),
                MoneyFormat.signedRub(amount),
                lead = SalaryFormat.NO_DATE,
                note = SettlementFormat.payoutNote(view.colleagues.any { it.colleagueId == id && it.isSettled }),
            )
        }
        HorizontalDivider(thickness = 2.dp, color = colors.text)
        MoneyRow(SalaryFormat.ON_HAND, MoneyFormat.rub(view.totals.onHand), Modifier.testTag(SALARY_ON_HAND_TAG), bold = true)
        if (view.hasForecast && view.forecastOnHand != view.totals.onHand) Muted(SalaryFormat.ifScheduled(view.forecastOnHand))
    }
}

@Composable
private fun AdvanceChip(received: Boolean) {
    val colors = PersonalkaTheme.colors
    Text(
        if (received) SalaryFormat.ADVANCE_RECEIVED else SalaryFormat.ADVANCE_EXPECTED,
        modifier = Modifier
            .padding(top = Dimens.space1)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = Dimens.space2, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = colors.text,
    )
}

// --- Окна ---

@Composable
private fun PlanPanel(view: SalaryMonth, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(RateRules.moneyText(view.plan)) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val amount = RateRules.money(text)
    val error = if (!tried) null else RateRules.moneyProblem(text) ?: if (amount == null || amount == 0L) SalaryFormat.AMOUNT_EMPTY else null
    Panel(SalaryFormat.PLAN_TITLE, onDismiss) {
        Field(
            SalaryFormat.PLAN_FIELD,
            text,
            { text = it },
            error = error,
            hint = SalaryFormat.PLAN_NOTE,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            tag = SALARY_PLAN_FIELD_TAG,
        )
        PanelActions(SalaryFormat.SAVE, {
            tried = true
            if (amount != null && amount > 0) onSave(amount)
        }, SalaryFormat.CANCEL, onDismiss)
    }
}

@Composable
private fun AdjustmentPanel(
    data: SalaryMonthData,
    id: String?,
    onDismiss: () -> Unit,
    onSave: (amount: Long, comment: String, inClub: Boolean) -> Unit,
    onDelete: (deduction: Boolean) -> Unit,
) {
    val old = id?.let { i -> data.adjustments.firstOrNull { it.id == i } }
    var deduction by rememberSaveable { mutableStateOf((old?.amount ?: 0) < 0) }
    var text by rememberSaveable { mutableStateOf(RateRules.moneyText(old?.amount?.let { if (it < 0) -it else it })) }
    var comment by rememberSaveable { mutableStateOf(old?.comment.orEmpty()) }
    var inClub by rememberSaveable { mutableStateOf(old?.inClubAccrual ?: true) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val abs = RateRules.money(text)?.takeIf { it > 0 }
    val signed = abs?.let { if (deduction) -it else it }
    val amountError = if (!tried) null else RateRules.moneyProblem(text) ?: if (abs == null) SalaryFormat.AMOUNT_EMPTY else null
    val commentError = if (tried && comment.isBlank()) SalaryFormat.ADJUST_FOR_EMPTY else null
    val willBe = signed?.let { data.view.pay.earned - (old?.amount ?: 0) + it }
    Panel(SalaryFormat.ADJUST_TITLE, onDismiss) {
        Segmented(listOf(false, true), deduction, { if (it) SalaryFormat.DEDUCTION else SalaryFormat.BONUS }, { deduction = it })
        Field(
            SalaryFormat.ADJUST_AMOUNT,
            text,
            { text = it },
            error = amountError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            tag = ADJUST_AMOUNT_TAG,
        )
        Field(SalaryFormat.ADJUST_FOR, comment, { comment = it }, error = commentError, tag = ADJUST_FOR_TAG)
        CheckRow(SalaryFormat.IN_CLUB, inClub, { inClub = it }, subtitle = SalaryFormat.IN_CLUB_NOTE)
        willBe?.let { Muted(SalaryFormat.earnedWillBe(it)) }
        val save = {
            tried = true
            if (signed != null && comment.isNotBlank()) onSave(signed, comment, inClub)
        }
        if (old == null) {
            PanelActions(SalaryFormat.addAdjustment(deduction), save, SalaryFormat.CANCEL, onDismiss)
        } else {
            PanelActions(SalaryFormat.SAVE, save, SalaryFormat.DELETE, onSecondary = { onDelete(old.amount < 0) })
        }
    }
}

@Composable
private fun AdvancePanel(view: SalaryMonth, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val expected = view.scheme?.advanceDefault
    var text by rememberSaveable { mutableStateOf(RateRules.moneyText(view.advanceActual ?: expected)) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val amount = RateRules.money(text)
    val error = if (!tried) null else RateRules.moneyProblem(text) ?: if (amount == null) SalaryFormat.AMOUNT_EMPTY else null
    val date = Payouts.advanceDate(view.month, view.scheme) ?: return
    Panel(SalaryFormat.advanceTitle(date), onDismiss) {
        Field(
            SalaryFormat.ADVANCE_FIELD,
            text,
            { text = it },
            error = error,
            hint = expected?.let { SalaryFormat.advanceExpected(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            tag = ADVANCE_FIELD_TAG,
        )
        PanelActions(SalaryFormat.SAVE, {
            tried = true
            if (amount != null) onSave(amount)
        }, SalaryFormat.CANCEL, onDismiss)
    }
}
