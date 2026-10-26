package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.calendar.MonthRules
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.datetime.LocalDate

/** Строки вида «Список»; ключ заголовка дня — [agendaTitleKey]. */
const val AGENDA_TAG = "calendar-agenda"

/** Строка тренировки или дежурства в «Месяце» и «Списке» — по id тренировки (дежурство — тоже тренировка). */
fun calendarRowTag(id: String) = "calendar-row-$id"

/** Ключ заголовка дня в списке: прокрутить к нему в тесте — `performScrollToKey`. */
fun agendaTitleKey(date: LocalDate) = "title-$date"

/** Заголовок дня в «Месяце» и «Списке»: нажатие — этот день в «Дне». */
fun dayTitleTag(date: LocalDate) = "day-title-$date"

/** Отступ снизу у списков: «+» не закрывает последнюю строку (56 dp и по 16 dp над и под ним). */
internal val FAB_CLEARANCE = 88.dp

/** Строка списка (макет «Месяц»): 52 dp, время в колонке 44 dp, полоса 3 × 36 dp. */
private val ROW_HEIGHT = 52.dp
private val TIME_WIDTH = 44.dp
private val STRIPE_WIDTH = 3.dp
private val STRIPE_HEIGHT = 36.dp
private val BADGE_SIZE = 20.dp
private val BADGE_DOT = 8.dp

/** «Список» на разложенном — по центру, не шире этого: строке на 760 dp глазу далеко до значков. */
private val AGENDA_MAX_WIDTH = 640.dp

/** Сколько строк до конца списка, когда пора подгрузить следующие недели. */
private const val LOAD_AHEAD_ROWS = 5

/** Строка дня в «Месяце» и «Списке»: тренировка или дежурство; [start] — минута суток, [length] — минуты. */
internal sealed interface DayEntry {
    val key: String
    val start: Int
    val length: Int

    data class Training(val training: CalendarTraining) : DayEntry {
        override val key: String get() = "t-${training.id}"
        override val start: Int get() = ClubTime.minuteOfDay(training.start)
        override val length: Int get() = training.durationMin
    }

    data class Duty(val zone: CalendarZone) : DayEntry {
        override val key: String get() = "d-${zone.id}-${zone.date}"
        override val start: Int get() = zone.start
        override val length: Int get() = zone.end - zone.start
    }
}

/**
 * Строки дня по времени: тренировки и дежурства (запреты в списке не показываются — они видны на сетке часов).
 * На одно время длинная — выше, как левее в сетке: отмена на 50 минут — над грудничковым на 30, дежурство — над
 * тренировками, которые на нём стоят.
 */
internal fun dayEntries(trainings: List<CalendarTraining>, zones: List<CalendarZone>): List<DayEntry> =
    (trainings.map { DayEntry.Training(it) } + zones.filter { it.kind == ZoneKind.DUTY }.map { DayEntry.Duty(it) })
        .sortedWith(compareBy<DayEntry> { it.start }.thenByDescending { it.length })

/** Строки дня в списке: нажатие на тренировку — как на блок, на дежурство — как на его заливку. */
internal fun LazyListScope.dayRows(entries: List<DayEntry>, onOpen: (CalendarTraining) -> Unit, onZone: (CalendarZone) -> Unit) {
    items(entries, key = { it.key }) { entry ->
        when (entry) {
            is DayEntry.Training -> TrainingRow(entry.training) { onOpen(entry.training) }
            is DayEntry.Duty -> DutyRow(entry.zone) { onZone(entry.zone) }
        }
    }
}

/**
 * Заголовок дня (макет «Месяц»): «Вторник, 20 октября» и справа «10 тренировок» — без отменённых, как число
 * в клетке месяца. Нажатие — этот день в виде «День», как день над колонками «Нескольких дней».
 */
@Composable
internal fun DayTitle(date: LocalDate, count: Int, onDay: (LocalDate) -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.touch)
            .clickable(role = Role.Button) { onDay(date) }
            .semantics(mergeDescendants = true) { heading() }
            .testTag(dayTitleTag(date))
            .padding(horizontal = Dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DateFormat.header(date),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) Text(CalendarFormat.trainings(count), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
    }
}

