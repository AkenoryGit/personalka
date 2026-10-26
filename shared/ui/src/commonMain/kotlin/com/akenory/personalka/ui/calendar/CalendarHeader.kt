package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarPlace
import com.akenory.personalka.domain.calendar.CalendarDrag
import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.SlotFormat
import com.akenory.personalka.ui.clients.MenuChoice
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** Кнопка вида, кнопка мест и месяц с мини-календарём в шапке календаря. */
const val CALENDAR_VIEW_TAG = "calendar-view"
const val CALENDAR_PLACE_TAG = "calendar-place"
const val CALENDAR_MONTH_TAG = "calendar-month"
const val CALENDAR_SLOTS_TAG = "calendar-slots"

/** Пункт меню вида: показ отменённых (ТЗ 5.3) — на этом телефоне, по умолчанию включён. */
internal const val SHOW_CANCELLED = "Показывать отменённые"

/** День в строке над колонками «Нескольких дней». */
fun dayHeaderTag(date: LocalDate) = "day-header-$date"

/** Строка дней над сеткой: пн–вс в «Дне», «вт 20» над колонками «Нескольких дней». */
const val DAYS_ROW_TAG = "calendar-days-row"

/**
 * Свайп по строке дней листает неделями (ТЗ 5.3): влево — тот же день недели неделей позже, вправо — раньше.
 * [onDays] получает +7 или −7. Короткое движение — не свайп: нажатие на день в строке работает как раньше.
 */
internal fun Modifier.weekSwipe(onDays: (Int) -> Unit): Modifier = pointerInput(Unit) {
    var dx = 0f
    detectHorizontalDragGestures(
        onDragStart = { dx = 0f },
        onDragEnd = { CalendarDrag.weekSwipe(dx / density).takeIf { it != 0 }?.let(onDays) },
        onDragCancel = { dx = 0f },
        onHorizontalDrag = { change, amount ->
            change.consume()
            dx += amount
        },
    )
}

/** Мест больше — на разложенном вместо чипов кнопка со списком, как на сложенном: ряд чипов не помещается. */
private const val PLACE_CHIPS_MAX = 4

/**
 * Шапка календаря (ТЗ 5.3, макеты «3 дня» и «Месяц»): месяц и год с «▾» — мини-календарь, «Сегодня», переключатель
 * вида и фильтр мест. На сложенном фильтр — кнопка «Все места ▾» после вида, на разложенном — чипы «Все / ББ / ДБ /
 * Зал» перед «Сегодня». Мест одно — фильтра нет (10.7).
 *
 * @param month какой месяц и год написать: на сетке — месяц выбранного дня, в «Месяце» — листаемый месяц.
 * @param day выбранный день: мини-календарь открывается на его месяце и отмечает его.
 * @param views виды этого состояния Fold; в меню вида под ними — «Показывать отменённые».
 * @param place выбранное место; `null` — все.
 * @param onPick день из мини-календаря.
 */
@Composable
internal fun CalendarHeader(
    month: LocalDate,
    day: LocalDate,
    today: LocalDate,
    view: CalendarView,
    views: List<CalendarView>,
    showCancelled: Boolean,
    places: List<CalendarPlace>,
    place: CalendarPlace?,
    wide: Boolean,
    onToday: () -> Unit,
    onView: (CalendarView) -> Unit,
    onShowCancelled: (Boolean) -> Unit,
    onPlace: (CalendarPlace?) -> Unit,
    onPick: (LocalDate) -> Unit,
    onSlots: (() -> Unit)? = null,
) {
    val colors = PersonalkaTheme.colors
    val filter = places.size > 1
    val chips = filter && wide && places.size <= PLACE_CHIPS_MAX
    // Мини-календарь открыт — переживает сложение Fold, как меню тренировки.
    var mini by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space2, end = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        // Месяц и год с «▾» (макет «Месяц»): нажатие — мини-календарь под ним.
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .heightIn(min = Dimens.touch)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.DropdownList) { mini = true }
                    .semantics(mergeDescendants = true) { heading() }
                    .testTag(CALENDAR_MONTH_TAG)
                    .padding(start = Dimens.space2, end = Dimens.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DateFormat.monthYear(month),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(PIcons.ArrowDropDown, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = mini, onDismissRequest = { mini = false }, containerColor = colors.surface) {
                MiniCalendar(selected = day, today = today) { date ->
                    mini = false
                    onPick(date)
                }
            }
        }
        if (chips) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                ChoiceChip(CalendarFormat.ALL, place == null, onClick = { onPlace(null) })
                places.forEach { p -> ChoiceChip(p.shortName, place?.id == p.id, onClick = { onPlace(p) }) }
            }
        }
        IconButton(onClick = onToday) {
            Icon(PIcons.Today, contentDescription = TODAY_LABEL, tint = colors.text)
        }
        HeaderMenu(CalendarFormat.view(view), CALENDAR_VIEW_TAG) { close ->
            views.forEach { option ->
                MenuChoice(CalendarFormat.view(option), option == view) {
                    close()
                    onView(option)
                }
            }
            HorizontalDivider(color = colors.divider)
            MenuToggle(SHOW_CANCELLED, showCancelled) {
                close()
                onShowCancelled(!showCancelled)
            }
            // «Свободные слоты» (5.13): поиск окон — из меню календаря.
            if (onSlots != null) {
                HorizontalDivider(color = colors.divider)
                DropdownMenuItem(
                    text = { Text(SlotFormat.TITLE, style = MaterialTheme.typography.bodyLarge, color = colors.text) },
                    onClick = {
                        close()
                        onSlots()
                    },
                    modifier = Modifier.testTag(CALENDAR_SLOTS_TAG),
                    leadingIcon = { Icon(PIcons.EventAvailable, contentDescription = null, tint = colors.text) },
                )
            }
        }
        if (filter && !chips) {
            HeaderMenu(place?.shortName ?: CalendarFormat.ALL_PLACES, CALENDAR_PLACE_TAG) { close ->
                MenuChoice(CalendarFormat.ALL_PLACES, place == null) {
                    close()
                    onPlace(null)
                }
                places.forEach { p ->
                    MenuChoice(p.name, place?.id == p.id) {
                        close()
                        onPlace(p)
                    }
                }
            }
        }
    }
}

