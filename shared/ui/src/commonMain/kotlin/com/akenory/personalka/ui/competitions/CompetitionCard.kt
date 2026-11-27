package com.akenory.personalka.ui.competitions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.competitions.CompetitionRepository
import com.akenory.personalka.data.competitions.CompetitionRow
import com.akenory.personalka.domain.competition.Competition
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.CompetitionStart
import com.akenory.personalka.domain.competition.Participant
import com.akenory.personalka.domain.format.CompetitionFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.ResultTime
import com.akenory.personalka.domain.result.SwimEvent
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.groups.FormLabelSpaced
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.results.TimeTransformation
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val COMPETITION_PICK_TAG = "competition-pick"
const val COMPETITION_EDIT_TAG = "competition-edit"
const val COMPETITION_SHARE_TAG = "competition-share"
const val COMPETITION_COLLECTED_TAG = "competition-collected"
const val SHARE_ENTRY_TAG = "competition-share-entry"
const val SHARE_DEBT_TAG = "competition-share-debt"
const val SHARE_RESULTS_TAG = "competition-share-results"
const val START_ADD_TAG = "competition-start-add"
const val START_ENTRY_TAG = "competition-start-entry"
const val START_RESULT_TAG = "competition-start-result"
const val START_PLACE_TAG = "competition-start-place"
const val START_HINT_TAG = "competition-start-hint"
const val START_DELETE_TAG = "competition-start-delete"
const val ENTRY_REMOVE_TAG = "competition-entry-remove"
fun participantTag(name: String) = "competition-participant-$name"
fun startsLineTag(name: String) = "competition-starts-$name"
fun handTag(name: String, item: String) = "competition-hand-$name-$item"
fun missingTag(item: String) = "competition-missing-$item"
fun startRowTag(text: String) = "competition-start-$text"

/** Окно старта: новый у участника [entryId] или существующий [startId]. */
private const val NEW_START = "new"

/**
 * Карточка соревнования (5.21): дата, бассейн, место и условия; «что не сдано» чипами с отбором; «Собрано … из …»;
 * главная кнопка «Подобрать участников»; участники с флажками «сдал». Нажатие на участника — его окно: отметки,
 * старты, «Убрать из соревнования»; нажатие на старт — окно старта. «Поделиться» — заявка, кто не сдал, итоги.
 */
