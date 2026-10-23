package com.akenory.personalka.ui.clients

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.data.clients.ClientTrainingRow
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.RelativeRow
import com.akenory.personalka.data.clients.WaitRow
import com.akenory.personalka.data.clients.adultOn
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.ClientStats
import com.akenory.personalka.domain.client.Relations
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.series.ClientScheduleRules
import com.akenory.personalka.domain.series.ScheduleSeries
import com.akenory.personalka.domain.training.ClientOutcome
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.results.ResultFormPanel
import com.akenory.personalka.ui.results.ResultWho
import com.akenory.personalka.ui.results.ResultsTab
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.ErrorText
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.training.TrainingFormKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

const val CARD_MENU_TAG = "client-card-menu"
const val ADD_RELATIVE_TAG = "add-relative"
const val DELETE_SURNAME_TAG = "delete-surname"
fun relativeRowTag(name: String) = "relative-$name"
private const val NEW_RESULT = "new"
const val CARD_ADD_TRAINING_TAG = "card-add-training"
const val CARD_NOTE_TAG = "card-note"
const val CARD_ADD_SERIES_TAG = "card-add-series"
const val CARD_ADD_RESULT_TAG = "card-add-result"
const val END_DAY_TAG = "end-day"
fun cardTabTag(tab: CardTab) = "card-tab-${tab.name}"
fun changeFromTag(seriesId: String) = "change-from-$seriesId"
fun endSeriesTag(seriesId: String) = "end-series-$seriesId"

/** Вкладки карточки (5.9). «Результаты» — только когда они есть (10.7: пустое не показывается). */
enum class CardTab(val title: String) { TRAININGS("Тренировки"), RESULTS(ResultFormat.TAB), INFO("Инфо"), SCHEDULE("Расписание") }

private enum class HistoryFilter(val title: String) { ALL("Все"), DONE("Проведённые"), CANCELLED("Отменённые") }

/**
 * Карточка клиента (5.9, макеты ClCard, ClCardResults, ClCardInfo, ClCardSchedule, ClRelInfo): имя, «Ребёнок» или
 * «Взрослый», возраст и расписание; счётчики; «+ Тренировка» и «+ Результат»; вкладки «Тренировки», «Результаты»
 * (когда они есть), «Инфо» и «Расписание». Пока нет ни тренировок, ни серий, ни результатов, нет ни счётчиков,
 * ни вкладок — сразу «Инфо» (10.7: пустое не показывается).
 *
 * Меню «…»: «Изменить», «Объединить с…», «В архив», «Удалить полностью» — с двойным подтверждением и вводом
 * фамилии. «Кабинет» и «В лист ожидания…» появятся в своих шагах (2.13, 2.4).
 *
 * @param onForm открыть форму тренировки ([TrainingFormKey]): «+ Тренировка», «+ Серия», «Изменить с даты…».
 */
