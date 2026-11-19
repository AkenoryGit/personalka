package com.akenory.personalka.ui.settings

import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.akenory.personalka.ui.theme.raised
import com.akenory.personalka.ui.theme.insetLevel
import com.akenory.personalka.ui.theme.OnAurora
import com.akenory.personalka.ui.theme.AuroraBrush
import com.akenory.personalka.ui.setup.secondColorTag
import com.akenory.personalka.ui.setup.SINGLE_COLOR
import com.akenory.personalka.ui.setup.GradientPresets
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.ui.components.SettingsGroup
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarPrefs
import com.akenory.personalka.data.calendar.CalendarSettingsRepository
import com.akenory.personalka.data.calendar.VacationPreview
import com.akenory.personalka.data.calendar.VacationResult
import com.akenory.personalka.data.calendar.ZoneRow
import com.akenory.personalka.domain.calendar.CalendarSettingsRules
import com.akenory.personalka.domain.calendar.Vacation
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.PersonalFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.restriction.DayHours
import com.akenory.personalka.domain.restriction.WorkSchedule
import com.akenory.personalka.domain.restriction.problem
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.setup.ColorPalette
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
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

internal const val CALENDAR_ROW_TAG = "settings-calendar"
internal const val WORK_START_TAG = "work-start"
internal const val WORK_END_TAG = "work-end"
internal const val VACATION_TAG = "vacation"
internal const val VACATION_FROM_TAG = "vacation-from"
internal const val VACATION_TO_TAG = "vacation-to"
internal const val VACATION_WHO_TAG = "vacation-who"
internal fun vacationRowTag(key: String) = "vacation-row-$key"
internal fun stepTag(minutes: Int) = "step-$minutes"
internal fun horizonTag(weeks: Int) = "horizon-$weeks"
internal fun noticeTag(minutes: Int) = "notice-$minutes"
internal fun zoneRowTag(key: String) = "zone-$key"
internal fun scheduleDayTag(day: DayOfWeek) = "schedule-$day"
internal const val SCHEDULE_SWITCH_TAG = "schedule-switch"
internal const val SCHEDULE_DAY_SWITCH_TAG = "schedule-day-switch"
internal const val SCHEDULE_DAY_START_TAG = "schedule-day-start"
internal const val SCHEDULE_DAY_END_TAG = "schedule-day-end"
internal const val PERSONAL_SWITCH_TAG = "personal-switch"

/** Какое окно выбора открыто: начало или конец часов работы, первый или последний день отпуска. */
private enum class Picker { START, END, FROM, TO }

/**
 * «Настройки → Календарь» (ТЗ 5.16, шаг 1.13г): часы работы, шаг перетаскивания, горизонт серий — пишутся сразу,
 * как только выбраны; «Отпуск с … по …» — окно с двумя датами и «Отменить» после записи; списки своих запретов,
 * запретов клуба и дежурств. Свой запрет и дежурство открывают ту же форму, что из календаря ([onForm]); запрет
 * клуба — только строкой. Пустой список не показывается (10.7).
 *
 * С шага 3.15а «Отпуск» — свой пункт «Ещё»: при [vacation] экран показывает только его, а «Календарь» — всё, кроме него.
 */
