package com.akenory.personalka.ui.calendar

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.MonthDay
import com.akenory.personalka.domain.calendar.MonthItem
import com.akenory.personalka.domain.calendar.MonthRules
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.CalendarMoneyFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.money.CalendarMoney
import com.akenory.personalka.domain.money.DayMoney
import com.akenory.personalka.ui.components.ShadeDot
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.shade
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

/** Листание «Месяца» и список выбранного дня под ним. */
const val MONTH_PAGER_TAG = "calendar-month-pager"
const val MONTH_DAY_LIST_TAG = "calendar-month-day"

/** Клетка дня в «Месяце» и в мини-календаре. */
fun monthDayTag(date: LocalDate) = "month-$date"
fun miniDayTag(date: LocalDate) = "mini-$date"

/** Сводка клетки месяца — число и точки, как их видит тест. */
val MonthSummary = SemanticsPropertyKey<MonthDay>("MonthSummary")
var SemanticsPropertyReceiver.monthSummary by MonthSummary

/** Деньги клетки месяца (шаг 5.10г), как их видит тест; денег нет — `null`. */
val MonthMoney = SemanticsPropertyKey<DayMoney?>("MonthMoney")
var SemanticsPropertyReceiver.monthMoney by MonthMoney

/** Строка месяца на сложенном (макет «Месяц»): число, число тренировок и точки. */
private val MONTH_ROW = 64.dp

/** С деньгами — без точек (шаг 5.10з): число и сумма, строка ниже. */
private val MONTH_ROW_MONEY = 52.dp

/** Строка дней недели над сеткой месяца. */
private val WEEKDAY_ROW = 28.dp

/** Список выбранного дня справа от месяца на разложенном. */
private val DAY_PANE = 360.dp

/** Точки под числом (макет «Месяц»): 8 dp с кольцом цвета места, через 3 dp. */
private val DOT = 8.dp
private val DOT_GAP = 3.dp

/** Мини-календарь: клетка 40 dp, всегда шесть строк — при листании окно не прыгает. */
private val MINI_CELL = 40.dp
private const val MINI_ROWS = 6

/**
 * «Месяц» (ТЗ 5.3, макет «Месяц»): сетка дней с числом тренировок без отменённых и до четырёх точек — цвет места
 * оттенком типа, как блок на сетке часов; под сеткой — список выбранного дня, на разложенном — справа. Свайп
 * листает месяцы; день соседнего месяца в первой и последней строке приглушён, нажатие на него открывает его месяц.
 *
 * @param months листание по месяцам — страница `MonthRules.page`; хранится выше, чем ширина экрана.
 * @param onSelect нажатие на день сетки.
 * @param onDay нажатие на заголовок дня в списке — этот день в виде «День».
 */
