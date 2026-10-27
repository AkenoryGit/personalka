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
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.sync.Attach
import com.akenory.personalka.data.sync.SignOutCheck
import com.akenory.personalka.data.sync.SyncProgress
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.format.SyncFormat
import com.akenory.personalka.domain.sync.SyncProblem
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

internal const val SYNC_CONNECT_TAG = "sync-connect"
internal const val SYNC_STATUS_TAG = "sync-status"
internal const val SYNC_PROBLEM_TAG = "sync-problem"
internal const val SYNC_PROGRESS_TAG = "sync-progress"
internal const val SYNC_NOW_TAG = "sync-now"
internal const val SYNC_DISCONNECT_TAG = "sync-disconnect"
internal const val SYNC_WIPE_TAG = "sync-wipe"

/**
 * Синхронизация для экранов (шаг 2.10а): состояние и подключение ([repository]), «обменяться сейчас» ([now]) —
 * обмен идёт в приложении (`SyncRunner`), экраны только смотрят на состояние. Открытое окно копии ([panel], шаг 5.1а)
 * живёт здесь, а не в экране: после входа экран аккаунта собирается заново, а вопрос должен остаться.
 */
class SyncTools(val repository: SyncRepository, val now: () -> Unit) {
    internal var panel by mutableStateOf<SyncPanel?>(null)
}

/**
 * Окно копии на сервере (шаг 5.1а): после входа — данные на сервере ([ServerHasData]) или на телефоне данные другого
 * аккаунта ([OtherAccount]); подтверждение «Удалить копию с сервера» ([Disconnect]); «Выйти» ([SignOut]).
 */
internal sealed interface SyncPanel {
    data object ServerHasData : SyncPanel

    data class OtherAccount(val email: String, val complete: Boolean) : SyncPanel

    data object Disconnect : SyncPanel

    data class SignOut(val check: SignOutCheck) : SyncPanel
}

/**
 * Виден ли раздел «Сервер» в «Данных» (10.7, шаг 5.1а): только после входа — копия подключена на этом телефоне
 * (удалить её можно и после конца срока, 1.5) или открыта у аккаунта.
 */
internal fun syncVisible(connected: Boolean, account: Account?, now: LocalDateTime): Boolean =
    account != null && (connected || Feature.SERVER_COPY in AccountRules.open(account.features, now.date))

/** Открыта ли у аккаунта «Копия на сервере» сегодня: тогда вход сам её включает (шаг 5.1а). */
internal fun copyOpen(account: Account?, now: LocalDateTime): Boolean =
    account != null && Feature.SERVER_COPY in AccountRules.open(account.features, now.date)

/**
 * Вход = копия на сервере (шаг 5.1а, ТЗ 1.5): включить копию и, если нужен выбор, открыть окно ([SyncPanels]).
 */
internal suspend fun turnOnCopy(sync: SyncRepository, messages: MessageState, onPanel: (SyncPanel?) -> Unit) {
    when (val result = sync.attach()) {
        Attach.Done -> messages.show(SyncFormat.CONNECTED)
        Attach.ServerHasData -> onPanel(SyncPanel.ServerHasData)
        is Attach.OtherAccount -> onPanel(SyncPanel.OtherAccount(result.email, result.complete))
        Attach.Failed -> sync.state.value.problem?.let { messages.show(SyncFormat.problem(it)) }
    }
}

/**
 * Раздел «Сервер» в «Настройки → Данные» (ТЗ 5.16, 1.5, 7.2; шаги 2.10а, 5.1а). «Подключить сервер» нет: копию
 * включает вход. Подключена — время последней синхронизации, сколько ждёт отправки и почему обмен не идёт,
 * «Синхронизировать» и «Удалить копию с сервера» с подтверждением. Вошли раньше, а копия не включена (её открыли
 * позже или вход был до шага 5.1а) — «Включить копию». Первая выгрузка — полосой «Выгружено N из M».
 *
 * @param onSyncNow «Синхронизировать»: обмен идёт в приложении ([com.akenory.personalka.data.sync.SyncRunner.now]),
 *   экран только смотрит на [SyncRepository.state].
 */
