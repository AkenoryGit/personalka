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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
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
import com.akenory.personalka.domain.calendar.MonthRules
import com.akenory.personalka.domain.calendar.MoveProblem
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.reference.TypePresets
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.clients.MenuAction
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PrimaryButton
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

internal const val TODAY_LABEL = "Сегодня"

/** Кнопки окна зоны (ТЗ 5.3, макет «Нажатие на запрет»). */
internal const val EDIT_ZONE = "Изменить"
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
 * «Календарь» (ТЗ 5.3): шапка с месяцем и мини-календарём, «Сегодня», видом и фильтром мест; вид «День» со строкой
 * недели пн–вс или «Несколько дней» (3, 5, 7) со строкой дней над колонками — сетка с дежурствами и запретами под
 * тренировками, жесты — перенос, меню, быстрое создание; «Месяц» — сетка дней со списком выбранного дня; «Список»
 * — тренировки по дням подряд.
 *
 * Вид запоминается на этом телефоне отдельно для сложенного и разложенного Fold ([DevicePrefs]); по умолчанию
 * на сложенном — «День», на разложенном — «Несколько дней» по настройке тренера. Фильтр мест скрывает
 * тренировки, дежурства и запреты других мест; показ отменённых включается в меню вида.
 *
 * Выбранный день — один на все виды (шаг 1.12а): на сетке это первая колонка листания, в «Месяце» — выбранная
 * клетка, в «Списке» — первый видимый день. Пока открыт «Месяц» или «Список», листание сетки и страница месяца
 * стоят на выбранном дне, поэтому другой вид открывается сразу на нём, без кадра со старым днём.
 *
 * Выбранный день, положение сетки, страница месяца, прокрутка списка, фильтр мест, открытый список «+N», открытая
 * зона, меню тренировки, вопрос для серии и окно «Поставить всё равно?» хранятся выше всего, что зависит от ширины
 * экрана: сложение и разложение Fold их не сбрасывают.
 *
 * @param scope записи из окон: «Пропустить этот день» и его «Отменить» доживают до конца, даже если экран закрыли.
 * @param onOpen нажатие на тренировку.
 * @param onCreate новая тренировка на это время: удержание на пустом месте и «Новая тренировка на это время».
 * @param onEditZone «Изменить» в окне зоны: форма дежурства или своего запрета (шаг 1.13а).
 */
