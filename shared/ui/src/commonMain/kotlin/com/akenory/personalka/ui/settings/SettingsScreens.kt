package com.akenory.personalka.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.settings.draft
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.BackupFormat
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.ShadeDot
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.salary.openFeatures
import com.akenory.personalka.ui.setup.PlaceEditor
import com.akenory.personalka.ui.setup.TypeEditor
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.Shade
import com.akenory.personalka.ui.theme.dutyFill
import com.akenory.personalka.ui.theme.shade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Экраны настроек в стопке раздела «Ещё». */
object SettingsRoute {
    const val HOME = "settings"
    const val PROFILE = "settings/profile"
    const val PLACES = "settings/places"
    const val CALENDAR = "settings/calendar"
    const val REMINDERS = "settings/reminders"
    const val DATA = "settings/data"
    const val ACCOUNT = "settings/account"
    const val CABINETS = "settings/cabinets"
    const val RATES = "settings/rates"
    const val TIERS = "settings/tiers"
    const val CATEGORY = "settings/category"
    const val SCHEME = "settings/scheme"
    const val NEW_PLACE = "place/new"
    const val NEW_TYPE = "type/new"
    private const val PLACE = "place/"
    private const val TYPE = "type/"
    private const val RATE = "rate/"

    fun place(id: String) = PLACE + id
    fun type(id: String) = TYPE + id
    fun rate(typeId: String) = RATE + typeId

    fun isSettings(route: String): Boolean =
        route == HOME || route.startsWith("settings/") || route.startsWith(PLACE) || route.startsWith(TYPE) || route.startsWith(RATE)

    /** Форма с «✕» во весь экран: на сложенном Fold нижняя панель прячется (макеты «Новый тип»). */
    fun isForm(route: String?): Boolean = route != null && (route.startsWith(PLACE) || route.startsWith(TYPE))

    internal fun placeId(route: String): String? = route.removePrefix(PLACE).takeIf { route.startsWith(PLACE) && it != "new" }
    internal fun typeId(route: String): String? = route.removePrefix(TYPE).takeIf { route.startsWith(TYPE) && it != "new" }
    internal fun rateTypeId(route: String): String? = route.removePrefix(RATE).takeIf { route.startsWith(RATE) }
}

internal const val PROFILE_ROW_TAG = "settings-profile"
internal const val PLACES_ROW_TAG = "settings-places"
internal const val ADD_PLACE_TAG = "add-place"
internal const val ADD_TYPE_TAG = "add-type"
internal fun placeRowTag(shortName: String) = "place-$shortName"
internal fun typeRowTag(name: String) = "type-$name"

/**
 * Экран настроек по маршруту (ТЗ 5.16). Пока есть «Профиль», «Места и типы», «Календарь» (шаг 1.13г) и «Данные»
 * (шаг 1.15, если платформа дала [backup]; в них — «Аккаунт», шаг 2.8а, если есть [account], и «Сервер», шаг 2.10а,
 * если есть [sync]), «Кабинеты» (шаг 2.13, если [cabinets] — сервер подключён и «Кабинеты» открыты) и «Ставки и ступени»
 * (шаг 3.6); остальные разделы появятся в своих шагах, до этого их не видно (10.7).
 *
 * @param onForm открыть форму тренировки, дежурства или своего запрета ([com.akenory.personalka.ui.training.TrainingFormKey])
 *   из списков раздела «Календарь».
 * Записи в базу идут в [scope] приложения: экран закрывается сразу, а запись и «Отменить» доживают до конца.
 */
