package com.akenory.personalka.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarPlace
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.data.calendar.MoveResult
import com.akenory.personalka.data.calendar.WorkHours
import com.akenory.personalka.data.training.EditScope
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.calendar.CalendarDrag
import com.akenory.personalka.domain.calendar.CalendarGrid
import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.calendar.CalendarViews
import com.akenory.personalka.domain.calendar.DaySpan
import com.akenory.personalka.domain.calendar.MoveProblem
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.TypePresets
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.clients.MenuAction
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.training.ConflictPanel
import com.akenory.personalka.ui.training.ScopePanel
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.Motion
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Нажатие на тренировку примера: её нет в базе — ни формы, ни карточки. */
internal const val SAMPLE_READ_ONLY = "Это пример дня — его не изменить"

internal const val TODAY_LABEL = "Сегодня"

/** Кнопка окна запрета (ТЗ 5.3). «Изменить» появится с формой запрета в шаге 1.13а. */
internal const val SKIP_DAY = "Пропустить этот день"

/** Меню по длительному нажатию на тренировку (ТЗ 5.3, макет «Удержание на тренировке»). */
internal const val NEW_AT_THIS_TIME = "Новая тренировка на это время"
internal const val CANCEL_TRAINING = "Отменить"
internal const val COPY_TRAINING = "Копировать"

/** Что «Отменить» в меню отвечает до шага 1.14, где появится отмена из карточки (5.6). */
internal const val CANCEL_LATER = "Отмена тренировки — в шаге 1.14"

/** После «Копировать»: вставить её предлагает форма новой тренировки (5.5). */
internal const val COPIED = "Тренировка скопирована"

/** Проведённую или с отметками «пришёл» не перенести туда, где её ещё нельзя было бы отметить. */
internal const val MARKED_STAYS = "Проведённую тренировку нельзя перенести в будущее"

/** День в строке недели. */
fun weekDayTag(date: LocalDate) = "week-$date"

private const val ID_SEPARATOR = "\n"

/**
 * «Календарь» (ТЗ 5.3): шапка с месяцем, «Сегодня», видом и фильтром мест; вид «День» со строкой недели пн–вс
 * или «Несколько дней» (3, 5, 7) со строкой дней над колонками; сетка с дежурствами и запретами под тренировками,
 * жесты — перенос, меню, быстрое создание. Месяц, список и мини-календарь — шаг 1.12а.
 *
 * Вид запоминается на этом телефоне отдельно для сложенного и разложенного Fold ([DevicePrefs]); по умолчанию
 * на сложенном — «День», на разложенном — «Несколько дней» по настройке тренера. Фильтр мест скрывает
 * тренировки, дежурства и запреты других мест; показ отменённых включается в меню вида.
 *
 * Выбранный день, положение сетки, фильтр мест, открытый список «+N», открытая зона, меню тренировки, вопрос
 * для серии и окно «Поставить всё равно?» хранятся выше всего, что зависит от ширины экрана: сложение и разложение
 * Fold их не сбрасывают. «День» и «Несколько дней» — одна и та же сетка с одним листанием: первая колонка — выбранный день.
 *
 * @param sample показать «пример дня» на сегодня — с дежурством и запретом, пока их не создать формой (до 1.13а).
 * @param scope записи из окон: «Пропустить этот день» и его «Отменить» доживают до конца, даже если экран закрыли.
 * @param onOpen нажатие на тренировку.
 * @param onCreate новая тренировка на это время: удержание на пустом месте и «Новая тренировка на это время».
 * @param onSample пример дня в меню вида.
 */
