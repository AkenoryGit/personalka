package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.ui.clients.MenuChoice
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** Кнопка вида и кнопка мест в шапке календаря. */
const val CALENDAR_VIEW_TAG = "calendar-view"
const val CALENDAR_PLACE_TAG = "calendar-place"

/** Пункт меню вида: показ отменённых (ТЗ 5.3) — на этом телефоне, по умолчанию включён. */
internal const val SHOW_CANCELLED = "Показывать отменённые"

/** День в строке над колонками «Нескольких дней». */
fun dayHeaderTag(date: LocalDate) = "day-header-$date"

/** Мест больше — на разложенном вместо чипов кнопка со списком, как на сложенном: ряд чипов не помещается. */
private const val PLACE_CHIPS_MAX = 4

/**
 * Шапка календаря (ТЗ 5.3, макеты «3 дня»): месяц и год, «Сегодня», переключатель вида и фильтр мест.
 * На сложенном фильтр — кнопка «Все места ▾» после вида, на разложенном — чипы «Все / ББ / ДБ / Зал» перед
 * «Сегодня». Мест одно — фильтра нет (10.7). Мини-календарь по нажатию на месяц — шаг 1.12а.
 *
 * @param views виды этого состояния Fold; в меню вида под ними — «Показывать отменённые».
 * @param place выбранное место; `null` — все.
 */
@Composable
internal fun CalendarHeader(
    day: LocalDate,
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
) {
    val colors = PersonalkaTheme.colors
    val filter = places.size > 1
    val chips = filter && wide && places.size <= PLACE_CHIPS_MAX
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = Dimens.space4, end = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        Text(
            text = DateFormat.monthYear(day),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
internal fun DaysHeader(pager: PagerState, columns: Int, today: LocalDate, wide: Boolean, onOpen: (LocalDate) -> Unit) {
    // Подписи — с запасом в ширину экрана с каждой стороны: бросок листает до [columns] дней за раз.
    val first = pager.currentPage
    val pages = (first - columns) until (first + 2 * columns)
    Row(Modifier.fillMaxWidth().height(Dimens.topBar)) {
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
