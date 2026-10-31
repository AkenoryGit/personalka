package com.akenory.personalka.ui.training

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.akenory.personalka.domain.format.GroupShareFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SptAsk
import com.akenory.personalka.data.results.ResultRepository
import com.akenory.personalka.data.training.CardClient
import com.akenory.personalka.data.training.CardResult
import com.akenory.personalka.data.training.GuestChoice
import com.akenory.personalka.data.training.TrainingCard
import com.akenory.personalka.data.training.TrainingCardRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ReliabilityFormat
import com.akenory.personalka.domain.format.SptFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.SptPrompt
import com.akenory.personalka.domain.training.CancelReasons
import com.akenory.personalka.domain.training.CancelRules
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.CardMenu
import com.akenory.personalka.domain.training.CarryChoice
import com.akenory.personalka.domain.training.ClientOutcome
import com.akenory.personalka.domain.training.TrainingCardRules
import com.akenory.personalka.domain.training.TrainingPhase
import com.akenory.personalka.domain.training.came
import com.akenory.personalka.ui.clients.MenuAction
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.clients.SearchField
import com.akenory.personalka.ui.clients.SimilarClientPanel
import com.akenory.personalka.ui.clients.SptPurchasePanel
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.ReliabilityChip
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.results.ResultFormPanel
import com.akenory.personalka.ui.results.ResultWho
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.yearMonth
import com.akenory.personalka.ui.tour.TourTarget
import com.akenory.personalka.ui.tour.tourTarget
import com.akenory.personalka.ui.components.swallowTaps

const val TRAINING_CARD_TAG = "training-card"
const val CANCEL_PANEL_TAG = "cancel-panel"
const val CARD_MENU_TAG = "card-menu"
const val CARD_SCRIM_TAG = "card-scrim"
const val CARRY_PANEL_TAG = "carry-panel"
const val SPT_ASK_TAG = "spt-ask"
const val CHARGE_SWITCH_TAG = "charge-switch"
const val OWN_REASON_TAG = "own-reason"
const val CLOSED_MONTH_TAG = "closed-month"
const val DELETE_FOLLOWING_TAG = "delete-following"
const val CARD_BIRTHDAY_TAG = "card-birthday"

fun cardClientTag(id: String) = "card-client-$id"
fun cardClientActionTag(id: String) = "card-client-action-$id"
fun cancelWhoTag(id: String) = "cancel-who-$id"
const val CANCEL_WHOLE_TAG = "cancel-whole"
const val ADD_GUEST_TAG = "add-guest"
const val GUEST_PANEL_TAG = "guest-panel"
const val GUEST_SEARCH_TAG = "guest-search"
const val NEW_GUEST_TAG = "guest-new"
const val NEW_GUEST_LAST_TAG = "guest-new-last"
const val NEW_GUEST_FIRST_TAG = "guest-new-first"
fun guestPickTag(id: String) = "guest-pick-$id"
const val CARD_PAY_LINE_TAG = "card-pay-line"
const val CARD_SHARE_TAG = "card-share"

/** Системное «Поделиться» текстом (шаг 5.3п): даёт `App`; нет — текст копируется. */
val LocalShareText = staticCompositionLocalOf<((String) -> Unit)?> { null }
fun cardKidCoachTag(id: String) = "card-kid-coach-$id"
fun guestWhoseTag(o: Owner) = "guest-whose-${o.name}"

/**
 * Что открыто в карточке — строкой, чтобы пережить пересоздание окна: тренировка или сразу её панель отмены
 * («Отменить» в меню блока календаря, 5.3).
 */
object TrainingCardKey {
    private const val CANCEL = "#cancel"

    fun card(id: String): String = id

    fun cancel(id: String): String = id + CANCEL

    fun id(key: String): String = key.removeSuffix(CANCEL)

    fun opensCancel(key: String): Boolean = key.endsWith(CANCEL)
}

/** Кого отменяем — строкой: «Всю тренировку» или клиенты через запятую. */
private const val WHOLE = ""

/**
 * Карточка тренировки (ТЗ 5.4) и панель отмены (5.6) над [content] — календарём. На сложенном Fold — панель снизу
 * поверх затемнения, нажатие мимо закрывает; на разложенном — справа без затемнения, календарь слева остаётся
 * рабочим: нажатие на другую тренировку меняет карточку (макет «Разложенный Fold · сплит»).
 *
 * Открытая панель отмены, всё выбранное в ней и окна поверх хранятся здесь, выше ветки по ширине: сложение Fold
 * их не сбрасывает.
 *
 * @param key [TrainingCardKey]; `null` — карточка закрыта.
 * @param onEdit «Изменить» и «Перенести вместо отмены» — форма тренировки (5.5).
 * @param onClient имя клиента — его карточка (5.9); [onGroup] — название группы — карточка группы (5.10).
 * @param onCopy «Копировать» в «…».
 * @param onDiary «Дневник» (5.7) — экран дневника поверх приложения.
 */
