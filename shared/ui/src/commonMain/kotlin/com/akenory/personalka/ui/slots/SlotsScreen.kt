package com.akenory.personalka.ui.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarPlace
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.SlotResult
import com.akenory.personalka.data.calendar.WaitSlots
import com.akenory.personalka.data.calendar.WorkHours
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.calendar.DayStarts
import com.akenory.personalka.domain.calendar.SlotParams
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.DurationFormat
import com.akenory.personalka.domain.format.SlotFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.TrainingFormKey
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber

internal const val SLOTS_COPY_TAG = "slots-copy"
internal const val SLOTS_SHARE_TAG = "slots-share"
internal const val SLOTS_MORE_TAG = "slots-more"
internal const val SLOTS_NONE_TAG = "slots-none"
internal fun slotPeriodTag(days: Int) = "slots-period-$days"
internal fun slotDurationTag(minutes: Int) = "slots-duration-$minutes"
internal fun slotGapTag(minutes: Int?) = "slots-gap-${minutes ?: "any"}"
internal fun slotPlaceTag(id: String?) = "slots-place-${id ?: "any"}"
internal fun slotStartTag(day: DayStarts, minute: Int) = slotStartTag(day.date, day.locationId, minute)
internal fun slotStartTag(date: LocalDate, placeId: String?, minute: Int) = "slots-start-$date-$placeId-$minute"
internal fun slotPlaceRowTag(placeId: String) = "slots-place-row-$placeId"
internal const val SLOTS_CUSTOM_TAG = "slots-custom"
internal const val SLOTS_FROM_TAG = "slots-from"
internal const val SLOTS_TO_TAG = "slots-to"

/** Экран «Свободные слоты» в стопке «Ещё»; у «Найти время» из листа ожидания — с id записи. */
object SlotsRoute {
    const val ROOT = "slots"
    private const val WAIT = "slots:wait:"

    fun forWait(waitId: String): String = WAIT + waitId

    fun isSlots(route: String?): Boolean = route == ROOT || route?.startsWith(WAIT) == true

    fun waitOf(route: String?): String? = route?.takeIf { it.startsWith(WAIT) }?.removePrefix(WAIT)
}

/**
 * «Свободные слоты» (ТЗ 5.13, 4.13): вверху параметры, ниже — окна по дням, пересчитываются сразу. Последние
 * параметры запоминаются в настройках телефона; у «Найти время» ([waitId]) дни, время, место и длительность —
 * из записи листа ожидания, и запомненные не меняются. Результат — время начала занятий по помещениям (шаг 4.3б),
 * нажатие на время — новая тренировка на него в этом помещении ([onForm]). «Скопировать список» и «Поделиться» —
 * текст без имён; пометки «ждёт» в него не попадают. На разложенном Fold параметры слева, время справа; параметры и прокрутка объявлены над веткой «широкий / узкий».
 */
