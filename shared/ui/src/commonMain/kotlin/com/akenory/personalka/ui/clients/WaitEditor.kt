package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.WaitChoices
import com.akenory.personalka.data.clients.want
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitProblem
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.DiaryFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.TimeRange
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.training.TimePickerPanel
import com.akenory.personalka.ui.training.VoiceInput
import com.akenory.personalka.ui.training.appendHeard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

const val WAIT_SEARCH_TAG = "wait-search"
const val WAIT_SERIES_TAG = "wait-series"
const val WAIT_ONE_OFF_TAG = "wait-one-off"
const val WAIT_NOTE_TAG = "wait-note"
const val WAIT_NEW_LAST_TAG = "wait-new-last"
const val WAIT_NEW_FIRST_TAG = "wait-new-first"
fun waitClientTag(id: String) = "wait-client-$id"
fun waitDayTag(day: DayOfWeek) = "wait-day-${day.name}"
fun waitRangeTag(range: TimeRange) = "wait-range-${range.start.hour}"
fun waitPlaceTag(id: String?) = "wait-place-${id ?: "any"}"
fun waitTypeTag(id: String) = "wait-type-$id"

/** Сколько клиентов чипами над поиском: выбранный и первые «Не записаны дальше». */
private const val CLIENT_CHIPS = 3