/**
 * Тренировка (макет «Месяц»): начало, полоса цвета места, имя и «Тип · место», справа все значки по важности.
 * Отменённая — серая полоса, время и имя зачёркнуты; со списанием — ещё «· со списанием» и «₽».
 */
@Composable
private fun TrainingRow(training: CalendarTraining, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val cancelled = training.cancelled
    EntryRow(
        time = DateFormat.time(training.start.time),
        stripe = if (cancelled) colors.outline else PlaceColor.of(training.color).tone(colors.isDark).stripe,
        title = training.name,
        line = CalendarFormat.listLine(training.typeName, training.placeShort, training.durationMin, charged = cancelled && CalendarBadge.CHARGED in training.badges, who = training.who?.full),
        crossed = cancelled,
        badges = training.badges,
        tag = calendarRowTag(training.id),
        onClick = onClick,
    )
}

/** Дежурство (макет «Месяц»): «Дежурство» и «ББ · до 15:00 · за Александра», полоса цвета его места. */
@Composable
private fun DutyRow(zone: CalendarZone, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    EntryRow(
        time = DateFormat.minuteOfDay(zone.start),
        stripe = PlaceColor.of(zone.color).tone(colors.isDark).stripe,
        title = zone.title,
        line = CalendarFormat.dutyLine(zone.placeShort, zone.end, zone.coverFor),
        crossed = false,
        badges = emptyList(),
        tag = calendarRowTag(zone.id),
        onClick = onClick,
    )
}

/**
 * Строка тренировки или дежурства: время, полоса, имя и строка под ним, значки справа. Ею же рисуется расписание дня
 * на «Главной» (5.2): там справа ещё [status] («идёт»), а ближайшая выделена фоном ([highlighted]).
 */
@Composable
internal fun EntryRow(
    time: String,
    stripe: Color,
    title: String,
    line: String,
    crossed: Boolean,
    badges: List<CalendarBadge>,
    tag: String,
    onClick: () -> Unit,
    status: String? = null,
    highlighted: Boolean = false,
) {
    val colors = PersonalkaTheme.colors
    val decoration = if (crossed) TextDecoration.LineThrough else null
    val main = if (crossed) colors.textMuted else colors.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .then(if (highlighted) Modifier.clip(MaterialTheme.shapes.medium).background(colors.surface) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(tag)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Text(time, Modifier.width(TIME_WIDTH), style = MaterialTheme.typography.bodyLarge.tabular(), color = main, textDecoration = decoration)
        Box(Modifier.width(STRIPE_WIDTH).height(STRIPE_HEIGHT).clip(RoundedCornerShape(2.dp)).background(stripe))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = main, textDecoration = decoration, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        if (badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                badges.forEach { RowBadge(it) }
            }
        }
    }
}

/** Значок строки — те же, что на блоке, но все и крупнее: в строке для них есть место. */
@Composable
private fun RowBadge(badge: CalendarBadge) {
    val colors = PersonalkaTheme.colors
    val label = CalendarFormat.badge(badge)
    if (badge == CalendarBadge.NO_DIARY) {
        Box(Modifier.size(BADGE_DOT).clip(CircleShape).background(colors.accentGraphic).semantics { contentDescription = label })
        return
    }
    val (icon, tint) = when (badge) {
        CalendarBadge.CHARGED -> PIcons.BadgeRuble to colors.charged
        CalendarBadge.OVER_RESTRICTION -> PIcons.BadgeWarning to colors.over
        CalendarBadge.OVER_DUTY -> PIcons.BadgeLayers to colors.textMuted
        else -> PIcons.BadgeCheck to colors.done
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(BADGE_SIZE))
}

/**
 * Что показывает «Список»: дни с [from] по [to] не включая — номера дней от 1970 г., как страницы листания. Открывается
 * с выбранного дня на восемь недель вперёд; дошли до конца — ещё четыре недели, потянули вниз у самого начала —
 * четыре недели раньше. [moved] — тренер листал сам: тогда первый видимый день становится выбранным.
 */