@Composable
fun ClientCard(
    clientId: String,
    rows: List<ClientRow>,
    clients: ClientRepository,
    forms: TrainingRepository,
    nav: ClientNav,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onForm: (String) -> Unit,
    onBack: (() -> Unit)?,
    followup: Followup = Followup.EMPTY,
    waiting: List<WaitRow> = emptyList(),
) {
    val today = now.date
    val waits = waiting.firstOrNull { it.client.id == clientId }
    val row = rows.firstOrNull { it.client.id == clientId }
    val relatives by remember(clients, clientId, today) { clients.relatives(clientId, today) }.collectAsState(initial = null)
    val trainings by remember(clients, clientId) { clients.trainings(clientId) }.collectAsState(initial = null)
    val series by remember(forms, clientId) { forms.seriesOf(clientId) }.collectAsState(initial = null)
    val results by remember(clients, clientId) { clients.results.results(clientId) }.collectAsState(initial = null)
    val upcomingCount by produceState(initialValue = 4, clients) { value = clients.upcomingInCard() }
    var tab by rememberSaveable(clientId) { mutableStateOf(CardTab.TRAININGS) }
    var deleteStep by rememberSaveable(clientId) { mutableStateOf(0) }
    var surname by rememberSaveable(clientId) { mutableStateOf("") }
    // «Завершить»: какая серия, последний день и открыт ли выбор даты.
    var ending by rememberSaveable(clientId) { mutableStateOf<String?>(null) }
    var lastDay by rememberSaveable(clientId) { mutableStateOf(ClientScheduleRules.defaultLastDay(today).toString()) }
    var pickingDay by rememberSaveable(clientId) { mutableStateOf(false) }
    // Форма результата: NEW_RESULT — новый, иначе id изменяемого.
    var resultForm by rememberSaveable(clientId) { mutableStateOf<String?>(null) }

    if (row == null) {
        // Клиента только что удалили или объединили — карточка вот-вот закроется.
        CardTopBar(onBack, menu = null)
        return
    }
    val client = row.client
    val name = "${client.lastName} ${client.firstName}"
    val loadedTrainings = trainings
    val stats = loadedTrainings?.let { list ->
        ClientStats.of(list.map { it.training.start.date to ClientOutcome.of(it.training.status, it.participation.status) }, today)
    }
    val hasTrainings = !loadedTrainings.isNullOrEmpty()
    val schedule = series?.let { ClientScheduleRules.ordered(it, today) }
    val active = client.status != ClientStatus.ARCHIVED
    val hasResults = !results.isNullOrEmpty()
    val showTabs = hasTrainings || !schedule.isNullOrEmpty() || hasResults
    val tabs = CardTab.entries.filter { it != CardTab.RESULTS || hasResults }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CardTopBar(onBack) { close ->
                MenuAction("Изменить", PIcons.Edit, {
                    close()
                    nav.open(ClientRoute.edit(clientId))
                })
                if (active) {
                    // Лист ожидания (4.16): нет записи — новая с этим клиентом, есть — «Изменить» её.
                    MenuAction(if (waits == null) WaitFormat.TO_WAITLIST else WaitFormat.TITLE_EDIT, PIcons.Schedule, {
                        close()
                        nav.open(waits?.let { ClientRoute.waitEdit(it.entry.id) } ?: ClientRoute.waitNew(clientId))
                    })
                }
                if (rows.size > 1) {
                    MenuAction("Объединить с…", PIcons.CallMerge, {
                        close()
                        nav.open(ClientRoute.merge(clientId))
                    })
                }
                if (client.status == ClientStatus.ARCHIVED) {
                    MenuAction("Вернуть из архива", PIcons.Unarchive, {
                        close()
                        scope.launch { clients.unarchive(clientId) }
                        messages.show("$name — снова среди активных")
                    })
                } else {
                    MenuAction("В архив", PIcons.Archive, {
                        close()
                        // Сообщение — когда запись прошла: «Отменить» возвращает и места в группах (4.4).
                        scope.launch {
                            val undo = clients.archive(clientId) ?: return@launch
                            messages.showUndo("$name в архиве") { scope.launch { clients.undoArchive(undo) } }
                        }
                    })
                }
                HorizontalDivider(color = PersonalkaTheme.colors.divider)
                MenuAction("Удалить полностью", PIcons.Delete, {
                    close()
                    surname = ""
                    deleteStep = 1
                }, danger = true)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                CardHeader(row, today, noBooking = clientId in followup.noBooking, waits = waits != null)
                if (stats != null && !stats.isEmpty) StatsRow(stats)
                if (active) {
                    // Клиента в архиве не записывают: сначала «Вернуть из архива».
                    Row(
                        Modifier.fillMaxWidth().padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space3),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    ) {
                        SecondaryButton(
                            ClientFormat.ADD_TRAINING,
                            { onForm(TrainingFormKey.new(clientId = clientId)) },
                            modifier = Modifier.weight(1f).testTag(CARD_ADD_TRAINING_TAG),
                            icon = PIcons.Add,
                        )
                        SecondaryButton(
                            ResultFormat.ADD,
                            { resultForm = NEW_RESULT },
                            modifier = Modifier.weight(1f).testTag(CARD_ADD_RESULT_TAG),
                            icon = PIcons.Add,
                        )
                    }
                }
                val loadedResults = results
                if (loadedTrainings != null && schedule != null && loadedResults != null) {
                    if (showTabs) {
                        Tabs(tabs, tab) { tab = it }
                    }
                    val shownTab = if (!showTabs) CardTab.INFO else tab.takeIf { it in tabs } ?: CardTab.TRAININGS
                    when (shownTab) {
                        CardTab.TRAININGS -> TrainingsTab(clientId, loadedTrainings, now, upcomingCount)
                        CardTab.RESULTS -> ResultsTab(clientId, loadedResults, today, onOpen = { resultForm = it.id })
                        CardTab.INFO -> InfoTab(client, relatives.orEmpty(), today, nav, clients, messages, scope)
                        CardTab.SCHEDULE -> ScheduleTab(
                            list = schedule,
                            today = today,
                            active = active,
                            onChangeFrom = { onForm(TrainingFormKey.series(it)) },
                            onEnd = {
                                lastDay = ClientScheduleRules.defaultLastDay(today).toString()
                                ending = it
                            },
                            onAdd = { onForm(TrainingFormKey.new(clientId = clientId, weekly = true)) },
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        resultForm?.let { key ->
            val editing = results?.firstOrNull { it.id == key }
            if (key == NEW_RESULT || editing != null) {
                ResultFormPanel(
                    who = listOf(ResultWho(clientId, name)),
                    date = today,
                    editing = editing,
                    results = clients.results,
                    messages = messages,
                    scope = scope,
                    onClose = { resultForm = null },
                    // Сохранённый виден сразу: вкладка «Результаты».
                    onSaved = { tab = CardTab.RESULTS },
                )
            }
        }

        val endingSeries = schedule?.firstOrNull { it.id == ending }
        if (endingSeries != null) {
            val day = LocalDate.parse(lastDay)
            val problem = ClientScheduleRules.lastDayProblem(day, today)
            Panel(ClientFormat.END_TITLE, onDismiss = { ending = null }) {
                Text(
                    ClientFormat.seriesDays(endingSeries.weekdays, endingSeries.startTime, endingSeries.durationMin),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = PersonalkaTheme.colors.text,
                )
                SectionLabel(ClientFormat.LAST_DAY)
                PickBox(
                    text = TrainingFormat.date(day),
                    icon = PIcons.CalendarMonth,
                    onClick = { pickingDay = true },
                    modifier = Modifier.fillMaxWidth().testTag(END_DAY_TAG),
                    error = problem,
                )
                problem?.let { ErrorText(it) }
                Text(ClientFormat.END_NOTE, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
                PanelActions(
                    primary = ClientFormat.END_SERIES,
                    onPrimary = {
                        val id = endingSeries.id
                        ending = null
                        scope.launch {
                            val result = forms.endSeries(id, day) as? SaveResult.Saved ?: return@launch
                            messages.showUndo(result.message) { scope.launch { forms.undo(result.undo) } }
                        }
                    },
                    secondary = "Отмена",
                    onSecondary = { ending = null },
                    primaryEnabled = problem == null,
                )
            }
            if (pickingDay) {
                DatePickerPanel(
                    initial = day,
                    onPick = {
                        pickingDay = false
                        lastDay = it.toString()
                    },
                    onDismiss = { pickingDay = false },
                )
            }
        }

        when (deleteStep) {
            1 -> Panel("Удалить клиента полностью?", onDismiss = { deleteStep = 0 }) {
                Text(
                    "$name: карточка, результаты, связи и будущие тренировки удалятся навсегда, вернуть их будет нельзя. " +
                        "Прошедшие тренировки останутся в зарплате — без имени и записей о клиенте.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PersonalkaTheme.colors.text,
                )
                PanelActions("Дальше", { deleteStep = 2 }, "Отмена", { deleteStep = 0 })
            }
            2 -> Panel("Введите фамилию клиента", onDismiss = { deleteStep = 0 }) {
                Text(
                    "Так приложение убедится, что удаляется именно $name.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PersonalkaTheme.colors.text,
                )
                Field("Фамилия", surname, { surname = it }, tag = DELETE_SURNAME_TAG)
                PanelActions(
                    primary = "Удалить навсегда",
                    onPrimary = {
                        deleteStep = 0
                        scope.launch { clients.erase(clientId) }
                        nav.back()
                        messages.show(RuText.bySex(client.sex, "$name удалён", "$name удалена", "Клиент удалён"))
                    },
                    secondary = "Отмена",
                    onSecondary = { deleteStep = 0 },
                    primaryEnabled = ClientRules.key(surname) == ClientRules.key(client.lastName),
                )
            }
        }
    }
}

/** Верх карточки: «Назад» и меню «…». */
@Composable
private fun CardTopBar(onBack: (() -> Unit)?, menu: (@Composable (close: () -> Unit) -> Unit)?) {
    val colors = PersonalkaTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text)
            }
        }
        Spacer(Modifier.weight(1f))
        if (menu != null) {
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(CARD_MENU_TAG)) {
                    Icon(PIcons.MoreVert, contentDescription = "Ещё действия", tint = colors.text)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
                    menu { open = false }
                }
            }
        }
    }
}