@Composable
internal fun SyncSection(
    sync: SyncRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onPanel: (SyncPanel?) -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val state by sync.state.collectAsState()
    val pending by sync.pending.collectAsState(initial = 0)
    var working by remember { mutableStateOf(false) }
    SectionLabel(SyncFormat.SECTION)
    val progress = state.progress
    when {
        progress != null -> UploadProgress(progress)
        !state.connected -> {
            Text(SyncFormat.COPY_OFF, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            OutlineButton(
                if (working) SyncFormat.WORKING else SyncFormat.TURN_ON,
                {
                    if (!working) {
                        working = true
                        scope.launch {
                            try {
                                turnOnCopy(sync, messages, onPanel)
                            } finally {
                                working = false
                            }
                        }
                    }
                },
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
            state.error?.takeIf { state.problem == SyncProblem.FAILED }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.padding(top = Dimens.space1))
            }
            Column(Modifier.padding(top = Dimens.space3), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                OutlineButton(SyncFormat.SYNC_NOW, onSyncNow, Modifier.fillMaxWidth().testTag(SYNC_NOW_TAG))
                OutlineButton(SyncFormat.DISCONNECT, { onPanel(SyncPanel.Disconnect) }, Modifier.fillMaxWidth().testTag(SYNC_DISCONNECT_TAG))
            }
        }
    }
}

/**
 * Окна копии на сервере — поверх экрана, вне прокрутки (шаг 5.1а). Отказ от выбора после входа («Не входить», смахнуть
 * окно) — выход из аккаунта: аккаунт = копия, а копию с таким телефоном не включить.
 */
@Composable
internal fun SyncPanels(
    sync: SyncRepository,
    account: AccountRepository,
    messages: MessageState,
    scope: CoroutineScope,
    panel: SyncPanel?,
    onPanel: (SyncPanel?) -> Unit,
) {
    val text = PersonalkaTheme.colors.text
    var wipe by remember { mutableStateOf(false) }
    val pending by sync.pending.collectAsState(initial = 0)

    fun dontSignIn() {
        onPanel(null)
        scope.launch { account.logout() }
    }

    fun takeFromServer() {
        onPanel(null)
        scope.launch {
            if (sync.connect(takeFromServer = true)) {
                messages.show(SyncFormat.CONNECTED)
            } else {
                sync.state.value.problem?.let { messages.show(SyncFormat.problem(it)) }
            }
        }
    }

    when (panel) {
        null -> wipe = false
        SyncPanel.ServerHasData -> Panel(AccountFormat.TITLE, onDismiss = ::dontSignIn) {
            Text(SyncFormat.SERVER_HAS_DATA, style = MaterialTheme.typography.bodyMedium, color = text)
            Text(SyncFormat.SERVER_HAS_DATA_NOTE, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
            PanelActions(SyncFormat.TAKE, ::takeFromServer, SyncFormat.DONT_SIGN_IN, ::dontSignIn)
        }
        is SyncPanel.OtherAccount -> Panel(AccountFormat.TITLE, onDismiss = ::dontSignIn) {
            Text(SyncFormat.otherAccount(panel.email, panel.complete), style = MaterialTheme.typography.bodyMedium, color = text)
            if (panel.complete) {
                PanelActions(SyncFormat.REPLACE, ::takeFromServer, SyncFormat.DONT_SIGN_IN, ::dontSignIn)
            } else {
                PrimaryButton(SyncFormat.GOT_IT, ::dontSignIn, Modifier.fillMaxWidth().padding(top = Dimens.space2))
            }
        }
        SyncPanel.Disconnect -> Panel(SyncFormat.DISCONNECT, onDismiss = { onPanel(null) }) {
            Text(SyncFormat.DISCONNECT_TEXT, style = MaterialTheme.typography.bodyMedium, color = text)
            PanelActions(
                primary = SyncFormat.DISCONNECT_CONFIRM,
                onPrimary = {
                    onPanel(null)
                    scope.launch {
                        if (sync.disconnect()) {
                            // Сервер закрыл все входы аккаунта, и этот тоже.
                            account.logout()
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
        is SyncPanel.SignOut -> Panel(AccountFormat.SIGN_OUT, onDismiss = { onPanel(null) }) {
            val sent = panel.check == SignOutCheck.SENT
            Text(if (sent) SyncFormat.SIGN_OUT_SENT else SyncFormat.notSent(pending), style = MaterialTheme.typography.bodyMedium, color = text)
            if (sent) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = wipe, role = Role.Checkbox, onValueChange = { wipe = it })
                        .testTag(SYNC_WIPE_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(wipe)
                    Spacer(Modifier.width(Dimens.space3))
                    Text(SyncFormat.WIPE, style = MaterialTheme.typography.bodyLarge, color = text)
                }
                if (wipe) Text(SyncFormat.WIPE_NOTE, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
            }
            PanelActions(
                primary = SyncFormat.SIGN_OUT_DO,
                onPrimary = {
                    val clear = sent && wipe
                    onPanel(null)
                    scope.launch {
                        account.logout()
                        // Правка, сделанная, пока окно было открыто, не даст стереть — данные останутся.
                        if (clear && sync.wipePhone()) messages.show(SyncFormat.WIPED) else messages.show(AccountFormat.SIGNED_OUT_HERE)
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