@Stable
internal class AgendaState(from: Int, to: Int = from + AHEAD_DAYS) {
    var from by mutableIntStateOf(from)
        private set
    var to by mutableIntStateOf(to)
        private set
    var moved by mutableStateOf(false)

    /** Открыть список с [date]: «Сегодня», мини-календарь, переход из другого вида. */
    fun jump(date: LocalDate) {
        from = pageOf(date)
        to = from + AHEAD_DAYS
        moved = false
    }

    fun later() {
        to = (to + STEP_DAYS).coerceAtMost(PAGE_COUNT)
    }

    fun earlier() {
        from = (from - STEP_DAYS).coerceAtLeast(0)
    }

    companion object {
        const val AHEAD_DAYS = 56
        const val STEP_DAYS = 28

        val Saver: Saver<AgendaState, Any> = Saver(
            save = { arrayListOf(it.from, it.to, if (it.moved) 1 else 0) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val list = saved as List<Int>
                AgendaState(list[0], list[1]).apply { moved = list[2] == 1 }
            },
        )
    }
}

/**
 * «Список» (ТЗ 5.3, шаг 1.12а): тренировки и дежурства по дням подряд — строки как в списке под месяцем.
 * Дни без тренировок не показываются (10.7). Первый видимый день — выбранный: с него откроются «День», «Несколько
 * дней» и «Месяц», его месяц — в шапке.
 *
 * @param ready данные загружены для всех дней [state]: только тогда пустой список — «Тренировок нет», а не загрузка.
 * @param onTop первый видимый день, когда тренер листал сам.
 */
@Composable
internal fun AgendaView(
    state: AgendaState,
    list: LazyListState,
    ready: Boolean,
    itemsOf: (LocalDate) -> List<CalendarTraining>,
    zonesOf: (LocalDate) -> List<CalendarZone>,
    onTop: (LocalDate) -> Unit,
    onDay: (LocalDate) -> Unit,
    onOpen: (CalendarTraining) -> Unit,
    onZone: (CalendarZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = (state.from until state.to).mapNotNull { page ->
        val date = dateOfPage(page)
        val trainings = itemsOf(date)
        val entries = dayEntries(trainings, zonesOf(date))
        if (entries.isEmpty()) null else AgendaDay(date, entries, MonthRules.day(trainings.map { it.monthItem() }).count)
    }
    // День каждой строки списка, заголовки тоже: по первой видимой строке — первый видимый день.
    val dates by rememberUpdatedState(days.flatMap { day -> List(day.entries.size + 1) { day.date } })
    val top by rememberUpdatedState(onTop)

    LaunchedEffect(list, state) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) state.moved = true }
    }
    LaunchedEffect(list, state) {
        snapshotFlow { if (state.moved) dates.getOrNull(list.firstVisibleItemIndex) else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { top(it) }
    }
    // Конец близко — ещё четыре недели. Срабатывает, когда конец только показался: пока новых строк нет, не повторяется.
    LaunchedEffect(list, state) {
        snapshotFlow {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_AHEAD_ROWS
        }.distinctUntilChanged().filter { it }.collect { state.later() }
    }
    // Тянут вниз у самого начала — четыре недели раньше; строки, что были на экране, остаются на месте (по ключам).
    LaunchedEffect(list, state) {
        snapshotFlow { state.moved && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }
            .distinctUntilChanged()
            .filter { it }
            .collect { state.earlier() }
    }

    Box(modifier) {
        if (days.isEmpty()) {
            if (ready) {
                Text(
                    text = CalendarFormat.NO_TRAININGS,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PersonalkaTheme.colors.textMuted,
                )
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = AGENDA_MAX_WIDTH).fillMaxSize().testTag(AGENDA_TAG),
                contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
            ) {
                days.forEach { day ->
                    item(key = agendaTitleKey(day.date)) { DayTitle(day.date, day.count, onDay) }
                    dayRows(day.entries, onOpen, onZone)
                }
            }
        }
    }
}

/** День «Списка»: строки и число тренировок без отменённых. */
private class AgendaDay(val date: LocalDate, val entries: List<DayEntry>, val count: Int)