@Composable
fun TrainingCardHost(
    key: String?,
    wide: Boolean,
    cards: TrainingCardRepository,
    results: ResultRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onEdit: (String) -> Unit,
    onClient: (String) -> Unit,
    onGroup: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDiary: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val id = key?.let(TrainingCardKey::id)
    // Панель отмены: null — карточка; WHOLE — «Всю тренировку»; иначе id выбранных клиентов через запятую.
    var cancelWho by rememberSaveable(key) { mutableStateOf(if (key != null && TrainingCardKey.opensCancel(key)) WHOLE else null) }
    var reason by rememberSaveable(key) { mutableStateOf<String?>(CancelReasons.CANNOT_COME) }
    var ownReason by rememberSaveable(key) { mutableStateOf("") }
    var notifiedAt by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var charged by rememberSaveable(key) { mutableStateOf<String?>(null) }
    // «Другое время»: сначала дата, потом время.
    var pickDate by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var pickingDate by rememberSaveable(key) { mutableStateOf(false) }
    var askDelete by rememberSaveable(key) { mutableStateOf(false) }
    // «Удалить → Эту и все следующие» у тренировки серии (шаг 3.9к): второе подтверждение с числом тренировок.
    var askFollowing by rememberSaveable(key) { mutableStateOf(false) }
    // «Добавить результат» (5.11): форма с днём тренировки и её клиентами.
    var addingResult by rememberSaveable(key) { mutableStateOf(false) }
    // «Добавить на раз»: открыт выбор, набранный поиск, «Новый клиент» с фамилией и именем.
    var addingGuest by rememberSaveable(key) { mutableStateOf(false) }
    var guestQuery by rememberSaveable(key) { mutableStateOf("") }
    var newGuest by rememberSaveable(key) { mutableStateOf(false) }
    var guestLast by rememberSaveable(key) { mutableStateOf("") }
    var guestFirst by rememberSaveable(key) { mutableStateOf("") }
    // Похожий на нового ребёнка «на раз» (5.8, шаг 3.9о): окно «Похожий клиент уже есть».
    var guestSimilar by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var guestChecking by remember(key) { mutableStateOf(false) }
    // «Чей» ребёнок «на раз» в группе «Каждый своих» (шаг 5.3м): по умолчанию мой.
    var guestWhose by rememberSaveable(key) { mutableStateOf(Owner.SELF.name) }

    // Вопрос о переносе плана после отмены или «Вернуть» (4.3): ждёт ответа и после закрытия карточки.
    var asked by remember { mutableStateOf<CardResult?>(null) }
    var card by remember(id) { mutableStateOf<TrainingCard?>(null) }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        cards.card(id).collect { loaded ->
            // Тренировку удалили — карточка закрывается.
            if (loaded == null) onClose() else card = loaded
        }
    }
    val shown = card?.takeIf { it.id == id }
    // Сверенный месяц (4.1, шаг 3.7б): первая правка в карточке спрашивает подтверждение, дальше — нет.
    val closedMonths by remember(cards) { cards.closedMonths }.collectAsState(initial = emptySet())
    var closedConfirmed by rememberSaveable(key) { mutableStateOf(false) }
    var closedPending by remember(key) { mutableStateOf<(() -> Unit)?>(null) }
    val closedMonth = shown?.state?.start?.date?.yearMonth?.takeIf { it in closedMonths }

    fun guarded(action: () -> Unit) {
        if (closedMonth != null && !closedConfirmed) closedPending = action else action()
    }
    // «Это время ждут» (4.16) — в панели отмены и в подтверждении удаления; пересчитывается, когда они открываются.
    val waitLine by produceState<String?>(null, id, cancelWho != null, askDelete) {
        value = if (id != null && (cancelWho != null || askDelete)) cards.waitingLine(id) else null
    }
    // Сколько удалит «Эту и все следующие»: выбор есть, только если их больше одной.
    val followingCount by produceState<Int?>(null, id, askDelete, askFollowing) {
        if (id != null && (askDelete || askFollowing)) value = cards.followingCount(id)
    }
    // Вопрос СПТ на первой оплаченной ПТ (4.9, п. 4–5, шаг 3.7а) и его окно с датой покупки.
    val sptAsk by remember(id) { id?.let { cards.spt.ask(it) } ?: flowOf(null) }.collectAsState(initial = null)
    var sptAnswering by rememberSaveable(key) { mutableStateOf(false) }
    // «Кто ведёт» групповой тренировки с совместным ведением (шаг 3.7г).
    var editingSharing by rememberSaveable(key) { mutableStateOf(false) }
    val guestChoices by remember(id, addingGuest) {
        if (id != null && addingGuest) cards.guestChoices(id) else flowOf(emptyList())
    }.collectAsState(emptyList())

    fun closeGuest() {
        addingGuest = false
        guestQuery = ""
        newGuest = false
        guestLast = ""
        guestFirst = ""
        guestSimilar = null
        guestWhose = Owner.SELF.name
    }

    fun done(result: CardResult?) {
        when {
            result == null -> Unit
            // Вопрос о плане (4.3): сообщение с «Отменить» — после ответа.
            result.carry != null || result.uncarry != null -> asked = result
            else -> messages.showUndo(result.message) { scope.launch { cards.undo(result.undo) } }
        }
    }

    val draft = CancelDraft(
        who = cancelWho,
        reason = reason,
        ownReason = ownReason,
        notifiedAt = notifiedAt?.let(LocalDateTime::parse),
        charged = charged?.toBooleanStrictOrNull(),
    )
    // Сообщение с «Отменить» стоит внизу поверх экрана: пока оно видно, кнопки карточки поднимаются над ним.
    val messageGap = if (messages.current != null) MESSAGE_GAP else 0.dp
    val pane: @Composable ColumnScope.() -> Unit = {
        if (shown != null) {
            val who = cancelWho
            if (who == null && addingGuest) {
                GuestBody(
                    choices = guestChoices,
                    today = now.date,
                    query = guestQuery,
                    onQuery = { guestQuery = it },
                    creating = newGuest,
                    lastName = guestLast,
                    firstName = guestFirst,
                    onCreate = { last, first ->
                        newGuest = true
                        guestLast = last
                        guestFirst = first
                    },
                    onLastName = { guestLast = it },
                    onFirstName = { guestFirst = it },
                    colleague = shown.eachOwnColleague,
                    whose = Owner.valueOf(guestWhose),
                    onWhose = { guestWhose = it.name },
                    onPick = { clientId ->
                        val whose = Owner.valueOf(guestWhose)
                        closeGuest()
                        guarded { scope.launch { done(cards.addGuest(shown.id, clientId, whose)) } }
                    },
                    onAddNew = {
                        val last = guestLast
                        val first = guestFirst
                        // Сначала — нет ли его уже среди клиентов, и в архиве: иначе в базе появится дубль.
                        if (!guestChecking) {
                            guestChecking = true
                            scope.launch {
                                val similar = cards.similarGuest(last, first)
                                guestChecking = false
                                if (similar != null) {
                                    guestSimilar = similar.id
                                } else {
                                    val whose = Owner.valueOf(guestWhose)
                                    closeGuest()
                                    guarded { scope.launch { done(cards.addNewGuest(shown.id, last, first, whose)) } }
                                }
                            }
                        }
                    },
                    onBack = ::closeGuest,
                )
            } else if (who == null) {
                CardBody(
                    card = shown,
                    now = now,
                    wide = wide,
                    sptAsk = sptAsk,
                    onSpt = { sptAnswering = true },
                    onSharing = { editingSharing = true },
                    onClose = onClose,
                    onAction = { action ->
                        when (action) {
                            CardAction.DONE, CardAction.ALL_CAME -> guarded { scope.launch { done(cards.markDone(shown.id)) } }
                            CardAction.CANCEL -> cancelWho = WHOLE
                            CardAction.EDIT -> guarded { onEdit(shown.id) }
                            CardAction.RETURN -> guarded { scope.launch { done(cards.restore(shown.id)) } }
                            CardAction.DIARY -> onDiary(shown.id)
                        }
                    },
                    onMenu = { item ->
                        when (item) {
                            CardMenu.UNMARK_DONE -> guarded { scope.launch { done(cards.unmarkDone(shown.id)) } }
                            CardMenu.EDIT -> guarded { onEdit(shown.id) }
                            CardMenu.COPY -> onCopy(shown.id)
                            CardMenu.SKIP_DATE -> guarded {
                                scope.launch {
                                    val result = cards.skipDate(shown.id)
                                    if (result != null) onClose()
                                    done(result)
                                }
                            }
                            CardMenu.DELETE -> guarded { askDelete = true }
                            CardMenu.RESULT -> addingResult = true
                        }
                    },
                    onToggle = { clientId -> guarded { scope.launch { cards.toggleAttendance(shown.id, clientId) } } },
                    onCancelClient = { clientId -> cancelWho = clientId },
                    onReturnClient = { clientId -> guarded { scope.launch { done(cards.restoreClient(shown.id, clientId)) } } },
                    onAddGuest = { addingGuest = true },
                    onRemoveGuest = { clientId -> guarded { scope.launch { done(cards.removeGuest(shown.id, clientId)) } } },
                    onClient = onClient,
                    onGroup = onGroup,
                )
            } else {
                CancelBody(
                    card = shown,
                    draft = draft,
                    now = now,
                    waitLine = waitLine,
                    onBack = { cancelWho = null },
                    onWho = { cancelWho = it },
                    onReason = { picked ->
                        reason = picked
                        charged = null
                    },
                    onOwnReason = { ownReason = it },
                    onNow = {
                        notifiedAt = null
                        charged = null
                    },
                    onOtherTime = { pickingDate = true },
                    onCharged = { charged = it.toString() },
                    onCancel = { clientIds ->
                        val at = draft.notifiedAt ?: now
                        val reasonText = draft.reasonText
                        val chargedNow = draft.charged
                        cancelWho = null
                        guarded { scope.launch { done(cards.cancel(shown.id, clientIds, reasonText, at, chargedNow)) } }
                    },
                    onMove = {
                        cancelWho = null
                        guarded { onEdit(shown.id) }
                    },
                )
            }
            Spacer(Modifier.height(messageGap))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Календарь — всегда первым и на одном месте дерева: сложение Fold его не пересоздаёт (шаг 1.5).
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                content()
                if (key != null && !wide) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .background(PersonalkaTheme.colors.scrim)
                            .pointerInput(onClose) { detectTapGestures { onClose() } }
                            .testTag(CARD_SCRIM_TAG),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight * 0.88f)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .background(PersonalkaTheme.colors.bg)
                                .swallowTaps()
                                .semantics { paneTitle = shown?.title.orEmpty() }
                                .testTag(TRAINING_CARD_TAG),
                        ) {
                            Box(Modifier.fillMaxWidth().padding(top = Dimens.space2), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(PersonalkaTheme.colors.outline))
                            }
                            pane()
                        }
                    }
                }
            }
            if (key != null && wide) {
                VerticalDivider(color = PersonalkaTheme.colors.divider)
                Column(
                    Modifier
                        .width(CARD_WIDTH)
                        .fillMaxHeight()
                        .background(PersonalkaTheme.colors.bg)
                        .semantics { paneTitle = shown?.title.orEmpty() }
                        .testTag(TRAINING_CARD_TAG),
                ) { pane() }
            }
        }

        val similar = guestSimilar
        if (shown != null && similar != null) {
            SimilarClientPanel(
                clientId = similar,
                load = cards::client,
                today = now.date,
                onPick = { client ->
                    val whose = Owner.valueOf(guestWhose)
                    closeGuest()
                    guarded { scope.launch { done(cards.addSimilarGuest(shown.id, client.id, whose)) } }
                },
                onCreate = {
                    val last = guestLast
                    val first = guestFirst
                    val whose = Owner.valueOf(guestWhose)
                    closeGuest()
                    guarded { scope.launch { done(cards.addNewGuest(shown.id, last, first, whose)) } }
                },
                onDismiss = { guestSimilar = null },
            )
        }
        if (shown != null && askDelete) {
            val doneOne = shown.state.status == TrainingStatus.DONE
            Panel(title = if (doneOne) TrainingCardFormat.DELETE_DONE_TITLE else TrainingCardFormat.DELETE_TITLE, onDismiss = { askDelete = false }) {
                Text(
                    if (doneOne) TrainingCardFormat.deleteDoneText(shown.title, shown.state.start) else TrainingCardFormat.deleteText(shown.title, shown.state.start),
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    color = PersonalkaTheme.colors.text,
                )
                waitLine?.let { Note(it, Modifier.testTag(WAIT_LINE_TAG)) }
                val deleteOne = {
                    askDelete = false
                    scope.launch {
                        val result = cards.delete(shown.id)
                        if (result != null) onClose()
                        done(result)
                    }
                    Unit
                }
                // «Эту и все следующие» — только у будущей серии; проведённая удаляется одна.
                if (!doneOne && (followingCount ?: 0) > 1) {
                    Column(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        PrimaryButton(TrainingCardFormat.deleteOnly(shown.state.start.date), deleteOne, Modifier.fillMaxWidth())
                        OutlineButton(
                            TrainingCardFormat.DELETE_FOLLOWING,
                            {
                                askDelete = false
                                askFollowing = true
                            },
                            Modifier.fillMaxWidth().testTag(DELETE_FOLLOWING_TAG),
                        )
                        TextButton(onClick = { askDelete = false }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                            Text(TrainingCardFormat.KEEP, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
                        }
                    }
                } else {
                    PanelActions(
                        primary = TrainingCardFormat.DELETE,
                        onPrimary = deleteOne,
                        secondary = TrainingCardFormat.KEEP,
                        onSecondary = { askDelete = false },
                    )
                }
            }
        }
        val following = followingCount
        if (shown != null && askFollowing && following != null) {
            Panel(title = TrainingCardFormat.FOLLOWING_TITLE, onDismiss = { askFollowing = false }) {
                Text(
                    TrainingCardFormat.followingText(following, shown.state.start.date),
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    color = PersonalkaTheme.colors.text,
                )
                PanelActions(
                    primary = TrainingCardFormat.deleteFollowing(following),
                    onPrimary = {
                        askFollowing = false
                        scope.launch {
                            val result = cards.deleteFollowing(shown.id)
                            if (result != null) onClose()
                            done(result)
                        }
                    },
                    secondary = TrainingCardFormat.KEEP,
                    onSecondary = { askFollowing = false },
                )
            }
        }
        val pending = closedPending
        if (pending != null && closedMonth != null) {
            Panel(title = TrainingCardFormat.CLOSED_TITLE, onDismiss = { closedPending = null }, modifier = Modifier.testTag(CLOSED_MONTH_TAG)) {
                Text(TrainingCardFormat.closedText(closedMonth), style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
                PanelActions(
                    primary = TrainingCardFormat.CLOSED_CHANGE,
                    onPrimary = {
                        closedPending = null
                        closedConfirmed = true
                        pending()
                    },
                    secondary = TrainingCardFormat.CLOSED_KEEP,
                    onSecondary = { closedPending = null },
                )
            }
        }
        asked?.carry?.let { ask ->
            val answer = { choice: CarryChoice ->
                val result = asked
                asked = null
                if (result != null) scope.launch { done(cards.carry(result, choice)) }
            }
            Panel(title = TrainingCardFormat.CARRY_TITLE, onDismiss = { answer(CarryChoice.KEEP) }, modifier = Modifier.testTag(CARRY_PANEL_TAG)) {
                PlanQuestion(ask.whom, TrainingCardFormat.CARRY_MOVING to ask.moving, TrainingCardFormat.CARRY_EXISTING to ask.existing)
                Column(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PrimaryButton(TrainingCardFormat.CARRY_ADD, { answer(CarryChoice.ADD) }, Modifier.fillMaxWidth())
                    OutlineButton(TrainingCardFormat.CARRY_REPLACE, { answer(CarryChoice.REPLACE) }, Modifier.fillMaxWidth())
                    TextButton(onClick = { answer(CarryChoice.KEEP) }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                        Text(TrainingCardFormat.CARRY_KEEP, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
                    }
                }
            }
        }
        asked?.uncarry?.let { ask ->
            val answer = { remove: Boolean ->
                val result = asked
                asked = null
                if (result != null) scope.launch { done(cards.uncarry(result, remove)) }
            }
            Panel(title = TrainingCardFormat.UNCARRY_TITLE, onDismiss = { answer(false) }, modifier = Modifier.testTag(CARRY_PANEL_TAG)) {
                PlanQuestion(ask.whom, TrainingCardFormat.UNCARRY_CARRIED to ask.carried, TrainingCardFormat.UNCARRY_NOW to ask.current)
                PanelActions(
                    primary = TrainingCardFormat.CARRY_KEEP,
                    onPrimary = { answer(false) },
                    secondary = TrainingCardFormat.UNCARRY_REMOVE,
                    onSecondary = { answer(true) },
                )
            }
        }
        val sharing = shown?.sharing
        if (shown != null && editingSharing && sharing != null) {
            SharingPanel(
                sharing = sharing,
                onSave = { picked ->
                    editingSharing = false
                    guarded { scope.launch { done(cards.setSharing(shown.id, picked)) } }
                },
                onDismiss = { editingSharing = false },
            )
        }
        val ask = sptAsk
        if (shown != null && sptAnswering && ask != null) {
            SptPurchasePanel(
                case = ask.case,
                name = ask.name,
                initial = ask.purchaseDate,
                bonus = ask.bonus,
                title = sptQuestion(ask),
                onSave = { date ->
                    sptAnswering = false
                    scope.launch {
                        val saved = cards.spt.close(ask.case.id, date).first ?: return@launch
                        messages.showUndo(SptFormat.closedMessage(saved.name)) { scope.launch { cards.spt.undo(saved) } }
                    }
                },
                onUnmark = null,
                onDismiss = { sptAnswering = false },
            )
        }
        if (shown != null && addingResult) {
            // Отменённых не предлагаем; один клиент — сразу он.
            val present = shown.clients.filter { it.outcome != ClientOutcome.CANCELLED && it.outcome != ClientOutcome.CANCELLED_CHARGED }
            ResultFormPanel(
                who = present.ifEmpty { shown.clients }.map { ResultWho(it.id, it.name) },
                date = shown.state.start.date,
                editing = null,
                results = results,
                messages = messages,
                scope = scope,
                onClose = { addingResult = false },
            )
        }
        if (pickingDate) {
            DatePickerPanel(
                initial = (draft.notifiedAt ?: now).date,
                onPick = { date ->
                    pickingDate = false
                    pickDate = date.toString()
                },
                onDismiss = { pickingDate = false },
            )
        }
        pickDate?.let { date ->
            TimePickerPanel(
                initial = (draft.notifiedAt ?: now).time,
                onPick = { time ->
                    pickDate = null
                    notifiedAt = ClubTime.toMinute(LocalDateTime(LocalDate.parse(date), time)).toString()
                    charged = null
                },
                onDismiss = { pickDate = null },
            )
        }
    }
}

/** Высота сообщения внизу с его отступом (`MessageHost`): 48 + 12 dp. */
private val MESSAGE_GAP = 60.dp

/** Ширина карточки справа на разложенном Fold (макет «Разложенный Fold · сплит»). */
private val CARD_WIDTH = 320.dp

/** Что выбрано в панели отмены. [who] — [WHOLE] или id клиентов через запятую. */
private class CancelDraft(
    val who: String?,
    val reason: String?,
    val ownReason: String,
    val notifiedAt: LocalDateTime?,
    val charged: Boolean?,
) {
    val whole: Boolean get() = who.isNullOrEmpty()
    val clientIds: List<String> get() = if (whole) emptyList() else who.orEmpty().split(',')

    /** Причина: чип или свой текст. */
    val reasonText: String? get() = if (reason == null) ownReason.trim().ifEmpty { null } else reason
}

// --- Карточка ---

@Composable
private fun ColumnScope.CardBody(
    card: TrainingCard,
    now: LocalDateTime,
    wide: Boolean,
    sptAsk: SptAsk?,
    onSpt: () -> Unit,
    onSharing: () -> Unit,
    onClose: () -> Unit,
    onAction: (CardAction) -> Unit,
    onMenu: (CardMenu) -> Unit,
    onToggle: (String) -> Unit,
    onCancelClient: (String) -> Unit,
    onReturnClient: (String) -> Unit,
    onAddGuest: () -> Unit,
    onRemoveGuest: (String) -> Unit,
    onClient: (String) -> Unit,
    onGroup: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val state = card.state
    val buttons = TrainingCardRules.buttons(state, now, card.skippable, card.keepsDiary, card.settings)
    val phase = TrainingPhase.of(state, now)
    val isGroup = state.kind == TrainingKind.GROUP
    // Имя в шапке ведёт в карточку: у одного клиента — его, у группы — группы; у сплита — строки клиентов.
    val single = card.clients.singleOrNull()?.takeIf { !isGroup && !it.erased }
    val titleLink: (() -> Unit)? = single?.let { c -> { onClient(c.id) } } ?: card.groupId?.let { g -> { onGroup(g) } }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            if (wide) {
                IconButton(onClick = onClose) { Icon(PIcons.Close, contentDescription = "Закрыть", tint = colors.text) }
            }
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.touch)
                    .then(if (titleLink != null) Modifier.clickable(role = Role.Button, onClick = titleLink) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    card.title,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (titleLink != null) Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            }
            // «Поделиться» пришедшими на группу (шаг 5.3п): значок, не кнопка — лимит трёх кнопок не меняется (10.7).
            if (GroupShareFormat.canShare(state)) {
                val share = LocalShareText.current
                val clipboard = LocalClipboardManager.current
                IconButton(
                    onClick = {
                        val text = GroupShareFormat.text(state.start, card.groupName, card.clients.filter { it.state.came }.mapNotNull { it.fullName })
                        if (share != null) share(text) else clipboard.setText(AnnotatedString(text))
                    },
                    modifier = Modifier.testTag(CARD_SHARE_TAG),
                ) {
                    Icon(PIcons.Share, contentDescription = GroupShareFormat.SHARE, tint = colors.text)
                }
            }
            CardMenuButton(buttons.menu, onMenu)
        }
        val end = state.end
        Text(TrainingCardFormat.whenLine(state.start, end, state.durationMin), style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.textMuted)
        Text(TrainingCardFormat.typeLine(card.typeShort, card.placeShort, card.seriesWeekdays), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)

        val came = if (isGroup) state.participants.count { it.came } else null
        Box(Modifier.tourTarget(TourTarget.STATUS)) {
            StatusLine(
                outcome = card.outcome,
                phase = phase,
                note = TrainingCardFormat.statusNote(card.outcome, phase, now, state.start, end, came, TrainingCardRules.groupTotal(state), state.doneAt),
            )
        }
        card.warnings.forEach { WarningLine(it) }
        // Шаг 3.9р: именинник среди клиентов — строкой с тортом, как на блоке календаря.
        card.birthdays.forEach { BirthdayLine(it) }
        sptAsk?.let { SptAskRow(it, onSpt) }
        card.sharing?.let { SharingRow(it, onSharing) }
        // «Каждый своих» (шаг 5.3м): «мои 7 × 270 ₽ = 1 890 ₽ · Лены 5: отдаёте 1 262,25 ₽».
        card.payLine?.let {
            Text(it, Modifier.padding(top = Dimens.space2).testTag(CARD_PAY_LINE_TAG), style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text)
        }
        // Надёжность одного клиента (шаг 3.14): у сплита и мини-группы — в строке каждого.
        if (!isGroup && !TrainingCardRules.perClient(state)) {
            single?.let { c -> card.reliability[c.id]?.let { ReliabilityLine(c.id, it) } }
        }

        // Отменённая целиком или отменённый единственный клиент: причина и когда предупредили.
        val cancelled = card.clients.singleOrNull()?.state?.takeIf { it.isCancelled && !isGroup }
        val reason = cancelled?.cancelReason ?: state.cancelReason.takeIf { state.status == TrainingStatus.CANCELLED }
        if (reason != null) InfoRow(TrainingCardFormat.REASON, reason)
        cancelled?.notifiedAt?.let { InfoRow(TrainingCardFormat.NOTIFIED, TrainingCardFormat.notified(it, state.start)) }

        if (isGroup) {
            GroupMembers(card, now, onToggle, onCancelClient, onReturnClient, onAddGuest, onRemoveGuest, onClient)
        } else if (TrainingCardRules.perClient(state)) {
            SectionLabel(TrainingCardFormat.CLIENTS)
            card.clients.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                ClientLine(card, c, onCancelClient, onReturnClient, onRemoveGuest, onClient)
            }
        }

        card.plan?.let {
            SectionLabel(TrainingCardFormat.PLAN)
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        }
        // Отменённая: куда ушёл план (4.3, макет «Отмена со списанием»).
        card.carriedTo?.let { InfoRow(TrainingCardFormat.PLAN, TrainingCardFormat.planCarried(it)) }
        card.fact?.let {
            SectionLabel(TrainingCardFormat.FACT)
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        }
        // Поведение из дневника (5.7): у одного клиента — «Поведение», у сплита — у каждого с именем.
        if (!isGroup) {
            val behaving = card.clients.filter { it.behavior != null }
            behaving.forEach { c ->
                SectionLabel(if (card.clients.size > 1) TrainingCardFormat.behaviorOf(c.name) else TrainingCardFormat.BEHAVIOR)
                Text(c.behavior.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            }
        }
        if (card.noDiary) {
            Row(Modifier.padding(top = Dimens.space4), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accentGraphic))
                Spacer(Modifier.width(Dimens.space2))
                Text(CalendarFormat.badge(CalendarBadge.NO_DIARY), style = MaterialTheme.typography.bodyMedium, color = colors.text)
            }
        }
        Spacer(Modifier.height(Dimens.space4))
    }
    CardButtonsRow(buttons.buttons, diaryFirst = card.noDiary, onAction = onAction)
}

