package com.akenory.personalka.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.restrictions.RestrictionRepository
import com.akenory.personalka.data.training.EditScope
import com.akenory.personalka.data.training.FormClient
import com.akenory.personalka.data.training.FormGroup
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.data.training.StoredSeries
import com.akenory.personalka.data.training.StoredTraining
import com.akenory.personalka.data.training.TrainingFormData
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.reference.TypePresets
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.series.ClientScheduleRules
import com.akenory.personalka.domain.training.FormField
import com.akenory.personalka.domain.training.FormType
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.domain.training.TrainingFormRules
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.clients.SearchField
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.shade
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

const val TRAINING_FORM_TAG = "training-form"
const val FORM_SEARCH_TAG = "form-search"
const val FORM_DATE_TAG = "form-date"
const val FORM_TIME_TAG = "form-time"
const val FORM_UNTIL_TAG = "form-until"
const val FORM_PLAN_TAG = "form-plan"
const val FORM_OWN_DURATION_TAG = "form-own-duration"
const val FORM_WEEKLY_TAG = "form-weekly"
const val FORM_MODE_TAG = "form-mode"

fun formTypeTag(id: String) = "form-type-$id"
fun formClientTag(id: String) = "form-client-$id"
fun formGroupTag(id: String) = "form-group-$id"
fun formPlaceTag(id: String) = "form-place-$id"
fun formWeekdayTag(day: DayOfWeek) = "form-weekday-$day"

/**
 * Что открыто в форме — строкой, чтобы пережить пересоздание окна: новая запись на время (из календаря)
 * или на ближайший свободный час («+»), с клиентом из карточки; изменение тренировки или дежурства, изменение
 * своего запрета, «Изменить с даты…» серии клиента.
 */
object TrainingFormKey {
    private const val NEW = "new"
    private const val EDIT = "edit:"
    private const val RESTRICTION = "restriction:"
    private const val SERIES = "series:"
    private const val CLIENT = '#'
    private const val WEEKLY = '!'

    /**
     * Новая запись. [clientId] — «+ Тренировка» и «+ Серия» в карточке клиента (5.9): клиент уже выбран;
     * [weekly] — «+ Серия»: сразу «Каждую неделю».
     */
    fun new(start: LocalDateTime? = null, clientId: String? = null, weekly: Boolean = false): String = buildString {
        append(NEW)
        if (start != null) append('@').append(start)
        if (clientId != null) append(CLIENT).append(clientId)
        if (weekly) append(WEEKLY)
    }

    /** Тренировка или дежурство: форма выбирается по типу. */
    fun edit(id: String): String = EDIT + id

    /** Свой запрет — «Изменить» в окне зоны (5.3). */
    fun restriction(id: String): String = RESTRICTION + id

    fun editId(key: String): String? = key.takeIf { it.startsWith(EDIT) }?.removePrefix(EDIT)

    fun restrictionId(key: String): String? = key.takeIf { it.startsWith(RESTRICTION) }?.removePrefix(RESTRICTION)

    /** «Изменить с даты…» серии клиента (5.9). */
    fun series(id: String): String = SERIES + id

    fun seriesId(key: String): String? = key.takeIf { it.startsWith(SERIES) }?.removePrefix(SERIES)

    fun start(key: String): LocalDateTime? =
        key.takeIf { it.startsWith("$NEW@") }?.substringAfter('@')?.substringBefore(CLIENT)?.substringBefore(WEEKLY)?.let(LocalDateTime::parse)

    fun clientId(key: String): String? =
        key.takeIf { it.startsWith(NEW) && CLIENT in it }?.substringAfter(CLIENT)?.removeSuffix(WEEKLY.toString())

    fun weekly(key: String): Boolean = key.startsWith(NEW) && key.endsWith(WEEKLY)
}

/** Что создаёт форма — переключатель «Тренировка / Дежурство / Запрет» вверху новой записи (5.5, шаг 1.13а). */
enum class FormMode(val label: String) {
    TRAINING(TrainingFormat.MODE_TRAINING),
    DUTY(TrainingFormat.MODE_DUTY),
    RESTRICTION(TrainingFormat.MODE_RESTRICTION),
}

