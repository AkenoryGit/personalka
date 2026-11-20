package com.akenory.personalka.ui.training

import com.akenory.personalka.ui.theme.PlaceGradient
import com.akenory.personalka.ui.components.PlaceChip
import com.akenory.personalka.ui.components.StepLabel
import com.akenory.personalka.ui.components.FormStep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.akenory.personalka.data.restrictions.RestrictionRepository
import com.akenory.personalka.data.restrictions.RestrictionSaveResult
import com.akenory.personalka.data.training.EditScope
import com.akenory.personalka.data.training.FormPlace
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.data.training.StoredTraining
import com.akenory.personalka.data.training.TrainingFormData
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.PersonalFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.restriction.BlockKind
import com.akenory.personalka.domain.restriction.PersonalRules
import com.akenory.personalka.domain.restriction.ReminderRepeat
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.restriction.RestrictionField
import com.akenory.personalka.domain.restriction.RestrictionFormRules
import com.akenory.personalka.domain.training.FormField
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.domain.training.TrainingFormRules
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// Формы дежурства и запрета (ТЗ 5.5, шаг 1.13а, макет «Запрет») — в той же панели, что и форма тренировки.

const val FORM_END_TAG = "form-end"
const val FORM_REASON_TAG = "form-reason"
const val FORM_ALL_PLACES_TAG = "form-all-places"
const val FORM_PERIOD_FROM_TAG = "form-period-from"
const val FORM_COVER_FOR_TAG = "form-cover-for"

/**
 * Дежурство (5.5): место, дата, время «с — до», повтор и у разового — «За кого», если тренер дежурит за коллегу. Это тренировка типа «Дежурство» — запись, серия, вопрос
 * «Только эту / Эту и следующие» и «Отменить» те же, что у тренировки; с запретами дежурство не сверяется (4.5).
 */