@Composable
fun SettingsScreen(
    route: String,
    shell: ShellState,
    settings: SettingsRepository,
    calendar: CalendarRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onForm: (String) -> Unit,
    onBack: (() -> Unit)?,
    backup: BackupTools? = null,
    reminders: Reminders? = null,
    notifications: NotificationAccess? = null,
    account: AccountRepository? = null,
    sync: SyncTools? = null,
    cabinets: Boolean = false,
) {
    val places by remember(settings) { settings.places }.collectAsState(initial = null)
    val types by remember(settings) { settings.types }.collectAsState(initial = null)
    val loadedPlaces = places ?: return
    val loadedTypes = types ?: return
    val activePlaces = loadedPlaces.filter { !it.archived }
    val activeTypes = loadedTypes.filter { !it.archived }
    val close: () -> Unit = { shell.back() }

    when {
        route == SettingsRoute.HOME -> SettingsHome(settings, calendar, backup, activePlaces, activeTypes, now, shell, onBack, cabinets)
        route == SettingsRoute.RATES -> RatesScreen(settings.rates, activePlaces, activeTypes, now.date, shell, close)
        route == SettingsRoute.TIERS -> TierEditor(settings.rates, loadedTypes, now.date, messages, scope, close)
        SettingsRoute.rateTypeId(route) != null -> {
            val type = loadedTypes.firstOrNull { it.id == SettingsRoute.rateTypeId(route) } ?: return
            RateEditor(settings.rates, type, activePlaces, loadedTypes, now.date, messages, scope, close)
        }
        route == SettingsRoute.CABINETS -> if (cabinets) CabinetSettingsScreen(settings, messages, scope, close)
        route == SettingsRoute.CALENDAR -> CalendarSettingsScreen(calendar.settings, now, messages, scope, onForm, close)
        route == SettingsRoute.REMINDERS -> ReminderSettingsScreen(calendar.reminders, reminders, notifications, scope, close)
        route == SettingsRoute.DATA -> backup?.let { DataSettingsScreen(it, messages, scope, close, account, now, sync) { shell.open(SettingsRoute.ACCOUNT) } }
        route == SettingsRoute.ACCOUNT -> account?.let { AccountScreen(it, settings, now, messages, scope, close) }
        route == SettingsRoute.PROFILE -> ProfileScreen(settings, openFeatures(account, now.date), now.date, shell, scope, messages, close)
        route == SettingsRoute.CATEGORY -> CategoryEditor(settings, now.date, messages, scope, close)
        route == SettingsRoute.SCHEME -> SchemeEditor(settings, openFeatures(account, now.date), now.date, messages, scope, close)
        route == SettingsRoute.PLACES -> PlacesAndTypes(loadedPlaces, loadedTypes, shell, settings, scope, onBack = close)
        route == SettingsRoute.NEW_PLACE -> PlaceEditor(
            title = "Новое место",
            initial = ReferenceRules.newPlace(activePlaces.map { it.color }),
            others = activePlaces.map { it.draft() },
            samples = TypeFormat.shadeSamples(activeTypes.map { it.draft() }),
            onSave = { draft ->
                scope.launch { settings.addPlace(draft) }
                close()
                messages.show("Место «${draft.name.trim()}» добавлено")
            },
            onClose = close,
        )
        route == SettingsRoute.NEW_TYPE -> TypeEditor(
            title = "Новый тип",
            initial = ReferenceRules.newType(activeTypes.filter { it.kind != TrainingKind.DUTY }.map { it.shade }),
            others = activeTypes.map { it.draft() },
            places = activePlaces.map { it.draft() },
            onSave = { draft ->
                scope.launch { settings.addType(draft) }
                close()
                messages.show("Тип «${draft.name.trim()}» добавлен")
            },
            onClose = close,
        )
        SettingsRoute.placeId(route) != null -> {
            val place = loadedPlaces.firstOrNull { it.id == SettingsRoute.placeId(route) } ?: return
            PlaceEditor(
                title = place.name,
                initial = place.draft(),
                others = activePlaces.filter { it.id != place.id }.map { it.draft() },
                samples = TypeFormat.shadeSamples(activeTypes.map { it.draft() }),
                archived = place.archived,
                onSave = { draft ->
                    scope.launch { settings.updatePlace(place.id, draft, place.archived) }
                    close()
                },
                onArchive = { archive ->
                    scope.launch { settings.updatePlace(place.id, place.draft(), archive) }
                    close()
                    if (archive) {
                        messages.showUndo("«${place.name}» в архиве") {
                            scope.launch { settings.updatePlace(place.id, place.draft(), false) }
                        }
                    }
                },
                onClose = close,
            )
        }
        SettingsRoute.typeId(route) != null -> {
            val type = loadedTypes.firstOrNull { it.id == SettingsRoute.typeId(route) } ?: return
            TypeEditor(
                title = type.name,
                initial = type.draft(),
                others = activeTypes.filter { it.id != type.id }.map { it.draft() },
                places = activePlaces.map { it.draft() },
                archived = type.archived,
                onSave = { draft ->
                    scope.launch { settings.updateType(type.id, draft, type.archived) }
                    close()
                },
                onArchive = { archive ->
                    scope.launch { settings.updateType(type.id, type.draft(), archive) }
                    close()
                    if (archive) {
                        messages.showUndo("«${type.name}» в архиве") {
                            scope.launch { settings.updateType(type.id, type.draft(), false) }
                        }
                    }
                },
                onClose = close,
            )
        }
    }
}