/**
 * Форма тренировки, дежурства и запрета (ТЗ 5.5, макеты «Новая тренировка» и «Запрет»): нижняя панель почти
 * во весь экран, «Сохранить» прибита к низу. У новой записи вверху переключатель «Тренировка / Дежурство /
 * Запрет» — время из места нажатия переходит в каждую форму, а недописанное в одной не теряется при переходе
 * в другую. Изменение открывает форму того, что меняют, без переключателя. Дежурства в переключателе нет,
 * если у тренера нет типа дежурства (10.7).
 *
 * Форма стоит поверх всего приложения на одном месте дерева: выбранное и недописанное переживает сложение Fold.
 *
 * @param key [TrainingFormKey].
 * @param scope записи доживают до конца, даже когда форма уже закрыта.
 */
@Composable
fun TrainingFormSheet(
    key: String,
    forms: TrainingRepository,
    restrictions: RestrictionRepository,
    clients: ClientRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val editId = TrainingFormKey.editId(key)
    val restrictionId = TrainingFormKey.restrictionId(key)
    val seriesId = TrainingFormKey.seriesId(key)
    val data by remember(forms) { forms.form() }.collectAsState(initial = null)
    var stored by remember(key) { mutableStateOf<StoredTraining?>(null) }
    var storedRestriction by remember(key) { mutableStateOf<RestrictionDraft?>(null) }
    var storedSeries by remember(key) { mutableStateOf<StoredSeries?>(null) }
    // Изменение — запись как есть; её уже нет (удалили на другом телефоне) — форма закрывается.
    LaunchedEffect(key) {
        if (editId != null) {
            stored = forms.stored(editId)
            if (stored == null) onClose()
        } else if (restrictionId != null) {
            storedRestriction = restrictions.stored(restrictionId)
            if (storedRestriction == null) onClose()
        } else if (seriesId != null) {
            storedSeries = forms.storedSeries(seriesId)
            if (storedSeries == null) onClose()
        }
    }
    var picked by rememberSaveable(key) { mutableStateOf(FormMode.TRAINING.name) }
    val isNew = editId == null && restrictionId == null && seriesId == null
    val modes = listOfNotNull(FormMode.TRAINING, FormMode.DUTY.takeIf { data?.duty != null }, FormMode.RESTRICTION)
    val mode = when {
        restrictionId != null -> FormMode.RESTRICTION
        stored?.isDuty == true -> FormMode.DUTY
        editId != null -> FormMode.TRAINING
        else -> modes.firstOrNull { it.name == picked } ?: FormMode.TRAINING
    }
    val switch: @Composable () -> Unit = {
        if (isNew && data != null) {
            Segmented(
                options = modes,
                selected = mode,
                label = { it.label },
                onSelect = { picked = it.name },
                modifier = Modifier.padding(start = Dimens.space4, end = Dimens.space4, bottom = Dimens.space2).testTag(FORM_MODE_TAG),
            )
        }
    }
    if ((editId != null && stored == null) || (restrictionId != null && storedRestriction == null) || (seriesId != null && storedSeries == null)) {
        FormFrame(title = "", onClose = onClose, switch = {}, ready = false, onSave = {}) {}
        return
    }
    // Каждая форма помнит своё: «Запрет» и обратно не сбрасывает выбранное в «Тренировке».
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(mode.name) {
        when (mode) {
            FormMode.TRAINING -> TrainingFields(key, editId, stored, storedSeries, data, forms, clients, now, messages, scope, onClose, switch)
            FormMode.DUTY -> DutyFields(key, editId, stored, data, forms, messages, scope, onClose, switch)
            FormMode.RESTRICTION -> RestrictionFields(key, restrictionId, storedRestriction, data, restrictions, forms, messages, scope, onClose, switch)
        }
    }
}

/**
 * Рамка формы: затемнение, панель с «✕» и заголовком, переключатель, поля с прокруткой и «Сохранить» внизу;
 * [panels] — окна поверх (выбор даты, вопрос для серии, «Поставить всё равно?»). Нажатие мимо панели форму
 * не закрывает: недописанное не должно теряться от случайного касания.
 *
 * @param ready данные загружены: до этого полей и «Сохранить» нет.
 */