@Composable
internal fun CompetitionCard(
    id: String,
    rows: List<CompetitionRow>,
    repo: CompetitionRepository,
    shell: ShellState,
    messages: MessageState,
    scope: CoroutineScope,
    today: LocalDate,
    onBack: (() -> Unit)?,
    shareText: ((String) -> Unit)?,
) {
    val colors = PersonalkaTheme.colors
    val row = rows.firstOrNull { it.competition.id == id }
    val results by remember(repo) { repo.results }.collectAsState(initial = emptyMap())
    var filter by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var openEntry by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var openStart by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var sharing by rememberSaveable(id) { mutableStateOf(false) }

    if (row == null) {
        TopBar("", onBack = onBack)
        return
    }
    val c = row.competition
    val participants = row.participants
    val item = c.items.firstOrNull { it.id == filter }
    val shown = if (item == null) participants else participants.filter { item.id !in it.handedIn }
    val resultsOf: (String) -> List<ResultItem> = { results[it].orEmpty() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text) }
                }
                Spacer(Modifier.weight(1f))
                if (shareText != null) {
                    IconButton(onClick = { sharing = true }, modifier = Modifier.testTag(COMPETITION_SHARE_TAG)) {
                        Icon(PIcons.Share, contentDescription = CompetitionFormat.SHARE, tint = colors.text)
                    }
                }
                IconButton(onClick = { shell.open(CompetitionRoute.edit(id)) }, modifier = Modifier.testTag(COMPETITION_EDIT_TAG)) {
                    Icon(PIcons.Edit, contentDescription = CompetitionFormat.EDIT_TITLE, tint = colors.text)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Text(c.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Spacer(Modifier.height(Dimens.space1))
                Text(CompetitionFormat.header(c), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                CompetitionFormat.rules(c.rules)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
                if (c.items.isNotEmpty() && participants.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.space3))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        c.items.forEach { collect ->
                            ChoiceChip(
                                CompetitionFormat.missing(collect, CompetitionRules.missing(collect, participants)),
                                filter == collect.id,
                                onClick = { filter = if (filter == collect.id) null else collect.id },
                                toggle = true,
                                modifier = Modifier.testTag(missingTag(collect.title)),
                            )
                        }
                    }
                    CompetitionRules.collected(c.items, participants)?.let {
                        Text(
                            CompetitionFormat.collected(it),
                            Modifier.padding(top = Dimens.space1).testTag(COMPETITION_COLLECTED_TAG),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.space4))
                PrimaryButton(CompetitionFormat.PICK, { shell.open(CompetitionRoute.pick(id)) }, Modifier.fillMaxWidth().testTag(COMPETITION_PICK_TAG))
                FormLabelSpaced(
                    if (item == null) "Участники · ${participants.size}" else "${CompetitionFormat.NOT_HANDED} ${item.title.lowercase()} · ${shown.size}",
                )
                if (participants.isEmpty()) {
                    Text(CompetitionFormat.NO_PARTICIPANTS, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                } else {
                    ListCard {
                        shown.forEachIndexed { index, p ->
                            if (index > 0) RowDivider()
                            ParticipantRow(c, p, resultsOf(p.clientId), onOpen = { openEntry = p.id }) { itemId, handed ->
                                scope.launch { repo.setHanded(p.id, itemId, handed) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        val entry = participants.firstOrNull { it.id == openEntry }
        val startKey = openStart
        if (entry != null && startKey != null) {
            StartPanel(
                c = c,
                participant = entry,
                start = entry.starts.firstOrNull { it.id == startKey },
                results = resultsOf(entry.clientId),
                today = today,
                repo = repo,
                messages = messages,
                scope = scope,
                onClose = { openStart = null },
            )
        } else if (entry != null) {
            ParticipantPanel(
                c = c,
                p = entry,
                results = resultsOf(entry.clientId),
                onHanded = { itemId, handed -> scope.launch { repo.setHanded(entry.id, itemId, handed) } },
                onStart = { openStart = it ?: NEW_START },
                onRemove = {
                    openEntry = null
                    scope.launch {
                        val undo = repo.remove(entry.id) ?: return@launch
                        messages.showUndo(CompetitionFormat.REMOVED) { scope.launch { repo.undoRemove(undo) } }
                    }
                },
                onClose = { openEntry = null },
            )
        }
        if (sharing && shareText != null) {
            SharePanel(c, participants, resultsOf, today, onShare = { text ->
                sharing = false
                shareText(text)
            }, onClose = { sharing = false })
        }
    }
}

/** Строка участника: «Петров Лев, 2014», старты с заявочным временем, пункты сбора — чипами-флажками. */
@Composable
private fun ParticipantRow(c: Competition, p: Participant, results: List<ResultItem>, onOpen: () -> Unit, onHanded: (String, Boolean) -> Unit) {
    val colors = PersonalkaTheme.colors
    val title = CompetitionFormat.person(p.name, p.birthYear)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(participantTag(p.name.short))
            .padding(horizontal = Dimens.space4, vertical = Dimens.space3),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(
            CompetitionFormat.starts(p.starts.map { it.event to CompetitionRules.entryTime(it, results, c.date).cs }),
            Modifier.testTag(startsLineTag(p.name.short)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (c.items.isNotEmpty()) {
            FlowRow(Modifier.padding(top = Dimens.space1), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                c.items.forEach { item ->
                    val handed = item.id in p.handedIn
                    ChoiceChip(
                        item.title,
                        handed,
                        onClick = { onHanded(item.id, !handed) },
                        toggle = true,
                        modifier = Modifier.testTag(handTag(p.name.short, item.title)),
                    )
                }
            }
        }
    }
}

/** Окно участника: пункты сбора флажками, старты (нажатие — окно старта), «Добавить старт», «Убрать из соревнования». */
@Composable
private fun ParticipantPanel(
    c: Competition,
    p: Participant,
    results: List<ResultItem>,
    onHanded: (String, Boolean) -> Unit,
    onStart: (String?) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    Panel(CompetitionFormat.person(p.name, p.birthYear), onDismiss = onClose) {
        if (c.items.isNotEmpty()) {
            SectionLabel(CompetitionFormat.ITEMS)
            c.items.forEach { item ->
                val handed = item.id in p.handedIn
                CheckRow(CompetitionFormat.item(item), handed, { onHanded(item.id, it) }, Modifier.testTag(handTag(p.name.short, item.title) + "-panel"))
            }
        }
        SectionLabel(CompetitionFormat.STARTS)
        if (p.starts.isEmpty()) {
            Text(CompetitionFormat.NO_STARTS, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.textMuted)
        } else {
            ListCard {
                p.starts.sortedWith(compareBy(ResultRules.ORDER) { it.event }).forEachIndexed { index, start ->
                    if (index > 0) RowDivider()
                    val event = ResultFormat.event(start.event)
                    NavRow(
                        title = event,
                        subtitle = CompetitionFormat.startLine(CompetitionRules.entryTime(start, results, c.date).cs, start.resultCs, start.place),
                        onClick = { onStart(start.id) },
                        modifier = Modifier.testTag(startRowTag(event)),
                    )
                }
            }
        }
        Row {
            SecondaryButton(CompetitionFormat.ADD_START, { onStart(null) }, icon = PIcons.Add, modifier = Modifier.testTag(START_ADD_TAG))
        }
        Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
            PrimaryButton(CompetitionFormat.CLOSE, onClose, Modifier.fillMaxWidth())
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(ENTRY_REMOVE_TAG)) {
                Text(CompetitionFormat.REMOVE, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
            }
        }
    }
}

/**
 * Окно старта (5.21): стиль и дистанция чипами, заявочное время — лучшее в бассейне соревнования до его дня, пока
 * тренер не ввёл своё; с дня соревнования — результат и место. «Удалить старт» — с «Отменить».
 */
@Composable
private fun StartPanel(
    c: Competition,
    participant: Participant,
    start: CompetitionStart?,
    results: List<ResultItem>,
    today: LocalDate,
    repo: CompetitionRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val key = start?.id ?: NEW_START
    val fallback = CompetitionRules.newEvent(c.poolM)
    var styleName by rememberSaveable(key) { mutableStateOf((start?.event ?: fallback).style.name) }
    var distance by rememberSaveable(key) { mutableStateOf((start?.event ?: fallback).distanceM) }
    var entryTouched by rememberSaveable(key) { mutableStateOf(start?.entryCs != null) }
    var entryDigits by rememberSaveable(key) { mutableStateOf(start?.entryCs?.let { ResultTime.digits(ResultTime.format(it)) } ?: "") }
    var resultDigits by rememberSaveable(key) { mutableStateOf(start?.resultCs?.let { ResultTime.digits(ResultTime.format(it)) } ?: "") }
    var placeText by rememberSaveable(key) { mutableStateOf(start?.place?.toString() ?: "") }

    val style = SwimStyle.valueOf(styleName)
    val event = SwimEvent(style, ResultRules.fitDistance(style, distance), c.poolM)
    val auto = CompetitionRules.entryTime(CompetitionStart("", event), results, c.date)
    val shownEntry = if (entryTouched) entryDigits else auto.cs?.let { ResultTime.digits(ResultTime.format(it)) } ?: ""
    val finish = CompetitionRules.canFinish(c.date, today)
    val entryOk = shownEntry.isEmpty() || ResultTime.parse(shownEntry) != null
    val resultOk = !finish || resultDigits.isEmpty() || ResultTime.parse(resultDigits) != null
    val placeOk = !finish || placeText.isBlank() || CompetitionRules.place(placeText) != null

    Panel(if (start == null) CompetitionFormat.NEW_START else CompetitionFormat.START_TITLE, onDismiss = onClose) {
        Text(CompetitionFormat.person(participant.name, participant.birthYear), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.textMuted)
        SectionLabel(CompetitionFormat.STYLE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SwimStyle.entries.forEach { option ->
                ChoiceChip(ResultFormat.styleChip(option), option == style, onClick = { styleName = option.name })
            }
        }
        SectionLabel(CompetitionFormat.DISTANCE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            ResultRules.distances(style).forEach { meters ->
                ChoiceChip(ResultFormat.meters(meters), meters == event.distanceM, onClick = { distance = meters })
            }
        }
        Field(
            label = CompetitionFormat.ENTRY_TIME,
            value = shownEntry,
            onValueChange = {
                entryTouched = true
                entryDigits = ResultTime.digits(it)
            },
            modifier = Modifier.fillMaxWidth(),
            error = ResultFormat.timeProblem(shownEntry),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            tag = START_ENTRY_TAG,
            visualTransformation = TimeTransformation,
        )
        Text(
            CompetitionFormat.entryHint(auto, c.poolM),
            Modifier.testTag(START_HINT_TAG),
            style = MaterialTheme.typography.bodySmall,
            color = PersonalkaTheme.colors.textMuted,
        )
        if (finish) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Field(
                    label = CompetitionFormat.RESULT,
                    value = resultDigits,
                    onValueChange = { resultDigits = ResultTime.digits(it) },
                    modifier = Modifier.weight(1.4f),
                    error = ResultFormat.timeProblem(resultDigits),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    tag = START_RESULT_TAG,
                    visualTransformation = TimeTransformation,
                )
                Field(
                    label = CompetitionFormat.RANK,
                    value = placeText,
                    onValueChange = { placeText = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.weight(1f),
                    error = if (placeOk) null else CompetitionFormat.RANK_BAD,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    tag = START_PLACE_TAG,
                )
            }
        }
        PanelActions(
            primary = CompetitionFormat.SAVE,
            onPrimary = {
                val stored = if (entryTouched) CompetitionRules.storedEntry(ResultTime.parse(entryDigits), auto.best) else null
                val resultCs = if (finish) ResultTime.parse(resultDigits) else start?.resultCs
                val place = if (finish) CompetitionRules.place(placeText) else start?.place
                onClose()
                scope.launch {
                    val message = repo.saveStart(participant.id, start?.id, event.style, event.distanceM, stored, resultCs, place)
                    messages.show(message)
                }
            },
            secondary = CompetitionFormat.CANCEL,
            onSecondary = onClose,
            primaryEnabled = entryOk && resultOk && placeOk,
        )
        if (start != null) {
            TextButton(
                onClick = {
                    onClose()
                    scope.launch {
                        val undo = repo.deleteStart(start.id) ?: return@launch
                        messages.showUndo(CompetitionFormat.START_DELETED) { scope.launch { repo.undoDeleteStart(undo) } }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(START_DELETE_TAG),
            ) {
                Text(CompetitionFormat.START_DELETE, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
            }
        }
    }
}

/** «Поделиться»: «Заявка», «Кто не сдал» (при пунктах сбора), «Итоги» (с дня соревнования). */
@Composable
private fun SharePanel(
    c: Competition,
    participants: List<Participant>,
    resultsOf: (String) -> List<ResultItem>,
    today: LocalDate,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
) {
    Panel(CompetitionFormat.SHARE, onDismiss = onClose) {
        ListCard {
            NavRow(
                CompetitionFormat.SHARE_ENTRY,
                onClick = {
                    onShare(
                        CompetitionFormat.entryText(c, CompetitionRules.entryList(participants, resultsOf, c.date), CompetitionRules.withoutStarts(participants)),
                    )
                },
                modifier = Modifier.testTag(SHARE_ENTRY_TAG),
                subtitle = CompetitionFormat.SHARE_ENTRY_NOTE,
            )
            if (c.items.isNotEmpty()) {
                RowDivider()
                NavRow(
                    CompetitionFormat.SHARE_DEBT,
                    onClick = { onShare(CompetitionFormat.debtText(c, participants)) },
                    modifier = Modifier.testTag(SHARE_DEBT_TAG),
                    subtitle = CompetitionFormat.SHARE_DEBT_NOTE,
                )
            }
            if (CompetitionRules.canFinish(c.date, today)) {
                RowDivider()
                NavRow(
                    CompetitionFormat.SHARE_RESULTS,
                    onClick = {
                        val rows = participants.map { it to CompetitionRules.results(it, resultsOf(it.clientId), c.date) }
                        onShare(CompetitionFormat.resultsText(c, rows))
                    },
                    modifier = Modifier.testTag(SHARE_RESULTS_TAG),
                    subtitle = CompetitionFormat.SHARE_RESULTS_NOTE,
                )
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
            Text(CompetitionFormat.CANCEL, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
        }
    }
}