@Composable
internal fun MonthView(
    months: PagerState,
    selected: LocalDate,
    today: LocalDate,
    wide: Boolean,
    itemsOf: (LocalDate) -> List<CalendarTraining>,
    zonesOf: (LocalDate) -> List<CalendarZone>,
    onSelect: (LocalDate) -> Unit,
    onDay: (LocalDate) -> Unit,
    onOpen: (CalendarTraining) -> Unit,
    onZone: (CalendarZone) -> Unit,
    modifier: Modifier = Modifier,
    money: Map<YearMonth, CalendarMoney>? = null,
    onMonths: () -> Unit = {},
) {
    val summaryOf: (LocalDate) -> MonthDay = { date -> MonthRules.day(itemsOf(date).map { it.monthItem() }) }
    // Деньги по дням (шаг 5.10г): `null` — выключены или зарплата не настроена, клетка как раньше — число тренировок.
    val moneyOf: ((LocalDate) -> DayMoney?)? = money?.let { all -> { date -> all[date.yearMonth]?.day(date) } }
    val selectedMoney = money?.get(selected.yearMonth)
    val list = @Composable { listModifier: Modifier ->
        DayList(
            date = selected,
            entries = dayEntries(itemsOf(selected), zonesOf(selected)),
            count = summaryOf(selected).count,
            money = selectedMoney?.let { m ->
                CalendarMoneyFormat.dayLine(m.day(selected), todayEarned = if (selected == today) m.today else null)
            },
            onDay = onDay,
            onOpen = onOpen,
            onZone = onZone,
            modifier = listModifier,
        )
    }
    if (wide) {
        // Разложенный: месяц на всю высоту слева, строки делят её поровну; список дня — справа.
        Row(modifier) {
            MonthGrid(months, selected, today, rowHeight = null, summaryOf, onSelect, Modifier.weight(1f).fillMaxHeight(), money = money, moneyOf = moneyOf, onMonths = onMonths)
            VerticalDivider(color = PersonalkaTheme.colors.divider)
            list(Modifier.width(DAY_PANE).fillMaxHeight())
        }
    } else {
        // Сложенный: строки по 64 dp; месяц из 4–6 строк — сетка плавно меняет высоту, список под ней едет следом.
        val rows = MonthRules.weeks(MonthRules.firstOf(months.currentPage)).size
        val row = if (money != null) MONTH_ROW_MONEY else MONTH_ROW
        val height by animateDpAsState(row * rows, label = "month-height")
        Column(modifier) {
            MonthGrid(
                months, selected, today, rowHeight = row, summaryOf, onSelect, Modifier.fillMaxWidth(), pagerHeight = height,
                money = money, moneyOf = moneyOf, onMonths = onMonths,
            )
            list(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Тренировка для сводки дня: цвет места, оттенок типа, отмена и начало. */
internal fun CalendarTraining.monthItem() = MonthItem(color, shade, cancelled, ClubTime.minuteOfDay(start))

/** Дни недели и листание месяцев; [rowHeight] `null` — строки делят высоту поровну. */
@Composable
private fun MonthGrid(
    months: PagerState,
    selected: LocalDate,
    today: LocalDate,
    rowHeight: Dp?,
    summaryOf: (LocalDate) -> MonthDay,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier,
    pagerHeight: Dp? = null,
    money: Map<YearMonth, CalendarMoney>? = null,
    moneyOf: ((LocalDate) -> DayMoney?)? = null,
    onMonths: () -> Unit = {},
) {
    Column(modifier) {
        if (money != null) {
            val shown = MonthRules.firstOf(months.currentPage)
            MonthMoneyLine(money[shown.yearMonth], onMonths)
        }
        WeekdayRow(Modifier.padding(horizontal = Dimens.space2))
        HorizontalPager(
            state = months,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (pagerHeight != null) Modifier.height(pagerHeight) else Modifier.weight(1f))
                .background(PersonalkaTheme.colors.surface)
                .testTag(MONTH_PAGER_TAG),
            verticalAlignment = Alignment.Top,
        ) { page ->
            MonthPage(MonthRules.firstOf(page), selected, today, rowHeight, summaryOf, onSelect, moneyOf)
        }
    }
}

/** «пн … вс» над сеткой месяца и мини-календаря. */
@Composable
private fun WeekdayRow(modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Row(modifier.fillMaxWidth().height(WEEKDAY_ROW), verticalAlignment = Alignment.CenterVertically) {
        DateFormat.WEEK.forEach { day ->
            Text(
                text = DateFormat.weekdayShort(day),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthPage(
    first: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    rowHeight: Dp?,
    summaryOf: (LocalDate) -> MonthDay,
    onSelect: (LocalDate) -> Unit,
    moneyOf: ((LocalDate) -> DayMoney?)? = null,
) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.space2)) {
        MonthRules.weeks(first).forEach { monday ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier.weight(1f))
                    // Линия над каждой строкой — цвет разделителей (макет «Месяц»).
                    .drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
            ) {
                repeat(7) { i ->
                    val date = monday.plus(i, DateTimeUnit.DAY)
                    MonthCell(
                        date = date,
                        inMonth = date.month == first.month,
                        isSelected = date == selected,
                        isToday = date == today,
                        summary = summaryOf(date),
                        // Сумма — только у дней этого месяца: соседний месяц в крайних строках без денег.
                        money = if (moneyOf != null && date.month == first.month) moneyOf(date) else null,
                        moneyOn = moneyOf != null,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * Клетка дня: число (сегодня — в оранжевом кружке, день соседнего месяца — приглушён), под ним число тренировок
 * без отменённых и точки. Выбранный день — на плашке surface-alt. TalkBack читает «вт 20.10, 10 тренировок».
 */
@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    summary: MonthDay,
    onClick: () -> Unit,
    modifier: Modifier,
    money: DayMoney? = null,
    moneyOn: Boolean = false,
) {
    val colors = PersonalkaTheme.colors
    Column(
        modifier = modifier
            .padding(start = 2.dp, end = 2.dp, top = 3.dp, bottom = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            // С деньгами фон — по сумме дня (шаг 5.10г), выбранный день — рамкой; без денег — плашка, как раньше.
            .background(
                when {
                    moneyOn -> colors.dayFill(money)
                    isSelected -> colors.surfaceAlt
                    else -> Color.Transparent
                },
            )
            .then(if (moneyOn && isSelected) Modifier.border(2.dp, colors.text, MaterialTheme.shapes.medium) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            // Метка — левее: clearAndSetSemantics стирает и то, что стоит правее него в цепочке.
            .testTag(monthDayTag(date))
            .clearAndSetSemantics {
                selected = isSelected
                role = Role.Tab
                contentDescription = CalendarFormat.monthDay(date, summary.count) + CalendarMoneyFormat.cellDescription(money)
                monthMoney = money
                monthSummary = summary
            }
            .padding(top = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(if (isToday) colors.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.labelLarge.tabular(),
                fontWeight = if (inMonth || isToday) FontWeight.Medium else FontWeight.Normal,
                // С деньгами главное в клетке — сумма: число дня приглушено (шаг 5.10з).
                color = when {
                    isToday -> colors.onAccent
                    inMonth && !moneyOn -> colors.text
                    else -> colors.textMuted
                },
            )
        }
        if (moneyOn) {
            Text(
                text = money?.let(CalendarMoneyFormat::cell).orEmpty(),
                modifier = Modifier.height(16.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp).tabular(),
                fontWeight = money?.weight() ?: FontWeight.Normal,
                color = money?.let { colors.moneyText(it) } ?: colors.textMuted,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Text(
                text = if (summary.count > 0) summary.count.toString() else "",
                modifier = Modifier.height(16.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp).tabular(),
                color = colors.textMuted,
            )
        }
        // С деньгами точек типов нет (шаг 5.10з): строка месяца ниже, под сеткой видно больше тренировок.
        if (!moneyOn) {
            Row(Modifier.padding(top = Dimens.space1), horizontalArrangement = Arrangement.spacedBy(DOT_GAP)) {
                summary.dots.forEach { dot -> ShadeDot(PlaceColor.of(dot.color).shade(colors.isDark, dot.shade), size = DOT) }
            }
        }
    }
}

/**
 * Список выбранного дня под месяцем (макет «Месяц»): заголовок «Вторник, 20 октября» и «10 тренировок», строки
 * тренировок и дежурств. Нажатие на заголовок — этот день в «Дне».
 */
@Composable
private fun DayList(
    date: LocalDate,
    entries: List<DayEntry>,
    count: Int,
    onDay: (LocalDate) -> Unit,
    onOpen: (CalendarTraining) -> Unit,
    onZone: (CalendarZone) -> Unit,
    modifier: Modifier,
    money: String? = null,
) {
    LazyColumn(modifier.testTag(MONTH_DAY_LIST_TAG), contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
        item(key = agendaTitleKey(date)) { DayTitle(date, count, onDay, money) }
        if (entries.isEmpty()) {
            item(key = "none-$date") {
                Text(
                    text = CalendarFormat.NO_TRAININGS,
                    modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PersonalkaTheme.colors.textMuted,
                )
            }
        }
        dayRows(entries, onOpen, onZone)
    }
}

/**
 * Мини-календарь по нажатию на месяц в шапке (ТЗ 5.3): месяц со стрелками и свайпом, сегодня — в оранжевом
 * кружке, выбранный день — на плашке; нажатие на день открывает его в текущем виде.
 */
@Composable
internal fun MiniCalendar(selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val colors = PersonalkaTheme.colors
    val pager = rememberPagerState(initialPage = MonthRules.page(selected)) { MonthRules.PAGE_COUNT }
    val scope = rememberCoroutineScope()
    Column(Modifier.width(MINI_CELL * 7 + Dimens.space4).padding(horizontal = Dimens.space2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                Icon(PIcons.ChevronRight, contentDescription = CalendarFormat.PREVIOUS_MONTH, tint = colors.text, modifier = Modifier.rotate(180f))
            }
            Text(
                text = DateFormat.monthYear(MonthRules.firstOf(pager.currentPage)),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(MonthRules.PAGE_COUNT - 1)) } }) {
                Icon(PIcons.ChevronRight, contentDescription = CalendarFormat.NEXT_MONTH, tint = colors.text)
            }
        }
        WeekdayRow()
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(MINI_CELL * MINI_ROWS), verticalAlignment = Alignment.Top) { page ->
            val first = MonthRules.firstOf(page)
            val weeks = MonthRules.weeks(first)
            Column {
                (0 until MINI_ROWS).forEach { row ->
                    val monday = weeks.first().plus(row * 7, DateTimeUnit.DAY)
                    Row(Modifier.fillMaxWidth().height(MINI_CELL)) {
                        repeat(7) { i ->
                            val date = monday.plus(i, DateTimeUnit.DAY)
                            MiniDay(date, inMonth = date.month == first.month, isSelected = date == selected, isToday = date == today, onPick = onPick, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniDay(date: LocalDate, inMonth: Boolean, isSelected: Boolean, isToday: Boolean, onPick: (LocalDate) -> Unit, modifier: Modifier) {
    val colors = PersonalkaTheme.colors
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Button) { onPick(date) }
            .semantics(mergeDescendants = true) {
                selected = isSelected
                contentDescription = DateFormat.short(date)
            }
            .testTag(miniDayTag(date)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isToday -> colors.accent
                        isSelected -> colors.surfaceAlt
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = when {
                    isToday -> colors.onAccent
                    inMonth -> colors.text
                    else -> colors.textMuted
                },
            )
        }
    }
}