@Composable
internal fun FormFrame(
    title: String,
    onClose: () -> Unit,
    switch: @Composable () -> Unit,
    ready: Boolean,
    onSave: () -> Unit,
    panels: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val wide = LocalWide.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .imePadding()
                .padding(top = 40.dp)
                .then(if (wide) Modifier.widthIn(max = 600.dp) else Modifier)
                .fillMaxWidth()
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.bg)
                .testTag(TRAINING_FORM_TAG),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = Dimens.space2), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.outline))
            }
            TopBar(title, onBack = onClose, close = true)
            switch()
            if (!ready) {
                Spacer(Modifier.weight(1f))
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.space4),
                ) {
                    content()
                    Spacer(Modifier.height(Dimens.space4))
                }
            }
            BottomAction(TrainingFormat.SAVE, onClick = onSave, enabled = ready)
        }
        panels()
    }
}

/**
 * Форма тренировки (макет «Новая тренировка»): тип (последний первым), клиент из недавних или поиском, место, когда,
 * длительность, повтор, план. Новая на пустом месте — за 3 нажатия: удержание → клиент → «Сохранить» (10.7).
 * Из карточки клиента клиент уже выбран, у «+ Серия» — «Каждую неделю».
 *
 * «Изменить с даты…» ([series], 5.9) — та же форма без плана: «с даты», дни недели и всё, что меняет серия (4.4);
 * конец серии меняет «Завершить».
 *
 * Перед записью — ответы, которых не хватает: «Только эту / Эту и следующие» у тренировки серии (4.4),
 * «Поставить всё равно?» под запретом (4.5); потом — сообщение с «Отменить» (5.20).
 */