/**
 * «+ В ожидание» и «Изменить» записи листа ожидания (5.8, 4.16, макет ClWaitAdd): клиент чипом или поиском, нет
 * в списке — «Новый клиент» (фамилия, имя, телефон); «Постоянное время» и/или «Разово» (у разового — срок «до …»);
 * дни недели; время начала чипами «Утро», «День», «Вечер» или «Точнее…»; место (по умолчанию любое) и тип
 * (по умолчанию первый — ПТ); заметка с голосовым вводом. Одна запись на клиента: у кого запись уже есть, та
 * и меняется.
 *
 * @param entryId `null` — новая запись; [clientId] — клиент, уже выбранный: «В лист ожидания…» из его карточки.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaitEditor(
    entryId: String?,
    clientId: String?,
    rows: List<ClientRow>,
    followup: Followup,
    clients: ClientRepository,
    nav: ClientNav,
    messages: MessageState,
    scope: CoroutineScope,
    today: LocalDate,
    voice: VoiceInput?,
) {
    val colors = PersonalkaTheme.colors
    val choices by remember(clients) { clients.waitlist.choices() }.collectAsState(initial = null)
    var loaded by rememberSaveable { mutableStateOf(entryId == null) }
    var client by rememberSaveable { mutableStateOf(clientId) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var newLast by rememberSaveable { mutableStateOf("") }
    var newFirst by rememberSaveable { mutableStateOf("") }
    var newPhone by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf(true) }
    var oneOff by rememberSaveable { mutableStateOf(false) }
    var until by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var ranges by rememberSaveable { mutableStateOf(listOf<String>()) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var exact by rememberSaveable { mutableStateOf(false) }
    var exactFrom by rememberSaveable { mutableStateOf("07:00") }
    var exactTo by rememberSaveable { mutableStateOf("09:00") }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // «Изменить» — поля из записи, один раз.
    LaunchedEffect(entryId) {
        if (entryId == null || loaded) return@LaunchedEffect
        val entry = clients.waitlist.entry(entryId) ?: return@LaunchedEffect
        val w = entry.want()
        client = entry.clientId
        series = w.series
        oneOff = w.oneOff
        until = w.until?.toString().orEmpty()
        days = w.days.map { it.isoDayNumber }
        ranges = w.ranges.map { it.toString() }
        place = w.locationId
        type = w.typeId
        note = w.note
        loaded = true
    }
    // Новая запись: тип по умолчанию — первый (ПТ).
    val loadedChoices = choices
    LaunchedEffect(loadedChoices, loaded) {
        if (loaded && type == null) type = loadedChoices?.types?.firstOrNull()?.id
    }

    val active = rows.filter { it.client.status == ClientStatus.ACTIVE }
    val want = WaitWant(
        series = series,
        oneOff = oneOff,
        days = days.map { DayOfWeek(it) }.toSet(),
        ranges = ranges.map { TimeRange.parse(it) },
        locationId = place,
        typeId = type,
        until = until.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        note = note,
    )
    val newReady = adding && newLast.isNotBlank() && newFirst.isNotBlank()
    val problems = WaitRules.problems(WaitDraft(client ?: "new".takeIf { newReady }, want), today)
    val shown = if (showErrors) problems else emptySet()

    fun save() {
        if (saving) return
        if (problems.isNotEmpty()) {
            showErrors = true
            return
        }
        saving = true
        scope.launch {
            val id = client ?: clients.create(ClientDraft(newLast.trim(), newFirst.trim(), contactPhone = newPhone))
            clients.waitlist.save(WaitDraft(id, want))
            messages.show(if (entryId == null) WaitFormat.SAVED else WaitFormat.CHANGED)
        }
        nav.back()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(if (entryId == null) WaitFormat.TITLE_NEW else WaitFormat.TITLE_EDIT, onBack = nav::back, close = true)
            if (!loaded || loadedChoices == null) return@Column
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Spacer(Modifier.height(Dimens.space1))
                // Клиент: выбранный и первые «Не записаны дальше» чипами, остальные — поиском.
                Column {
                    FormLabel(WaitFormat.CLIENT)
                    val chips = (listOfNotNull(client) + followup.rows.map { it.client.id }).distinct().take(CLIENT_CHIPS)
                    if (chips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                            chips.forEach { id ->
                                val row = rows.firstOrNull { it.client.id == id } ?: return@forEach
                                ChoiceChip(
                                    text = CalendarFormat.client(row.name),
                                    selected = client == id,
                                    onClick = {
                                        client = if (client == id) null else id
                                        adding = false
                                    },
                                    modifier = Modifier.testTag(waitClientTag(id)),
                                )
                            }
                        }
                    }
                    if (entryId == null) {
                        SearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = WaitFormat.SEARCH,
                            modifier = Modifier.padding(top = Dimens.space2).testTag(WAIT_SEARCH_TAG),
                        )
                        if (query.isNotBlank() && !adding) {
                            val found = active.filter { ClientRules.matches(query, it.name) }.sortedBy { ClientRules.sortKey(it.name) }.take(5)
                            found.forEach { row ->
                                PersonRow(
                                    title = row.name.short,
                                    subtitle = row.subtitle(today),
                                    selected = client == row.client.id,
                                    onClick = {
                                        client = row.client.id
                                        query = ""
                                    },
                                    modifier = Modifier.testTag(waitClientTag(row.client.id)),
                                )
                            }
                            if (found.isEmpty()) {
                                SecondaryButton(
                                    WaitFormat.newClient(query),
                                    {
                                        val draft = prefillDraft(query)
                                        newLast = draft.lastName
                                        newFirst = draft.firstName
                                        adding = true
                                        client = null
                                    },
                                    modifier = Modifier.padding(top = Dimens.space2),
                                    icon = PIcons.PersonAdd,
                                )
                            }
                        }
                    }
                    if (adding) {
                        val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                        Column(Modifier.padding(top = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                            Field(WaitFormat.NEW_LAST, newLast, { newLast = it }, keyboardOptions = words, tag = WAIT_NEW_LAST_TAG)
                            Field(WaitFormat.NEW_FIRST, newFirst, { newFirst = it }, keyboardOptions = words, tag = WAIT_NEW_FIRST_TAG)
                            Field(
                                WaitFormat.NEW_PHONE,
                                newPhone,
                                { newPhone = it },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                            )
                        }
                    }
                    if (WaitProblem.NO_CLIENT in shown) ProblemText(WaitFormat.problem(WaitProblem.NO_CLIENT))
                }

                Column {
                    FormLabel(WaitFormat.KIND)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        ChoiceChip(WaitFormat.SERIES, series, { series = !series }, Modifier.testTag(WAIT_SERIES_TAG), toggle = true)
                        ChoiceChip(WaitFormat.ONE_OFF, oneOff, { oneOff = !oneOff }, Modifier.testTag(WAIT_ONE_OFF_TAG), toggle = true)
                    }
                    if (WaitProblem.NO_KIND in shown) ProblemText(WaitFormat.problem(WaitProblem.NO_KIND))
                    if (oneOff) {
                        Spacer(Modifier.height(Dimens.space2))
                        FormLabel(WaitFormat.UNTIL)
                        PickBox(
                            text = want.until?.let { TrainingFormat.date(it) } ?: WaitFormat.NO_UNTIL,
                            icon = PIcons.CalendarMonth,
                            onClick = { picking = "until" },
                            modifier = Modifier.fillMaxWidth(),
                            error = WaitFormat.problem(WaitProblem.UNTIL_PAST).takeIf { WaitProblem.UNTIL_PAST in shown },
                        )
                    }
                }

                Column {
                    FormLabel(WaitFormat.DAYS)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DateFormat.WEEK.forEach { day ->
                            val on = day.isoDayNumber in days
                            ChoiceChip(
                                text = DateFormat.weekdayShort(day),
                                selected = on,
                                onClick = { days = if (on) days - day.isoDayNumber else days + day.isoDayNumber },
                                modifier = Modifier.weight(1f).testTag(waitDayTag(day)),
                                toggle = true,
                                fill = true,
                            )
                        }
                    }
                }

                Column {
                    FormLabel(WaitFormat.TIME)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        WaitRules.PRESETS.forEach { range ->
                            val on = range.toString() in ranges
                            ChoiceChip(
                                text = WaitFormat.preset(range),
                                selected = on,
                                onClick = { ranges = if (on) ranges - range.toString() else ranges + range.toString() },
                                modifier = Modifier.testTag(waitRangeTag(range)),
                                toggle = true,
                            )
                        }
                        // Свои промежутки — чипами со своим временем; нажатие убирает.
                        ranges.map { TimeRange.parse(it) }.filterNot { WaitRules.isPreset(it) }.forEach { range ->
                            ChoiceChip(WaitFormat.range(range), true, { ranges = ranges - range.toString() }, toggle = true)
                        }
                        ChoiceChip(WaitFormat.MORE_EXACT, false, { exact = true })
                    }
                }

                Column {
                    FormLabel(WaitFormat.PLACE)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        ChoiceChip(WaitFormat.ANY_PLACE, place == null, { place = null }, Modifier.testTag(waitPlaceTag(null)))
                        loadedChoices.places.forEach { p ->
                            ChoiceChip(p.shortName, place == p.id, { place = p.id }, Modifier.testTag(waitPlaceTag(p.id)))
                        }
                    }
                }

                Column {
                    FormLabel(WaitFormat.TYPE)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        loadedChoices.types.forEach { t ->
                            ChoiceChip(t.shortName, type == t.id, { type = t.id }, Modifier.testTag(waitTypeTag(t.id)))
                        }
                    }
                    if (WaitProblem.NO_TYPE in shown) ProblemText(WaitFormat.problem(WaitProblem.NO_TYPE))
                }

                val mic: (@Composable () -> Unit)? = voice?.let { v ->
                    @Composable {
                        IconButton(onClick = { v.listen { heard -> note = appendHeard(note, heard) } }) {
                            Icon(PIcons.Mic, contentDescription = DiaryFormat.VOICE, tint = colors.textMuted)
                        }
                    }
                }
                Field(
                    label = WaitFormat.NOTE,
                    value = note,
                    onValueChange = { note = it },
                    placeholder = WaitFormat.NOTE_HINT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    tag = WAIT_NOTE_TAG,
                    singleLine = false,
                    trailing = mic,
                )
                Spacer(Modifier.height(Dimens.space4))
            }
            BottomAction(WaitFormat.SAVE, onClick = ::save)
        }

        if (exact) {
            val from = LocalTime.parse(exactFrom)
            val to = LocalTime.parse(exactTo)
            Panel(WaitFormat.EXACT_TITLE, onDismiss = { exact = false }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    Column(Modifier.weight(1f)) {
                        FormLabel(WaitFormat.EXACT_FROM)
                        PickBox(DateFormat.time(from), PIcons.Schedule, { picking = "from" }, Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f)) {
                        FormLabel(WaitFormat.EXACT_TO)
                        PickBox(DateFormat.time(to), PIcons.Schedule, { picking = "to" }, Modifier.fillMaxWidth())
                    }
                }
                PanelActions(
                    primary = WaitFormat.EXACT_ADD,
                    onPrimary = {
                        ranges = (ranges + TimeRange(from, to).toString()).distinct()
                        exact = false
                    },
                    secondary = "Отмена",
                    onSecondary = { exact = false },
                    primaryEnabled = from < to,
                )
            }
        }
        when (picking) {
            "until" -> DatePickerPanel(
                initial = want.until ?: today.plus(14, DateTimeUnit.DAY),
                onPick = {
                    until = it.toString()
                    picking = null
                },
                onDismiss = { picking = null },
                onClear = {
                    until = ""
                    picking = null
                },
            )
            "from" -> TimePickerPanel(LocalTime.parse(exactFrom), {
                exactFrom = it.toString()
                picking = null
            }, { picking = null }, title = WaitFormat.EXACT_FROM)
            "to" -> TimePickerPanel(LocalTime.parse(exactTo), {
                exactTo = it.toString()
                picking = null
            }, { picking = null }, title = WaitFormat.EXACT_TO)
        }
    }
}

/** Ошибка под группой чипов — словами, цветом error. */
@Composable
private fun ProblemText(text: String) {
    Text(text, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.error)
}