/** «Настройки»: разделы строками со стрелкой. */
@Composable
private fun SettingsHome(
    settings: SettingsRepository,
    calendar: CalendarRepository,
    backup: BackupTools?,
    places: List<LocationEntity>,
    types: List<TrainingTypeEntity>,
    now: LocalDateTime,
    shell: ShellState,
    onBack: (() -> Unit)?,
    cabinets: Boolean,
) {
    val coach by remember(settings) { settings.coach }.collectAsState(initial = null)
    val cabinetsOn by remember(settings) { settings.cabinetsEnabled }.collectAsState(initial = false)
    val prefs by remember(calendar) { calendar.settings.prefs }.collectAsState(initial = null)
    val diary by remember(calendar) { calendar.reminders.prefs }.collectAsState(initial = null)
    val rates by remember(settings) { settings.rates.rates }.collectAsState(initial = null)
    val tiers by remember(settings) { settings.rates.tiers }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Настройки", onBack = onBack)
        ListCard(Modifier.padding(horizontal = Dimens.space4)) {
            NavRow(
                title = "Профиль",
                subtitle = coach?.let { CoachName(it.lastName, it.firstName, it.middleName).full },
                onClick = { shell.open(SettingsRoute.PROFILE) },
                modifier = Modifier.testTag(PROFILE_ROW_TAG),
            )
            RowDivider()
            NavRow(
                title = "Места и типы",
                subtitle = TypeFormat.placesAndTypes(places.size, types.size),
                onClick = { shell.open(SettingsRoute.PLACES) },
                modifier = Modifier.testTag(PLACES_ROW_TAG),
            )
            RowDivider()
            NavRow(
                title = RateFormat.TITLE,
                subtitle = rates?.let { r -> tiers?.let { t -> RateFormat.settingsRow(missingRates(types, places, r, now.date), currentTiers(types, t, now.date)) } },
                onClick = { shell.open(SettingsRoute.RATES) },
                modifier = Modifier.testTag(RATES_ROW_TAG),
            )
            RowDivider()
            NavRow(
                title = CalendarSettingsFormat.TITLE,
                subtitle = prefs?.let { calendarSummary(it) },
                onClick = { shell.open(SettingsRoute.CALENDAR) },
                modifier = Modifier.testTag(CALENDAR_ROW_TAG),
            )
            RowDivider()
            NavRow(
                title = ReminderFormat.TITLE,
                subtitle = diary?.let { reminderSummary(it) },
                onClick = { shell.open(SettingsRoute.REMINDERS) },
                modifier = Modifier.testTag(REMINDERS_ROW_TAG),
            )
            if (cabinets) {
                RowDivider()
                NavRow(
                    title = CabinetLinkFormat.SETTINGS_TITLE,
                    subtitle = if (cabinetsOn) CabinetLinkFormat.ON else CabinetLinkFormat.OFF,
                    onClick = { shell.open(SettingsRoute.CABINETS) },
                    modifier = Modifier.testTag(CABINETS_ROW_TAG),
                )
            }
            if (backup != null) {
                val data by backup.repository.state.collectAsState()
                RowDivider()
                NavRow(
                    title = BackupFormat.TITLE,
                    subtitle = dataSummary(data),
                    onClick = { shell.open(SettingsRoute.DATA) },
                    modifier = Modifier.testTag(DATA_ROW_TAG),
                )
            }
        }
    }
}