@Composable
private fun TrainingFields(
    key: String,
    editId: String?,
    stored: StoredTraining?,
    series: StoredSeries?,
    data: TrainingFormData?,
    forms: TrainingRepository,
    clients: ClientRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    switch: @Composable () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val copied by forms.copied.collectAsState()
    var draft by rememberSaveable(key, stateSaver = DraftSaver) { mutableStateOf<TrainingDraft?>(null) }
    var keepDuration by rememberSaveable(key) { mutableStateOf(editId != null || series != null) }
    var keepPlace by rememberSaveable(key) { mutableStateOf(editId != null || series != null) }
    var ownDuration by rememberSaveable(key) { mutableStateOf(false) }
    var ownText by rememberSaveable(key) { mutableStateOf("") }
    var showErrors by rememberSaveable(key) { mutableStateOf(false) }
    var query by rememberSaveable(key) { mutableStateOf("") }
    var moreTypes by rememberSaveable(key) { mutableStateOf(false) }
    var panel by rememberSaveable(key) { mutableStateOf<String?>(null) }

    // Новая — тип, место и длительность по умолчанию; изменение — тренировка как есть.
    LaunchedEffect(key, data != null, stored) {
        val loaded = data ?: return@LaunchedEffect
        val kept = stored?.draft ?: series?.draft
        if (editId != null || series != null) {
            val s = kept ?: return@LaunchedEffect
            if (draft == null) {
                draft = s
                if (s.durationMin !in ReferenceRules.DURATION_CHIPS) {
                    ownDuration = true
                    ownText = s.durationMin.toString()
                }
            }
        } else if (draft == null) {
            val type = loaded.types.firstOrNull()
            val minutes = type?.defaultDurationMin ?: TypePresets.DEFAULT_DURATION
            val start = TrainingFormKey.start(key) ?: forms.suggestStart(minutes)
            draft = TrainingDraft(
                start = start,
                typeId = type?.id,
                clientIds = listOfNotNull(TrainingFormKey.clientId(key)),
                locationId = type?.let { loaded.lastPlaceByType[it.id] } ?: loaded.places.firstOrNull()?.id,
                durationMin = minutes,
                repeat = if (TrainingFormKey.weekly(key)) Repeat(setOf(start.date.dayOfWeek)) else null,
            )
        }
    }

    val form = data ?: TrainingFormData()
    val current = draft
    val type = form.types.firstOrNull { it.id == current?.typeId }
    val status = stored?.status ?: TrainingStatus.PLANNED
    val marked = stored?.marked ?: false
    val problems = if (showErrors && current != null) formProblems(current, type, now, status, marked, series) else emptyMap()

    fun update(change: (TrainingDraft) -> TrainingDraft) {
        draft = draft?.let(change)
    }

    fun save(answer: EditScope? = null, confirmed: Boolean = false) {
        val d = draft ?: return
        showErrors = true
        if (formProblems(d, type, now, status, marked, series).isNotEmpty()) return
        scope.launch {
            val result = when {
                series != null -> forms.changeFrom(series.id, d, confirmed)
                editId != null -> forms.update(editId, d, answer, confirmed)
                else -> forms.create(d, confirmed)
            }
            when (result) {
                is SaveResult.Saved -> {
                    onClose()
                    messages.showUndo(result.message) { scope.launch { forms.undo(result.undo) } }
                }
                is SaveResult.AskScope -> panel = Panels.scope(result.date, result.question)
                is SaveResult.Confirm -> panel = Panels.conflict(answer, result.conflicts)
                is SaveResult.Invalid -> {
                    // Пусто — тренировки уже нет (удалили на другом телефоне): закрыть форму.
                    if (result.problems.isEmpty()) onClose()
                }
            }
        }
    }

    fun select(client: FormClient) {
        val limit = type?.let(TrainingFormRules::clientLimit)
        update { it.copy(clientIds = TrainingFormRules.toggleClient(it.clientIds, client.id, limit), groupId = null) }
        query = ""
    }

    fun createClient(name: ClientName) {
        scope.launch {
            val id = clients.create(ClientDraft(name.lastName, name.firstName, name.middleName.orEmpty()))
            val limit = type?.let(TrainingFormRules::clientLimit)
            update { it.copy(clientIds = TrainingFormRules.toggleClient(it.clientIds, id, limit), groupId = null) }
        }
        query = ""
    }

    FormFrame(
        title = when {
            series != null -> TrainingFormat.SERIES_TITLE
            editId != null -> TrainingFormat.EDIT_TITLE
            else -> TrainingFormat.NEW_TITLE
        },
        onClose = onClose,
        switch = switch,
        ready = current != null && data != null,
        onSave = { save() },
        panels = {
            val open = panel
            val d = current
            if (open != null && d != null) {
                when {
                    open == Panels.DATE -> DatePickerPanel(
                        initial = d.start.date,
                        onPick = { date ->
                            panel = null
                            // У серии дни выбраны сами по себе: новая дата их не меняет.
                            update { if (series != null) it.copy(start = LocalDateTime(date, it.start.time)) else it.onDate(date) }
                        },
                        onDismiss = { panel = null },
                    )
                    open == Panels.TIME -> TimePickerPanel(
                        initial = d.start.time,
                        onPick = { time ->
                            panel = null
                            update { it.copy(start = LocalDateTime(it.start.date, time)) }
                        },
                        onDismiss = { panel = null },
                    )
                    open == Panels.UNTIL -> DatePickerPanel(
                        initial = d.repeat?.until ?: d.start.date,
                        onPick = { date ->
                            panel = null
                            update { it.copy(repeat = it.repeat?.copy(until = date)) }
                        },
                        onDismiss = { panel = null },
                        onClear = {
                            panel = null
                            update { it.copy(repeat = it.repeat?.copy(until = null)) }
                        },
                    )
                    Panels.isScope(open) -> ScopePanel(
                        title = TrainingFormat.SCOPE_EDIT_TITLE,
                        question = Panels.scopeQuestion(open),
                        date = Panels.scopeDate(open),
                        back = TrainingFormat.BACK_TO_EDIT,
                        onOnlyThis = {
                            panel = null
                            save(EditScope.ONLY_THIS)
                        },
                        onFollowing = {
                            panel = null
                            save(EditScope.FOLLOWING)
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.isConflict(open) -> ConflictPanel(
                        conflicts = Panels.conflicts(open),
                        onDismiss = { panel = null },
                        onPlace = {
                            val answer = Panels.conflictScope(open)
                            panel = null
                            save(answer, confirmed = true)
                        },
                    )
                    Panels.isSimilar(open) -> {
                        val similar = form.clients.firstOrNull { it.id == Panels.similarId(open) }
                        if (similar != null) {
                            SimilarPanel(
                                client = similar,
                                onPick = {
                                    panel = null
                                    select(similar)
                                },
                                onCreate = {
                                    val text = Panels.similarText(open)
                                    panel = null
                                    newClientName(text)?.let(::createClient)
                                },
                                onDismiss = { panel = null },
                            )
                        }
                    }
                }
            }
        },
    ) {
        if (current == null) return@FormFrame
        val paste = copied
        if (editId == null && series == null && paste != null) {
            SecondaryButton(
                text = TrainingFormat.PASTE,
                onClick = {
                    update {
                        it.copy(
                            typeId = paste.typeId,
                            locationId = paste.locationId,
                            durationMin = paste.durationMin,
                            clientIds = paste.clientIds,
                            groupId = paste.groupId,
                        )
                    }
                    keepDuration = true
                    keepPlace = true
                    ownDuration = paste.durationMin !in ReferenceRules.DURATION_CHIPS
                    ownText = paste.durationMin.toString()
                },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2),
                icon = PIcons.ContentCopy,
            )
        }

        // Тип: пять чипов, остальные — под «Ещё N»; выбранный виден всегда.
        SectionLabel("Тип")
        val index = form.types.indexOfFirst { it.id == current.typeId }
        val showAll = moreTypes || index >= TrainingFormRules.TYPE_CHIPS
        // Точка типа — цвет выбранного места его оттенком: так блок будет выглядеть в календаре.
        val placeColor = PlaceColor.of(form.places.firstOrNull { it.id == current.locationId }?.color)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            form.types.take(if (showAll) form.types.size else TrainingFormRules.TYPE_CHIPS).forEach { t ->
                ChoiceChip(
                    text = t.name,
                    selected = t.id == current.typeId,
                    onClick = {
                        if (t.id == current.typeId) return@ChoiceChip
                        update { d ->
                            val next = TrainingFormRules.withType(d, t, keepDuration)
                            if (keepPlace) next else next.copy(locationId = form.lastPlaceByType[t.id] ?: next.locationId)
                        }
                        if (!keepDuration) ownDuration = false
                    },
                    modifier = Modifier.testTag(formTypeTag(t.id)),
                    dot = placeColor.shade(colors.isDark, t.shade),
                )
            }
            if (!showAll && form.types.size > TrainingFormRules.TYPE_CHIPS) {
                ChoiceChip(
                    text = TrainingFormat.moreTypes(form.types.size - TrainingFormRules.TYPE_CHIPS),
                    selected = false,
                    onClick = { moreTypes = true },
                    icon = PIcons.ArrowDropDown,
                )
            }
        }
        problems[FormField.TYPE]?.let { ErrorText(it) }

        if (type != null) {
            People(
                draft = current,
                type = type,
                form = form,
                query = query,
                error = problems[FormField.CLIENTS],
                onQuery = { query = it },
                onClient = ::select,
                onGroup = { group ->
                    update { d ->
                        if (d.groupId == group.id) {
                            d.copy(groupId = null)
                        } else {
                            d.copy(groupId = group.id, clientIds = emptyList(), locationId = if (keepPlace) d.locationId else group.locationId)
                        }
                    }
                },
                onNewClient = { name ->
                    val similar = ClientRules.similar(name, form.clients) { it.name }.firstOrNull()
                    if (similar != null) panel = Panels.similar(similar.id, query) else createClient(name)
                },
            )
        }

        SectionLabel("Место")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            form.places.forEach { place ->
                ChoiceChip(
                    text = place.shortName,
                    selected = place.id == current.locationId,
                    onClick = {
                        keepPlace = true
                        update { it.copy(locationId = place.id) }
                    },
                    modifier = Modifier.testTag(formPlaceTag(place.id)),
                )
            }
        }
        problems[FormField.PLACE]?.let { ErrorText(it) }

        SectionLabel("Когда")
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            PickBox(
                text = if (series != null) TrainingFormat.fromDate(current.start.date) else TrainingFormat.date(current.start.date),
                icon = PIcons.CalendarMonth,
                onClick = { panel = Panels.DATE },
                modifier = Modifier.weight(1f).testTag(FORM_DATE_TAG),
                error = problems[FormField.DATE],
            )
            PickBox(
                text = TrainingFormat.timeRange(current.start, TrainingTime.end(current.start, current.durationMin)),
                icon = PIcons.Schedule,
                onClick = { panel = Panels.TIME },
                modifier = Modifier.weight(1f).testTag(FORM_TIME_TAG),
                error = problems[FormField.TIME],
            )
        }
        problems[FormField.DATE]?.let { ErrorText(it) }
        problems[FormField.TIME]?.let { ErrorText(it) }

        SectionLabel("Длительность, мин")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            ReferenceRules.DURATION_CHIPS.forEach { minutes ->
                ChoiceChip(
                    text = minutes.toString(),
                    selected = !ownDuration && current.durationMin == minutes,
                    onClick = {
                        ownDuration = false
                        keepDuration = true
                        update { it.copy(durationMin = minutes) }
                    },
                )
            }
            ChoiceChip(
                text = TrainingFormat.OWN_DURATION,
                selected = ownDuration || current.durationMin !in ReferenceRules.DURATION_CHIPS,
                onClick = {
                    ownDuration = true
                    keepDuration = true
                    ownText = current.durationMin.toString()
                },
            )
        }
        if (ownDuration) {
            Field(
                label = "Своя длительность, мин",
                value = ownText,
                onValueChange = { text ->
                    ownText = text.filter { it.isDigit() }.take(3)
                    ownText.toIntOrNull()?.let { minutes -> update { it.copy(durationMin = minutes) } }
                },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2),
                error = problems[FormField.DURATION],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                tag = FORM_OWN_DURATION_TAG,
            )
        } else {
            problems[FormField.DURATION]?.let { ErrorText(it) }
        }

        SectionLabel("Повтор")
        val seriesRepeat = stored?.seriesRepeat
        val weekdays = current.repeat?.weekdays
        if (series != null && weekdays != null) {
            WeekdayRow(weekdays, problems[FormField.WEEKDAYS]) { days -> update { it.copy(repeat = it.repeat?.copy(weekdays = days)) } }
            return@FormFrame
        }
        if (seriesRepeat != null) {
            // Повтор серии меняет «Эту и следующие» и «Изменить с даты» (4.4), а не это поле.
            Text(
                TrainingFormat.seriesRepeat(seriesRepeat.weekdays, seriesRepeat.until),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = colors.text,
            )
        } else {
            RepeatChoice(
                draft = current,
                weekdaysError = problems[FormField.WEEKDAYS],
                untilError = problems[FormField.UNTIL],
                onRepeat = { repeat -> update { it.copy(repeat = repeat) } },
                onUntil = { panel = Panels.UNTIL },
            )
        }

        Field(
            label = "План",
            value = current.plan,
            onValueChange = { text -> update { it.copy(plan = text) } },
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.space5),
            tag = FORM_PLAN_TAG,
            placeholder = "Необязательно",
            singleLine = false,
        )
    }
}