@Composable
internal fun SlotsScreen(
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    now: LocalDateTime,
    messages: MessageState,
    onBack: (() -> Unit)?,
    onForm: (String) -> Unit,
    shareText: ((String) -> Unit)? = null,
    waitId: String? = null,
) {
    val wide = LocalWide.current
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val stored = remember(prefs) { prefs.string(PREF_KEY) }
    val params = SlotParams.decode(saved ?: if (waitId == null) stored else null)
    var loaded by rememberSaveable(waitId) { mutableStateOf(waitId == null) }
    var wait by remember(waitId) { mutableStateOf<WaitSlots?>(null) }
    var more by rememberSaveable { mutableStateOf(params.days.isNotEmpty() || params.ranges.isNotEmpty()) }
    val paramsScroll = rememberScrollState()
    val resultScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(waitId) {
        if (waitId == null) return@LaunchedEffect
        val found = calendar.slots.forWait(waitId, SlotParams.decode(stored))
        wait = found
        if (!loaded) {
            found?.let {
                saved = it.params.encode()
                more = true
            }
            loaded = true
        }
    }
    val change: (SlotParams) -> Unit = { p ->
        saved = p.encode()
        if (waitId == null) prefs.putString(PREF_KEY, p.encode())
    }

    val hours by calendar.workHours.collectAsState(initial = WorkHours())
    val places by calendar.places.collectAsState(initial = emptyList())
    val query = params.query(now.date, hours.start, hours.end)
    val placeShort: (String) -> String = { id -> places.firstOrNull { it.id == id }?.shortName.orEmpty() }
    val result by remember(query, now) { calendar.slots.observe(query, now) }.collectAsState(initial = null)

    val copy: () -> Unit = {
        result?.let {
            clipboard.setText(AnnotatedString(SlotFormat.copyText(it.places, placeShort)))
            messages.show(SlotFormat.COPIED)
        }
    }
    val share: (() -> Unit)? = shareText?.let { send -> { result?.let { send(SlotFormat.copyText(it.places, placeShort)) } } }
    // Из «Найти время» время записывает того, кто ждёт, как «Записать» в листе ожидания (4.16).
    val open: (LocalDateTime, String?) -> Unit = { start, place ->
        val who = wait
        val where = place ?: params.locationId
        onForm(
            if (who == null) {
                TrainingFormKey.new(start = start, locationId = where)
            } else {
                TrainingFormKey.new(start = start, clientId = who.clientId, weekly = who.series, typeId = who.typeId, locationId = where, waitId = waitId)
            },
        )
    }
    // Свои даты «С» и «По» — календарём (шаг 4.3б); открытый календарь переживает сложение.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val period = PeriodPick(query.from, query.to, params.custom(now.date)) { picking = it }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(PersonalkaTheme.colors.bg)) {
            TopBar(SlotFormat.TITLE, onBack = onBack)
            if (!loaded) return@Column
            wait?.let {
                Text(
                    SlotFormat.forWait(it.name),
                    Modifier.padding(horizontal = Dimens.space4).padding(bottom = Dimens.space2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PersonalkaTheme.colors.textMuted,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(paramsScroll).padding(Dimens.space4)) {
                        Params(params, places, period, more, { more = it }, change)
                    }
                    VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(resultScroll).padding(Dimens.space4).padding(bottom = 56.dp)) {
                        Results(result, placeShort, copy, share, open)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(resultScroll).padding(Dimens.space4).padding(bottom = 56.dp)) {
                    Params(params, places, period, more, { more = it }, change)
                    HorizontalDivider(Modifier.padding(vertical = Dimens.space4), color = PersonalkaTheme.colors.divider)
                    Results(result, placeShort, copy, share, open)
                }
            }
        }
        picking?.let { end ->
            DatePickerPanel(
                initial = if (end == PICK_FROM) query.from else query.to,
                onPick = { date ->
                    picking = null
                    val from = if (end == PICK_FROM) date else query.from
                    val to = if (end == PICK_FROM) maxOf(query.to, date) else maxOf(date, from)
                    change(params.copy(fromDate = from, toDate = to))
                },
                onDismiss = { picking = null },
            )
        }
    }
}

private const val PICK_FROM = "from"
private const val PICK_TO = "to"

/** Период на экране: даты запроса, свои ли они, и открыть календарь для «С» или «По». */
private class PeriodPick(val from: LocalDate, val to: LocalDate, val custom: Boolean, val onPick: (String) -> Unit)

private const val PREF_KEY = "slots.params"

/**
 * Параметры 4.13: период, место, длительность и перерыв видны всегда — это главное; дни недели, время начала
 * и переключатели — под «Дни, время, запреты» (раскрыто, если что-то из них уже выбрано).
 */
@Composable
private fun ColumnScope.Params(
    p: SlotParams,
    places: List<CalendarPlace>,
    period: PeriodPick,
    more: Boolean,
    onMore: (Boolean) -> Unit,
    onChange: (SlotParams) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    Label(SlotFormat.PERIOD)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        SlotFormat.PERIODS.forEach { days ->
            ChoiceChip(
                SlotFormat.period(days),
                !period.custom && p.periodDays == days,
                { onChange(p.copy(periodDays = days, fromDate = null, toDate = null)) },
                Modifier.testTag(slotPeriodTag(days)),
            )
        }
        ChoiceChip(SlotFormat.CUSTOM_PERIOD, period.custom, { period.onPick(PICK_FROM) }, Modifier.testTag(SLOTS_CUSTOM_TAG))
    }
    // Даты периода видны всегда: «С 27.10.2026 по 09.11.2026», нажатие — календарь.
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        DateChip(SlotFormat.FROM, period.from, Modifier.testTag(SLOTS_FROM_TAG)) { period.onPick(PICK_FROM) }
        DateChip(SlotFormat.TO, period.to, Modifier.testTag(SLOTS_TO_TAG)) { period.onPick(PICK_TO) }
    }
    if (places.size > 1) {
        Label(SlotFormat.PLACE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            ChoiceChip(SlotFormat.ANY_PLACE, p.locationId == null, { onChange(p.copy(locationId = null)) }, Modifier.testTag(slotPlaceTag(null)))
            places.forEach { place ->
                ChoiceChip(place.shortName, p.locationId == place.id, { onChange(p.copy(locationId = place.id)) }, Modifier.testTag(slotPlaceTag(place.id)))
            }
        }
    }
    Label(SlotFormat.DURATION)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        (SlotFormat.DURATIONS + p.durationMin).distinct().sorted().forEach { minutes ->
            ChoiceChip(DurationFormat.minutes(minutes), p.durationMin == minutes, { onChange(p.copy(durationMin = minutes)) }, Modifier.testTag(slotDurationTag(minutes)))
        }
    }
    Label(SlotFormat.GAP)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        SlotFormat.GAPS.forEach { gap ->
            ChoiceChip(SlotFormat.gap(gap), p.maxGapMin == gap, { onChange(p.copy(maxGapMin = gap)) }, Modifier.testTag(slotGapTag(gap)))
        }
    }
    Text(SlotFormat.GAP_HINT, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space3)
            .heightIn(min = Dimens.touch)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { onMore(!more) }
            .testTag(SLOTS_MORE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SlotFormat.MORE, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.text)
        Icon(if (more) PIcons.ExpandLess else PIcons.ExpandMore, contentDescription = null, tint = colors.textMuted)
    }
    if (!more) return
    Label(SlotFormat.DAYS)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DateFormat.WEEK.forEach { day ->
            val on = day in p.days
            ChoiceChip(
                text = DateFormat.weekdayShort(day),
                selected = on,
                onClick = { onChange(p.copy(days = if (on) p.days - day else p.days + day)) },
                modifier = Modifier.weight(1f).testTag("slots-day-${day.isoDayNumber}"),
                toggle = true,
                fill = true,
            )
        }
    }
    Label(SlotFormat.TIME)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        (WaitRules.PRESETS + p.ranges).distinct().forEach { range ->
            val on = range in p.ranges
            val text = if (WaitRules.isPreset(range)) WaitFormat.preset(range) else WaitFormat.range(range)
            ChoiceChip(text, on, { onChange(p.copy(ranges = if (on) p.ranges - range else (p.ranges + range).sortedBy { it.start })) }, toggle = true)
        }
    }
    Spacer(Modifier.size(Dimens.space2))
    SwitchRow(SlotFormat.RESTRICTIONS, p.restrictions, { onChange(p.copy(restrictions = it)) })
    SwitchRow(SlotFormat.DUTY_BUSY, p.dutyBusy, { onChange(p.copy(dutyBusy = it)) }, subtitle = SlotFormat.DUTY_BUSY_HINT)
    SwitchRow(SlotFormat.EMPTY_DAYS, p.emptyDays, { onChange(p.copy(emptyDays = it)) }, subtitle = SlotFormat.EMPTY_DAYS_HINT)
}

