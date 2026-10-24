package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.Account
import com.akenory.personalka.data.sync.ServerData
import com.akenory.personalka.data.sync.SyncProgress
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.SyncFormat
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

internal const val SYNC_CONNECT_TAG = "sync-connect"
internal const val SYNC_AGREE_TAG = "sync-agree"
internal const val SYNC_STATUS_TAG = "sync-status"
internal const val SYNC_PROBLEM_TAG = "sync-problem"
internal const val SYNC_PROGRESS_TAG = "sync-progress"
internal const val SYNC_NOW_TAG = "sync-now"
internal const val SYNC_DISCONNECT_TAG = "sync-disconnect"

/**
 * Синхронизация для экранов (шаг 2.10а): состояние и подключение ([repository]), «обменяться сейчас» ([now]) —
 * обмен идёт в приложении (`SyncRunner`), экраны только смотрят на состояние.
 */
class SyncTools(val repository: SyncRepository, val now: () -> Unit)

/** Окно раздела «Сервер»: условия при подключении, выбор при данных на сервере, подтверждение отключения. */
internal enum class SyncPanel { TERMS, SERVER_HAS_DATA, DISCONNECT }

/**
 * Виден ли раздел «Сервер» в «Данных» (10.7): сервер уже подключён на этом телефоне — всегда (отключиться можно и
 * после конца срока, 1.5); иначе — только после входа в аккаунт с открытой «Копией на сервере».
 */
internal fun syncVisible(connected: Boolean, account: Account?, now: LocalDateTime): Boolean =
    connected || account != null && Feature.SERVER_COPY in AccountRules.open(account.features, now.date)

/**
 * Раздел «Сервер» в «Настройки → Данные» (ТЗ 5.16, 1.5, 7.2; шаг 2.10а). Не подключён — «Подключить сервер»
 * с условиями и галочкой; если на сервере уже есть данные тренера — только «Взять данные с сервера» (слияние двух
 * разных первых запусков дало бы двойные типы и места). Подключён — время последней синхронизации, сколько ждёт
 * отправки и почему обмен не идёт, «Синхронизировать» и «Отключиться от сервера» с подтверждением. Первая выгрузка —
 * полосой «Выгружено N из M».
 *
 * @param onSyncNow «Синхронизировать»: обмен идёт в приложении ([com.akenory.personalka.data.sync.SyncRunner.now]),
 *   экран только смотрит на [SyncRepository.state].
 */
@Composable
internal fun SyncSection(
    sync: SyncRepository,
    now: LocalDateTime,
    onPanel: (SyncPanel) -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val state by sync.state.collectAsState()
    val pending by sync.pending.collectAsState(initial = 0)
    SectionLabel(SyncFormat.SECTION)
    val progress = state.progress
    when {
        progress != null -> UploadProgress(progress)
        !state.connected -> {
            Text(SyncFormat.CONNECT_NOTE, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            OutlineButton(
                SyncFormat.CONNECT,
                { onPanel(SyncPanel.TERMS) },
                Modifier.fillMaxWidth().padding(top = Dimens.space3).testTag(SYNC_CONNECT_TAG),
            )
        }
        else -> {
            val last = state.lastSync?.let { ClubTime.now(it) }
            Text(
                if (state.running) SyncFormat.SYNCING else SyncFormat.status(last, pending, now.date),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.testTag(SYNC_STATUS_TAG),
            )
            state.problem?.let {
                Text(
                    SyncFormat.problem(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(top = Dimens.space1).testTag(SYNC_PROBLEM_TAG),
                )
            }
            Column(Modifier.padding(top = Dimens.space3), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                OutlineButton(SyncFormat.SYNC_NOW, onSyncNow, Modifier.fillMaxWidth().testTag(SYNC_NOW_TAG))
                OutlineButton(SyncFormat.DISCONNECT, { onPanel(SyncPanel.DISCONNECT) }, Modifier.fillMaxWidth().testTag(SYNC_DISCONNECT_TAG))
            }
        }
    }
}

/** Окна раздела «Сервер» — поверх экрана «Данных», вне прокрутки. */
@Composable
internal fun SyncPanels(
    sync: SyncRepository,
    messages: MessageState,
    scope: CoroutineScope,
    panel: SyncPanel?,
    onPanel: (SyncPanel?) -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    fun connect(takeFromServer: Boolean) {
        onPanel(null)
        scope.launch {
            if (sync.connect(takeFromServer)) {
                messages.show(SyncFormat.CONNECTED)
            } else {
                sync.state.value.problem?.let { messages.show(SyncFormat.problem(it)) }
            }
        }
    }

    when (panel) {
        null -> agreed = false
        SyncPanel.TERMS -> Panel(SyncFormat.CONNECT, onDismiss = { onPanel(null) }) {
            Text(SyncFormat.TERMS_NO_WARRANTY, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
            Text(SyncFormat.TERMS_CONSENTS, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
                    .testTag(SYNC_AGREE_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBox(agreed)
                Spacer(Modifier.width(Dimens.space3))
                Text(SyncFormat.AGREE, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
            }
            PanelActions(
                primary = if (checking) SyncFormat.CHECKING else SyncFormat.DO_CONNECT,
                onPrimary = {
                    checking = true
                    scope.launch {
                        try {
                            when (sync.checkServer()) {
                                ServerData.EMPTY -> connect(takeFromServer = false)
                                ServerData.HAS_DATA -> onPanel(SyncPanel.SERVER_HAS_DATA)
                                ServerData.FAILED -> {
                                    onPanel(null)
                                    sync.state.value.problem?.let { messages.show(SyncFormat.problem(it)) }
                                }
                            }
                        } finally {
                            checking = false
                        }
                    }
                },
                secondary = "Отмена",
                onSecondary = { onPanel(null) },
                primaryEnabled = agreed && !checking,
            )
        }
        SyncPanel.SERVER_HAS_DATA -> Panel(SyncFormat.CONNECT, onDismiss = { onPanel(null) }) {
            Text(SyncFormat.SERVER_HAS_DATA, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
            Text(SyncFormat.SERVER_HAS_DATA_NOTE, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
            PanelActions(SyncFormat.TAKE, { connect(takeFromServer = true) }, "Отмена", { onPanel(null) })
        }
        SyncPanel.DISCONNECT -> Panel(SyncFormat.DISCONNECT, onDismiss = { onPanel(null) }) {
            Text(SyncFormat.DISCONNECT_TEXT, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
            PanelActions(
                primary = SyncFormat.DISCONNECT_CONFIRM,
                onPrimary = {
                    onPanel(null)
                    scope.launch {
                        if (sync.disconnect()) {
                            messages.show(SyncFormat.DISCONNECTED)
                        } else {
                            sync.state.value.problem?.let { messages.show(SyncFormat.problem(it)) }
                        }
                    }
                },
                secondary = "Отмена",
                onSecondary = { onPanel(null) },
            )
        }
    }
}

/** Полоса первой выгрузки (7.2, п. 5): «Выгружено 120 из 1 200». */
@Composable
internal fun UploadProgress(progress: SyncProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(SYNC_PROGRESS_TAG), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Text(SyncFormat.progress(progress.sent, progress.total), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 1f else progress.sent.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(),
            color = PersonalkaTheme.colors.primary,
            trackColor = PersonalkaTheme.colors.surfaceAlt,
        )
    }
}
