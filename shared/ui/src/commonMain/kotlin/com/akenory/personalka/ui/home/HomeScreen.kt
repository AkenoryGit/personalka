package com.akenory.personalka.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.home.HomeData
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.home.BackupFacts
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.HomeTraining
import com.akenory.personalka.domain.home.TodoItem
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.home.Upcoming
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.ui.calendar.COPIED
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.TrainingCardHost
import com.akenory.personalka.ui.training.TrainingCardKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

internal const val NEXT_TAG = "home-next"
internal const val MARK_TAG = "home-mark"
internal const val MORE_TODAY_TAG = "home-more-today"
internal const val LATER_TAG = "home-later"
internal const val TODO_TAG = "home-todo"
internal const val PAST_TAG = "home-past"
internal fun todoTag(kind: TodoKind) = "todo-${kind.name}"
internal fun dayRowTag(id: String) = "home-day-$id"

/**
 * Что знает «Главная»: тренировки из базы ([data], `null` — ещё грузятся) и пункты «Нужно сделать». Считается
 * в приложении, а не в экране: число пунктов — это и счётчик дел на значке «Главной» (5.2).
 */
@Immutable
class HomeView(val data: HomeData?, val todo: List<TodoItem>)

/** «Главная» по «сейчас»: данные зависят только от дня, правила пересчитываются каждую минуту. */
@Composable
fun rememberHomeView(calendar: CalendarRepository, backup: BackupRepository?, now: LocalDateTime): HomeView {
    val data by remember(calendar, now.date) { calendar.home.data(now.date) }.collectAsState(initial = null)
    val facts = if (backup == null) {
        null
    } else {
        val state by backup.state.collectAsState()
        BackupFacts(folderChosen = state.folder != null, last = state.last)
    }
    return remember(data, facts, now) {
        HomeView(data, data?.let { HomeRules.todo(it.trainings, now, it.settings, facts) }.orEmpty())
    }
}

/**
 * «Главная» (ТЗ 5.2, макеты шага 0.6): «Ближайшая тренировка» и «Нужно сделать»; «Деньги» — шаг 3.9. На разложенном
 * Fold слева — расписание дня с прошедшими, свёрнутыми в строку, справа — те же блоки.
 *
 * Нажатие на тренировку открывает её карточку (5.4) прямо здесь, поверх «Главной», как в календаре. Прокрутка блоков
 * и раскрытые прошедшие хранятся выше ветки по ширине: сложение Fold их не сбрасывает.
 *
 * @param card открытая карточка ([TrainingCardKey]); хранится в приложении: «Назад» закрывает сначала её.
 * @param onDay день в календаре: «Ещё сегодня» и строка завтрашней.
 * @param onTodo нажатие на пункт «Нужно сделать»; пункт с тренировкой открывает её карточку здесь же.
 */
@Composable
fun HomeScreen(
    view: HomeView,
    calendar: CalendarRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    card: String?,
    onCard: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onClient: (String) -> Unit,
    onGroup: (String) -> Unit,
    onDay: (LocalDate) -> Unit,
    onTodo: (TodoItem) -> Unit,
) {
    val wide = LocalWide.current
    val scroll = rememberScrollState()
    val dayScroll = rememberScrollState()
    var showPast by rememberSaveable { mutableStateOf(false) }
    val trainings = view.data?.trainings.orEmpty()
    val upcoming = remember(trainings, now) { HomeRules.upcoming(trainings, now) }
    val open: (String) -> Unit = { id -> onCard(TrainingCardKey.card(id)) }
    val mark: (HomeTraining) -> Unit = { t ->
        scope.launch {
            calendar.cards.markDone(t.id)?.let { result ->
                messages.showUndo(result.message) { scope.launch { calendar.cards.undo(result.undo) } }
            }
        }
    }
    val todo: (TodoItem) -> Unit = { item -> item.trainingId?.let(open) ?: onTodo(item) }

    TrainingCardHost(
        key = card,
        wide = wide,
        cards = calendar.cards,
        now = now,
        messages = messages,
        scope = scope,
        onClose = { onCard(null) },
        onEdit = onEdit,
        onClient = onClient,
        onGroup = onGroup,
        onCopy = { id -> scope.launch { if (calendar.copy(id)) messages.show(COPIED) } },
    ) {
        if (view.data == null) return@TrainingCardHost
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                DayColumn(
                    trainings = trainings,
                    upcoming = upcoming,
                    now = now,
                    scroll = dayScroll,
                    showPast = showPast,
                    onShowPast = { showPast = it },
                    onOpen = open,
                    modifier = Modifier.width(DAY_WIDTH).fillMaxHeight(),
                )
                VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
                Blocks(upcoming, view.todo, now, scroll, open, mark, onDay, todo, Modifier.weight(1f).fillMaxHeight().padding(top = Dimens.space4))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(DateFormat.header(now.date))
                Blocks(upcoming, view.todo, now, scroll, open, mark, onDay, todo, Modifier.weight(1f))
            }
        }
    }
}