@Composable
internal fun CalendarSettingsScreen(
    settings: CalendarSettingsRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onForm: (String) -> Unit,
    onBack: (() -> Unit)?,
    vacation: Boolean = false,
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
    // День графика, который сейчас меняют (шаг 5.10д): имя дня недели.
    var dayEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var dayPicker by rememberSaveable { mutableStateOf<String?>(null) }
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
            TopBar(if (vacation) CalendarSettingsFormat.VACATION else CalendarSettingsFormat.TITLE, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                if (vacation) {
                    OutlineButton(
                        CalendarSettingsFormat.VACATION_BUTTON,
                        {
                            val week = CalendarSettingsRules.defaultVacation(today)
                            from = week.from.toString()
                            to = week.to.toString()
                            vacationOpen = true
                        },
                        modifier = Modifier.padding(top = Dimens.space2).testTag(VACATION_TAG),
                        icon = PIcons.CalendarMonth,
                    )
                    Hint(CalendarSettingsFormat.VACATION_NOTE)
                } else {
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

                // Рабочий график по дням (шаг 5.10д): слоты — только в эти часы, вне их — вопрос при записи.
                SectionLabel(PersonalFormat.SECTION_SCHEDULE)
                SettingsGroup {
                    SwitchRow(
                        title = PersonalFormat.SCHEDULE,
                        checked = loaded.schedule != null,
                        onToggle = { on -> scope.launch { settings.saveSchedule(if (on) WorkSchedule.DEFAULT else null) } },
                        modifier = Modifier.testTag(SCHEDULE_SWITCH_TAG),
                        subtitle = PersonalFormat.SCHEDULE_NOTE,
                    )
                }
                // Шаг 5.16: дни недели — отдельной карточкой ниже выключателя, одним столбиком компактных строк:
                // день «Сиянием» (рабочий) или во вдавленном кружке (выходной), часы — во вдавленной плашке.
                loaded.schedule?.let { schedule ->
                    Spacer(Modifier.height(Dimens.space3))
                    ListCard {
                        DateFormat.WEEK.forEachIndexed { index, day ->
                            if (index > 0) RowDivider(start = 64.dp)
                            ScheduleDayRow(day, schedule.on(day) != null, PersonalFormat.day(schedule.on(day)), Modifier.clickable { dayEdit = day.name }.testTag(scheduleDayTag(day)))
                        }
                    }
                }

                // Личные дела и напоминания (шаг 5.10е): выключены — календарь как раньше.
                SectionLabel(PersonalFormat.SECTION_PERSONAL)
                SettingsGroup {
                    SwitchRow(
                        title = PersonalFormat.PERSONAL,
                        checked = loaded.personal,
                        onToggle = { on -> scope.launch { settings.savePersonal(on) } },
                        modifier = Modifier.testTag(PERSONAL_SWITCH_TAG),
                        subtitle = PersonalFormat.PERSONAL_NOTE,
                    )
                }
                if (loaded.personal) {
                    // Шаг 5.14е: цвет личных дел — переходом, как у мест: готовые, «Один цвет» и свой второй цвет.
                    val pair = ReferenceRules.gradient(loaded.personalColor, loaded.personalColor2)
                    SectionLabel(PersonalFormat.COLOR)
                    ColorPalette(loaded.personalColor) { key -> scope.launch { settings.savePersonalColor(key) } }
                    SectionLabel("Переход цвета")
                    GradientPresets(
                        color = pair.first,
                        selected = if (pair.first == pair.second) SINGLE_COLOR else ReferenceRules.presetOf(pair)?.name,
                        onPick = { from, to -> scope.launch { settings.savePersonalGradient(from, to) } },
                    )
                    SectionLabel("Свой переход: второй цвет")
                    ColorPalette(pair.second, tag = ::secondColorTag) { key -> scope.launch { settings.savePersonalColor2(key) } }
                }

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

                zones?.let { lists ->
                    ZoneList(CalendarSettingsFormat.OWN_RESTRICTIONS, lists.own) { onForm(TrainingFormKey.restriction(it)) }
                    ZoneList(CalendarSettingsFormat.DUTIES, lists.duties) { onForm(TrainingFormKey.edit(it)) }
                }
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
                // Защита от ошибки (шаг 3.12): до записи видно, у кого пропадут тренировки, а на кнопке — сколько.
                val preview by produceState<VacationPreview?>(null, from, to) { value = settings.vacationPreview(vacation) }
                val loadedPreview = preview
                if (loadedPreview != null && fromProblem == null && toProblem == null) {
                    if (loadedPreview.rows.isEmpty()) {
                        Hint(CalendarSettingsFormat.VACATION_NOBODY)
                    } else {
                        SectionLabel(CalendarSettingsFormat.VACATION_WHO, Modifier.padding(top = 0.dp))
                        ListCard(Modifier.testTag(VACATION_WHO_TAG)) {
                            loadedPreview.rows.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                InfoRow(row.title, row.subtitle, Modifier.testTag(vacationRowTag(row.key)))
                            }
                        }
                        Hint(CalendarSettingsFormat.VACATION_WHO_NOTE)
                    }
                }
                PanelActions(
                    primary = CalendarSettingsFormat.vacationDelete(loadedPreview?.trainings ?: 0),
                    onPrimary = {
                        vacationOpen = false
                        scope.launch {
                            val result = settings.vacation(vacation) as? VacationResult.Saved ?: return@launch
                            messages.showUndo(result.message) { scope.launch { settings.undo(result.undo, result.notices) } }
                        }
                    },
                    secondary = "Отмена",
                    onSecondary = { vacationOpen = false },
                    primaryEnabled = fromProblem == null && toProblem == null && loadedPreview != null,
                )
            }
        }

        val editing = dayEdit?.let(DayOfWeek::valueOf)
        val schedule = loaded.schedule
        if (editing != null && schedule != null) {
            val hours = schedule.on(editing)
            fun save(next: DayHours?) {
                scope.launch { settings.saveSchedule(WorkSchedule(schedule.days + (editing to next))) }
            }
            Panel(PersonalFormat.weekday(editing), onDismiss = { dayEdit = null }) {
                SwitchRow(
                    title = PersonalFormat.WORK_DAY,
                    checked = hours != null,
                    onToggle = { on -> save(if (on) WorkSchedule.DEFAULT.on(DayOfWeek.MONDAY) else null) },
                    modifier = Modifier.testTag(SCHEDULE_DAY_SWITCH_TAG),
                )
                if (hours != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        PickBox(
                            text = CalendarSettingsFormat.from(hours.start),
                            icon = PIcons.Schedule,
                            onClick = { dayPicker = "start" },
                            modifier = Modifier.weight(1f).testTag(SCHEDULE_DAY_START_TAG),
                            error = hours.problem(),
                        )
                        PickBox(
                            text = CalendarSettingsFormat.to(hours.end),
                            icon = null,
                            onClick = { dayPicker = "end" },
                            modifier = Modifier.weight(1f).testTag(SCHEDULE_DAY_END_TAG),
                            error = hours.problem(),
                        )
                    }
                    hours.problem()?.let { ErrorText(it) }
                }
                PrimaryButton(PersonalFormat.DONE, { dayEdit = null }, Modifier.fillMaxWidth())
            }
            if (hours != null) {
                when (dayPicker) {
                    "start" -> TimePickerPanel(hours.start, { dayPicker = null; save(hours.copy(start = it)) }, { dayPicker = null }, title = START_TITLE)
                    "end" -> TimePickerPanel(hours.end, { dayPicker = null; save(hours.copy(end = it)) }, { dayPicker = null }, title = END_TITLE)
                }
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
internal fun Chips(options: List<Int>, selected: Int, label: (Int) -> String, tag: (Int) -> String, onPick: (Int) -> Unit) {
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
                InfoRow(row.title, row.subtitle, Modifier.testTag(zoneRowTag(row.key)))
            }
        }
    }
}

/** Строка списка только для просмотра: заголовок и подпись. */
@Composable
internal fun InfoRow(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge.tabular(), color = PersonalkaTheme.colors.text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
    }
}

private const val START_TITLE = "Начало"
private const val END_TITLE = "Конец"

/** День рабочего графика (шаг 5.16): «пн» в кружке («Сиянием» — рабочий, вдавленный — выходной) и часы справа. */
@Composable
private fun ScheduleDayRow(day: DayOfWeek, working: Boolean, hours: String, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Dimens.space4, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).then(
                if (working) Modifier.raised(CircleShape, colors, elevation = 3).background(AuroraBrush) else Modifier.insetLevel(CircleShape, colors),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(DateFormat.weekdayShort(day), style = MaterialTheme.typography.labelLarge, color = if (working) OnAurora else colors.textMuted)
        }
        Spacer(Modifier.weight(1f))
        Text(
            hours,
            modifier = if (working) Modifier.insetLevel(RoundedCornerShape(10.dp), colors).padding(horizontal = 12.dp, vertical = 6.dp) else Modifier,
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = if (working) colors.text else colors.textMuted,
        )
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.padding(start = Dimens.space2).size(20.dp))
    }
}