/**
 * Клиенты по типу (5.5): ПТ и СПТ — ровно один, сплит — один или два (вторым первыми — родственники первого, 4.17),
 * группа — группа или разовый список детей. Над поиском — выбранные и недавние, под ним — найденные
 * и «Новый клиент «…»».
 */
@Composable
private fun People(
    draft: TrainingDraft,
    type: FormType,
    form: TrainingFormData,
    query: String,
    error: String?,
    onQuery: (String) -> Unit,
    onClient: (FormClient) -> Unit,
    onGroup: (FormGroup) -> Unit,
    onNewClient: (ClientName) -> Unit,
) {
    val byId = form.clients.associateBy { it.id }
    val group = type.kind == TrainingKind.GROUP
    if (group) {
        SectionLabel("Группа")
        // Группы этого типа и недавние — первыми.
        val groups = form.groups.sortedWith(
            compareBy<FormGroup>(
                { it.typeId != type.id },
                { form.recentGroupIds.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            ),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            groups.forEach { g ->
                ChoiceChip(g.name, selected = g.id == draft.groupId, onClick = { onGroup(g) }, modifier = Modifier.testTag(formGroupTag(g.id)))
            }
        }
        if (draft.groupId != null) {
            error?.let { ErrorText(it) }
            return
        }
    }
    val limit = TrainingFormRules.clientLimit(type)
    SectionLabel(
        when {
            group -> if (form.groups.isEmpty()) "Дети" else "Или дети на раз"
            limit == 1 -> "Клиент"
            else -> "Клиенты"
        },
    )
    val selected = draft.clientIds.mapNotNull { byId[it] }
    // Вторым в сплит первыми предлагаются родственники первого.
    val first = draft.clientIds.firstOrNull()
    val relatives = if (type.kind == TrainingKind.SPLIT && first != null && draft.clientIds.size == 1) form.relatives[first].orEmpty() else emptyList()
    val suggested = (relatives + form.recentClientIds).distinct()
        .filter { it !in draft.clientIds }
        .mapNotNull { byId[it] }
        .take(TrainingFormRules.RECENT_CLIENTS)
    if (selected.isNotEmpty() || suggested.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            (selected + suggested).forEach { c ->
                ChoiceChip(
                    text = c.label,
                    selected = c.id in draft.clientIds,
                    onClick = { onClient(c) },
                    modifier = Modifier.testTag(formClientTag(c.id)),
                    toggle = limit != 1,
                )
            }
        }
    }
    SearchField(
        value = query,
        onValueChange = onQuery,
        placeholder = "Найти или добавить клиента",
        modifier = Modifier.padding(top = Dimens.space2).testTag(FORM_SEARCH_TAG),
    )
    if (query.isNotBlank()) {
        val found = form.clients.filter { it.id !in draft.clientIds && ClientRules.matches(query, it.name) }.take(TrainingFormRules.FOUND_CLIENTS)
        Column(Modifier.fillMaxWidth().padding(top = Dimens.space2).clip(MaterialTheme.shapes.medium)) {
            found.forEach { c ->
                PersonRow(title = c.name.full(), subtitle = null, onClick = { onClient(c) })
            }
            newClientName(query)?.let { name ->
                PersonRow(title = TrainingFormat.newClient(query), subtitle = null, onClick = { onNewClient(name) }, leading = PIcons.PersonAdd)
            }
        }
    }
    error?.let { ErrorText(it) }
}