/** Имя, метки и строка «11 лет · Вт, Чт 13:30 · ПТ ББ». */
@Composable
private fun CardHeader(row: ClientRow, today: LocalDate, noBooking: Boolean, waits: Boolean) {
    val colors = PersonalkaTheme.colors
    val client = row.client
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.space4)) {
        Text(
            "${client.lastName} ${client.firstName}",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
        )
        Spacer(Modifier.height(Dimens.space2))
        val line = listOfNotNull(client.birthDate?.let { ClientFormat.age(it, today) }, ClientFormat.schedule(row.schedule))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            OutlineLabel(ClientFormat.adult(client.adultOn(today)))
            if (client.status == ClientStatus.ARCHIVED) OutlineLabel("В архиве")
            if (noBooking) OutlineLabel(FollowupFormat.NO_BOOKING, PIcons.EventBusy)
            if (waits) OutlineLabel(WaitFormat.LABEL, PIcons.Schedule)
            if (line.isNotEmpty()) {
                Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}

/** Метка с рамкой: «Ребёнок», «В архиве», «Нет записи» — со значком. */
@Composable
private fun OutlineLabel(text: String, icon: ImageVector? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .border(1.dp, colors.outline, MaterialTheme.shapes.small)
            .padding(horizontal = Dimens.space2, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.text)
    }
}

