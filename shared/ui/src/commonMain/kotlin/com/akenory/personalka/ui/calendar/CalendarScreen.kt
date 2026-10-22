package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.WorkHours
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.calendar.CalendarGrid
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Что «Календарь» отвечает на нажатие на тренировку до шага 1.14, где появится её карточка. */
internal const val TRAINING_CARD_LATER = "Карточка тренировки — в шаге 1.14"

internal const val TODAY_LABEL = "Сегодня"

/** День в строке недели. */
fun weekDayTag(date: LocalDate) = "week-$date"

private const val ID_SEPARATOR = "\n"

/**
 * «Календарь», вид «День» (ТЗ 5.3): шапка с месяцем и «Сегодня», строка недели пн–вс, сетка дня. Несколько дней,
 * месяц, список и фильтр мест — шаг 1.12, запреты и дежурства — 1.10, перенос и меню — 1.11.
 *
 * Выбранный день, положение сетки и открытый список «+N» хранятся выше всего, что зависит от ширины экрана:
 * сложение и разложение Fold их не сбрасывают.
 *
 * @param sample показать «пример дня» на сегодня — до шага 1.13, где тренировки можно будет создавать.
 * @param onOpen нажатие на тренировку.
 */
@Composable
fun CalendarScreen(
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    now: LocalDateTime,
    sample: Boolean,
    onOpen: (CalendarTraining) -> Unit,
) {
    val today = now.date
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = pageOf(today)) { PAGE_COUNT }
    val grid = rememberSaveable(saver = DayGridState.Saver) {
        DayGridState(CalendarGrid.openTop(now.time), prefs.float(DevicePrefs.CALENDAR_HOUR) ?: CalendarGrid.HOUR_DEFAULT_DP)
    }
    var moreIds by rememberSaveable { mutableStateOf<String?>(null) }

    val day = dateOfPage(pager.currentPage)
    val work by remember(calendar) { calendar.workHours }.collectAsState(initial = WorkHours())

    // Тренировки трёх недель вокруг выбранной: соседние дни при листании уже на месте. Пока грузится новое окно,
    // видно старое — дни не мигают пустыми.
    val weekStart = DateFormat.weekStart(day)
    var loaded by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarTraining>>()) }
    LaunchedEffect(calendar, weekStart) {
        calendar.trainings(weekStart.minus(7, DateTimeUnit.DAY), weekStart.plus(14, DateTimeUnit.DAY)).collect { list ->
            loaded = list.groupBy { it.start.date }
        }
    }
    val sampleDay = remember(sample, today) { if (sample) CalendarSample.day(today) else emptyList() }
    val itemsOf: (LocalDate) -> List<CalendarTraining> = { date ->
        val stored = loaded[date].orEmpty()
        if (date == today && sampleDay.isNotEmpty()) stored + sampleDay else stored
    }

    // Часы сетки — по дню, на котором листание остановилось: во время свайпа подписи часов не прыгают.
    val settled = itemsOf(dateOfPage(pager.settledPage))
    val hours = CalendarGrid.hours(work.start, work.end, settled.map { it.span() })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CalendarHeader(
                day = day,
                onToday = {
                    grid.showNow(now.time)
                    scope.launch { pager.animateScrollToPage(pageOf(today)) }
                },
            )
            WeekStrip(day = day, today = today, onSelect = { date -> scope.launch { pager.animateScrollToPage(pageOf(date)) } })
            DayGrid(
                pager = pager,
                grid = grid,
                hours = hours,
                now = now,
                itemsOf = itemsOf,
                onOpen = onOpen,
                onMore = { hidden -> moreIds = hidden.joinToString(ID_SEPARATOR) { it.id } },
                onZoomEnd = { prefs.putFloat(DevicePrefs.CALENDAR_HOUR, grid.hourDp) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        val hidden = moreIds?.split(ID_SEPARATOR)?.let { ids -> itemsOf(day).filter { it.id in ids } }.orEmpty()
        if (hidden.isNotEmpty()) {
            MorePanel(
                hidden = hidden,
                onDismiss = { moreIds = null },
                onOpen = {
                    moreIds = null
                    onOpen(it)
                },
            )
        }
    }
}

/** Шапка: месяц и год выбранного дня и «Сегодня». Мини-календарь, вид и фильтр мест — шаг 1.12. */
@Composable
private fun CalendarHeader(day: LocalDate, onToday: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space4, end = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DateFormat.monthYear(day),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            maxLines = 1,
        )
        IconButton(onClick = onToday) {
            Icon(PIcons.Today, contentDescription = TODAY_LABEL, tint = colors.text)
        }
    }
}

/** Строка недели пн–вс под шапкой (ТЗ 5.3): нажатие открывает день, сегодняшнее число — в оранжевом кружке. */
@Composable
private fun WeekStrip(day: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val colors = PersonalkaTheme.colors
    val monday = DateFormat.weekStart(day)
    Row(Modifier.fillMaxWidth().height(Dimens.row).padding(horizontal = Dimens.space2)) {
        repeat(7) { i ->
            val date = monday.plus(i, DateTimeUnit.DAY)
            val isSelected = date == day
            val isToday = date == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = Dimens.space1, horizontal = 2.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (isSelected) colors.surfaceAlt else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(date) }
                    .semantics(mergeDescendants = true) {
                        selected = isSelected
                        contentDescription = DateFormat.short(date)
                    }
                    .testTag(weekDayTag(date)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(DateFormat.weekdayShort(date.dayOfWeek), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isToday) colors.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = date.day.toString(),
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = if (isToday) colors.onAccent else colors.text,
                    )
                }
            }
        }
    }
}

/** Список под меткой «+N»: скрытые наложения, нажатие — как на блок. */
@Composable
private fun MorePanel(hidden: List<CalendarTraining>, onDismiss: () -> Unit, onOpen: (CalendarTraining) -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(title = CalendarFormat.moreTitle(hidden.size), onDismiss = onDismiss) {
        hidden.forEach { training ->
            val decoration = if (training.cancelled) TextDecoration.LineThrough else null
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touch)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button) { onOpen(training) }
                    .padding(vertical = Dimens.space1),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = training.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    textDecoration = decoration,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = CalendarFormat.timePlace(training.start, TrainingTime.end(training.start, training.durationMin), training.placeShort),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.textMuted,
                    textDecoration = decoration,
                )
            }
        }
    }
}