/** «Не повторять / Каждую неделю», дни недели (по умолчанию — день даты) и «до — бессрочно» или «до даты». */
@Composable
internal fun RepeatChoice(
    draft: TrainingDraft,
    weekdaysError: String?,
    untilError: String?,
    onRepeat: (Repeat?) -> Unit,
    onUntil: () -> Unit,
) {
    val repeat = draft.repeat
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ChoiceChip(TrainingFormat.NO_REPEAT, selected = repeat == null, onClick = { onRepeat(null) })
        ChoiceChip(
            TrainingFormat.WEEKLY,
            selected = repeat != null,
            onClick = { if (repeat == null) onRepeat(Repeat(setOf(draft.start.date.dayOfWeek))) },
            modifier = Modifier.testTag(FORM_WEEKLY_TAG),
        )
    }
    if (repeat == null) return
    WeekdayRow(repeat.weekdays, weekdaysError) { days -> onRepeat(repeat.copy(weekdays = days)) }
    PickBox(
        text = repeat.until?.let(TrainingFormat::until) ?: TrainingFormat.FOREVER,
        icon = PIcons.CalendarMonth,
        onClick = onUntil,
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2).testTag(FORM_UNTIL_TAG),
        error = untilError,
    )
    untilError?.let { ErrorText(it) }
}