/** Счётчики: проведено · отменено · со списанием · в месяц. */
@Composable
private fun StatsRow(stats: ClientStats) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space3)
            .fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium)
            .padding(vertical = Dimens.space3),
    ) {
        listOf(
            RuText.digits(stats.done.toLong()) to "проведено",
            RuText.digits(stats.cancelled.toLong()) to "отменено",
            RuText.digits(stats.charged.toLong()) to "со списанием",
            ClientFormat.tenths(stats.perMonthTenths) to "в месяц",
        ).forEach { (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text)
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Tabs(tabs: List<CardTab>, selected: CardTab, onSelect: (CardTab) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.padding(top = Dimens.space3)) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touch)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                        .testTag(cardTabTag(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        tab.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.text else colors.textMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(64.dp).height(3.dp).background(if (isSelected) colors.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
    }
}

// --- Тренировки ---

/**
 * «Тренировки» (5.9): вверху ближайшие — сколько задано в настройках, у первой план; ниже история от новых
 * к старым со статусом клиента, фильтр «Все / Проведённые / Отменённые». Нажатие раскрывает план, факт
 * и поведение или причину отмены.
 */
@Composable
private fun TrainingsTab(clientId: String, list: List<ClientTrainingRow>, now: LocalDateTime, upcomingCount: Int) {
    val colors = PersonalkaTheme.colors
    var filter by rememberSaveable(clientId) { mutableStateOf(HistoryFilter.ALL) }
    var expanded by rememberSaveable(clientId) { mutableStateOf<String?>(null) }
    val ahead: (ClientTrainingRow) -> Boolean = {
        it.training.start >= now && it.training.status == TrainingStatus.PLANNED && it.participation.status == ParticipationStatus.EXPECTED
    }
    val upcoming = list.filter(ahead).sortedBy { it.training.start }.take(upcomingCount)
    val history = list.filterNot(ahead).sortedByDescending { it.training.start }.filter { row ->
        val outcome = row.outcome
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.DONE -> outcome == ClientOutcome.DONE
            HistoryFilter.CANCELLED -> outcome == ClientOutcome.CANCELLED || outcome == ClientOutcome.CANCELLED_CHARGED
        }
    }

    Column(Modifier.padding(horizontal = Dimens.space4)) {
        if (upcoming.isNotEmpty()) {
            TabSectionTitle("Ближайшие")
            ListCard {
                upcoming.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    TrainingItem(row, showPlan = index == 0, expanded = false, onClick = null, status = false)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            Text("История", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            HistoryFilter.entries.forEach { option ->
                ChoiceChip(option.title, filter == option, onClick = { filter = option })
            }
        }
        Spacer(Modifier.height(Dimens.space2))
        if (history.isEmpty()) {
            Text("Здесь пока пусто", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        } else {
            ListCard {
                history.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    val id = row.training.id
                    TrainingItem(
                        row = row,
                        showPlan = false,
                        expanded = expanded == id,
                        onClick = { expanded = if (expanded == id) null else id },
                        status = true,
                    )
                }
            }
        }
    }
}

private val ClientTrainingRow.outcome: ClientOutcome get() = ClientOutcome.of(training.status, participation.status)

/** Строка тренировки: дата и время слева, «ПТ · ББ», статус справа; раскрытая — записи дневника. */
@Composable
private fun TrainingItem(row: ClientTrainingRow, showPlan: Boolean, expanded: Boolean, onClick: (() -> Unit)?, status: Boolean) {
    val colors = PersonalkaTheme.colors
    val training = row.training
    val outcome = row.outcome
    val what = listOfNotNull(row.type?.shortName, row.place?.shortName).joinToString(" · ").ifEmpty { "Тренировка" }
    val diaryMissing = outcome == ClientOutcome.DONE && row.type?.diaryFact == true && training.fact.isNullOrBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space3),
    ) {
        Column(Modifier.width(72.dp)) {
            Text(DateFormat.short(training.start.date), style = MaterialTheme.typography.labelMedium.tabular(), color = colors.text)
            Text(DateFormat.time(training.start.time), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(what, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (diaryMissing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(colors.accentGraphic, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("нет дневника", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            val plan = training.plan
            if (showPlan && !plan.isNullOrBlank()) {
                Text("План: $plan", style = MaterialTheme.typography.bodySmall, color = colors.text)
            }
            if (expanded) {
                val reason = row.participation.cancelReason ?: training.cancelReason
                listOfNotNull(
                    plan?.let { "План" to it },
                    training.fact?.let { "Факт" to it },
                    row.participation.behavior?.let { "Поведение" to it },
                    reason?.takeIf { outcome == ClientOutcome.CANCELLED || outcome == ClientOutcome.CANCELLED_CHARGED }?.let { "Причина" to it },
                ).filter { it.second.isNotBlank() }.forEach { (label, text) ->
                    Spacer(Modifier.height(Dimens.space1))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                }
            }
        }
        if (status) StatusLabel(outcome)
    }
}

/** Итог клиента на тренировке (4.1) — те же слова, что в кабинете. */
@Composable
private fun StatusLabel(outcome: ClientOutcome) {
    val colors = PersonalkaTheme.colors
    val (background, content) = when (outcome) {
        ClientOutcome.DONE -> colors.doneContainer to colors.onDoneContainer
        ClientOutcome.CANCELLED_CHARGED -> colors.chargedContainer to colors.onChargedContainer
        ClientOutcome.CANCELLED -> colors.surfaceAlt to colors.text
        ClientOutcome.PLANNED -> Color.Transparent to colors.textMuted
    }
    Row(
        Modifier.background(background, MaterialTheme.shapes.small).padding(horizontal = Dimens.space2, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (outcome == ClientOutcome.DONE) {
            Icon(PIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(outcome.label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun TabSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Dimens.space4, bottom = Dimens.space2),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

// --- Расписание ---

/**
 * «Расписание» (5.9, макет ClCardSchedule): серии клиента — дни и время, тип · место, период. У идущей —
 * «Изменить с даты…» и «Завершить», завершённая — бледнее и без кнопок; ниже «+ Серия».
 */
@Composable
private fun ScheduleTab(
    list: List<ScheduleSeries>,
    today: LocalDate,
    active: Boolean,
    onChangeFrom: (String) -> Unit,
    onEnd: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.padding(horizontal = Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        TabSectionTitle(ClientFormat.SCHEDULE_TITLE)
        if (list.isEmpty()) {
            Text(ClientFormat.NO_SCHEDULE, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        }
        list.forEach { s ->
            val ended = s.endedOn(today)
            ListCard {
                Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        ClientFormat.seriesDays(s.weekdays, s.startTime, s.durationMin),
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = if (ended) colors.textMuted else colors.text,
                    )
                    Text(
                        ClientFormat.seriesPeriod(s.typeShort, s.placeShort, s.validFrom, s.validTo, today),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = colors.textMuted,
                    )
                    if (!ended && active) {
                        Row(Modifier.padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                            SecondaryButton(ClientFormat.CHANGE_FROM, { onChangeFrom(s.id) }, modifier = Modifier.testTag(changeFromTag(s.id)))
                            Spacer(Modifier.width(Dimens.space2))
                            TextButton(onClick = { onEnd(s.id) }, modifier = Modifier.heightIn(min = Dimens.touch).testTag(endSeriesTag(s.id))) {
                                Text(ClientFormat.END_SERIES, style = MaterialTheme.typography.labelLarge, color = colors.text)
                            }
                        }
                    }
                }
            }
        }
        if (active) {
            OutlineButton(
                ClientFormat.ADD_SERIES,
                onAdd,
                modifier = Modifier.fillMaxWidth().testTag(CARD_ADD_SERIES_TAG),
                icon = PIcons.Add,
            )
        }
    }
}

// --- Инфо ---

/**
 * «Инфо» (5.9): дата рождения и пол, родственники (4.17), для связи, фото и видео, имя в 1С. Заметка «видна
 * только вам» — шаг 2.4, стартовая тренировка — с расчётом СПТ (3.4).
 */
@Composable
private fun InfoTab(
    client: ClientEntity,
    relatives: List<RelativeRow>,
    today: LocalDate,
    nav: ClientNav,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
) {
    val colors = PersonalkaTheme.colors
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = Dimens.space4).padding(top = Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        val birth = client.birthDate
        val sex = client.sex
        if (birth != null || sex != null) {
            ListCard {
                if (birth != null) InfoLine("Дата рождения", ClientFormat.birth(birth, today))
                if (birth != null && sex != null) RowDivider()
                if (sex != null) InfoLine("Пол", ClientFormat.sex(sex))
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Text("Родственники", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            SecondaryButton(
                "Родственник",
                { nav.open(ClientRoute.relative(client.id)) },
                icon = PIcons.Add,
                modifier = Modifier.testTag(ADD_RELATIVE_TAG),
            )
        }
        if (relatives.isEmpty()) {
            Text("Родственников нет", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        } else {
            ListCard {
                relatives.forEachIndexed { index, relative ->
                    if (index > 0) RowDivider()
                    RelativeItem(
                        relative = relative,
                        today = today,
                        onOpen = { nav.open(ClientRoute.card(relative.relative.id)) },
                        onEdit = { nav.open(ClientRoute.relation(client.id, relative.link.relationId)) },
                        onRemove = {
                            val id = relative.link.relationId
                            scope.launch { clients.removeRelation(id) }
                            messages.showUndo("Связь убрана") { scope.launch { clients.restoreRelation(id) } }
                        },
                    )
                }
            }
        }

        val phone = client.contactPhone
        val media = client.mediaUrl
        Spacer(Modifier.height(Dimens.space1))
        ListCard {
            if (phone != null || client.contactName != null) {
                InfoLine(
                    label = listOfNotNull("Для связи", client.contactName).joinToString(" · "),
                    value = phone?.let { ClientFormat.phone(it) } ?: "Телефона нет",
                    action = if (phone != null) {
                        {
                            IconButton(onClick = { runCatching { uri.openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) } }) {
                                Icon(PIcons.Call, contentDescription = "Позвонить", tint = colors.text)
                            }
                        }
                    } else {
                        null
                    },
                )
                RowDivider()
            }
            if (media != null) {
                InfoLine(
                    label = "Фото и видео",
                    value = media.removePrefix("https://").removePrefix("http://"),
                    action = {
                        IconButton(onClick = { runCatching { uri.openUri(if ("://" in media) media else "https://$media") } }) {
                            Icon(PIcons.OpenInNew, contentDescription = "Открыть ссылку", tint = colors.text)
                        }
                    },
                )
            } else {
                InfoLine(
                    label = "Фото и видео",
                    value = "Ссылки нет",
                    muted = true,
                    action = {
                        TextButton(onClick = { nav.open(ClientRoute.edit(client.id)) }) {
                            Text("Добавить", style = MaterialTheme.typography.labelLarge, color = colors.text)
                        }
                    },
                )
            }
            val name1c = client.name1c
            if (name1c != null) {
                RowDivider()
                InfoLine("Имя в 1С", name1c)
            }
            val note = client.privateNote
            if (note != null) {
                RowDivider()
                NoteLine(note)
            }
        }
    }
}

/** Заметка «только для себя» (5.9): замок, подпись «Заметка · видна только вам», текст целиком. */
@Composable
private fun NoteLine(text: String) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.row).testTag(CARD_NOTE_TAG)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ClientFormat.NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        }
        Spacer(Modifier.width(Dimens.space2))
        Icon(PIcons.Lock, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

/** Строка «Инфо»: подпись мелко, значение под ней, справа — действие. */
@Composable
private fun InfoLine(label: String, value: String, muted: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.row).padding(start = Dimens.space4, end = 4.dp, top = Dimens.space2, bottom = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = if (muted) colors.textMuted else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null) action() else Spacer(Modifier.width(Dimens.space3))
    }
}

/**
 * Родственник (5.9, 4.17): кем приходится, имя, возраст и расписание; в архиве — с пометкой. Нажатие открывает
 * его карточку, долгое — «Изменить связь» и «Убрать связь»; для TalkBack — те же действия у строки.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelativeItem(relative: RelativeRow, today: LocalDate, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val colors = PersonalkaTheme.colors
    var menu by remember { mutableStateOf(false) }
    val person = relative.relative
    val name = "${person.lastName} ${person.firstName}"
    val word = Relations.word(relative.link.role, person.sex, relative.link.label)
    val line = listOfNotNull(person.birthDate?.let { ClientFormat.age(it, today) }, ClientFormat.schedule(relative.schedule)) +
        if (person.status == ClientStatus.ARCHIVED) listOf("в архиве") else emptyList()
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.row)
                .background(if (menu) colors.surfaceAlt else Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Изменить связь") { onEdit(); true },
                        CustomAccessibilityAction("Убрать связь") { onRemove(); true },
                    )
                }
                .testTag(relativeRowTag(name))
                .padding(start = Dimens.space4, end = Dimens.space2, top = Dimens.space2, bottom = Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(word, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                Text(name, style = MaterialTheme.typography.labelLarge, color = colors.text)
                if (line.isNotEmpty()) {
                    Text(
                        line.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
            MenuAction("Изменить связь", PIcons.Edit, {
                menu = false
                onEdit()
            })
            MenuAction("Убрать связь", PIcons.LinkOff, {
                menu = false
                onRemove()
            }, danger = true)
        }
    }
}