@Composable
private fun DateChip(title: String, date: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .heightIn(min = Dimens.touch)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        Spacer(Modifier.width(Dimens.space1))
        Text(DateFormat.full(date), style = MaterialTheme.typography.bodyLarge, color = colors.text)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        Modifier.padding(top = Dimens.space3, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/**
 * Свободное время по помещениям (шаг 4.3б): «ББ», под ним дни «Вторник (27.10.2026)» и время начала занятий по :00
 * и :30; вверху — сколько дней, «Скопировать список» и «Поделиться». Пока считается — пусто.
 */
@Composable
private fun ColumnScope.Results(
    result: SlotResult?,
    placeShort: (String) -> String,
    onCopy: () -> Unit,
    onShare: (() -> Unit)?,
    onOpen: (LocalDateTime, String?) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    if (result == null) return
    if (result.places.isEmpty()) {
        Text(SlotFormat.NONE, Modifier.testTag(SLOTS_NONE_TAG), style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text(SlotFormat.NONE_HINT, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        return
    }
    Text(SlotFormat.summary(result.places), style = MaterialTheme.typography.titleMedium, color = colors.text)
    Text(SlotFormat.TAP_HINT, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    Row(Modifier.padding(top = Dimens.space3), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        OutlineButton(SlotFormat.COPY, onCopy, Modifier.testTag(SLOTS_COPY_TAG), icon = PIcons.ContentCopy)
        if (onShare != null) OutlineButton(SlotFormat.SHARE, onShare, Modifier.testTag(SLOTS_SHARE_TAG), icon = PIcons.Share)
    }
    result.places.forEach { place ->
        place.locationId?.let { id ->
            Text(
                placeShort(id) + ":",
                Modifier.padding(top = Dimens.space5).semantics { heading() }.testTag(slotPlaceRowTag(id)),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        place.days.forEach { day -> Day(day, result.waiting[day].orEmpty(), onOpen) }
    }
}

@Composable
private fun Day(day: DayStarts, waiting: List<String>, onOpen: (LocalDateTime, String?) -> Unit) {
    val colors = PersonalkaTheme.colors
    Text(
        SlotFormat.dayLong(day.date),
        Modifier.padding(top = Dimens.space3, bottom = Dimens.space2),
        style = MaterialTheme.typography.labelLarge,
        color = colors.text,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        day.starts.forEach { minute ->
            Box(
                Modifier
                    .heightIn(min = Dimens.touch)
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.surface)
                    .clickable(role = Role.Button) { onOpen(day.at(minute), day.locationId) }
                    .testTag(slotStartTag(day, minute))
                    .padding(horizontal = Dimens.space3),
                contentAlignment = Alignment.Center,
            ) {
                Text(SlotFormat.start(minute), style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
    }
    if (waiting.isNotEmpty()) {
        Text(SlotFormat.waiting(waiting), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}