/** Дни недели пн–вс чипами во всю ширину; нажатие добавляет или снимает день. */
@Composable
internal fun WeekdayRow(weekdays: Set<DayOfWeek>, error: String?, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DateFormat.WEEK.forEach { day ->
            ChoiceChip(
                text = DateFormat.weekdayShort(day),
                selected = day in weekdays,
                onClick = { onChange(if (day in weekdays) weekdays - day else weekdays + day) },
                modifier = Modifier.weight(1f).testTag(formWeekdayTag(day)),
                toggle = true,
                fill = true,
            )
        }
    }
    error?.let { ErrorText(it) }
}

/** Поле, которое открывает выбор: значок и значение, рамка как у поля ввода. */
@Composable
internal fun PickBox(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, error: String? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .heightIn(min = Dimens.field)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .border(if (error != null) 2.dp else 1.dp, if (error != null) colors.error else colors.outline, MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = text
                if (error != null) this.error(error)
            }
            .padding(horizontal = Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.space2))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text, maxLines = 1)
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.error)
}

/** Похожий клиент уже есть (5.8): выбрать его или всё же создать нового. */
@Composable
private fun SimilarPanel(client: FormClient, onPick: () -> Unit, onCreate: () -> Unit, onDismiss: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(title = "Похожий клиент уже есть", onDismiss = onDismiss) {
        Text(client.name.full(), style = MaterialTheme.typography.labelLarge, color = colors.text)
        Text("Это тот же клиент?", style = MaterialTheme.typography.bodyLarge, color = colors.text)
        PanelActions(primary = "Да, выбрать", onPrimary = onPick, secondary = "Нет, создать нового", onSecondary = onCreate)
    }
}

private fun ClientName.full(): String = listOfNotNull(lastName, firstName, middleName?.takeIf { it.isNotBlank() }).joinToString(" ")

/**
 * Ошибки формы; у «Изменить с даты…» конец серии не проверяется (его меняет «Завершить»), зато дата —
 * не в прошлом и не позже конца серии.
 */