private val DAY_WIDTH = 320.dp

/** Под последним блоком — место для «+»: он не ложится на строки. */
private val FAB_SPACE = 88.dp

@Composable
private fun Blocks(
    upcoming: Upcoming,
    todo: List<TodoItem>,
    now: LocalDateTime,
    scroll: ScrollState,
    onOpen: (String) -> Unit,
    onMark: (HomeTraining) -> Unit,
    onDay: (LocalDate) -> Unit,
    onTodo: (TodoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(scroll).padding(horizontal = Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        NextBlock(upcoming, now, onOpen, onMark, onDay)
        if (todo.isNotEmpty()) TodoBlock(todo, onTodo)
        Spacer(Modifier.height(FAB_SPACE))
    }
}

/** Блок на surface со скруглением 16, как на макетах «Главная». */
@Composable
private fun HomeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PersonalkaTheme.colors.surface),
    ) { content() }
}

/**
 * «Ближайшая тренировка» (5.2): шапка «Ближайшая · 16:00–16:50» и «через 12 мин», полоса цвета места, имя,
 * «Тип · место · каждый вт и чт», план. С начала минус 15 минут — «Проведено» (у группы «Все пришли»); «Дневник» —
 * с дневником (шаг 2.1). Ниже — «Ещё сегодня» или первая тренировка следующего дня; сегодня всё — «Сегодня всё.
 * Завтра первая в 07:00» и строка той тренировки.
 */