@Composable
fun CalendarScreen(
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    now: LocalDateTime,
    sample: Boolean,
    messages: MessageState,
    scope: CoroutineScope,
    onOpen: (CalendarTraining) -> Unit,
    onCreate: (LocalDateTime) -> Unit,
    onSample: (Boolean) -> Unit,
) {
    val today = now.date
    val wide = LocalWide.current
    val ui = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = pageOf(today)) { PAGE_COUNT }
    val grid = rememberSaveable(saver = DayGridState.Saver) {
        DayGridState(CalendarGrid.openTop(now.time), prefs.float(DevicePrefs.CALENDAR_HOUR) ?: CalendarGrid.HOUR_DEFAULT_DP)
    }
    var moreIds by rememberSaveable { mutableStateOf<String?>(null) }
    var zoneKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var askKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Пропущенные зоны и перенесённые тренировки примера — только в памяти, как и сам пример.
    var sampleSkipped by rememberSaveable { mutableStateOf("") }
    var sampleMoves by rememberSaveable { mutableStateOf("") }

    val day = dateOfPage(pager.currentPage)
    val work by remember(calendar) { calendar.workHours }.collectAsState(initial = WorkHours())
    val step by remember(calendar) { calendar.dragStep }.collectAsState(initial = CalendarDrag.STEP_DEFAULT)
    // Пунктирный блок новой тренировки — такой длины, какую подставит форма: у типа, который в ней первый.
    val newDuration by remember(calendar) {
        calendar.forms.form().map { it.types.firstOrNull()?.defaultDurationMin ?: TypePresets.DEFAULT_DURATION }
    }.collectAsState(initial = TypePresets.DEFAULT_DURATION)

    // Вид — свой у сложенного и разложенного экрана, на этом телефоне; выбор пишется сразу.
    val weekViewDays by remember(calendar) { calendar.weekViewDays }.collectAsState(initial = CalendarViews.DAY_COUNTS.first())
    var viewChanges by remember { mutableIntStateOf(0) }
    val view = remember(wide, weekViewDays, viewChanges) {
        CalendarViews.parse(prefs.string(viewKey(wide)), wide) ?: CalendarViews.default(wide, weekViewDays)
    }
    fun select(next: CalendarView) {
        prefs.putString(viewKey(wide), next.code)
        viewChanges++
    }
    var showCancelled by remember { mutableStateOf(prefs.boolean(DevicePrefs.CALENDAR_SHOW_CANCELLED) ?: true) }
    val places by remember(calendar) { calendar.places }.collectAsState(initial = emptyList())
    var placeId by rememberSaveable { mutableStateOf<String?>(null) }
    // Место убрали в архив — фильтр снова «Все места».
    val place = places.firstOrNull { it.id == placeId }

    // Тренировки трёх недель вокруг выбранной: соседние дни при листании уже на месте. Пока грузится новое окно,
    // видно старое — дни не мигают пустыми.
    val weekStart = DateFormat.weekStart(day)
    var loaded by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarTraining>>()) }
    var loadedZones by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarZone>>()) }
    LaunchedEffect(calendar, weekStart) {
        calendar.trainings(weekStart.minus(7, DateTimeUnit.DAY), weekStart.plus(14, DateTimeUnit.DAY)).collect { list ->
            loaded = list.groupBy { it.start.date }
        }
    }
    LaunchedEffect(calendar, weekStart) {
        calendar.zones(weekStart.minus(7, DateTimeUnit.DAY), weekStart.plus(14, DateTimeUnit.DAY)).collect { list ->
            loadedZones = list.groupBy { it.date }
        }
    }
    val restrictionSkipped = CalendarSample.RESTRICTION_ID in sampleSkipped.split(ID_SEPARATOR)
    val moves = remember(sampleMoves) { decodeMoves(sampleMoves) }
    val sampleDay = remember(sample, today, moves, restrictionSkipped) {
        if (sample) CalendarSample.day(today, moves, restrictionSkipped) else emptyList()
    }
    val sampleZones = remember(sample, today, sampleSkipped) {
        if (sample) CalendarSample.zones(today).filter { it.id !in sampleSkipped.split(ID_SEPARATOR) } else emptyList()
    }
    val itemsOf: (LocalDate) -> List<CalendarTraining> = { date ->
        val stored = loaded[date].orEmpty()
        val shown = sampleDay.filter { it.start.date == date }
        (if (shown.isEmpty()) stored else stored + shown).filter { inPlace(place, it.placeId, it.placeShort) && (showCancelled || !it.cancelled) }
    }

    // Перенос (ТЗ 5.3, 4.4, 4.5): у тренировки серии — «Только эту / Эту и следующие», под запретом того же места —
    // окно «Поставить всё равно?», потом сообщение с «Отменить».
    fun moveStored(id: String, start: LocalDateTime, confirmed: Boolean, answer: EditScope? = null) {
        scope.launch {
            when (val result = calendar.move(id, start, confirmed, answer)) {
                is MoveResult.Moved -> messages.showUndo(CalendarFormat.moved(start)) { scope.launch { calendar.undoMove(result.undo) } }
                is MoveResult.AskScope -> askKey = encodeAsk(id, start, result.date, result.question)
                is MoveResult.Confirm -> {
                    pendingKey = encodePending(id, start, answer, result.conflicts)
                }
                is MoveResult.Refused -> {
                    if (result.problem == MoveProblem.MARKED_IN_FUTURE) messages.show(MARKED_STAYS)
                }
            }
        }
    }
    fun moveSample(training: CalendarTraining, start: LocalDateTime, confirmed: Boolean) {
        val status = if (training.cancelled) TrainingStatus.CANCELLED else TrainingStatus.PLANNED
        when (CalendarDrag.problem(status, training.marked, start, now)) {
            MoveProblem.MARKED_IN_FUTURE -> messages.show(MARKED_STAYS)
            MoveProblem.CANCELLED -> Unit
            null -> {
                val conflicts = if (confirmed) emptyList() else CalendarSample.conflicts(training, start, today, restrictionSkipped)
                if (conflicts.isNotEmpty()) {
                    pendingKey = encodePending(training.id, start, null, conflicts)
                } else {
                    val before = moves
                    sampleMoves = encodeMoves(moves + (training.id to start))
                    messages.showUndo(CalendarFormat.moved(start)) { sampleMoves = encodeMoves(before) }
                }
            }
        }
    }
    fun move(training: CalendarTraining, start: LocalDateTime, confirmed: Boolean) {
        if (CalendarSample.isSample(training)) moveSample(training, start, confirmed) else moveStored(training.id, start, confirmed)
    }
    val zonesOf: (LocalDate) -> List<CalendarZone> = { date ->
        val stored = loadedZones[date].orEmpty()
        (if (date == today && sampleZones.isNotEmpty()) stored + sampleZones else stored).filter { inPlace(place, it.placeId, it.placeShort) }
    }

    // Часы сетки — по дням, на которых листание остановилось: во время свайпа подписи часов не прыгают.
    // Дежурство — тоже тренировка и расширяет сетку; запрет — нет: «весь день» не должен открывать ночь.
    val settledDays = (0 until view.columns).map { dateOfPage(pager.settledPage + it) }
    val duties = settledDays.flatMap { date -> zonesOf(date).filter { it.kind == ZoneKind.DUTY }.map { DaySpan(it.id, it.start, it.end) } }
    val hours = CalendarGrid.hours(work.start, work.end, settledDays.flatMap { date -> itemsOf(date).map { it.span() } } + duties)
    val shownDays = (0 until view.columns).map { dateOfPage(pager.currentPage + it) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CalendarHeader(
                day = day,
                view = view,
                views = CalendarViews.options(wide),
                showCancelled = showCancelled,
                sample = sample,
                places = places,
                place = place,
                wide = wide,
                onToday = {
                    grid.showNow(now.time)
                    ui.launch { pager.animateScrollToPage(pageOf(today)) }
                },
                onView = ::select,
                onShowCancelled = { show ->
                    showCancelled = show
                    prefs.putBoolean(DevicePrefs.CALENDAR_SHOW_CANCELLED, show)
                },
                onSample = onSample,
                onPlace = { placeId = it?.id },
            )
            // Свайп по строке дней — неделя вперёд или назад, тот же день недели.
            val onWeek: (Int) -> Unit = { days ->
                ui.launch { pager.animateScrollToPage((pager.currentPage + days).coerceIn(0, PAGE_COUNT - 1)) }
            }
            if (view == CalendarView.Day) {
                WeekStrip(day = day, today = today, onSelect = { date -> ui.launch { pager.animateScrollToPage(pageOf(date)) } }, onWeek = onWeek)
            } else {
                // Нажатие на день над колонкой — этот день в виде «День».
                DaysHeader(
                    pager = pager,
                    columns = view.columns,
                    today = today,
                    wide = wide,
                    onOpen = { date ->
                        select(CalendarView.Day)
                        ui.launch { pager.scrollToPage(pageOf(date)) }
                    },
                    onWeek = onWeek,
                )
            }
            DayGrid(
                pager = pager,
                grid = grid,
                hours = hours,
                now = now,
                step = step,
                columns = view.columns,
                newDuration = newDuration,
                itemsOf = itemsOf,
                zonesOf = zonesOf,
                menuId = menuId,
                menu = { training ->
                    TrainingMenu(
                        training = training,
                        onDismiss = { menuId = null },
                        onNew = {
                            menuId = null
                            onCreate(training.start)
                        },
                        onCancel = {
                            menuId = null
                            messages.show(CANCEL_LATER)
                        },
                        onCopy = {
                            menuId = null
                            if (CalendarSample.isSample(training)) {
                                messages.show(COPIED)
                            } else {
                                scope.launch { if (calendar.copy(training.id)) messages.show(COPIED) }
                            }
                        },
                    )
                },
                onOpen = onOpen,
                onMore = { hidden -> moreIds = hidden.joinToString(ID_SEPARATOR) { it.id } },
                onZone = { zone -> zoneKey = zoneKeyOf(zone) },
                onCreate = onCreate,
                onMenu = { training -> menuId = training.id },
                onMove = { training, start -> move(training, start, confirmed = false) },
                onZoomEnd = { prefs.putFloat(DevicePrefs.CALENDAR_HOUR, grid.hourDp) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        val hidden = moreIds?.split(ID_SEPARATOR)?.let { ids -> shownDays.flatMap(itemsOf).filter { it.id in ids } }.orEmpty()
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

        val zone = zoneKey?.let { key -> zonesOf(zoneDateOf(key)).firstOrNull { zoneKeyOf(it) == key } }
        if (zone != null) {
            ZonePanel(
                zone = zone,
                // Пропустить можно сегодняшний и будущий день запрета: прошлый уже ни на что не влияет.
                canSkip = zone.kind == ZoneKind.RESTRICTION && zone.date >= today,
                onDismiss = { zoneKey = null },
                onSkip = {
                    zoneKey = null
                    val text = ZoneFormat.skipped(zone.date)
                    if (CalendarSample.isSample(zone)) {
                        sampleSkipped += ID_SEPARATOR + zone.id
                        messages.showUndo(text) { sampleSkipped = sampleSkipped.replace(ID_SEPARATOR + zone.id, "") }
                    } else {
                        scope.launch { calendar.skipRestrictionDay(zone.id, zone.date) }
                        messages.showUndo(text) { scope.launch { calendar.unskipRestrictionDay(zone.id, zone.date) } }
                    }
                },
            )
        }

        val ask = askKey?.let(::decodeAsk)
        if (ask != null) {
            ScopePanel(
                title = TrainingFormat.SCOPE_MOVE_TITLE,
                question = ask.question,
                date = ask.date,
                back = ZoneFormat.CONFLICT_CANCEL,
                onOnlyThis = {
                    askKey = null
                    moveStored(ask.id, ask.start, confirmed = false, answer = EditScope.ONLY_THIS)
                },
                onFollowing = {
                    askKey = null
                    moveStored(ask.id, ask.start, confirmed = false, answer = EditScope.FOLLOWING)
                },
                onDismiss = { askKey = null },
            )
        }

        val pending = pendingKey?.let(::decodePending)
        if (pending != null) {
            ConflictPanel(
                conflicts = pending.conflicts,
                onDismiss = { pendingKey = null },
                onPlace = {
                    pendingKey = null
                    val training = sampleDay.firstOrNull { it.id == pending.id }
                    if (training != null) {
                        moveSample(training, pending.start, confirmed = true)
                    } else {
                        moveStored(pending.id, pending.start, confirmed = true, answer = pending.answer)
                    }
                },
            )
        }
    }
}

/** Перенос, который ждёт ответа «Поставить всё равно?»: тренировка, новое начало, ответ для серии и запреты под ним. */
private class PendingMove(val id: String, val start: LocalDateTime, val answer: EditScope?, val conflicts: List<String>)

private fun encodePending(id: String, start: LocalDateTime, answer: EditScope?, conflicts: List<String>): String =
    (listOf(id, start.toString(), answer?.name.orEmpty()) + conflicts).joinToString(ID_SEPARATOR)

private fun decodePending(text: String): PendingMove {
    val lines = text.split(ID_SEPARATOR)
    return PendingMove(lines[0], LocalDateTime.parse(lines[1]), lines[2].takeIf { it.isNotEmpty() }?.let(EditScope::valueOf), lines.drop(3))
}

/** Перенос тренировки серии, который ждёт ответа «Только эту / Эту и следующие». */
private class AskMove(val id: String, val start: LocalDateTime, val date: LocalDate, val question: String)

private fun encodeAsk(id: String, start: LocalDateTime, date: LocalDate, question: String): String =
    listOf(id, start.toString(), date.toString(), question).joinToString(ID_SEPARATOR)

private fun decodeAsk(text: String): AskMove {
    val lines = text.split(ID_SEPARATOR)
    return AskMove(lines[0], LocalDateTime.parse(lines[1]), LocalDate.parse(lines[2]), lines.drop(3).joinToString(ID_SEPARATOR))
}

/** Перенесённые тренировки примера: «id=2026-10-20T12:15» построчно. */
private fun encodeMoves(moves: Map<String, LocalDateTime>): String =
    moves.entries.joinToString(ID_SEPARATOR) { "${it.key}=${it.value}" }

private fun decodeMoves(text: String): Map<String, LocalDateTime> =
    text.split(ID_SEPARATOR).filter { it.isNotBlank() }.associate { it.substringBefore('=') to LocalDateTime.parse(it.substringAfter('=')) }

/** Открытая зона: вид, id и день — переживает поворот и сложение, а у повторяющегося запрета id одинаков во все дни. */
private fun zoneKeyOf(zone: CalendarZone): String = "${zone.kind}/${zone.id}/${zone.date}"

/** День открытой зоны: в «Нескольких днях» она может быть в любой колонке. */
private fun zoneDateOf(key: String): LocalDate = LocalDate.parse(key.substringAfterLast('/'))

/** Где хранится вид: у сложенного и разложенного экрана — свой. */
private fun viewKey(wide: Boolean): String = if (wide) DevicePrefs.CALENDAR_VIEW_WIDE else DevicePrefs.CALENDAR_VIEW_FOLDED

/**
 * Тренировка или зона в месте, выбранном фильтром: `null` — все места. Пример дня живёт со своими id мест,
 * поэтому сверяется его короткое имя.
 */
private fun inPlace(place: CalendarPlace?, placeId: String, placeShort: String): Boolean =
    place == null || placeId == place.id || (CalendarSample.isSamplePlace(placeId) && placeShort == place.shortName)

/**
 * Строка недели пн–вс под шапкой (ТЗ 5.3): нажатие открывает день, сегодняшнее число — в оранжевом кружке.
 * Свайп по строке — неделя вперёд или назад ([onWeek]); новая неделя въезжает с той стороны, куда листают.
 */
@Composable
private fun WeekStrip(day: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit, onWeek: (Int) -> Unit) {
    AnimatedContent(
        targetState = DateFormat.weekStart(day),
        modifier = Modifier.fillMaxWidth().clipToBounds().weekSwipe(onWeek).testTag(DAYS_ROW_TAG),
        transitionSpec = {
            val forward = targetState > initialState
            val slide = tween<IntOffset>(Motion.MEDIUM)
            (slideInHorizontally(slide) { if (forward) it else -it } togetherWith slideOutHorizontally(slide) { if (forward) -it else it })
        },
        label = "week",
    ) { monday ->
        WeekRow(monday, day, today, onSelect)
    }
}

@Composable
private fun WeekRow(monday: LocalDate, day: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val colors = PersonalkaTheme.colors
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

/**
 * Окно зоны (ТЗ 5.3, макет «Нажатие на запрет»): «ДБ 17:00–18:00 — групповая тренировка», как повторяется
 * и «Пропустить этот день». «Изменить» — с формой запрета и дежурства (1.13): выключенное не показывается (10.7).
 */
@Composable
private fun ZonePanel(zone: CalendarZone, canSkip: Boolean, onDismiss: () -> Unit, onSkip: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(title = zone.title, onDismiss = onDismiss) {
        Text(text = zone.text, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text)
        zone.repeat?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted) }
        if (canSkip) SecondaryButton(SKIP_DAY, onSkip, Modifier.fillMaxWidth(), icon = PIcons.EventBusy)
    }
}

/**
 * Меню по длительному нажатию на тренировку (ТЗ 5.3, макет «Удержание на тренировке»): «Новая тренировка на это
 * время», «Отменить», «Копировать». У отменённой «Отменить» нет — она уже отменена.
 */
@Composable
private fun TrainingMenu(
    training: CalendarTraining,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, containerColor = PersonalkaTheme.colors.surface) {
        MenuAction(NEW_AT_THIS_TIME, PIcons.Add, onNew)
        if (!training.cancelled) MenuAction(CANCEL_TRAINING, PIcons.EventBusy, onCancel)
        MenuAction(COPY_TRAINING, PIcons.ContentCopy, onCopy)
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