/** Вопрос СПТ (4.9, п. 4–5): в окне — «Засчитать СПТ закрытой?», после — «Блок был куплен со скидкой до …?». */
private fun sptQuestion(ask: SptAsk): String = when (val prompt = ask.prompt) {
    is SptPrompt.CloseNow -> SptFormat.ASK_CLOSE
    is SptPrompt.AskRetro -> SptFormat.askRetro(prompt.windowUntil)
}

/** Строка вопроса СПТ в карточке: значок, вопрос, «Орлов М. · СПТ пн 12.10»; нажатие — окно с датой покупки. */
@Composable
private fun SptAskRow(ask: SptAsk, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space3)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Dimens.row)
            .padding(horizontal = Dimens.space3, vertical = Dimens.space2)
            .testTag(SPT_ASK_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PIcons.ShoppingBag, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Dimens.space3))
        Column(Modifier.weight(1f)) {
            Text(sptQuestion(ask), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(SptFormat.askNote(ask.name, ask.case.sptDate), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
        }
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}

/** Кнопка «⋮» с меню карточки: «Удалить» — цветом ошибки. */
@Composable
private fun CardMenuButton(items: List<CardMenu>, onMenu: (CardMenu) -> Unit) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(CARD_MENU_TAG)) {
            Icon(PIcons.MoreVert, contentDescription = TrainingCardFormat.MORE, tint = PersonalkaTheme.colors.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = PersonalkaTheme.colors.surface) {
            items.forEach { item ->
                MenuAction(
                    text = TrainingCardFormat.menu(item),
                    icon = when (item) {
                        CardMenu.UNMARK_DONE -> PIcons.Undo
                        CardMenu.EDIT -> PIcons.Edit
                        CardMenu.COPY -> PIcons.ContentCopy
                        CardMenu.SKIP_DATE -> PIcons.EventBusy
                        CardMenu.DELETE -> PIcons.Delete
                        CardMenu.RESULT -> PIcons.Add
                    },
                    onClick = {
                        open = false
                        onMenu(item)
                    },
                    danger = item == CardMenu.DELETE,
                )
            }
        }
    }
}

