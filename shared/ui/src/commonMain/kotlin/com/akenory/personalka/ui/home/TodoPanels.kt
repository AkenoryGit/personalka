package com.akenory.personalka.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ConfirmVisit
import com.akenory.personalka.data.clients.FollowupRow
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.money.SptWaiting
import com.akenory.personalka.domain.cabinet.ConsentDue
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.SptFormat
import com.akenory.personalka.domain.format.TodoListFormat
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.money.SptRules
import com.akenory.personalka.ui.clients.CallButton
import com.akenory.personalka.ui.clients.FollowupCard
import com.akenory.personalka.ui.clients.FollowupPause
import com.akenory.personalka.ui.clients.SptPurchasePanel
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.ReliabilityChip
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.groups.AbsentList
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.TrainingCardKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

internal fun todoPanelTag(kind: TodoKind) = "todo-panel-${kind.name.lowercase()}"
internal fun confirmComingTag(clientId: String) = "confirm-coming-$clientId"
internal fun confirmNotComingTag(clientId: String) = "confirm-not-coming-$clientId"
internal fun sptBoughtTag(clientId: String) = "todo-spt-bought-$clientId"

/** Пункты «Нужно сделать», которые открывают своё окно со списком (шаг 3.16), а не экран. */
internal val PANEL_KINDS = setOf(
    TodoKind.CONFIRM_VISIT,
    TodoKind.UNMARKED_GROUPS,
    TodoKind.NO_DIARY,
    TodoKind.NOT_BOOKED,
    TodoKind.GROUP_ABSENT,
    TodoKind.SPT_PURCHASE,
    TodoKind.CONSENT_REVOKED,
)

internal fun todoFillTag(id: String) = "todo-fill-$id"
internal fun todoMarkTag(id: String) = "todo-mark-$id"
internal fun todoOpenCardTag(clientId: String) = "todo-open-card-$clientId"

/**
 * Окно пункта «Нужно сделать» (ТЗ 5.2, шаг 3.16): заголовок — как у пункта, сверху — почему эти люди здесь и что
 * сделать, ниже — каждый со своими подробностями и кнопками. Сделанный уходит из списка; список опустел — окно
 * закрывается само, а с «Главной» уходит и пункт. Нажатие на имя — карточка клиента.
 *
 * Списки читаются здесь же, пока окно открыто, — каждая отметка сразу видна.
 */
@Composable
internal fun TodoListPanel(
    kind: TodoKind,
    title: String,
    clients: ClientRepository,
    groups: GroupRepository?,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onClient: (String) -> Unit,
    onCard: (String) -> Unit,
    onForm: (String) -> Unit,
    calendar: CalendarRepository,
    trainings: List<CalendarTraining>,
    consents: List<ConsentDue>,
    onDiary: (String) -> Unit,
) {
    when (kind) {
        TodoKind.UNMARKED_GROUPS -> {
            val byId = trainings.associateBy { it.id }
            val list = HomeRules.unmarkedGroups(trainings.map { it.slot() }, now).mapNotNull { byId[it.id] }
            ListPanel(kind, title, TodoListFormat.GROUPS_WHY, list, onClose) { groups ->
                TrainingRows(groups.map { Triple(it.id, it.title, it.start) }, now, TodoListFormat.MARK, ::todoMarkTag) { id -> onClose(); onCard(TrainingCardKey.card(id)) }
            }
        }
        TodoKind.NO_DIARY -> {
            val list by remember(calendar) { calendar.diaries.missing }.collectAsState(initial = null)
            ListPanel(kind, title, TodoListFormat.DIARY_WHY, list, onClose) { missing ->
                TrainingRows(missing.map { Triple(it.id, it.title, it.start) }, now, TodoListFormat.FILL, ::todoFillTag) { id -> onClose(); onDiary(id) }
            }
        }
        TodoKind.CONSENT_REVOKED -> ListPanel(kind, title, TodoListFormat.CONSENT_WHY, consents, onClose) { list ->
            ConsentRows(list, now) { id -> onClose(); onClient(id) }
        }
        TodoKind.CONFIRM_VISIT -> {
            val list by remember(clients, now) { clients.reliability.confirm(now) }.collectAsState(initial = null)
            ListPanel(kind, title, TodoListFormat.CONFIRM_WHY, list, onClose) { visits ->
                ConfirmList(visits, clients, messages, scope, onClient, onCard = { key -> onClose(); onCard(key) })
            }
        }
        TodoKind.NOT_BOOKED -> {
            val followup by remember(clients, now) { clients.followup.observe(now) }.collectAsState(initial = null)
            val loaded = followup
            var pausing by rememberSaveable { mutableStateOf<String?>(null) }
            ListPanel(
                kind, title, TodoListFormat.followupWhy(loaded?.days ?: 0), loaded?.rows, onClose,
                overlay = { rows ->
                    rows.firstOrNull { it.client.id == pausing }?.let { row ->
                        FollowupPause(row, clients, messages, scope, now.date, onClose = { pausing = null })
                    }
                },
            ) { rows ->
                FollowupRows(rows, clients, messages, scope, now, onClient, onForm = { key -> onClose(); onForm(key) }, onPause = { pausing = it })
            }
        }
        TodoKind.GROUP_ABSENT -> {
            val list by remember(groups, now.date) { groups?.absent(now.date) ?: flowOf(emptyList()) }.collectAsState(initial = null)
            ListPanel(kind, title, TodoListFormat.ABSENT_WHY, list, onClose) { absent ->
                if (groups != null) {
                    Box(Modifier.border(1.dp, PersonalkaTheme.colors.divider, MaterialTheme.shapes.medium)) {
                        AbsentList(absent, groups, clients, messages, scope, showGroup = true, onOpen = onClient)
                    }
                }
            }
        }
        TodoKind.SPT_PURCHASE -> {
            val list by remember(clients, now.date) { clients.spt.waiting(now.date) }.collectAsState(initial = null)
            var buying by rememberSaveable { mutableStateOf<String?>(null) }
            ListPanel(
                kind, title, TodoListFormat.SPT_WHY, list, onClose,
                overlay = { waiting ->
                    waiting.firstOrNull { it.case.id == buying }?.let { w -> SptBuy(w, clients, messages, scope, now, onClose = { buying = null }) }
                },
            ) { waiting ->
                SptList(waiting, messages, now, onClient, onBuy = { buying = it })
            }
        }
        else -> LaunchedEffect(kind) { onClose() }
    }
}

