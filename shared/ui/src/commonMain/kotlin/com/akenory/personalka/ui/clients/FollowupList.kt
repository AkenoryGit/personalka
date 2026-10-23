package com.akenory.personalka.ui.clients

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.FollowupRow
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.FollowupRules
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.training.TrainingFormKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

fun followupRowTag(name: String) = "followup-$name"
fun followupBookTag(name: String) = "followup-book-$name"
fun followupContactedTag(name: String) = "followup-contacted-$name"
fun followupMenuTag(name: String) = "followup-menu-$name"
const val PAUSE_DAY_TAG = "followup-pause-day"
const val PAUSE_NOTE_TAG = "followup-pause-note"

/**
 * «Без записи» (5.8, 4.15, макет ClNoBooking): раздел «Не записаны дальше» — карточка на клиента: имя, «писали 16.10»
 * справа, пометка «после СПТ 12.10», «была пн 12.10 · СПТ ББ»; «Записать», «Написал», звонок (долгое нажатие
 * копирует номер) и меню с «Пауза до…» и «Больше не ходит». Нажатие на имя открывает карточку клиента.
 *
 * Раздел «Ждут время» сверху — с листом ожидания (шаг 2.4а).
 *
 * @param onForm «Записать»: форма тренировки с этим клиентом, тем же типом и местом на ближайший такой же день
 *   недели и время, что и последнее посещение.
 */
@Composable
internal fun FollowupList(
    followup: Followup,
    query: String,
    scroll: LazyListState,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onOpen: (String) -> Unit,
    onForm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    val today = now.date
    val rows = followup.rows.filter { ClientRules.matches(query, it.name) }
    var pausing by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier) {
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag(CLIENT_LIST_TAG),
            contentPadding = PaddingValues(start = Dimens.space4, end = Dimens.space4, bottom = Dimens.space6),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            item(key = "title") {
                Column(Modifier.padding(top = Dimens.space3, bottom = Dimens.space1)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            FollowupFormat.SECTION,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                        )
                        if (rows.isNotEmpty()) {
                            Spacer(Modifier.width(Dimens.space2))
                            Text(rows.size.toString(), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                    Text(FollowupFormat.sectionNote(followup.days), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) FollowupFormat.EMPTY else "Никого не нашлось",
                        modifier = Modifier.padding(vertical = Dimens.space3),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textMuted,
                    )
                }
            }
            items(rows, key = { it.client.id }) { row ->
                FollowupItem(
                    row = row,
                    today = today,
                    onOpen = { onOpen(row.client.id) },
                    onBook = {
                        val last = row.item.last
                        onForm(
                            TrainingFormKey.new(
                                start = FollowupRules.bookingStart(last, now),
                                clientId = row.client.id,
                                typeId = last.typeId,
                                locationId = last.locationId,
                            ),
                        )
                    },
                    onContacted = {
                        scope.launch {
                            val before = clients.followup.contacted(row.client.id) ?: return@launch
                            messages.showUndo(FollowupFormat.contactedMessage(row.short)) { scope.launch { clients.followup.undo(before) } }
                        }
                    },
                    onPause = { pausing = row.client.id },
                    onGone = {
                        scope.launch {
                            val undo = clients.archive(row.client.id) ?: return@launch
                            messages.showUndo("${row.short} в архиве") { scope.launch { clients.undoArchive(undo) } }
                        }
                    },
                    onCopied = { messages.show(FollowupFormat.PHONE_COPIED) },
                )
            }
        }

        val paused = followup.rows.firstOrNull { it.client.id == pausing }
        if (paused != null) {
            PausePanel(
                row = paused,
                today = today,
                onDismiss = { pausing = null },
                onSave = { until, note ->
                    pausing = null
                    scope.launch {
                        val before = clients.followup.pause(paused.client.id, until, note) ?: return@launch
                        messages.showUndo(FollowupFormat.pausedMessage(paused.short, until)) { scope.launch { clients.followup.undo(before) } }
                    }
                },
            )
        }
    }
}

private val FollowupRow.name: ClientName get() = ClientName(client.lastName, client.firstName, client.middleName)