@Composable
fun CalendarScreen(
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onOpen: (CalendarTraining) -> Unit,
    onCreate: (LocalDateTime) -> Unit,
    onEditZone: (CalendarZone) -> Unit,
) {
    val today = now.date
    val wide = LocalWide.current
    val ui = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = pageOf(today)) { PAGE_COUNT }
    val months = rememberPagerState(initialPage = MonthRules.page(today)) { MonthRules.PAGE_COUNT }
    val agenda = rememberSaveable(saver = AgendaState.Saver) { AgendaState(pageOf(today)) }
    val agendaList = rememberLazyListState()
    // Выбранный день «Месяца» и «Списка» — номер дня от 1970 г., как страница листания; на сетке он идёт за листанием.
    var pickedPage by rememberSaveable { mutableIntStateOf(pageOf(today)) }
    val picked = dateOfPage(pickedPage)
    val grid = rememberSaveable(saver = DayGridState.Saver) {
        DayGridState(CalendarGrid.openTop(now.time), prefs.float(DevicePrefs.CALENDAR_HOUR) ?: CalendarGrid.HOUR_DEFAULT_DP)
    }
    var moreIds by rememberSaveable { mutableStateOf<String?>(null) }
    var zoneKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var askKey by rememberSaveable { mutableStateOf<String?>(null) }

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
    // Выбранный день: на сетке — первая колонка листания, в «Месяце» и «Списке» — свой.
    val day = if (view.grid) dateOfPage(pager.currentPage) else picked

    // На сетке выбранный день идёт за листанием — с него откроются «Месяц» и «Список».
    LaunchedEffect(view.grid) {
        if (!view.grid) return@LaunchedEffect
        if (pager.currentPage != pickedPage) pager.requestScrollToPage(pickedPage)
        snapshotFlow { pager.currentPage }.collect { pickedPage = it }
    }
    // В «Месяце» и «Списке» сетка ждёт на выбранном дне, страница месяца — на его месяце: другой вид откроется на нём же.
    LaunchedEffect(view, pickedPage) {
        if (!view.grid && pager.currentPage != pickedPage) pager.requestScrollToPage(pickedPage)
        val month = MonthRules.page(dateOfPage(pickedPage))
        if (view != CalendarView.Month && months.currentPage != month) months.requestScrollToPage(month)
    }
    // «Месяц» перелистнули — выбран тот же день в новом месяце, а в месяце сегодняшнего дня — сегодня.
    LaunchedEffect(view) {
        if (view != CalendarView.Month) return@LaunchedEffect
        snapshotFlow { months.settledPage }.collect { page ->
            val current = dateOfPage(pickedPage)
            if (MonthRules.page(current) != page) pickedPage = pageOf(MonthRules.sameDay(current, page, today))
        }
    }
    // «Список» открывается с выбранного дня.
    val isAgenda = view == CalendarView.Agenda
    LaunchedEffect(isAgenda) {
        if (!isAgenda) return@LaunchedEffect
        agenda.jump(dateOfPage(pickedPage))
        agendaList.requestScrollToItem(0)
    }

    /**
     * Открыть [date] в текущем виде: «Сегодня» и мини-календарь. На сетке листание едет к нему, в «Месяце» — страница
     * месяца, «Список» начинается с него.
     */
    fun go(date: LocalDate, animate: Boolean) {
        val page = pageOf(date)
        when {
            view.grid -> ui.launch { if (animate) pager.animateScrollToPage(page) else pager.scrollToPage(page) }
            view == CalendarView.Month -> {
                pickedPage = page
                ui.launch { if (animate) months.animateScrollToPage(MonthRules.page(date)) else months.scrollToPage(MonthRules.page(date)) }
            }
            else -> {
                pickedPage = page
                agenda.jump(date)
                agendaList.requestScrollToItem(0)
            }
        }
    }

    /** Нажатие на заголовок дня в «Месяце» и «Списке»: этот день в виде «День». */
    fun openDay(date: LocalDate) {
        pickedPage = pageOf(date)
        pager.requestScrollToPage(pickedPage)
        select(CalendarView.Day)
    }
    var showCancelled by remember { mutableStateOf(prefs.boolean(DevicePrefs.CALENDAR_SHOW_CANCELLED) ?: true) }
    val places by remember(calendar) { calendar.places }.collectAsState(initial = emptyList())
    var placeId by rememberSaveable { mutableStateOf<String?>(null) }
    // Место убрали в архив — фильтр снова «Все места».
    val place = places.firstOrNull { it.id == placeId }

    // Что загружено — по виду. Сетка: три недели вокруг выбранного дня — соседние дни при листании уже на месте.
    // «Месяц»: страница и по месяцу с каждой стороны — при свайпе соседний месяц уже с числами и точками.
    // «Список»: его дни. Пока грузится новое окно, видно старое — дни не мигают пустыми.
    val range = when {
        view.grid -> DateFormat.weekStart(day).let { DayRange(it.minus(7, DateTimeUnit.DAY), it.plus(14, DateTimeUnit.DAY)) }
        view == CalendarView.Month -> months.settledPage.let { page ->
            DayRange(
                MonthRules.span(MonthRules.firstOf((page - 1).coerceAtLeast(0))).from,
                MonthRules.span(MonthRules.firstOf((page + 1).coerceAtMost(MonthRules.PAGE_COUNT - 1))).to,
            )
        }
        else -> DayRange(dateOfPage(agenda.from), dateOfPage(agenda.to))
    }
    var loaded by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarTraining>>()) }
    var loadedZones by remember { mutableStateOf(emptyMap<LocalDate, List<CalendarZone>>()) }
    var loadedRange by remember { mutableStateOf<DayRange?>(null) }
    LaunchedEffect(calendar, range) {
        calendar.trainings(range.from, range.to).collect { list ->
            loaded = list.groupBy { it.start.date }
            loadedRange = range
        }
    }
    LaunchedEffect(calendar, range) {
        calendar.zones(range.from, range.to).collect { list ->
            loadedZones = list.groupBy { it.date }
        }
    }
    val itemsOf: (LocalDate) -> List<CalendarTraining> = { date ->
        loaded[date].orEmpty().filter { inPlace(place, it.placeId) && (showCancelled || !it.cancelled) }
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
    val zonesOf: (LocalDate) -> List<CalendarZone> = { date ->
        loadedZones[date].orEmpty().filter { inPlace(place, it.placeId) }
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
                month = if (view == CalendarView.Month) MonthRules.firstOf(months.currentPage) else day,
                day = day,
                today = today,
                view = view,
                views = CalendarViews.options(wide),
                showCancelled = showCancelled,
                places = places,
                place = place,
                wide = wide,
                onToday = {
                    if (view.grid) grid.showNow(now.time)
                    go(today, animate = true)
                },
                onView = ::select,
                onShowCancelled = { show ->
                    showCancelled = show
                    prefs.putBoolean(DevicePrefs.CALENDAR_SHOW_CANCELLED, show)
                },
                onPlace = { placeId = it?.id },
                onPick = { date -> go(date, animate = false) },
            )
            // Свайп по строке дней — неделя вперёд или назад, тот же день недели.
            val onWeek: (Int) -> Unit = { days ->
                ui.launch { pager.animateScrollToPage((pager.currentPage + days).coerceIn(0, PAGE_COUNT - 1)) }
            }
            val onZone: (CalendarZone) -> Unit = { zone -> zoneKey = zoneKeyOf(zone) }
            if (view == CalendarView.Month) {
                MonthView(
                    months = months,
                    selected = picked,
                    today = today,
                    wide = wide,
                    itemsOf = itemsOf,
                    zonesOf = zonesOf,
                    onSelect = { date ->
                        pickedPage = pageOf(date)
                        // День соседнего месяца — листаем на его месяц.
                        val page = MonthRules.page(date)
                        if (page != months.currentPage) ui.launch { months.animateScrollToPage(page) }
                    },
                    onDay = ::openDay,
                    onOpen = onOpen,
                    onZone = onZone,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (view == CalendarView.Agenda) {
                AgendaView(
                    state = agenda,
                    list = agendaList,
                    ready = loadedRange == range,
                    itemsOf = itemsOf,
                    zonesOf = zonesOf,
                    onTop = { date -> pickedPage = pageOf(date) },
                    onDay = ::openDay,
                    onOpen = onOpen,
                    onZone = onZone,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (view == CalendarView.Day) {
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
            if (view.grid) DayGrid(
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
                            scope.launch { if (calendar.copy(training.id)) messages.show(COPIED) }
                        },
                    )
                },
                onOpen = onOpen,
                onMore = { hidden -> moreIds = hidden.joinToString(ID_SEPARATOR) { it.id } },
                onZone = onZone,
                onCreate = onCreate,
                onMenu = { training -> menuId = training.id },
                onMove = { training, start -> moveStored(training.id, start, confirmed = false) },
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
                onEdit = {
                    zoneKey = null
                    onEditZone(zone)
                },
                onSkip = {
                    zoneKey = null
                    scope.launch { calendar.skipRestrictionDay(zone.id, zone.date) }
                    messages.showUndo(ZoneFormat.skipped(zone.date)) { scope.launch { calendar.unskipRestrictionDay(zone.id, zone.date) } }
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
                    moveStored(pending.id, pending.start, confirmed = true, answer = pending.answer)
                },
            )
        }
    }
}