/** Метка статуса и пояснение: «Идёт · до конца 30 мин», «Отмена со списанием». */
@Composable
private fun StatusLine(outcome: ClientOutcome, phase: TrainingPhase, note: String?) {
    val colors = PersonalkaTheme.colors
    val label = TrainingCardFormat.statusLabel(outcome, phase)
    Row(Modifier.padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        val (bg, fg, icon) = when (outcome) {
            ClientOutcome.DONE -> Triple(colors.doneContainer, colors.onDoneContainer, PIcons.Check)
            ClientOutcome.CANCELLED_CHARGED -> Triple(colors.chargedContainer, colors.onChargedContainer, PIcons.BadgeRuble)
            ClientOutcome.CANCELLED -> Triple(colors.surfaceAlt, colors.text, PIcons.Close)
            ClientOutcome.PLANNED -> Triple(Color.Transparent, colors.text, PIcons.Schedule)
        }
        StatusChip(label, bg, fg, icon, outlined = outcome == ClientOutcome.PLANNED)
        if (note != null) {
            Spacer(Modifier.width(Dimens.space2))
            Text(note, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.textMuted)
        }
    }
}

@Composable
private fun StatusChip(label: String, bg: Color, fg: Color, icon: ImageVector, outlined: Boolean = false) {
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(bg, shape)
            .then(if (outlined) Modifier.border(1.dp, PersonalkaTheme.colors.outline, shape) else Modifier)
            .padding(horizontal = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Тело вопроса о плане (макет «У следующей уже есть план»): «Орлов М. · вт 27.10, 17:00» и два текста в плашках. */
@Composable
private fun PlanQuestion(whom: String, first: Pair<String, String>, second: Pair<String, String>) {
    val colors = PersonalkaTheme.colors
    Text(whom, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.textMuted)
    listOf(first, second).forEach { (label, text) ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
                .padding(Dimens.space3),
            verticalArrangement = Arrangement.spacedBy(Dimens.space1),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            Text(text, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text)
        }
    }
}

/** Именинник в карточке (шаг 3.9р): торт и «День рождения · Ковалёв А. · 9 лет». */
@Composable
private fun BirthdayLine(text: String) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .padding(top = Dimens.space3)
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(Dimens.space3)
            .semantics(mergeDescendants = true) {}
            .testTag(CARD_BIRTHDAY_TAG),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PIcons.Cake, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Text(ClientFormat.cardBirthday(text), style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text)
    }
}

