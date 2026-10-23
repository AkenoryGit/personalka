package com.akenory.personalka.ui.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarPrefs
import com.akenory.personalka.data.calendar.CalendarSettingsRepository
import com.akenory.personalka.data.calendar.VacationResult
import com.akenory.personalka.data.calendar.ZoneRow
import com.akenory.personalka.domain.calendar.CalendarSettingsRules
import com.akenory.personalka.domain.calendar.Vacation
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.ErrorText
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.training.TimePickerPanel
import com.akenory.personalka.ui.training.TrainingFormKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

internal const val CALENDAR_ROW_TAG = "settings-calendar"
internal const val WORK_START_TAG = "work-start"
internal const val WORK_END_TAG = "work-end"
internal const val VACATION_TAG = "vacation"
internal const val VACATION_FROM_TAG = "vacation-from"
internal const val VACATION_TO_TAG = "vacation-to"
internal fun stepTag(minutes: Int) = "step-$minutes"
internal fun horizonTag(weeks: Int) = "horizon-$weeks"
internal fun noticeTag(minutes: Int) = "notice-$minutes"
internal fun zoneRowTag(key: String) = "zone-$key"

/** Какое окно выбора открыто: начало или конец часов работы, первый или последний день отпуска. */
private enum class Picker { START, END, FROM, TO }

/**
 * «Настройки → Календарь» (ТЗ 5.16, шаг 1.13г): часы работы, шаг перетаскивания, горизонт серий — пишутся сразу,
 * как только выбраны; «Отпуск с … по …» — окно с двумя датами и «Отменить» после записи; списки своих запретов,
 * запретов клуба и дежурств. Свой запрет и дежурство открывают ту же форму, что из календаря ([onForm]); запрет
 * клуба — только строкой. Пустой список не показывается (10.7).
 */