/** Загруженные дни: с [from] по [to] не включая. */
private data class DayRange(val from: LocalDate, val to: LocalDate)

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

/** Открытая зона: вид, id и день — переживает поворот и сложение, а у повторяющегося запрета id одинаков во все дни. */
private fun zoneKeyOf(zone: CalendarZone): String = "${zone.kind}/${zone.id}/${zone.date}"

/** День открытой зоны: в «Нескольких днях» она может быть в любой колонке. */
private fun zoneDateOf(key: String): LocalDate = LocalDate.parse(key.substringAfterLast('/'))

/** Где хранится вид: у сложенного и разложенного экрана — свой. */
private fun viewKey(wide: Boolean): String = if (wide) DevicePrefs.CALENDAR_VIEW_WIDE else DevicePrefs.CALENDAR_VIEW_FOLDED

/** Тренировка или зона в месте, выбранном фильтром: `null` — все места. */
private fun inPlace(place: CalendarPlace?, placeId: String): Boolean = place == null || placeId == place.id

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
 * Окно зоны (ТЗ 5.3, макет «Нажатие на запрет»): «ДБ 17:00–18:00 — групповая тренировка», как повторяется,
 * «Изменить» (дежурство и свой запрет — форма, шаг 1.13а) и «Пропустить этот день». Запрет клуба меняет только
 * администратор (этап 2): у него «Изменить» нет (10.7).
 */
@Composable
private fun ZonePanel(zone: CalendarZone, canSkip: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onSkip: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(title = zone.title, onDismiss = onDismiss) {
        Text(text = zone.text, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text)
        zone.repeat?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted) }
        if (zone.editable) PrimaryButton(EDIT_ZONE, onEdit, Modifier.fillMaxWidth())
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