/**
 * Окно со списком: [list] `null` — ещё читается (окно уже открыто, пусто); пустой — дела сделаны, окно закрывается.
 * [overlay] — окно поверх него.
 */
@Composable
private fun <T> ListPanel(
    kind: TodoKind,
    title: String,
    why: String,
    list: List<T>?,
    onClose: () -> Unit,
    overlay: @Composable (List<T>) -> Unit = {},
    content: @Composable ColumnScope.(List<T>) -> Unit,
) {
    if (list != null && list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Panel(title, onDismiss = onClose, modifier = Modifier.testTag(todoPanelTag(kind))) {
        Why(why)
        if (list != null) content(list)
    }
    // Окно поверх окна («Пауза до…», дата покупки) — рядом, а не внутри: внутри прокрутки оно не измерится.
    if (list != null) overlay(list)
}

/** Пояснение сверху окна: фон невыбранного чипа, скругление 12. */
@Composable
private fun Why(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(PersonalkaTheme.colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(horizontal = Dimens.space3, vertical = Dimens.space2),
        style = MaterialTheme.typography.bodyMedium,
        color = PersonalkaTheme.colors.text,
    )
}

/** Строка списка: имя (нажатие — карточка клиента) и справа время или срок. */
@Composable
private fun NameLine(name: String, side: String, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (side.isNotEmpty()) Text(side, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
    }
}

@Composable
private fun Muted(text: String) {
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall.tabular(), color = PersonalkaTheme.colors.textMuted)
}

/** Карточка-список с рамкой: в окне фон тот же, что у карточки. */
@Composable
private fun BorderedList(content: @Composable () -> Unit) {
    ListCard(Modifier.border(1.dp, PersonalkaTheme.colors.divider, MaterialTheme.shapes.medium), content)
}

/**
 * «Уточнить, придёт ли»: время, тип и место, надёжность; «Придёт» — клиент уходит из списка до начала этой
 * тренировки (с «Отменить»), «Не придёт» — окно отмены тренировки, звонок — набор номера.
 */
@Composable
private fun ConfirmList(
    visits: List<ConfirmVisit>,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClient: (String) -> Unit,
    onCard: (String) -> Unit,
) {
    BorderedList {
        visits.forEachIndexed { index, v ->
            if (index > 0) RowDivider(start = 0.dp)
            Column(Modifier.fillMaxWidth().padding(start = Dimens.space3, end = 4.dp, top = Dimens.space3, bottom = Dimens.space2)) {
                Column(Modifier.padding(end = Dimens.space2)) {
                    NameLine(v.name, TodoListFormat.today(v.start)) { onClient(v.clientId) }
                    Muted(TodoListFormat.typePlace(v.typeShort, v.placeShort))
                    v.reliability?.let { r ->
                        Spacer(Modifier.padding(top = 4.dp))
                        ReliabilityChip(r, TodoListFormat.reliability(r), v.clientId)
                    }
                }
                Row(Modifier.padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    SecondaryButton(TodoListFormat.COMING, {
                        scope.launch {
                            val before = clients.confirmVisit(v.participationId) ?: return@launch
                            messages.showUndo(TodoListFormat.comingMessage(v.name)) { scope.launch { clients.undoConfirm(before) } }
                        }
                    }, Modifier.testTag(confirmComingTag(v.clientId)))
                    OutlineButton(TodoListFormat.NOT_COMING, { onCard(TrainingCardKey.cancel(v.trainingId)) }, Modifier.testTag(confirmNotComingTag(v.clientId)))
                    Spacer(Modifier.weight(1f))
                    v.phone?.let { CallButton(it) { messages.show(FollowupFormat.PHONE_COPIED) } }
                }
            }
        }
    }
}