@Composable
private fun NextBlock(
    upcoming: Upcoming,
    now: LocalDateTime,
    onOpen: (String) -> Unit,
    onMark: (HomeTraining) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val next = upcoming.next
    val later = upcoming.later
    HomeCard(Modifier.testTag(NEXT_TAG)) {
        if (next != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(next.id) }
                    .padding(Dimens.space4),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        HomeFormat.nextHeader(next),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = colors.textMuted,
                    )
                    Text(HomeFormat.startsIn(next.start, now), style = MaterialTheme.typography.labelMedium.tabular(), color = colors.textMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(PlaceColor.of(next.color).tone(colors.isDark).stripe))
                    Spacer(Modifier.width(Dimens.space3))
                    Column(Modifier.weight(1f)) {
                        Text(next.title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            TrainingCardFormat.typeLine(next.typeShort, next.placeShort, next.weekdays),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
                }
                next.plan?.let { plan ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.textMuted)) { append(TrainingCardFormat.PLAN + " ") }
                            append(plan)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (HomeRules.canMark(next, now)) {
                PrimaryButton(
                    text = TrainingCardFormat.action(if (next.state.kind == TrainingKind.GROUP) CardAction.ALL_CAME else CardAction.DONE),
                    onClick = { onMark(next) },
                    modifier = Modifier.fillMaxWidth().padding(start = Dimens.space4, end = Dimens.space4, bottom = Dimens.space4).testTag(MARK_TAG),
                )
            }
            val last = upcoming.lastToday
            when {
                upcoming.moreToday > 0 && last != null -> {
                    RowDivider(start = 0.dp)
                    LinkRow(HomeFormat.moreToday(upcoming.moreToday, last), MORE_TODAY_TAG) { onDay(now.date) }
                }
                later != null -> {
                    RowDivider(start = 0.dp)
                    LinkRow(HomeFormat.later(later, now.date), LATER_TAG) { onDay(later.start.date) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Text(HomeFormat.NEXT, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                Text(HomeFormat.todayDone(later, now.date), style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
            if (later != null) {
                RowDivider(start = 0.dp)
                LinkRow(HomeFormat.laterRow(later), LATER_TAG) { onDay(later.start.date) }
            }
        }
    }
}

/** Строка внизу блока со стрелкой: открывает день в календаре. */
@Composable
private fun LinkRow(text: String, tag: String, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}

/**
 * «Нужно сделать» (5.2): заголовок со счётчиком дел — оранжевым, как на значке «Главной»; строка пункта — значок
 * в круге, текст, подпись, число справа и стрелка.
 */
@Composable
private fun TodoBlock(items: List<TodoItem>, onTodo: (TodoItem) -> Unit) {
    val colors = PersonalkaTheme.colors
    HomeCard(Modifier.testTag(TODO_TAG)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(HomeFormat.TODO, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Spacer(Modifier.width(Dimens.space2))
            Text(
                text = items.size.toString(),
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .widthIn(min = 24.dp)
                    .background(colors.accent, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = colors.onAccent,
                textAlign = TextAlign.Center,
            )
        }
        items.forEachIndexed { index, item ->
            if (index > 0) RowDivider(start = 72.dp)
            TodoRow(item) { onTodo(item) }
        }
        Spacer(Modifier.height(Dimens.space1))
    }
}

@Composable
private fun TodoRow(item: TodoItem, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(todoTag(item.kind))
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(todoIcon(item.kind), contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Dimens.space4))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.note, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item.count?.let {
            Spacer(Modifier.width(Dimens.space2))
            Text(it.toString(), style = MaterialTheme.typography.labelLarge.tabular(), color = colors.text)
        }
        Spacer(Modifier.width(Dimens.space2))
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}

private fun todoIcon(kind: TodoKind): ImageVector = when (kind) {
    TodoKind.UNMARKED -> PIcons.Groups
    TodoKind.BACKUP_FOLDER -> PIcons.FolderOpen
    TodoKind.BACKUP_OLD -> PIcons.Schedule
}

/**
 * Расписание дня на разложенном Fold (5.2): прошедшие свёрнуты в «Показать прошедшие · 7», дальше оставшиеся —
 * время, полоса цвета места, имя и «Тип · место»; ближайшая выделена и подписана «через 12 мин», идущая — «идёт»,
 * отменённая зачёркнута. Внизу — «Завтра: 9 тренировок, первая в 07:00».
 */
@Composable
private fun DayColumn(
    trainings: List<HomeTraining>,
    upcoming: Upcoming,
    now: LocalDateTime,
    scroll: ScrollState,
    showPast: Boolean,
    onShowPast: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    val today = now.date
    val tomorrow = today.plus(1, DateTimeUnit.DAY)
    val visible = trainings.filter { it.state.kind != TrainingKind.DUTY }.sortedWith(compareBy({ it.start }, { it.id }))
    val day = visible.filter { it.start.date == today }
    val (past, rest) = day.partition { it.end <= now }
    val tomorrowList = visible.filter { it.start.date == tomorrow && it.state.status != TrainingStatus.CANCELLED }
    Column(modifier.verticalScroll(scroll)) {
        TopBar(DateFormat.header(today))
        if (past.isNotEmpty()) {
            Text(
                if (showPast) HomeFormat.HIDE_PAST else HomeFormat.showPast(past.size),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touch)
                    .clickable(role = Role.Button) { onShowPast(!showPast) }
                    .testTag(PAST_TAG)
                    .padding(horizontal = Dimens.space4, vertical = Dimens.space3),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.textMuted,
            )
        }
        (if (showPast) day else rest).forEach { t ->
            DayRow(t, now, next = t.id == upcoming.next?.id, onClick = { onOpen(t.id) })
        }
        Text(
            HomeFormat.tomorrow(tomorrowList.size, tomorrowList.firstOrNull()?.start),
            Modifier.padding(Dimens.space4),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.textMuted,
        )
        Spacer(Modifier.height(FAB_SPACE))
    }
}

@Composable
private fun DayRow(training: HomeTraining, now: LocalDateTime, next: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val cancelled = training.state.status == TrainingStatus.CANCELLED
    val going = !cancelled && training.start <= now && now < training.end
    val decoration = if (cancelled) TextDecoration.LineThrough else null
    val main = if (cancelled) colors.textMuted else colors.text
    val line = TrainingCardFormat.typeLine(training.typeShort, training.placeShort, null) +
        if (next && !going) " · " + HomeFormat.startsIn(training.start, now) else ""
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space2)
            .clip(MaterialTheme.shapes.medium)
            .background(if (next) colors.surface else Color.Transparent)
            .heightIn(min = Dimens.row)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(dayRowTag(training.id))
            .padding(horizontal = Dimens.space2, vertical = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Text(DateFormat.time(training.start.time), Modifier.width(48.dp), style = MaterialTheme.typography.bodyLarge.tabular(), color = main, textDecoration = decoration)
        Box(
            Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp))
                .background(if (cancelled) colors.outline else PlaceColor.of(training.color).tone(colors.isDark).stripe),
        )
        Column(Modifier.weight(1f)) {
            Text(training.title, style = MaterialTheme.typography.labelLarge, color = main, textDecoration = decoration, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (going) {
            Text(
                HomeFormat.ONGOING,
                Modifier.clearAndSetSemantics { contentDescription = HomeFormat.ONGOING },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }
    }
}