@Composable
internal fun DutyFields(
    key: String,
    editId: String?,
    stored: StoredTraining?,
    data: TrainingFormData?,
    forms: TrainingRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    switch: @Composable () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    var draft by rememberSaveable(key, stateSaver = DraftSaver) { mutableStateOf<TrainingDraft?>(null) }
    var showErrors by rememberSaveable(key) { mutableStateOf(false) }
    var panel by rememberSaveable(key) { mutableStateOf<String?>(null) }

    // Новое — с времени нажатия на длительность типа (4 часа), место — последнее у дежурств; изменение — как есть.
    LaunchedEffect(key, data != null, stored) {
        val loaded = data ?: return@LaunchedEffect
        if (draft != null) return@LaunchedEffect
        draft = if (editId != null) {
            stored?.draft ?: return@LaunchedEffect
        } else {
            val duty = loaded.duty
            val start = TrainingFormKey.start(key) ?: forms.suggestStart(TrainingTime.MIN_DURATION)
            TrainingFormRules.newDuty(start, duty, duty?.let { loaded.lastPlaceByType[it.id] } ?: loaded.places.firstOrNull()?.id)
        }
    }

    val current = draft
    val problems = if (showErrors && current != null) TrainingFormRules.dutyProblems(current) else emptyMap()

    fun update(change: (TrainingDraft) -> TrainingDraft) {
        draft = draft?.let(change)
    }

    fun save(answer: EditScope? = null, confirmed: Boolean = false) {
        val d = draft ?: return
        showErrors = true
        if (TrainingFormRules.dutyProblems(d).isNotEmpty()) return
        scope.launch {
            when (val result = if (editId == null) forms.create(d, confirmed) else forms.update(editId, d, answer, confirmed)) {
                is SaveResult.Saved -> {
                    onClose()
                    messages.showUndo(result.message) { scope.launch { forms.undo(result.undo) } }
                }
                is SaveResult.AskScope -> panel = Panels.scope(result.date, result.question)
                is SaveResult.Confirm -> panel = Panels.conflict(answer, result.conflicts)
                is SaveResult.Invalid -> if (result.problems.isEmpty()) onClose()
            }
        }
    }

    FormFrame(
        title = if (editId == null) TrainingFormat.NEW_DUTY_TITLE else TrainingFormat.EDIT_DUTY_TITLE,
        onClose = onClose,
        switch = switch,
        ready = current != null && data != null,
        onSave = { save() },
        panels = {
            val open = panel
            if (open != null && current != null) {
                when {
                    open == Panels.DATE -> DatePickerPanel(
                        initial = current.start.date,
                        onPick = { date ->
                            panel = null
                            update { it.onDate(date) }
                        },
                        onDismiss = { panel = null },
                    )
                    open == Panels.TIME -> TimePickerPanel(
                        initial = current.start.time,
                        title = START_TITLE,
                        onPick = { time ->
                            panel = null
                            update { TrainingFormRules.withDutyStart(it, LocalDateTime(it.start.date, time)) }
                        },
                        onDismiss = { panel = null },
                    )
                    open == Panels.END -> TimePickerPanel(
                        initial = TrainingFormRules.endTime(current),
                        title = END_TITLE,
                        onPick = { time ->
                            panel = null
                            update { TrainingFormRules.withDutyEnd(it, time) }
                        },
                        onDismiss = { panel = null },
                    )
                    open == Panels.UNTIL -> DatePickerPanel(
                        initial = current.repeat?.until ?: current.start.date,
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
                }
            }
        },
    ) {
        val form = data
        if (current == null || form == null) return@FormFrame
        // Шаг 5.18 (вариант А): дежурство — шагами «Где», «Когда», «Повтор».
        val placeShort = form.places.firstOrNull { it.id == current.locationId }?.shortName
        FormStep(1, "Где", placeShort) {
            PlaceChips(form.places, current.locationId, problems[FormField.PLACE]) { id -> update { it.copy(locationId = id) } }
        }
        FormStep(2, "Когда", TrainingFormat.date(current.start.date) + " · " + DateFormat.time(current.start.time)) {
            PickBox(
                text = TrainingFormat.date(current.start.date),
                icon = PIcons.CalendarMonth,
                onClick = { panel = Panels.DATE },
                modifier = Modifier.fillMaxWidth().testTag(FORM_DATE_TAG),
            )

            StepLabel("Время")
            TimeRow(
                start = current.start.time,
                end = TrainingFormRules.endTime(current),
                error = problems[FormField.TIME],
                onStart = { panel = Panels.TIME },
                onEnd = { panel = Panels.END },
            )


        }
        FormStep(3, "Повтор", if (current.repeat == null && stored?.seriesRepeat == null) "один раз" else "каждую неделю") {
            val seriesRepeat = stored?.seriesRepeat
            if (seriesRepeat != null) {
                // Повтор серии меняет «Эту и следующие» (4.4), а не это поле — как у тренировки.
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

            // «За кого» — у одного дежурства: подмена коллеги не повторяется каждую неделю.
            if (seriesRepeat == null && current.repeat == null) {
                Field(
                    label = ZoneFormat.COVER_FOR_LABEL,
                    value = current.coverFor,
                    onValueChange = { text -> update { it.copy(coverFor = text.take(TrainingFormRules.COVER_FOR_MAX)) } },
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.space5),
                    tag = FORM_COVER_FOR_TAG,
                    placeholder = ZoneFormat.COVER_FOR_PLACEHOLDER,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        }
    }
}

/**
 * Запрет (5.5, макет «Запрет»): место, время «с — до», причина, повтор с днями недели, период «с — до» или дата
 * разового. «Для всего клуба» нет: запретов клуба нет, каждый тренер ведёт свои (решение 28.10.2026, шаг 5.3а). После записи
 * флаг «поверх запрета» у будущих тренировок пересчитывается без окон (4.5). У своего запрета — «Удалить запрет».
 */
@Composable
internal fun RestrictionFields(
    key: String,
    restrictionId: String?,
    stored: RestrictionDraft?,
    data: TrainingFormData?,
    restrictions: RestrictionRepository,
    forms: TrainingRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    switch: @Composable () -> Unit,
) {
    var draft by rememberSaveable(key, stateSaver = RestrictionSaver) { mutableStateOf<RestrictionDraft?>(null) }
    var showErrors by rememberSaveable(key) { mutableStateOf(false) }
    var panel by rememberSaveable(key) { mutableStateOf<String?>(null) }

    // Новый — с времени нажатия на час, место не выбрано: мест может быть несколько (шаг 1.13д); изменение — как есть.
    LaunchedEffect(key, data != null, stored) {
        val loaded = data ?: return@LaunchedEffect
        if (draft != null) return@LaunchedEffect
        draft = if (restrictionId != null) {
            stored ?: return@LaunchedEffect
        } else {
            val start = TrainingFormKey.start(key) ?: forms.suggestStart(RestrictionFormRules.DEFAULT_MINUTES)
            RestrictionFormRules.new(start, null)
        }
    }

    val current = draft
    val problems = if (showErrors && current != null) RestrictionFormRules.problems(current) else emptyMap()

    fun update(change: (RestrictionDraft) -> RestrictionDraft) {
        draft = draft?.let(change)
    }

    fun save() {
        val d = draft ?: return
        showErrors = true
        if (RestrictionFormRules.problems(d).isNotEmpty()) return
        scope.launch {
            when (val result = restrictions.save(restrictionId, d)) {
                is RestrictionSaveResult.Saved -> {
                    onClose()
                    messages.showUndo(result.message) { scope.launch { restrictions.undo(result.undo) } }
                }
                is RestrictionSaveResult.Invalid -> if (result.problems.isEmpty()) onClose()
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            val undo = restrictions.delete(id)
            onClose()
            if (undo != null) messages.showUndo(TrainingFormat.RESTRICTION_DELETED) { scope.launch { restrictions.undo(undo) } }
        }
    }

    FormFrame(
        title = if (restrictionId == null) TrainingFormat.NEW_RESTRICTION_TITLE else TrainingFormat.EDIT_RESTRICTION_TITLE,
        onClose = onClose,
        switch = switch,
        ready = current != null && data != null,
        onSave = ::save,
        panels = {
            val open = panel
            if (open != null && current != null) {
                when (open) {
                    Panels.DATE -> DatePickerPanel(
                        initial = current.date,
                        onPick = { date ->
                            panel = null
                            update { d ->
                                // День повтора по умолчанию — день даты: сменили дату — сменился и он.
                                val days = if (d.weekdays == setOf(d.date.dayOfWeek)) setOf(date.dayOfWeek) else d.weekdays
                                d.copy(date = date, weekdays = days)
                            }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.TIME -> TimePickerPanel(
                        initial = current.start,
                        title = START_TITLE,
                        onPick = { time ->
                            panel = null
                            update { RestrictionFormRules.withStart(it, time) }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.END -> TimePickerPanel(
                        initial = current.end,
                        title = END_TITLE,
                        onPick = { time ->
                            panel = null
                            update { RestrictionFormRules.withEnd(it, time) }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.UNTIL -> DatePickerPanel(
                        initial = current.validTo ?: current.date,
                        onPick = { date ->
                            panel = null
                            update { it.copy(validTo = date) }
                        },
                        onDismiss = { panel = null },
                        onClear = {
                            panel = null
                            update { it.copy(validTo = null) }
                        },
                    )
                }
            }
        },
    ) {
        val form = data
        if (current == null || form == null) return@FormFrame
        // Шаг 5.18 (вариант А): запрет — шагами «Где», «Когда», «Причина».
        FormStep(1, "Где", null) {
            if (restrictionId == null) {
                // Новый запрет — сразу на несколько мест (шаг 1.13д): по запрету на каждое; все места — один «Все места» (5.3ю).
                val order = form.places.map { it.id }
                ManyPlaceChips(
                    places = form.places,
                    selected = current.selected(order),
                    error = problems[RestrictionField.PLACE],
                    onToggle = { id -> update { RestrictionFormRules.togglePlace(it, id, order) } },
                    onAll = { update { RestrictionFormRules.toggleAll(it, order) } },
                )
            } else {
                // Изменение — одно место или «Все места» (шаг 5.3ю).
                PlaceChips(form.places, current.locationId, problems[RestrictionField.PLACE], allPlaces = true) { id ->
                    update { RestrictionFormRules.pickPlace(it, id) }
                }
            }
        }
        FormStep(2, "Когда", DateFormat.timeRange(current.start, current.end)) {
            TimeRow(
                start = current.start,
                end = current.end,
                error = problems[RestrictionField.TIME],
                onStart = { panel = Panels.TIME },
                onEnd = { panel = Panels.END },
            )
            StepLabel("Повтор")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                ChoiceChip(TrainingFormat.NO_REPEAT, selected = !current.weekly, onClick = { update { RestrictionFormRules.weekly(it, on = false) } })
                ChoiceChip(
                    TrainingFormat.WEEKLY,
                    selected = current.weekly,
                    onClick = { update { RestrictionFormRules.weekly(it, on = true) } },
                    modifier = Modifier.testTag(FORM_WEEKLY_TAG),
                )
            }
            if (current.weekly) {
                WeekdayRow(current.weekdays, problems[RestrictionField.WEEKDAYS]) { days -> update { it.copy(weekdays = days) } }
                StepLabel("Период")
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PickBox(
                        text = TrainingFormat.periodFrom(current.date),
                        icon = PIcons.CalendarMonth,
                        onClick = { panel = Panels.DATE },
                        modifier = Modifier.weight(1f).testTag(FORM_PERIOD_FROM_TAG),
                    )
                    PickBox(
                        text = current.validTo?.let(TrainingFormat::until) ?: TrainingFormat.FOREVER,
                        icon = null,
                        onClick = { panel = Panels.UNTIL },
                        modifier = Modifier.weight(1f).testTag(FORM_UNTIL_TAG),
                        error = problems[RestrictionField.PERIOD],
                    )
                }
                problems[RestrictionField.PERIOD]?.let { ErrorText(it) }
            } else {
                StepLabel("Дата")
                PickBox(
                    text = TrainingFormat.date(current.date),
                    icon = PIcons.CalendarMonth,
                    onClick = { panel = Panels.DATE },
                    modifier = Modifier.fillMaxWidth().testTag(FORM_DATE_TAG),
                )
            }
        }
        FormStep(3, "Причина", null) {
            Field(
                label = "Причина",
                value = current.reason,
                onValueChange = { text -> update { it.copy(reason = text) } },
                modifier = Modifier.fillMaxWidth(),
                tag = FORM_REASON_TAG,
                placeholder = TrainingFormat.REASON_PLACEHOLDER,
            )
        }

        if (restrictionId != null) {
            SecondaryButton(
                text = TrainingFormat.DELETE_RESTRICTION,
                onClick = { delete(restrictionId) },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space6),
                icon = PIcons.Delete,
            )
        }
    }
}

/**
 * Личное дело или напоминание (шаги 5.10е, 5.10ж; макет «Персоналка — личные дела и рабочий график»): «Дело /
 * Напоминание», название, дата и время, повтор. У дела — «Можно ставить тренировки», «Дорога до и после» и
 * «Напомнить»; у напоминания — «Уведомление». Места нет: личное — во всех местах. Пишется в таблицу запретов —
 * дальше то же, что у запрета: «Поставить всё равно?», флаг «поверх запрета», слоты.
 */
@Composable
internal fun PersonalFields(
    key: String,
    restrictionId: String?,
    stored: RestrictionDraft?,
    data: TrainingFormData?,
    restrictions: RestrictionRepository,
    forms: TrainingRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    switch: @Composable () -> Unit,
) {
    var draft by rememberSaveable(key, stateSaver = RestrictionSaver) { mutableStateOf<RestrictionDraft?>(null) }
    var showErrors by rememberSaveable(key) { mutableStateOf(false) }
    var panel by rememberSaveable(key) { mutableStateOf<String?>(null) }

    LaunchedEffect(key, data != null, stored) {
        if (data == null || draft != null) return@LaunchedEffect
        draft = if (restrictionId != null) {
            stored ?: return@LaunchedEffect
        } else {
            val start = TrainingFormKey.start(key) ?: forms.suggestStart(RestrictionFormRules.DEFAULT_MINUTES)
            RestrictionFormRules.newPersonal(start, BlockKind.PERSONAL)
        }
    }

    val current = draft
    val problems = if (showErrors && current != null) RestrictionFormRules.problems(current) else emptyMap()

    fun update(change: (RestrictionDraft) -> RestrictionDraft) {
        draft = draft?.let(change)
    }

    fun save() {
        val d = draft ?: return
        showErrors = true
        if (RestrictionFormRules.problems(d).isNotEmpty()) return
        scope.launch {
            when (val result = restrictions.save(restrictionId, d)) {
                is RestrictionSaveResult.Saved -> {
                    onClose()
                    messages.showUndo(result.message) { scope.launch { restrictions.undo(result.undo) } }
                }
                is RestrictionSaveResult.Invalid -> if (result.problems.isEmpty()) onClose()
            }
        }
    }

    fun delete(id: String, reminder: Boolean) {
        scope.launch {
            val undo = restrictions.delete(id)
            onClose()
            val text = if (reminder) PersonalFormat.REMINDER_DELETED else PersonalFormat.TASK_DELETED
            if (undo != null) messages.showUndo(text) { scope.launch { restrictions.undo(undo) } }
        }
    }

    val reminder = current?.kind == BlockKind.REMINDER
    FormFrame(
        title = when {
            restrictionId == null -> PersonalFormat.NEW_TITLE
            reminder -> PersonalFormat.EDIT_REMINDER_TITLE
            else -> PersonalFormat.EDIT_TASK_TITLE
        },
        onClose = onClose,
        switch = switch,
        ready = current != null && data != null,
        onSave = ::save,
        panels = {
            val open = panel
            if (open != null && current != null) {
                when (open) {
                    Panels.DATE -> DatePickerPanel(
                        initial = current.date,
                        onPick = { date ->
                            panel = null
                            update { d ->
                                val days = if (d.weekdays == setOf(d.date.dayOfWeek)) setOf(date.dayOfWeek) else d.weekdays
                                d.copy(date = date, weekdays = days)
                            }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.TIME -> TimePickerPanel(
                        initial = current.start,
                        title = START_TITLE,
                        onPick = { time ->
                            panel = null
                            update { d -> if (d.kind == BlockKind.REMINDER) RestrictionFormRules.reminderEnd(d.copy(start = time)) else RestrictionFormRules.withStart(d, time) }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.END -> TimePickerPanel(
                        initial = current.end,
                        title = END_TITLE,
                        onPick = { time ->
                            panel = null
                            update { RestrictionFormRules.withEnd(it, time) }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.UNTIL -> DatePickerPanel(
                        initial = current.validTo ?: current.date,
                        onPick = { date ->
                            panel = null
                            update { it.copy(validTo = date) }
                        },
                        onDismiss = { panel = null },
                        onClear = {
                            panel = null
                            update { it.copy(validTo = null) }
                        },
                    )
                }
            }
        },
    ) {
        if (current == null || data == null) return@FormFrame
        // Шаг 5.18 (вариант А): личное — шагами «Что», «Когда», «Повтор», «Тренировки и уведомление».
        FormStep(1, "Что", current.reason.trim().ifEmpty { null }) {
            // Дело или напоминание — у новой записи; изменение остаётся тем, чем было.
            if (restrictionId == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    ChoiceChip(
                        PersonalFormat.TASK,
                        selected = !reminder,
                        onClick = { update { RestrictionFormRules.switchKind(it, BlockKind.PERSONAL) } },
                        modifier = Modifier.testTag(FORM_PERSONAL_TASK_TAG),
                    )
                    ChoiceChip(
                        PersonalFormat.REMINDER,
                        selected = reminder,
                        onClick = { update { RestrictionFormRules.switchKind(it, BlockKind.REMINDER) } },
                        modifier = Modifier.testTag(FORM_PERSONAL_REMINDER_TAG),
                    )
                }
            }

            Field(
                label = if (reminder) PersonalFormat.REMINDER_NAME else PersonalFormat.TASK_NAME,
                value = current.reason,
                onValueChange = { text -> update { it.copy(reason = text) } },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space3),
                tag = FORM_REASON_TAG,
                placeholder = if (reminder) PersonalFormat.REMINDER_NAME_PLACEHOLDER else PersonalFormat.TASK_NAME_PLACEHOLDER,
                error = problems[RestrictionField.TITLE],
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        FormStep(2, "Когда", TrainingFormat.date(current.date) + " · " + DateFormat.time(current.start)) {
            PickBox(
                text = TrainingFormat.date(current.date),
                icon = PIcons.CalendarMonth,
                onClick = { panel = Panels.DATE },
                modifier = Modifier.fillMaxWidth().testTag(FORM_DATE_TAG),
            )
            if (reminder) {
                PickBox(
                    text = DateFormat.time(current.start),
                    icon = PIcons.Schedule,
                    onClick = { panel = Panels.TIME },
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2).testTag(FORM_TIME_TAG),
                )
            } else {
                Column(Modifier.padding(top = Dimens.space2)) {
                    TimeRow(
                        start = current.start,
                        end = current.end,
                        error = problems[RestrictionField.TIME],
                        onStart = { panel = Panels.TIME },
                        onEnd = { panel = Panels.END },
                    )
                }
            }
        }
        FormStep(3, "Повтор", null) {
            if (reminder) {
                val repeat = RestrictionFormRules.repeatOf(current)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    ReminderRepeat.entries.forEach { r ->
                        ChoiceChip(PersonalFormat.repeat(r), selected = r == repeat, onClick = { update { RestrictionFormRules.withRepeat(it, r) } })
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    ChoiceChip(TrainingFormat.NO_REPEAT, selected = !current.weekly, onClick = { update { RestrictionFormRules.weekly(it, on = false) } })
                    ChoiceChip(
                        TrainingFormat.WEEKLY,
                        selected = current.weekly,
                        onClick = { update { RestrictionFormRules.weekly(it, on = true) } },
                        modifier = Modifier.testTag(FORM_WEEKLY_TAG),
                    )
                }
                if (current.weekly) WeekdayRow(current.weekdays, problems[RestrictionField.WEEKDAYS]) { days -> update { it.copy(weekdays = days) } }
            }
            if (current.weekly || current.monthly) {
                PickBox(
                    text = current.validTo?.let(TrainingFormat::until) ?: TrainingFormat.FOREVER,
                    icon = null,
                    onClick = { panel = Panels.UNTIL },
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.space2).testTag(FORM_UNTIL_TAG),
                    error = problems[RestrictionField.PERIOD],
                )
                problems[RestrictionField.PERIOD]?.let { ErrorText(it) }
            }
        }
        FormStep(4, if (reminder) PersonalFormat.REMINDER_NOTIFY else "Тренировки и уведомление", null) {
            if (reminder) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PersonalRules.REMINDER_NOTIFY.forEach { m ->
                        ChoiceChip(PersonalFormat.reminderNotify(m), selected = current.notifyMinutes == m, onClick = { update { it.copy(notifyMinutes = m) } })
                    }
                }
                Text(
                    PersonalFormat.REMINDER_NOTE,
                    modifier = Modifier.padding(top = Dimens.space3),
                    style = MaterialTheme.typography.bodySmall,
                    color = PersonalkaTheme.colors.textMuted,
                )
            } else {
                SwitchRow(
                    title = PersonalFormat.ALLOW_TRAININGS,
                    checked = !current.blocksTrainings,
                    onToggle = { allow -> update { it.copy(blocksTrainings = !allow) } },
                    modifier = Modifier.testTag(FORM_PERSONAL_ALLOW_TAG),
                    subtitle = PersonalFormat.ALLOW_TRAININGS_NOTE,
                )
                if (current.blocksTrainings) {
                    StepLabel(PersonalFormat.TRAVEL)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        PersonalRules.TRAVELS.forEach { m ->
                            ChoiceChip(
                                PersonalFormat.travel(m),
                                selected = current.travelMinutes == m,
                                onClick = { update { it.copy(travelMinutes = m) } },
                                modifier = Modifier.testTag(formTravelTag(m)),
                            )
                        }
                    }
                }
                StepLabel(PersonalFormat.TASK_NOTIFY)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PersonalRules.TASK_NOTIFY.forEach { m ->
                        ChoiceChip(PersonalFormat.taskNotify(m), selected = current.notifyMinutes == m, onClick = { update { it.copy(notifyMinutes = m) } })
                    }
                }
                val travel = if (current.blocksTrainings) current.travelMinutes else 0
                PersonalFormat.leaveAt(current.start, travel, current.notifyMinutes)?.let {
                    Text(it, modifier = Modifier.padding(top = Dimens.space2), style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
                }
            }
        }

        if (restrictionId != null) {
            SecondaryButton(
                text = if (reminder) PersonalFormat.DELETE_REMINDER else PersonalFormat.DELETE_TASK,
                onClick = { delete(restrictionId, reminder) },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space6),
                icon = PIcons.Delete,
            )
        }
    }
}

/** Чипы и переключатель личного (шаг 5.10е) для тестов. */
const val FORM_PERSONAL_TASK_TAG = "form-personal-task"
const val FORM_PERSONAL_REMINDER_TAG = "form-personal-reminder"
const val FORM_PERSONAL_ALLOW_TAG = "form-personal-allow"
fun formTravelTag(minutes: Int) = "form-travel-$minutes"

/** Место чипами, как в форме тренировки; [allPlaces] — первым чип «Все места», когда мест больше одного (запрет). */
@Composable
private fun PlaceChips(places: List<FormPlace>, selected: String?, error: String?, allPlaces: Boolean = false, onPick: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        if (allPlaces && places.size > 1) {
            ChoiceChip(
                text = TrainingFormat.ALL_PLACES,
                selected = selected == Restriction.ALL_PLACES,
                onClick = { onPick(Restriction.ALL_PLACES) },
                modifier = Modifier.testTag(FORM_ALL_PLACES_TAG),
            )
        }
        places.forEach { place ->
            // Шаг 5.18: место — чипом своего цвета.
            val g = PlaceGradient.of(place.color, place.color2)
            PlaceChip(
                text = place.shortName,
                selected = place.id == selected,
                ends = g.ends(colors.isDark, 1),
                ink = g.ink(colors.isDark, 1),
                onClick = { onPick(place.id) },
                modifier = Modifier.testTag(formPlaceTag(place.id)),
            )
        }
    }
    error?.let { ErrorText(it) }
}

/** Места нового запрета: несколько чипов сразу и «Все места», когда мест больше одного. */
@Composable
private fun ManyPlaceChips(
    places: List<FormPlace>,
    selected: Set<String>,
    error: String?,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        if (places.size > 1) {
            ChoiceChip(
                text = TrainingFormat.ALL_PLACES,
                selected = places.all { it.id in selected },
                onClick = onAll,
                modifier = Modifier.testTag(FORM_ALL_PLACES_TAG),
                toggle = true,
            )
        }
        places.forEach { place ->
            ChoiceChip(
                text = place.shortName,
                selected = place.id in selected,
                onClick = { onToggle(place.id) },
                modifier = Modifier.testTag(formPlaceTag(place.id)),
                toggle = true,
            )
        }
    }
    error?.let { ErrorText(it) }
}

/** Время «с 17:00 — до 18:00»: два поля, каждое открывает циферблат; ошибка — под обоими. */
@Composable
private fun TimeRow(start: LocalTime, end: LocalTime, error: String?, onStart: () -> Unit, onEnd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        PickBox(
            text = TrainingFormat.from(start),
            icon = PIcons.Schedule,
            onClick = onStart,
            modifier = Modifier.weight(1f).testTag(FORM_TIME_TAG),
            error = error,
        )
        PickBox(
            text = TrainingFormat.to(end),
            icon = null,
            onClick = onEnd,
            modifier = Modifier.weight(1f).testTag(FORM_END_TAG),
            error = error,
        )
    }
    error?.let { ErrorText(it) }
}

private const val START_TITLE = "Начало"
private const val END_TITLE = "Конец"

/** Черновик запрета списком строк: переживает пересоздание окна и выгрузку из памяти. */
private val RestrictionSaver: Saver<RestrictionDraft?, Any> = Saver(
    save = { d ->
        if (d == null) {
            arrayListOf<String>()
        } else {
            arrayListOf(
                d.locationId.orEmpty(),
                d.start.toString(),
                d.end.toString(),
                d.reason,
                d.weekly.toString(),
                d.weekdays.joinToString(",") { it.name },
                d.date.toString(),
                d.validTo?.toString().orEmpty(),
                d.otherPlaces.joinToString(","),
                d.kind.name,
                d.travelMinutes.toString(),
                d.blocksTrainings.toString(),
                d.notifyMinutes?.toString().orEmpty(),
                d.monthly.toString(),
            )
        }
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val v = saved as List<String>
        if (v.isEmpty()) {
            null
        } else {
            RestrictionDraft(
                locationId = v[0].ifEmpty { null },
                start = LocalTime.parse(v[1]),
                end = LocalTime.parse(v[2]),
                reason = v[3],
                weekly = v[4].toBoolean(),
                weekdays = v[5].split(',').filter { it.isNotEmpty() }.map(DayOfWeek::valueOf).toSet(),
                date = LocalDate.parse(v[6]),
                validTo = v[7].ifEmpty { null }?.let(LocalDate::parse),
                otherPlaces = v.getOrElse(8) { "" }.split(',').filter { it.isNotEmpty() }.toSet(),
                kind = v.getOrNull(9)?.let { BlockKind.valueOf(it) } ?: BlockKind.RESTRICTION,
                travelMinutes = v.getOrNull(10)?.toIntOrNull() ?: 0,
                blocksTrainings = v.getOrNull(11)?.toBoolean() ?: true,
                notifyMinutes = v.getOrNull(12)?.toIntOrNull(),
                monthly = v.getOrNull(13)?.toBoolean() ?: false,
            )
        }
    },
)