/** Тренировки «Без дневника» и «Группа без отметки»: кто или какая группа, когда, одна кнопка. */
@Composable
private fun TrainingRows(
    rows: List<Triple<String, String, LocalDateTime>>,
    now: LocalDateTime,
    action: String,
    tag: (String) -> String,
    onAction: (String) -> Unit,
) {
    BorderedList {
        rows.forEachIndexed { index, (id, title, start) ->
            if (index > 0) RowDivider(start = 0.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.space3, vertical = Dimens.space3)) {
                NameLine(title, TodoListFormat.at(start, now.date)) { onAction(id) }
                SecondaryButton(action, { onAction(id) }, Modifier.padding(top = Dimens.space2).testTag(tag(id)))
            }
        }
    }
}

/** «Удалить данные клиента»: имя, до какого дня удалить и сколько осталось; удаление — в карточке, с подтверждением. */
@Composable
private fun ConsentRows(list: List<ConsentDue>, now: LocalDateTime, onOpen: (String) -> Unit) {
    BorderedList {
        list.forEachIndexed { index, c ->
            if (index > 0) RowDivider(start = 0.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.space3, vertical = Dimens.space3)) {
                NameLine(c.name.short, CabinetLinkFormat.left(c.deleteBy, now.date)) { onOpen(c.clientId) }
                Muted(TodoListFormat.deleteBy(c.deleteBy))
                SecondaryButton(TodoListFormat.OPEN_CARD, { onOpen(c.clientId) }, Modifier.padding(top = Dimens.space2).testTag(todoOpenCardTag(c.clientId)))
            }
        }
    }
}

/** «Без записи»: те же карточки, что в «Клиентах» → «Без записи», с «Записать», «Написал» и меню. */
@Composable
private fun FollowupRows(
    rows: List<FollowupRow>,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onClient: (String) -> Unit,
    onForm: (String) -> Unit,
    onPause: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        rows.forEach { row ->
            FollowupCard(
                row = row,
                clients = clients,
                messages = messages,
                scope = scope,
                now = now,
                onOpen = { onClient(row.client.id) },
                onForm = onForm,
                onPause = { onPause(row.client.id) },
                modifier = Modifier.border(1.dp, PersonalkaTheme.colors.divider, MaterialTheme.shapes.medium),
            )
        }
    }
}

/**
 * «СПТ ждут покупки»: сколько осталось до конца окна, день СПТ, место и доплата; «Купил блок» — окно даты покупки
 * ([SptBuy], как в «Инфо» клиента), звонок — набор номера.
 */
@Composable
private fun SptList(
    list: List<SptWaiting>,
    messages: MessageState,
    now: LocalDateTime,
    onClient: (String) -> Unit,
    onBuy: (String) -> Unit,
) {
    BorderedList {
        list.forEachIndexed { index, w ->
            if (index > 0) RowDivider(start = 0.dp)
            val clientId = w.case.clientId
            Column(Modifier.fillMaxWidth().padding(start = Dimens.space3, end = 4.dp, top = Dimens.space3, bottom = Dimens.space2)) {
                Column(Modifier.padding(end = Dimens.space2)) {
                    NameLine(CalendarFormat.client(w.name), TodoListFormat.sptLeft(w.case.windowUntil, now.date)) { onClient(clientId) }
                    Muted(TodoListFormat.sptLine(w.case, w.typeShort, w.placeShort))
                    Muted(TodoListFormat.sptBonus(w.bonus))
                }
                Row(Modifier.padding(top = Dimens.space2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    SecondaryButton(TodoListFormat.SPT_BOUGHT, { onBuy(w.case.id) }, Modifier.testTag(sptBoughtTag(clientId)))
                    Spacer(Modifier.weight(1f))
                    w.phone?.let { CallButton(it) { messages.show(FollowupFormat.PHONE_COPIED) } }
                }
            }
        }
    }
}

/** «Купил блок со скидкой» из окна: дата покупки, «Сохранить» — дело закрыто, с «Отменить». */
@Composable
private fun SptBuy(
    w: SptWaiting,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onClose: () -> Unit,
) {
    SptPurchasePanel(
        case = w.case,
        name = CalendarFormat.client(w.name),
        initial = SptRules.defaultPurchase(w.case, now.date),
        bonus = w.bonus,
        onSave = { date ->
            onClose()
            scope.launch {
                val saved = clients.spt.close(w.case.id, date).first ?: return@launch
                messages.showUndo(SptFormat.closedMessage(saved.name)) { scope.launch { clients.spt.undo(saved) } }
            }
        },
        onUnmark = null,
        onDismiss = onClose,
    )
}