/** «Яковлева Татьяна» — в сообщениях. */
private val FollowupRow.short: String get() = name.short

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowupItem(
    row: FollowupRow,
    today: LocalDate,
    onOpen: () -> Unit,
    onBook: () -> Unit,
    onContacted: () -> Unit,
    onPause: () -> Unit,
    onGone: () -> Unit,
    onCopied: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val client = row.client
    val title = row.short
    val phone = client.contactPhone
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .testTag(followupRowTag(title))
            .padding(start = Dimens.space4, end = 4.dp, top = Dimens.space3, bottom = Dimens.space2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = Dimens.space3)
                .clickable(role = Role.Button, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.item.contactedOn?.let {
                Text(FollowupFormat.contacted(it), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        row.item.afterSpt?.let { date ->
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier
                    .border(1.dp, colors.outline, MaterialTheme.shapes.small)
                    .padding(horizontal = Dimens.space2, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PIcons.Flag, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(FollowupFormat.afterSpt(date), style = MaterialTheme.typography.labelMedium, color = colors.text)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            FollowupFormat.lastVisit(client.sex, row.item.last.start, row.typeShort, row.placeShort, today),
            modifier = Modifier.padding(end = Dimens.space3),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.size(Dimens.space3))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SecondaryButton(FollowupFormat.BOOK, onBook, modifier = Modifier.testTag(followupBookTag(title)))
            OutlineButton(FollowupFormat.CONTACTED, onContacted, modifier = Modifier.testTag(followupContactedTag(title)))
            Spacer(Modifier.weight(1f))
            if (phone != null) {
                Box(
                    Modifier
                        .size(Dimens.touch)
                        .clip(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = { runCatching { uri.openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) } },
                            onLongClick = {
                                clipboard.setText(AnnotatedString(ClientFormat.phone(phone)))
                                onCopied()
                            },
                        )
                        .semantics {
                            contentDescription = FollowupFormat.CALL
                            role = Role.Button
                            customActions = listOf(
                                CustomAccessibilityAction("Скопировать номер") {
                                    clipboard.setText(AnnotatedString(ClientFormat.phone(phone)))
                                    onCopied()
                                    true
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PIcons.Call, contentDescription = null, tint = colors.text)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(followupMenuTag(title))) {
                    Icon(PIcons.MoreVert, contentDescription = "Ещё", tint = colors.text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                    MenuAction(FollowupFormat.PAUSE, PIcons.PauseCircle, {
                        menu = false
                        onPause()
                    })
                    MenuAction(FollowupFormat.GONE, PIcons.Archive, {
                        menu = false
                        onGone()
                    })
                }
            }
        }
    }
}

/** «Пауза до…»: день, с которого клиент вернётся в список, и причина для себя (4.15). По умолчанию — через неделю. */
@Composable
private fun PausePanel(row: FollowupRow, today: LocalDate, onDismiss: () -> Unit, onSave: (LocalDate, String) -> Unit) {
    val colors = PersonalkaTheme.colors
    var day by rememberSaveable(row.client.id) { mutableStateOf(today.plus(7, DateTimeUnit.DAY).toString()) }
    var note by rememberSaveable(row.client.id) { mutableStateOf("") }
    var picking by rememberSaveable(row.client.id) { mutableStateOf(false) }
    val until = LocalDate.parse(day)
    val problem = if (until <= today) "Выберите день после сегодняшнего" else null
    Panel(FollowupFormat.PAUSE_TITLE, onDismiss = onDismiss) {
        Text(row.short, style = MaterialTheme.typography.labelLarge, color = colors.text)
        SectionLabel(FollowupFormat.PAUSE_UNTIL)
        PickBox(
            text = TrainingFormat.date(until),
            icon = PIcons.CalendarMonth,
            onClick = { picking = true },
            modifier = Modifier.fillMaxWidth().testTag(PAUSE_DAY_TAG),
            error = problem,
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        Field(FollowupFormat.PAUSE_NOTE, note, { note = it }, placeholder = FollowupFormat.PAUSE_NOTE_HINT, tag = PAUSE_NOTE_TAG)
        PanelActions(
            primary = FollowupFormat.PAUSE_SAVE,
            onPrimary = { onSave(until, note) },
            secondary = "Отмена",
            onSecondary = onDismiss,
            primaryEnabled = problem == null,
        )
    }
    if (picking) {
        DatePickerPanel(
            initial = until,
            onPick = {
                picking = false
                day = it.toString()
            },
            onDismiss = { picking = false },
        )
    }
}
