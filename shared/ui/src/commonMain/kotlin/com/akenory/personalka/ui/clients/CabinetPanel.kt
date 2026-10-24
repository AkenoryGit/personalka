package com.akenory.personalka.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.cabinet.CabinetPreview
import com.akenory.personalka.data.cabinet.CabinetRepository
import com.akenory.personalka.data.cabinet.CabinetResult
import com.akenory.personalka.data.cabinet.CabinetStatus
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.cabinet.CabinetLinkRules
import com.akenory.personalka.domain.cabinet.ConsentState
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val CARD_CABINET_TAG = "card-cabinet"
const val CABINET_OPEN_TAG = "cabinet-open"
const val CABINET_LINK_TAG = "cabinet-link"
const val CABINET_SEND_TAG = "cabinet-send"
const val CABINET_COPY_TAG = "cabinet-copy"
const val CABINET_CODE_TAG = "cabinet-code"
const val CABINET_NEW_TAG = "cabinet-new"
const val CABINET_ISSUE_TAG = "cabinet-issue"
const val CABINET_CONSENT_TAG = "cabinet-consent"

const val CABINET_PREVIEW_TAG = "cabinet-preview"
const val PREVIEW_SCREEN_TAG = "cabinet-preview-screen"
const val PREVIEW_NOT_SEEN_TAG = "cabinet-preview-not-seen"
const val PREVIEW_CLOSE_TAG = "cabinet-preview-close"

/**
 * Встроенный браузер платформы для «Как видит родитель» (6.4): открывает [url] на всё отведённое место; «Назад»
 * системы — назад по страницам, а с первой — [onClose].
 */
typealias CabinetBrowser = @Composable (url: String, onClose: () -> Unit, modifier: Modifier) -> Unit

/**
 * Кабинет в приложении (шаг 2.13): ссылки на сервере и то, что даёт платформа, — системное «Поделиться» [share]
 * и буфер обмена [copy]. Есть только в режиме «С сервером» с открытыми «Кабинетами» (5.9).
 * «Как видит родитель» (шаг 2.13б) — когда платформа даёт [browser]; перед ним — обмен [sync].
 */
class CabinetTools(
    val repository: CabinetRepository,
    val share: (String) -> Unit,
    val copy: (String) -> Unit,
    val sync: SyncRepository? = null,
    val browser: CabinetBrowser? = null,
)

/**
 * Окно «Кабинет» карточки клиента (5.9, макет ClCabinet): «Кабинет открыт», отметка согласия (6.1), ссылка
 * с «Отправить» и «Скопировать», «Код доступа», «Выпустить новую ссылку», «Последний раз открывали». Без связи —
 * ссылка, которую помнит телефон, её можно отправить; код и новая ссылка — когда сервер ответит.
 *
 * Действия идут в [scope] приложения: окно можно закрыть, не дожидаясь сервера. «Как видит родитель» открывает
 * кабинет на месте окна, «Назад» и крестик возвращают к окну.
 */