private fun formProblems(
    draft: TrainingDraft,
    type: FormType?,
    now: LocalDateTime,
    status: TrainingStatus,
    marked: Boolean,
    series: StoredSeries?,
): Map<FormField, String> {
    if (series == null) return TrainingFormRules.problems(draft, type, now, status, marked)
    val problems = TrainingFormRules.problems(draft.copy(repeat = draft.repeat?.copy(until = null)), type, now)
    val date = ClientScheduleRules.fromProblem(draft.start.date, now.date, series.validTo) ?: return problems
    return problems + (FormField.DATE to date)
}

/** Другая дата; день повтора по умолчанию — день даты: сменили дату — сменился и он. */
internal fun TrainingDraft.onDate(date: LocalDate): TrainingDraft {
    val days = repeat?.let { r -> if (r.weekdays == setOf(start.date.dayOfWeek)) r.copy(weekdays = setOf(date.dayOfWeek)) else r }
    return copy(start = LocalDateTime(date, start.time), repeat = days)
}

/** Новый клиент из строки поиска: фамилия и имя, дальше — отчество; одного слова мало. */
private fun newClientName(text: String): ClientName? {
    val words = text.trim().split(' ').filter { it.isNotEmpty() }.map { w -> w.replaceFirstChar { it.uppercaseChar() } }
    if (words.size < 2) return null
    return ClientName(words[0], words[1], words.drop(2).joinToString(" ").ifEmpty { null })
}

/** Открытое окно формы — строкой, чтобы пережить пересоздание: вид и что в нём. */
internal object Panels {
    const val DATE = "date"
    const val TIME = "time"
    const val UNTIL = "until"

    /** Конец «до» у дежурства и запрета. */
    const val END = "end"
    private const val SCOPE = "scope"
    private const val CONFLICT = "conflict"
    private const val SIMILAR = "similar"
    private const val SEP = "\n"

    fun scope(date: LocalDate, question: String) = listOf(SCOPE, date.toString(), question).joinToString(SEP)
    fun isScope(text: String) = text.startsWith(SCOPE + SEP)
    fun scopeDate(text: String): LocalDate = LocalDate.parse(text.split(SEP)[1])
    fun scopeQuestion(text: String): String = text.split(SEP).drop(2).joinToString(SEP)

    fun conflict(answer: EditScope?, lines: List<String>) = (listOf(CONFLICT, answer?.name.orEmpty()) + lines).joinToString(SEP)
    fun isConflict(text: String) = text.startsWith(CONFLICT + SEP)
    fun conflictScope(text: String): EditScope? = text.split(SEP)[1].takeIf { it.isNotEmpty() }?.let(EditScope::valueOf)
    fun conflicts(text: String): List<String> = text.split(SEP).drop(2)

    fun similar(clientId: String, query: String) = listOf(SIMILAR, clientId, query).joinToString(SEP)
    fun isSimilar(text: String) = text.startsWith(SIMILAR + SEP)
    fun similarId(text: String): String = text.split(SEP)[1]
    fun similarText(text: String): String = text.split(SEP).drop(2).joinToString(SEP)
}

/** Черновик формы сохраняется списком строк: переживает пересоздание окна и выгрузку из памяти. */
internal val DraftSaver: Saver<TrainingDraft?, Any> = Saver(
    save = { d ->
        if (d == null) {
            arrayListOf<String>()
        } else {
            arrayListOf(
                d.start.toString(),
                d.typeId.orEmpty(),
                d.clientIds.joinToString(","),
                d.groupId.orEmpty(),
                d.locationId.orEmpty(),
                d.durationMin.toString(),
                d.repeat?.weekdays?.joinToString(",") { it.name } ?: "-",
                d.repeat?.until?.toString().orEmpty(),
                d.plan,
            )
        }
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val v = saved as List<String>
        if (v.isEmpty()) {
            null
        } else {
            TrainingDraft(
                start = LocalDateTime.parse(v[0]),
                typeId = v[1].ifEmpty { null },
                clientIds = v[2].split(',').filter { it.isNotEmpty() },
                groupId = v[3].ifEmpty { null },
                locationId = v[4].ifEmpty { null },
                durationMin = v[5].toInt(),
                repeat = if (v[6] == "-") null else Repeat(v[6].split(',').filter { it.isNotEmpty() }.map(DayOfWeek::valueOf).toSet(), v[7].ifEmpty { null }?.let(LocalDate::parse)),
                plan = v[8],
            )
        }
    },
)