@Composable
internal fun CalendarSettingsScreen(
    settings: CalendarSettingsRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onForm: (String) -> Unit,
    onBack: () -> Unit,
) {
    val prefs by remember(settings) { settings.prefs }.collectAsState(initial = null)
    val zones by remember(settings, now.date) { settings.zones(now) }.collectAsState(initial = null)
    val loaded = prefs ?: return
    val today = now.date

    // Часы, которые ещё не записаны: конец не позже начала — ошибка под полями, в базу не идёт.
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var end by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var vacationOpen by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(today.toString()) }
    var to by rememberSaveable { mutableStateOf(today.toString()) }

    val workStart = start?.let(LocalTime::parse) ?: loaded.workStart
    val workEnd = end?.let(LocalTime::parse) ?: loaded.workEnd
    val hoursProblem = CalendarSettingsRules.workHoursProblem(workStart, workEnd)

    fun saveHours(newStart: LocalTime, newEnd: LocalTime) {
        start = newStart.toString()
        end = newEnd.toString()
        if (CalendarSettingsRules.workHoursProblem(newStart, newEnd) == null) scope.launch { settings.saveWorkHours(newStart, newEnd) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(CalendarSettingsFormat.TITLE, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                SectionLabel(CalendarSettingsFormat.WORK_HOURS)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PickBox(
                        text = CalendarSettingsFormat.from(workStart),
                        icon = PIcons.Schedule,
                        onClick = { picker = Picker.START },
                        modifier = Modifier.weight(1f).testTag(WORK_START_TAG),
                        error = hoursProblem,
                    )
                    PickBox(
                        text = CalendarSettingsFormat.to(workEnd),
                        icon = null,
                        onClick = { picker = Picker.END },
                        modifier = Modifier.weight(1f).testTag(WORK_END_TAG),
                        error = hoursProblem,
                    )
                }
                hoursProblem?.let { ErrorText(it) }
                Hint(CalendarSettingsFormat.WORK_HOURS_NOTE)

                SectionLabel(CalendarSettingsFormat.DRAG_STEP)
                Chips(CalendarSettingsRules.DRAG_STEPS, loaded.dragStep, CalendarSettingsFormat::step, ::stepTag) {
                    scope.launch { settings.saveDragStep(it) }
                }

                SectionLabel(CalendarSettingsFormat.HORIZON)
                Chips(CalendarSettingsRules.HORIZONS, loaded.horizonWeeks, CalendarSettingsFormat::weeks, ::horizonTag) {
                    scope.launch { settings.saveHorizon(it) }
                }
                Hint(CalendarSettingsFormat.HORIZON_NOTE)

                SectionLabel(CalendarSettingsFormat.CANCEL_NOTICE)
                Chips(CalendarSettingsRules.CANCEL_NOTICES, loaded.cancelNoticeMinutes, CalendarSettingsFormat::notice, ::noticeTag) {
                    scope.launch { settings.saveCancelNotice(it) }
                }
                Hint(CalendarSettingsFormat.CANCEL_NOTICE_NOTE)

                SectionLabel(CalendarSettingsFormat.VACATION)
                OutlineButton(
                    CalendarSettingsFormat.VACATION_BUTTON,
                    {
                        val week = CalendarSettingsRules.defaultVacation(today)
                        from = week.from.toString()
                        to = week.to.toString()
                        vacationOpen = true
                    },
                    modifier = Modifier.testTag(VACATION_TAG),
                    icon = PIcons.CalendarMonth,
                )

                zones?.let { lists ->
                    ZoneList(CalendarSettingsFormat.OWN_RESTRICTIONS, lists.own) { onForm(TrainingFormKey.restriction(it)) }
                    ZoneList(CalendarSettingsFormat.CLUB_RESTRICTIONS, lists.club, onOpen = null)
                    ZoneList(CalendarSettingsFormat.DUTIES, lists.duties) { onForm(TrainingFormKey.edit(it)) }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        if (vacationOpen) {
            val vacation = Vacation(LocalDate.parse(from), LocalDate.parse(to))
            val fromProblem = CalendarSettingsRules.vacationFromProblem(vacation, today)
            val toProblem = CalendarSettingsRules.vacationToProblem(vacation)
            Panel(CalendarSettingsFormat.VACATION_TITLE, onDismiss = { vacationOpen = false }) {
                SectionLabel(CalendarSettingsFormat.VACATION_FROM, Modifier.padding(top = 0.dp))
                PickBox(
                    text = TrainingFormat.date(vacation.from),
                    icon = PIcons.CalendarMonth,
                    onClick = { picker = Picker.FROM },
                    modifier = Modifier.fillMaxWidth().testTag(VACATION_FROM_TAG),
                    error = fromProblem,
                )
                fromProblem?.let { ErrorText(it) }
                SectionLabel(CalendarSettingsFormat.VACATION_TO, Modifier.padding(top = 0.dp))
                PickBox(
                    text = TrainingFormat.date(vacation.to),
                    icon = PIcons.CalendarMonth,
                    onClick = { picker = Picker.TO },
                    modifier = Modifier.fillMaxWidth().testTag(VACATION_TO_TAG),
                    error = toProblem,
                )
                toProblem?.let { ErrorText(it) }
                Hint(CalendarSettingsFormat.VACATION_NOTE)
                PanelActions(
                    primary = CalendarSettingsFormat.VACATION_SAVE,
                    onPrimary = {
                        vacationOpen = false
                        scope.launch {
                            val result = settings.vacation(vacation) as? VacationResult.Saved ?: return@launch
                            messages.showUndo(result.message) { scope.launch { settings.undo(result.undo) } }
                        }
                    },
                    secondary = "Отмена",
                    onSecondary = { vacationOpen = false },
                    primaryEnabled = fromProblem == null && toProblem == null,
                )
            }
        }

        when (picker) {
            Picker.START -> TimePickerPanel(workStart, { picker = null; saveHours(it, workEnd) }, { picker = null }, title = START_TITLE)
            Picker.END -> TimePickerPanel(workEnd, { picker = null; saveHours(workStart, it) }, { picker = null }, title = END_TITLE)
            Picker.FROM -> DatePickerPanel(LocalDate.parse(from), {
                picker = null
                // Последний день не раньше первого: сдвигается вместе с ним.
                val shift = it.toEpochDays() - LocalDate.parse(from).toEpochDays()
                from = it.toString()
                to = LocalDate.fromEpochDays(maxOf(LocalDate.parse(to).toEpochDays() + shift, it.toEpochDays())).toString()
            }, { picker = null })
            Picker.TO -> DatePickerPanel(LocalDate.parse(to), {
                picker = null
                to = it.toString()
            }, { picker = null })
            null -> Unit
        }
    }
}

/** Значения чипами в ряд: выбранное — текущее из настроек. */
@Composable
private fun Chips(options: List<Int>, selected: Int, label: (Int) -> String, tag: (Int) -> String, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        options.forEach { value ->
            ChoiceChip(
                text = label(value),
                selected = value == selected,
                onClick = { if (value != selected) onPick(value) },
                modifier = Modifier.testTag(tag(value)),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Dimens.space2),
        style = MaterialTheme.typography.bodySmall,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/** Список запретов или дежурств; пустой не показывается. [onOpen] `null` — строки только для просмотра. */
@Composable
private fun ZoneList(title: String, rows: List<ZoneRow>, onOpen: ((String) -> Unit)?) {
    if (rows.isEmpty()) return
    SectionLabel(title)
    ListCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            val id = row.id
            if (onOpen != null && id != null) {
                NavRow(row.title, { onOpen(id) }, Modifier.testTag(zoneRowTag(row.key)), subtitle = row.subtitle)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag(zoneRowTag(row.key))
                        .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
                ) {
                    Text(row.title, style = MaterialTheme.typography.bodyLarge.tabular(), color = PersonalkaTheme.colors.text)
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
                }
            }
        }
    }
}

/** Строка раздела в «Настройках». */
internal fun calendarSummary(prefs: CalendarPrefs): String =
    CalendarSettingsFormat.summary(prefs.workStart, prefs.workEnd, prefs.dragStep, prefs.horizonWeeks)

private const val START_TITLE = "Начало"
private const val END_TITLE = "Конец"