/** Кнопка шапки с выпадающим списком (макеты «3 дня»): плашка 36 dp цвета surface-alt, зона нажатия 48. */
@Composable
private fun HeaderMenu(text: String, tag: String, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val colors = PersonalkaTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(Modifier.heightIn(min = Dimens.touch), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(Dimens.chip)
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceAlt)
                .clickable(role = Role.DropdownList) { open = true }
                .testTag(tag)
                .padding(start = Dimens.space3, end = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1)
            Icon(PIcons.ArrowDropDown, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            items { open = false }
        }
    }
}

/** Пункт меню-переключатель: флажок справа. */
@Composable
private fun MenuToggle(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.text) },
        onClick = onClick,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
        trailingIcon = { CheckBox(checked) },
    )
}

/**
 * Дни над колонками «Нескольких дней» (макеты «3 дня»): «вт» и число, сегодняшнее — в оранжевом кружке; на
 * сложенном число под днём недели, на разложенном — рядом. Строка едет вместе с листанием: подписи ставятся
 * при раскладке по положению листания, поэтому свайп ничего не пересобирает. Нажатие открывает день.
 */
@Composable
internal fun DaysHeader(
    pager: PagerState,
    columns: Int,
    today: LocalDate,
    wide: Boolean,
    onOpen: (LocalDate) -> Unit,
    onWeek: (Int) -> Unit,
) {
    // Подписи — с запасом в ширину экрана с каждой стороны: бросок листает до [columns] дней за раз.
    val first = pager.currentPage
    val pages = (first - columns) until (first + 2 * columns)
    Row(Modifier.fillMaxWidth().height(Dimens.topBar).weekSwipe(onWeek).testTag(DAYS_ROW_TAG)) {
        Spacer(Modifier.width(Dimens.timeColumn))
        Layout(
            content = { pages.forEach { page -> key(page) { DayLabel(dateOfPage(page), today, wide, onOpen) } } },
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
        ) { measurables, constraints ->
            val column = constraints.maxWidth / columns.toFloat()
            val labels = measurables.map { it.measure(Constraints.fixed(column.roundToInt(), constraints.maxHeight)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val shift = pager.currentPage + pager.currentPageOffsetFraction
                labels.forEachIndexed { i, label -> label.place(((pages.first + i - shift) * column).roundToInt(), 0) }
            }
        }
    }
}

@Composable
private fun DayLabel(date: LocalDate, today: LocalDate, wide: Boolean, onOpen: (LocalDate) -> Unit) {
    val colors = PersonalkaTheme.colors
    val isToday = date == today
    val modifier = Modifier
        .fillMaxSize()
        .clickable(role = Role.Button) { onOpen(date) }
        .semantics(mergeDescendants = true) { contentDescription = DateFormat.short(date) }
        .testTag(dayHeaderTag(date))
    val label = @Composable {
        Text(DateFormat.weekdayShort(date.dayOfWeek), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(if (isToday) colors.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = if (isToday) colors.onAccent else colors.text,
            )
        }
    }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.space2, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            label()
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { label() }
    }
}