/**
 * «Профиль» (5.16): ФИО тренера с «Сохранить»; ниже — категория и схема выплат с датой, ожидаемый аванс
 * (в схеме) и «Совместное ведение групп» (шаг 3.6б).
 */
@Composable
private fun ProfileScreen(settings: SettingsRepository, open: Set<Feature>, today: LocalDate, shell: ShellState, scope: CoroutineScope, messages: MessageState, onBack: () -> Unit) {
    val coach by remember(settings) { settings.coach }.collectAsState(initial = null)
    val loaded = coach ?: return
    var last by rememberSaveable(loaded.id) { mutableStateOf(loaded.lastName) }
    var first by rememberSaveable(loaded.id) { mutableStateOf(loaded.firstName) }
    var middle by rememberSaveable(loaded.id) { mutableStateOf(loaded.middleName.orEmpty()) }
    val name = CoachName(last, first, middle)
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

    Column(Modifier.fillMaxSize()) {
        TopBar("Профиль", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Field("Фамилия", last, { last = it }, keyboardOptions = words)
            Field("Имя", first, { first = it }, keyboardOptions = words)
            Field("Отчество · если есть", middle, { middle = it }, keyboardOptions = words.copy(imeAction = ImeAction.Done))
            Text(
                "Имя и фамилию родители видят в кабинете, полное ФИО входит в согласие на обработку данных.",
                style = MaterialTheme.typography.bodySmall,
                color = PersonalkaTheme.colors.textMuted,
            )
            Column { ProfileSalaryRows(settings, open, today, shell, scope) }
            Spacer(Modifier.height(Dimens.space2))
        }
        BottomAction(
            text = "Сохранить",
            enabled = name.isComplete,
            onClick = {
                scope.launch { settings.saveName(name) }
                onBack()
                messages.show("Сохранено")
            },
        )
    }
}

/**
 * «Места и типы» (макет TypesList): места с коротким именем на подложке своего цвета и ручкой перетаскивания,
 * типы с точкой оттенка (в цвете первого места) и строкой «Персональная · 50 мин · как ПТ». Архивные — в конце
 * списков: история их помнит.
 */