@Composable
fun CabinetPanel(
    client: ClientEntity,
    tools: CabinetTools,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val repository = tools.repository
    var reload by remember { mutableIntStateOf(0) }
    var status by remember(client.id) { mutableStateOf<CabinetStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember(client.id) { mutableStateOf<CabinetPreview.Ready?>(null) }
    LaunchedEffect(client.id, reload) { status = repository.status(client.id) }
    val adult = ClientRules.isAdult(client.birthDate, client.isAdult, today)
    val browser = tools.browser
    preview?.let { ready ->
        if (browser != null) {
            CabinetPreviewScreen(ready, adult, browser, onClose = { preview = null; reload++ })
            return
        }
    }

    fun run(done: String? = null, action: suspend () -> CabinetResult) {
        busy = true
        scope.launch {
            when (val result = action()) {
                CabinetResult.Done -> done?.let(messages::show)
                is CabinetResult.Failed -> messages.show(result.message)
            }
            busy = false
            reload++
        }
    }

    Panel(CabinetLinkFormat.title(client.lastName, client.firstName), onDismiss = onClose) {
        SwitchRow(
            title = CabinetLinkFormat.OPEN,
            checked = client.cabinetEnabled,
            onToggle = { open -> if (!busy) run { repository.setOpen(client.id, open, withPin = true) } },
            subtitle = if (adult) CabinetLinkFormat.OPEN_NOTE_ADULT else CabinetLinkFormat.OPEN_NOTE,
            modifier = Modifier.testTag(CABINET_OPEN_TAG),
        )
        // Закрытый кабинет — только выключатель (10.7): ссылка хранится и заработает, когда его откроют.
        if (!client.cabinetEnabled) return@Panel
        val loaded = status
        if (loaded == null) {
            Text(CabinetLinkFormat.LOADING, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            return@Panel
        }
        if (loaded.online) {
            val line = CabinetLinkRules.consent(loaded.consent, child = !adult)
            val (bg, fg) = when (line.state) {
                ConsentState.GIVEN -> colors.doneContainer to colors.onDoneContainer
                ConsentState.REVOKED -> colors.errorContainer to colors.onErrorContainer
                ConsentState.NONE -> colors.surfaceAlt to colors.text
            }
            Row(
                Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.medium).padding(Dimens.space3)
                    .semantics(mergeDescendants = true) {}.testTag(CABINET_CONSENT_TAG),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Icon(if (line.state == ConsentState.GIVEN) PIcons.CheckCircle else PIcons.Info, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Text(line.text, style = MaterialTheme.typography.bodySmall, color = fg)
            }
        } else {
            loaded.problem?.let { Note(it) }
        }
        val link = loaded.link
        val url = link?.url
        when {
            link != null && url != null -> {
                val text = CabinetLinkRules.shareText(url, link.pin, adult)
                SectionLabel(CabinetLinkFormat.LINK)
                Row(
                    Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.medium).padding(Dimens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PIcons.Link, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.space2))
                    Text(
                        url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(CABINET_LINK_TAG),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PrimaryButton(CabinetLinkFormat.SEND, { tools.share(text) }, Modifier.weight(1f).testTag(CABINET_SEND_TAG))
                    SecondaryButton(
                        CabinetLinkFormat.COPY,
                        {
                            tools.copy(text)
                            messages.show(CabinetLinkFormat.copied(link.pin != null))
                        },
                        Modifier.weight(1f).testTag(CABINET_COPY_TAG),
                        icon = PIcons.ContentCopy,
                    )
                }
            }
            link != null -> {
                Note(CabinetLinkFormat.OTHER_PHONE)
            }
            loaded.online -> {
                Text(CabinetLinkFormat.NO_LINK, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                PrimaryButton(
                    CabinetLinkFormat.ISSUE,
                    { if (!busy) run { repository.issue(client.id, withPin = true) } },
                    Modifier.fillMaxWidth().testTag(CABINET_ISSUE_TAG),
                    enabled = !busy,
                )
            }
        }
        // Код и новая ссылка — только когда сервер ответил: без него их не поменять.
        if (link != null && loaded.online) {
            SwitchRow(
                title = CabinetLinkFormat.CODE,
                checked = link.pinOn,
                onToggle = { on -> if (!busy) run { repository.setPin(client.id, on) } },
                subtitle = if (link.pinOn) CabinetLinkFormat.codeOn(link.pin) else CabinetLinkFormat.CODE_OFF,
                modifier = Modifier.testTag(CABINET_CODE_TAG),
            )
        }
        if (link != null && (loaded.online || browser != null)) HorizontalDivider(color = colors.divider)
        // «Как видит родитель» (6.4) — и без связи: нажатие скажет «Нужен интернет».
        if (link != null && browser != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, enabled = !busy) {
                        busy = true
                        messages.show(CabinetLinkFormat.PREVIEW_LOADING)
                        scope.launch {
                            when (val result = repository.preview(client.id, tools.sync)) {
                                is CabinetPreview.Ready -> preview = result
                                is CabinetPreview.Failed -> messages.show(result.message)
                            }
                            busy = false
                        }
                    }
                    .padding(vertical = Dimens.space2)
                    .testTag(CABINET_PREVIEW_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PIcons.Visibility, contentDescription = null, tint = colors.text, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Dimens.space4))
                Text(CabinetLinkFormat.preview(adult), style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
                Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            }
        }
        if (link != null && loaded.online) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, enabled = !busy) {
                        run(CabinetLinkFormat.NEW_LINK_DONE) { repository.issue(client.id, withPin = link.pinOn) }
                    }
                    .padding(vertical = Dimens.space2)
                    .testTag(CABINET_NEW_TAG),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PIcons.LinkOff, contentDescription = null, tint = colors.text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(Dimens.space4))
                    Column {
                        Text(CabinetLinkFormat.NEW_LINK, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                        Text(CabinetLinkFormat.NEW_LINK_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    }
                }
            }
            Text(
                CabinetLinkFormat.lastOpened(link.lastOpenedAt?.let { ClubTime.now(it) }, today),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * «Как видит родитель» (6.4): тот же кабинет во встроенном браузере на месте окна. Сверху — заголовок с крестиком
 * и, если часть правок не ушла на сервер, плашка «Родитель пока не видит N последних изменений».
 */
@Composable
private fun CabinetPreviewScreen(preview: CabinetPreview.Ready, adult: Boolean, browser: CabinetBrowser, onClose: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxSize().background(colors.bg).testTag(PREVIEW_SCREEN_TAG)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = Dimens.space2, vertical = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, Modifier.testTag(PREVIEW_CLOSE_TAG)) { Icon(PIcons.Close, contentDescription = "Закрыть", tint = colors.text) }
            Column(Modifier.weight(1f).padding(start = Dimens.space2)) {
                Text(
                    CabinetLinkFormat.preview(adult),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(CabinetLinkFormat.PREVIEW_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        if (preview.pending > 0) {
            Row(
                Modifier.fillMaxWidth().background(colors.errorContainer).padding(Dimens.space3)
                    .semantics(mergeDescendants = true) {}.testTag(PREVIEW_NOT_SEEN_TAG),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PIcons.Info, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(18.dp))
                Text(CabinetLinkFormat.notSeen(preview.pending, adult), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
            }
        }
        browser(preview.url, onClose, Modifier.fillMaxWidth().weight(1f))
    }
}