@Composable
private fun WarningLine(text: String) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .padding(top = Dimens.space3)
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PIcons.BadgeLayers, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = Dimens.space3), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        Spacer(Modifier.width(Dimens.space3))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text)
    }
}

/** «Надёжность [84 %] 8 отмен из 50» у запланированной ПТ (шаг 3.14). */
@Composable
private fun ReliabilityLine(clientId: String, r: Reliability) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        Text(ReliabilityFormat.TITLE, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        Spacer(Modifier.width(Dimens.space2))
        ReliabilityChip(r, ReliabilityFormat.value(r), clientId)
        Spacer(Modifier.width(Dimens.space2))
        Text(ReliabilityFormat.basis(r), style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Строка клиента сплита: имя ведёт в его карточку, итог, значок отмены или «Вернуть» (5.4). */
@Composable
private fun ClientLine(
    card: TrainingCard,
    c: CardClient,
    onCancelClient: (String) -> Unit,
    onReturnClient: (String) -> Unit,
    onRemoveGuest: (String) -> Unit,
    onClient: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(cardClientTag(c.id)), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = Dimens.touch).then(if (c.erased) Modifier else Modifier.clickable(role = Role.Button) { onClient(c.id) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!c.erased) Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            card.reliability[c.id]?.let { ReliabilityChip(it, ReliabilityFormat.value(it), c.id, Modifier.padding(end = Dimens.space2)) }
        }
        val (bg, fg) = when (c.outcome) {
            ClientOutcome.DONE -> colors.doneContainer to colors.onDoneContainer
            ClientOutcome.CANCELLED_CHARGED -> colors.chargedContainer to colors.onChargedContainer
            else -> colors.surfaceAlt to colors.text
        }
        Text(
            c.outcome.label,
            modifier = Modifier.clip(MaterialTheme.shapes.small).background(bg).padding(horizontal = Dimens.space2, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
        ClientActionIcon(card, c, onCancelClient, onReturnClient, onRemoveGuest)
    }
}

/** Значок в строке клиента: убрать ребёнка «на раз», отменить клиента одного или вернуть отменённого. */
@Composable
private fun ClientActionIcon(
    card: TrainingCard,
    c: CardClient,
    onCancelClient: (String) -> Unit,
    onReturnClient: (String) -> Unit,
    onRemoveGuest: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    when {
        !c.state.isCancelled && TrainingCardRules.canRemoveGuest(card.state, c.guest) ->
            IconButton(onClick = { onRemoveGuest(c.id) }, modifier = Modifier.testTag(cardClientActionTag(c.id))) {
                Icon(PIcons.Close, contentDescription = TrainingCardFormat.removeGuest(c.name), tint = colors.textMuted)
            }
        TrainingCardRules.canCancelClient(card.state, c.id) ->
            IconButton(onClick = { onCancelClient(c.id) }, modifier = Modifier.testTag(cardClientActionTag(c.id))) {
                Icon(PIcons.EventBusy, contentDescription = TrainingCardFormat.cancelClient(c.name), tint = colors.textMuted)
            }
        TrainingCardRules.canReturnClient(card.state, c.id) ->
            IconButton(onClick = { onReturnClient(c.id) }, modifier = Modifier.testTag(cardClientActionTag(c.id))) {
                Icon(PIcons.Unarchive, contentDescription = TrainingCardFormat.returnClient(c.name), tint = colors.textMuted)
            }
        else -> Spacer(Modifier.width(Dimens.touch))
    }
}

/**
 * Состав группы (5.4, макет «Группа идёт»): «Пришли» сверху, «Ещё не отмечены» ниже; нажатие на ребёнка отмечает
 * «пришёл» и обратно. До начала минус 15 минут отмечать нельзя — нажатие ведёт в карточку ребёнка. Справа
 * от «Состав» — «Добавить на раз»; ребёнок «на раз» помечен в строке и убирается «×».
 */
@Composable
private fun GroupMembers(
    card: TrainingCard,
    now: LocalDateTime,
    onToggle: (String) -> Unit,
    onCancelClient: (String) -> Unit,
    onReturnClient: (String) -> Unit,
    onAddGuest: () -> Unit,
    onRemoveGuest: (String) -> Unit,
    onClient: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val state = card.state
    val toggle = TrainingCardRules.canToggle(state, now)
    val guests = card.clients.count { it.guest }
    Row(Modifier.fillMaxWidth().padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        Text(
            TrainingCardFormat.members(card.clients.size - guests, guests),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        if (TrainingCardRules.canAddGuest(state)) {
            TextButton(onClick = onAddGuest, modifier = Modifier.heightIn(min = Dimens.touch).testTag(ADD_GUEST_TAG)) {
                Icon(PIcons.PersonAdd, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.space2))
                Text(TrainingCardFormat.ADD_GUEST, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
    }
    if (toggle && state.status == TrainingStatus.PLANNED) {
        Text(TrainingCardFormat.MARK_HINT, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
    val came = card.clients.filter { it.state.came }
    val noShow = card.clients.filter { TrainingCardRules.noShow(state, it.state) }
    val waiting = card.clients.filter { it.state.status == ParticipationStatus.EXPECTED }
    val cancelled = card.clients.filter { it.state.isCancelled && !TrainingCardRules.noShow(state, it.state) }
    val sections = listOf(
        TrainingCardFormat.cameSection(came.size) to came,
        TrainingCardFormat.notMarkedSection(waiting.size) to waiting,
        TrainingCardFormat.noShowSection(noShow.size) to noShow,
        TrainingCardFormat.cancelledSection(cancelled.size) to cancelled,
    )
    sections.filter { it.second.isNotEmpty() }.forEach { (label, list) ->
        Text(label, modifier = Modifier.padding(top = Dimens.space3), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        list.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.touch)
                    .clickable(role = Role.Checkbox) { if (toggle) onToggle(c.id) else if (!c.erased) onClient(c.id) }
                    .semantics { selected = c.state.came }
                    .testTag(cardClientTag(c.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (c.state.came) {
                    Icon(PIcons.CheckCircle, contentDescription = null, tint = colors.done, modifier = Modifier.size(22.dp))
                } else {
                    Box(Modifier.size(22.dp).padding(2.dp).border(2.dp, colors.outline, CircleShape))
                }
                Spacer(Modifier.width(Dimens.space3))
                Text(c.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.reliability[c.id]?.let { ReliabilityChip(it, ReliabilityFormat.value(it), c.id, Modifier.padding(start = Dimens.space2)) }
                // Ребёнок коллеги в группе «Каждый своих» (шаг 5.3м): подпись — имя коллеги.
                val kidCoach = card.eachOwnColleague?.takeIf { c.colleagueKid }
                if (kidCoach != null) {
                    Text(
                        kidCoach,
                        modifier = Modifier.padding(start = Dimens.space2).testTag(cardKidCoachTag(c.id)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
                if (c.guest) {
                    Text(TrainingCardFormat.GUEST, modifier = Modifier.padding(start = Dimens.space2), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                }
                ClientActionIcon(card, c, onCancelClient, onReturnClient, onRemoveGuest)
            }
        }
    }
}

/**
 * Кнопки по состоянию (5.4): главная — графитом, «Отменить» первой — заливкой, иначе текстом; «Изменить» — контуром.
 * «Дневник» — заливкой, у проведённой без дневника ([diaryFirst]) — главная; в ряду из трёх у кнопок нет значков,
 * кроме «Дневника», — иначе подписи не влезают.
 */
@Composable
private fun CardButtonsRow(actions: List<CardAction>, diaryFirst: Boolean, onAction: (CardAction) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Row(Modifier.fillMaxWidth().padding(Dimens.space4), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            actions.forEachIndexed { i, action ->
                val text = TrainingCardFormat.action(action)
                val modifier = Modifier.weight(1f)
                when (action) {
                    CardAction.DONE, CardAction.ALL_CAME, CardAction.RETURN -> PrimaryButton(text, { onAction(action) }, modifier)
                    CardAction.DIARY ->
                        if (diaryFirst && i == 0) {
                            PrimaryButton(text, { onAction(action) }, modifier)
                        } else {
                            SecondaryButton(text, { onAction(action) }, modifier, icon = PIcons.EditNote.takeIf { actions.size < 3 || i == 0 })
                        }
                    CardAction.EDIT -> OutlineButton(text, { onAction(action) }, modifier, icon = PIcons.Edit.takeIf { actions.size < 3 })
                    CardAction.CANCEL ->
                        if (i == 0) {
                            SecondaryButton(text, { onAction(action) }, modifier.tourTarget(TourTarget.CANCEL), icon = PIcons.EventBusy)
                        } else {
                            TextButton(onClick = { onAction(action) }, modifier = modifier.tourTarget(TourTarget.CANCEL).heightIn(min = Dimens.button)) {
                                Text(text, style = MaterialTheme.typography.labelLarge, color = colors.text)
                            }
                        }
                }
            }
        }
    }
}

// --- Ребёнок «на раз» (5.4, 5.10) ---

/**
 * Выбор ребёнка «на раз» вместо карточки: без поиска — сначала дети, восемь человек; поиском — все действующие
 * клиенты, кого нет в тренировке. Нажатие на строку сразу добавляет (одно нажатие, 10.7); нет в списке —
 * «Новый клиент» с фамилией и именем и «Добавить».
 */
@Composable
private fun ColumnScope.GuestBody(
    choices: List<GuestChoice>,
    today: LocalDate,
    query: String,
    onQuery: (String) -> Unit,
    creating: Boolean,
    lastName: String,
    firstName: String,
    onCreate: (String, String) -> Unit,
    onLastName: (String) -> Unit,
    onFirstName: (String) -> Unit,
    onPick: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
    colleague: String? = null,
    whose: Owner = Owner.SELF,
    onWhose: (Owner) -> Unit = {},
) {
    val colors = PersonalkaTheme.colors
    val shown = if (query.isBlank()) {
        choices.sortedWith(compareBy<GuestChoice> { it.adult }.thenBy { ClientRules.sortKey(it.name) }).take(GUEST_SHORT_LIST)
    } else {
        choices.filter { ClientRules.matches(query, it.name) }.sortedBy { ClientRules.sortKey(it.name) }
    }
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.space4)
            .testTag(GUEST_PANEL_TAG),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Text(
                TrainingCardFormat.GUEST_TITLE,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
            )
            IconButton(onClick = onBack) { Icon(PIcons.Close, contentDescription = "Закрыть", tint = colors.text) }
        }
        Text(TrainingCardFormat.GUEST_HINT, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        // «Каждый своих» (шаг 5.3м): «Чей: Мой / Лена» — выбирается до нажатия на ребёнка.
        if (colleague != null) {
            Row(Modifier.padding(top = Dimens.space3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Text(CoCoachingFormat.WHOSE_GUEST, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                ChoiceChip(CoCoachingFormat.MY_KID, whose == Owner.SELF, onClick = { onWhose(Owner.SELF) }, modifier = Modifier.testTag(guestWhoseTag(Owner.SELF)))
                ChoiceChip(colleague, whose == Owner.COLLEAGUE, onClick = { onWhose(Owner.COLLEAGUE) }, modifier = Modifier.testTag(guestWhoseTag(Owner.COLLEAGUE)))
            }
        }
        Spacer(Modifier.height(Dimens.space3))
        SearchField(query, onQuery, TrainingCardFormat.GUEST_SEARCH, Modifier.testTag(GUEST_SEARCH_TAG))
        Spacer(Modifier.height(Dimens.space2))
        Column(Modifier.clip(MaterialTheme.shapes.medium)) {
            shown.forEach { c ->
                PersonRow(
                    title = c.name.lastName + " " + c.name.firstName,
                    subtitle = c.birthDate?.let { ClientFormat.age(it, today) },
                    onClick = { onPick(c.id) },
                    modifier = Modifier.testTag(guestPickTag(c.id)),
                )
            }
            PersonRow(
                title = TrainingCardFormat.newClient(query),
                subtitle = null,
                onClick = {
                    val words = query.trim().split(' ').filter { it.isNotEmpty() }.map { w -> w.replaceFirstChar { it.uppercaseChar() } }
                    onCreate(words.firstOrNull().orEmpty(), words.drop(1).joinToString(" "))
                },
                selected = creating,
                leading = PIcons.PersonAdd,
                modifier = Modifier.testTag(NEW_GUEST_TAG),
            )
        }
        if (creating) {
            Spacer(Modifier.height(Dimens.space3))
            val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                Field(TrainingCardFormat.LAST_NAME, lastName, onLastName, Modifier.weight(1f), keyboardOptions = words, tag = NEW_GUEST_LAST_TAG)
                Field(TrainingCardFormat.FIRST_NAME, firstName, onFirstName, Modifier.weight(1f), keyboardOptions = words, tag = NEW_GUEST_FIRST_TAG)
            }
        }
        Spacer(Modifier.height(Dimens.space4))
    }
    if (creating) {
        val ready = lastName.isNotBlank() && firstName.isNotBlank()
        Column(Modifier.fillMaxWidth().padding(Dimens.space4)) {
            PrimaryButton(TrainingCardFormat.ADD, onAddNew, Modifier.fillMaxWidth(), enabled = ready)
        }
    }
}

/** Сколько клиентов в выборе «на раз» без поиска — как в «В состав» (5.10). */
private const val GUEST_SHORT_LIST = 8

// --- Панель отмены (5.6) ---

/** Строка «Это время ждут: Петров К. (разово)» (4.16) в подтверждениях. */
const val WAIT_LINE_TAG = "wait-line"

@Composable
private fun ColumnScope.CancelBody(
    card: TrainingCard,
    draft: CancelDraft,
    now: LocalDateTime,
    waitLine: String?,
    onBack: () -> Unit,
    onWho: (String) -> Unit,
    onReason: (String?) -> Unit,
    onOwnReason: (String) -> Unit,
    onNow: () -> Unit,
    onOtherTime: () -> Unit,
    onCharged: (Boolean) -> Unit,
    onCancel: (List<String>) -> Unit,
    onMove: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val state = card.state
    val asksWho = CancelRules.asksWho(state)
    val open = CancelRules.open(state).toSet()
    // Выбранные клиенты, которых уже отменили (на другом телефоне), не считаются.
    val clientIds = draft.clientIds.filter { it in open }
    val whole = draft.whole || clientIds.isEmpty()
    val notified = draft.notifiedAt ?: now
    val summary = CancelRules.summary(state, whole, draft.reasonText, notified, card.settings, draft.charged)
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.space4)
            .testTag(CANCEL_PANEL_TAG),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (asksWho) TrainingCardFormat.CANCEL_CLIENT_TITLE else TrainingCardFormat.CANCEL_TITLE,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
            )
            IconButton(onClick = onBack) { Icon(PIcons.Close, contentDescription = "Закрыть", tint = colors.text) }
        }
        Text(
            card.title + " · " + CalendarFormat.at(state.start) + "–" + DateFormat.time(state.end.time),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = colors.textMuted,
        )
        Text(TrainingCardFormat.typeLine(card.typeShort, card.placeShort, null), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)

        if (asksWho) {
            SectionLabel(TrainingCardFormat.WHO)
            Chips {
                card.clients.filter { it.id in open }.forEach { c ->
                    ChoiceChip(
                        text = c.name,
                        selected = !whole && c.id in clientIds,
                        onClick = {
                            val next = if (c.id in clientIds) clientIds - c.id else clientIds + c.id
                            onWho(next.joinToString(","))
                        },
                        modifier = Modifier.testTag(cancelWhoTag(c.id)),
                        toggle = true,
                    )
                }
                ChoiceChip(TrainingCardFormat.WHOLE, selected = whole, onClick = { onWho(WHOLE) }, modifier = Modifier.testTag(CANCEL_WHOLE_TAG))
            }
        }

        SectionLabel(TrainingCardFormat.REASON)
        Chips {
            CancelReasons.DEFAULT_CHIPS.forEach { chip ->
                ChoiceChip(chip, selected = draft.reason == chip, onClick = { onReason(chip) })
            }
            ChoiceChip(TrainingCardFormat.OWN_REASON, selected = draft.reason == null, onClick = { onReason(null) }, icon = PIcons.Edit)
        }
        if (draft.reason == null) {
            Field(
                label = TrainingCardFormat.OWN_REASON,
                value = draft.ownReason,
                onValueChange = onOwnReason,
                placeholder = TrainingCardFormat.REASON_HINT,
                tag = OWN_REASON_TAG,
            )
        }

        if (summary.decision != null) {
            SectionLabel(TrainingCardFormat.WHEN_NOTIFIED)
            Chips {
                ChoiceChip(TrainingCardFormat.nowChip(now), selected = draft.notifiedAt == null, onClick = onNow)
                ChoiceChip(
                    text = draft.notifiedAt?.let(TrainingCardFormat::timeChip) ?: TrainingCardFormat.OTHER_TIME,
                    selected = draft.notifiedAt != null,
                    onClick = onOtherTime,
                    icon = PIcons.Schedule,
                )
            }
        }

        Column(
            Modifier
                .padding(top = Dimens.space4)
                .fillMaxWidth()
                .border(1.dp, colors.divider, MaterialTheme.shapes.medium)
                .padding(horizontal = Dimens.space3, vertical = Dimens.space2),
        ) {
            Row(Modifier.heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg, icon) = if (summary.charged) {
                    Triple(colors.chargedContainer, colors.onChargedContainer, PIcons.BadgeRuble)
                } else {
                    Triple(colors.surfaceAlt, colors.text, PIcons.Close)
                }
                StatusChip(TrainingCardFormat.summaryLabel(summary.charged), bg, fg, icon)
                TrainingCardFormat.summaryNote(summary.decision)?.let {
                    Spacer(Modifier.width(Dimens.space2))
                    Text(it, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text)
                }
            }
            if (summary.switchable) {
                SwitchRow(TrainingCardFormat.CHARGE_SWITCH, summary.charged, onCharged, Modifier.testTag(CHARGE_SWITCH_TAG))
            }
        }
        // Время освобождается, только когда отменяют всю тренировку.
        if (whole && waitLine != null) {
            Spacer(Modifier.height(Dimens.space3))
            Note(waitLine, Modifier.testTag(WAIT_LINE_TAG))
        }
        Spacer(Modifier.height(Dimens.space4))
    }
    Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        if (whole) {
            PrimaryButton(TrainingCardFormat.CANCEL_TRAINING, { onCancel(emptyList()) }, Modifier.fillMaxWidth())
            if (state.status == TrainingStatus.PLANNED) {
                OutlineButton(TrainingCardFormat.MOVE_INSTEAD, onMove, Modifier.fillMaxWidth(), icon = PIcons.SyncAlt)
            }
        } else {
            PrimaryButton(TrainingCardFormat.cancelFor(card.clients.filter { it.id in clientIds }.map { it.name }), { onCancel(clientIds) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) { content() }
}
