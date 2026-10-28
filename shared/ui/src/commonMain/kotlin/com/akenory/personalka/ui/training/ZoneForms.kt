package com.akenory.personalka.ui.training

import androidx.compose.foundation.layout.Arrangement
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
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
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
        PlaceChips(form.places, current.locationId, problems[FormField.PLACE]) { id -> update { it.copy(locationId = id) } }

        SectionLabel("Дата")
        PickBox(
            text = TrainingFormat.date(current.start.date),
            icon = PIcons.CalendarMonth,
            onClick = { panel = Panels.DATE },
            modifier = Modifier.fillMaxWidth().testTag(FORM_DATE_TAG),
        )

        SectionLabel("Время")
        TimeRow(
            start = current.start.time,
            end = TrainingFormRules.endTime(current),
            error = problems[FormField.TIME],
            onStart = { panel = Panels.TIME },
            onEnd = { panel = Panels.END },
        )

        SectionLabel("Повтор")
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
                            update { it.copy(start = time) }
                        },
                        onDismiss = { panel = null },
                    )
                    Panels.END -> TimePickerPanel(
                        initial = current.end,
                        title = END_TITLE,
                        onPick = { time ->
                            panel = null
                            update { it.copy(end = time) }
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
        if (restrictionId == null) {
            // Новый запрет — сразу на несколько мест (шаг 1.13д): по запрету на каждое.
            val order = form.places.map { it.id }
            ManyPlaceChips(
                places = form.places,
                selected = current.placeIds.toSet(),
                error = problems[RestrictionField.PLACE],
                onToggle = { id -> update { RestrictionFormRules.togglePlace(it, id, order) } },
                onAll = { update { RestrictionFormRules.toggleAll(it, order) } },
            )
        } else {
            PlaceChips(form.places, current.locationId, problems[RestrictionField.PLACE]) { id -> update { it.copy(locationId = id) } }
        }

        SectionLabel("Время")
        TimeRow(
            start = current.start,
            end = current.end,
            error = problems[RestrictionField.TIME],
            onStart = { panel = Panels.TIME },
            onEnd = { panel = Panels.END },
        )

        Field(
            label = "Причина",
            value = current.reason,
            onValueChange = { text -> update { it.copy(reason = text) } },
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.space5),
            tag = FORM_REASON_TAG,
            placeholder = TrainingFormat.REASON_PLACEHOLDER,
        )

        SectionLabel("Повтор")
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
            SectionLabel("Период")
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
            SectionLabel("Дата")
            PickBox(
                text = TrainingFormat.date(current.date),
                icon = PIcons.CalendarMonth,
                onClick = { panel = Panels.DATE },
                modifier = Modifier.fillMaxWidth().testTag(FORM_DATE_TAG),
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

/** Место чипами, как в форме тренировки. */
@Composable
private fun PlaceChips(places: List<FormPlace>, selected: String?, error: String?, onPick: (String) -> Unit) {
    SectionLabel("Место")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        places.forEach { place ->
            ChoiceChip(
                text = place.shortName,
                selected = place.id == selected,
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
    SectionLabel("Место")
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
            )
        }
    },
)