@Composable
private fun PlacesAndTypes(
    places: List<LocationEntity>,
    types: List<TrainingTypeEntity>,
    shell: ShellState,
    settings: SettingsRepository,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val active = places.filter { !it.archived }
    val archivedPlaces = places.filter { it.archived }
    val firstPlace = PlaceColor.of(active.firstOrNull()?.color)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Места и типы", onBack = onBack)
        Column(Modifier.padding(horizontal = Dimens.space4)) {
            HeaderWithAction("Места", "Место", ADD_PLACE_TAG) { shell.open(SettingsRoute.NEW_PLACE) }
            ListCard {
                ReorderablePlaces(
                    places = active,
                    onOpen = { shell.open(SettingsRoute.place(it.id)) },
                    onMove = { ids -> scope.launch { settings.reorderPlaces(ids + archivedPlaces.map { it.id }) } },
                )
                archivedPlaces.forEach { place ->
                    RowDivider()
                    PlaceRow(place, Modifier.clickable { shell.open(SettingsRoute.place(place.id)) })
                }
            }

            Spacer(Modifier.height(Dimens.space4))
            HeaderWithAction("Типы тренировок", "Новый тип", ADD_TYPE_TAG) { shell.open(SettingsRoute.NEW_TYPE) }
            ListCard {
                (types.filter { !it.archived } + types.filter { it.archived }).forEachIndexed { index, type ->
                    if (index > 0) RowDivider()
                    val draft = type.draft()
                    NavRow(
                        title = type.name,
                        subtitle = TypeFormat.summary(draft) + if (type.archived) " · в архиве" else "",
                        onClick = { shell.open(SettingsRoute.type(type.id)) },
                        modifier = Modifier.testTag(typeRowTag(type.name)),
                        leading = if (type.kind == TrainingKind.DUTY) {
                            // Дежурство в календаре — заливка цвета места, оттенка у него нет.
                            { ShadeDot(Shade(dutyFill(firstPlace, colors), firstPlace.tone(colors.isDark).stripe)) }
                        } else {
                            { ShadeDot(firstPlace.shade(colors.isDark, type.shade)) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(Dimens.space6))
        }
    }
}

@Composable
private fun HeaderWithAction(title: String, action: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Dimens.space2, bottom = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = PersonalkaTheme.colors.textMuted,
        )
        SecondaryButton(action, onClick, icon = PIcons.Add, modifier = Modifier.testTag(tag))
    }
}

private val PlaceRowHeight = 56.dp

/**
 * Действующие места с ручкой перетаскивания. Порядок меняется сразу на экране, в базу пишется, когда
 * палец отпущен. Для TalkBack — действия «Выше» и «Ниже» у строки.
 */
@Composable
private fun ReorderablePlaces(places: List<LocationEntity>, onOpen: (LocationEntity) -> Unit, onMove: (List<String>) -> Unit) {
    val ids = places.map { it.id }
    var order by remember(ids) { mutableStateOf(ids) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { PlaceRowHeight.toPx() }
    val byId = places.associateBy { it.id }

    fun move(id: String, by: Int): Boolean {
        val from = order.indexOf(id)
        val to = from + by
        if (from < 0 || to !in order.indices) return false
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    order.forEachIndexed { index, id ->
        val place = byId[id] ?: return@forEachIndexed
        key(id) {
            if (index > 0) RowDivider()
            PlaceRow(
                place = place,
                modifier = Modifier
                    .zIndex(if (dragged == id) 1f else 0f)
                    .graphicsLayer { translationY = if (dragged == id) offset else 0f }
                    .background(if (dragged == id) PersonalkaTheme.colors.surfaceAlt else PersonalkaTheme.colors.surface)
                    .clickable { onOpen(place) }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Выше") { move(id, -1).also { if (it) onMove(order) } },
                            CustomAccessibilityAction("Ниже") { move(id, 1).also { if (it) onMove(order) } },
                        )
                    },
                // Ключ — и список: когда новый порядок вернулся из базы, жест берёт уже новое состояние.
                handle = Modifier.pointerInput(id, ids) {
                    detectDragGestures(
                        onDragStart = {
                            dragged = id
                            offset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            if (offset > rowPx / 2 && move(id, 1)) offset -= rowPx
                            if (offset < -rowPx / 2 && move(id, -1)) offset += rowPx
                        },
                        onDragEnd = {
                            dragged = null
                            offset = 0f
                            if (order != ids) onMove(order)
                        },
                        onDragCancel = {
                            dragged = null
                            offset = 0f
                        },
                    )
                },
            )
        }
    }
}

/** Строка места: короткое имя на подложке цвета места, название; у действующего — ручка перетаскивания. */
@Composable
private fun PlaceRow(place: LocationEntity, modifier: Modifier = Modifier, handle: Modifier? = null) {
    val colors = PersonalkaTheme.colors
    val tone = PlaceColor.of(place.color).tone(colors.isDark)
    Row(
        modifier
            .fillMaxWidth()
            .height(PlaceRowHeight)
            .testTag(placeRowTag(place.shortName))
            .padding(start = Dimens.space4, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .widthIn(min = 36.dp)
                .background(tone.block(2), MaterialTheme.shapes.small)
                .border(1.5.dp, tone.stripe, MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(place.shortName, style = MaterialTheme.typography.labelMedium, color = tone.text)
        }
        Spacer(Modifier.width(Dimens.space4))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge, color = colors.text, maxLines = 1)
            if (place.archived) Text("в архиве", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (handle != null) {
            Box(handle.size(Dimens.touch), contentAlignment = Alignment.Center) {
                Icon(PIcons.DragIndicator, contentDescription = "Перетащить", tint = colors.textMuted)
            }
        } else {
            Spacer(Modifier.width(Dimens.space3))
        }
    }
}
