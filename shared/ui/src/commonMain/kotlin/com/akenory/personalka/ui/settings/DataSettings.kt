package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.backup.BackupRead
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.backup.BackupState
import com.akenory.personalka.domain.backup.BackupFrequency
import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.backup.Xlsx
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.format.BackupFormat
import com.akenory.personalka.domain.format.ExcelFormat
import com.akenory.personalka.domain.format.MoreFormat
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
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
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import com.akenory.personalka.ui.tour.TourTarget
import com.akenory.personalka.ui.tour.tourTarget

/**
 * Системные окна платформы для копий: выбор папки и файла, «Поделиться». На Android — Storage Access Framework
 * и Intent (`androidApp`), в тестах — подставные.
 */
interface PlatformFiles {
    /** Выбрать папку для копий: ответ — её адрес и название; передумал — ответа нет. */
    fun pickFolder(onPicked: (uri: String, name: String) -> Unit)

    /** Выбрать файл копии: ответ — содержимое файла. */
    fun openFile(onOpened: (ByteArray) -> Unit)

    /** Отправить файл: мессенджер, почта, облако, календарь. */
    fun share(name: String, mime: String, bytes: ByteArray)

    /** Выбрать PDF-отчёт 1С (шаг 4.2б): ответ — имя файла (в нём время выгрузки, ТЗ 8.1) и содержимое. */
    fun openReport(onOpened: (SharedFile) -> Unit) = openFile { onOpened(SharedFile("", it)) }

    /**
     * Отчёт 1С, присланный в приложение через «Поделиться» из почты или мессенджера (5.14): «Сверки» забирают его
     * и зовут [takeIncoming]. Платформа без такого входа — всегда `null`.
     */
    val incoming: StateFlow<SharedFile?> get() = NO_INCOMING

    fun takeIncoming() {}
}

/** Файл, пришедший в приложение: имя и содержимое. */
class SharedFile(val name: String, val bytes: ByteArray)

private val NO_INCOMING: StateFlow<SharedFile?> = MutableStateFlow(null)

/** Резервная копия для экранов: копии в базе телефона ([repository]) и системные окна ([files]). */
class BackupTools(val repository: BackupRepository, val files: PlatformFiles)

internal const val DATA_ROW_TAG = "settings-data"
internal const val ACCOUNT_ROW_TAG = "settings-account"
internal const val FOLDER_ROW_TAG = "backup-folder"
internal const val PASSWORD_ROW_TAG = "backup-password"
internal const val ENCRYPT_TAG = "backup-encrypt"
internal const val CREATE_NOW_TAG = "backup-now"
internal const val SHARE_TAG = "backup-share"
internal const val RESTORE_TAG = "backup-restore"
internal const val UPDATE_COPY_TAG = "backup-update-copy"
internal const val CALENDAR_EXPORT_TAG = "backup-ics"
internal const val EXCEL_EXPORT_TAG = "backup-xlsx"
internal const val PASSWORD_FIELD_TAG = "backup-password-field"
internal const val PASSWORD_REPEAT_TAG = "backup-password-repeat"
internal fun frequencyTag(f: BackupFrequency) = "backup-${f.code}"

/** Какой пункт «Данных» в «Ещё» открыт (шаг 3.15а): у каждого свой экран. */
internal enum class DataPart { SERVER, BACKUP, EXPORTS }

/** Что сделать, когда пароль будет задан: копия ждала пароля. */
private enum class AfterPassword { NOTHING, SAVE, SHARE }

/**
 * «Данные» (ТЗ 5.16, 7.6, шаг 1.15) — с шага 3.15а три пункта «Ещё», [part]:
 * - [DataPart.BACKUP] «Резервная копия»: папка для копий, частота автокопии, шифрование паролем, «Создать копию сейчас»,
 *   «Поделиться копией», «Восстановить из копии»;
 * - [DataPart.EXPORTS] «Выгрузки»: календарь (.ics) и Excel;
 * - [DataPart.SERVER] «Аккаунт»: строка аккаунта (шаг 2.8а, если платформа дала [account]) открывает экран входа
 *   ([onAccount]); под ней — «Сервер» (шаг 2.10а, [SyncSection]), если платформа дала [sync]: виден только после входа —
 *   с открытой «Копией на сервере» или когда она уже подключена (10.7, шаг 5.1а).
 */
@Composable
internal fun DataSettingsScreen(
    tools: BackupTools,
    part: DataPart,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
    account: AccountRepository? = null,
    now: LocalDateTime? = null,
    sync: SyncTools? = null,
    onAccount: () -> Unit = {},
) {
    val backup = tools.repository
    val files = tools.files
    val state by backup.state.collectAsState()
    // Окна держатся в памяти экрана: файл копии в сохраняемое состояние не кладётся (он большой и с данными детей).
    var passwordFor by remember { mutableStateOf<AfterPassword?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupRead.Ready?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    suspend fun saveAndTell() = messages.show(if (backup.saveNow()) BackupFormat.SAVED else BackupFormat.FOLDER_LOST)

    fun saveNow() = work { saveAndTell() }

    // Папку выбирают в системном окне: копия — когда оно ответит, сама по себе, без ожидания других дел экрана.
    fun pickFolderAndSave() = files.pickFolder { uri, name ->
        backup.chooseFolder(uri, name)
        scope.launch { saveAndTell() }
    }

    fun share() = work {
        val made = backup.create() ?: return@work
        files.share(made.name, ZIP, made.bytes)
        backup.shared()
    }

    fun pickFolder() = files.pickFolder { uri, name -> backup.chooseFolder(uri, name) }

    fun opened(result: BackupRead) {
        when (result) {
            is BackupRead.Ready -> {
                restoreBytes = null
                preview = result
            }
            BackupRead.NeedPassword -> wrongPassword = false
            BackupRead.WrongPassword -> wrongPassword = true
            BackupRead.NotBackup -> {
                restoreBytes = null
                messages.show(BackupFormat.NOT_BACKUP)
            }
            BackupRead.Newer -> {
                restoreBytes = null
                messages.show(BackupFormat.NEWER)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val title = when (part) {
                DataPart.SERVER -> MoreFormat.ACCOUNT
                DataPart.BACKUP -> MoreFormat.BACKUP
                DataPart.EXPORTS -> MoreFormat.EXPORTS
            }
            TopBar(title, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                if (part == DataPart.SERVER && account != null) {
                    val signedIn by account.account.collectAsState()
                    ListCard(Modifier.padding(top = Dimens.space2)) {
                        NavRow(
                            title = AccountFormat.TITLE,
                            subtitle = AccountFormat.summary(signedIn?.name, signedIn?.email),
                            onClick = onAccount,
                            modifier = Modifier.testTag(ACCOUNT_ROW_TAG),
                        )
                    }
                    val syncState = sync?.repository?.state?.collectAsState()?.value
                    if (sync != null && now != null && syncState != null && syncVisible(syncState.connected, signedIn, now)) {
                        SyncSection(sync.repository, now, messages, scope, onPanel = { sync.panel = it }, onSyncNow = sync.now)
                    }
                }
                if (part == DataPart.BACKUP) {
                    Text(BackupFormat.last(state.last), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
                    Spacer(Modifier.height(Dimens.space3))
                    ListCard {
                        NavRow(
                            title = BackupFormat.FOLDER,
                            subtitle = state.folderName ?: BackupFormat.FOLDER_NONE,
                            onClick = { pickFolder() },
                            modifier = Modifier.tourTarget(TourTarget.BACKUP_FOLDER).testTag(FOLDER_ROW_TAG),
                        )
                        if (state.encrypt) {
                            RowDivider()
                            NavRow(
                                title = BackupFormat.PASSWORD,
                                subtitle = if (state.hasPassword) BackupFormat.PASSWORD_SET else BackupFormat.PASSWORD_NONE,
                                onClick = { passwordFor = AfterPassword.NOTHING },
                                modifier = Modifier.testTag(PASSWORD_ROW_TAG),
                            )
                        }
                }
                Hint(BackupFormat.FOLDER_NOTE)

                if (state.folder != null) {
                    SectionLabel(BackupFormat.FREQUENCY)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        BackupFrequency.entries.forEach { f ->
                            ChoiceChip(
                                text = BackupFormat.frequency(f),
                                selected = f == state.frequency,
                                onClick = { backup.setFrequency(f) },
                                modifier = Modifier.testTag(frequencyTag(f)),
                            )
                        }
                    }
                }

                SwitchRow(
                    title = BackupFormat.ENCRYPT,
                    checked = state.encrypt,
                    onToggle = { backup.setEncrypt(it) },
                    subtitle = BackupFormat.ENCRYPT_NOTE,
                    modifier = Modifier.padding(top = Dimens.space3).testTag(ENCRYPT_TAG),
                )

                Column(Modifier.padding(top = Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    PrimaryButton(
                        text = if (busy) BackupFormat.WORKING else BackupFormat.CREATE_NOW,
                        onClick = {
                            when {
                                !state.ready -> passwordFor = AfterPassword.SAVE
                                state.folder == null -> pickFolderAndSave()
                                else -> saveNow()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag(CREATE_NOW_TAG),
                        enabled = !busy,
                    )
                    OutlineButton(
                        BackupFormat.SHARE,
                        { if (state.ready) share() else passwordFor = AfterPassword.SHARE },
                        Modifier.fillMaxWidth().testTag(SHARE_TAG),
                    )
                    OutlineButton(
                        BackupFormat.RESTORE,
                        {
                            files.openFile { bytes ->
                                restoreBytes = bytes
                                work { opened(backup.read(bytes)) }
                            }
                        },
                        Modifier.fillMaxWidth().testTag(RESTORE_TAG),
                    )
                }
                // Копия перед обновлением базы (2.7б) — в папке приложения, системным окном её не выбрать.
                val updateCopy = state.updateCopy
                if (updateCopy != null && backup.showUpdateCopy(updateCopy)) {
                    OutlineButton(
                        BackupFormat.UPDATE_COPY,
                        {
                            work {
                                val bytes = backup.updateCopyBytes()
                                if (bytes == null) {
                                    messages.show(BackupFormat.NOT_BACKUP)
                                } else {
                                    restoreBytes = bytes
                                    opened(backup.read(bytes))
                                }
                            }
                        },
                        Modifier.fillMaxWidth().padding(top = Dimens.space2).testTag(UPDATE_COPY_TAG),
                    )
                    Hint(BackupFormat.updateCopyNote(updateCopy))
                }
                }

                if (part == DataPart.EXPORTS) {
                    OutlineButton(
                        BackupFormat.CALENDAR,
                        {
                            work {
                                val (name, text) = backup.calendar()
                                files.share(name, ICS, text.encodeToByteArray())
                            }
                        },
                        Modifier.fillMaxWidth().padding(top = Dimens.space2).testTag(CALENDAR_EXPORT_TAG),
                    )
                    Hint(BackupFormat.CALENDAR_NOTE)
                    // Excel для чтения (7.6, шаг 3.8в): тем же «Поделиться» — в Google Таблицы, на почту или себе в мессенджер.
                    OutlineButton(
                        ExcelFormat.BUTTON,
                        {
                            work {
                                val made = backup.excel()
                                files.share(made.name, Xlsx.MIME, made.bytes)
                            }
                        },
                        Modifier.fillMaxWidth().padding(top = Dimens.space4).testTag(EXCEL_EXPORT_TAG),
                    )
                    Hint(ExcelFormat.NOTE)
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        if (sync != null && account != null) SyncPanels(sync.repository, account, messages, scope, sync.panel) { sync.panel = it }

        passwordFor?.let { after ->
            NewPasswordPanel(
                onSave = { password ->
                    passwordFor = null
                    work {
                        backup.setPassword(password)
                        when (after) {
                            AfterPassword.NOTHING -> Unit
                            AfterPassword.SAVE -> if (backup.state.value.folder == null) pickFolderAndSave() else saveAndTell()
                            AfterPassword.SHARE -> backup.create()?.let { made ->
                                files.share(made.name, ZIP, made.bytes)
                                backup.shared()
                            }
                        }
                    }
                },
                onDismiss = { passwordFor = null },
            )
        }

        val pending = restoreBytes
        if (pending != null && preview == null && !busy) {
            OpenPasswordPanel(
                wrong = wrongPassword,
                onOpen = { password -> work { opened(backup.read(pending, password)) } },
                onDismiss = {
                    restoreBytes = null
                    wrongPassword = false
                },
            )
        }

        preview?.let { copy ->
            RestorePanel(
                copy = copy,
                onConfirm = {
                    preview = null
                    work {
                        messages.show(if (backup.restore(copy)) BackupFormat.restored(copy.createdAt) else BackupFormat.RESTORE_FAILED)
                    }
                },
                onDismiss = { preview = null },
            )
        }
    }
}

/** Новый пароль копий: дважды и предупреждение, что забытый пароль не восстановить (7.6). */
@Composable
private fun NewPasswordPanel(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val secret = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
    Panel(BackupFormat.PASSWORD_TITLE, onDismiss = onDismiss) {
        Field(
            BackupFormat.PASSWORD_FIELD, password, { password = it },
            keyboardOptions = secret, tag = PASSWORD_FIELD_TAG, visualTransformation = PasswordVisualTransformation(),
        )
        Field(
            BackupFormat.PASSWORD_REPEAT, repeat, { repeat = it },
            error = BackupFormat.PASSWORD_MISMATCH.takeIf { repeat.isNotEmpty() && repeat.length >= password.length && repeat != password },
            keyboardOptions = secret.copy(imeAction = ImeAction.Done), tag = PASSWORD_REPEAT_TAG,
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(BackupFormat.PASSWORD_WARNING, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
        PanelActions(
            primary = BackupFormat.PASSWORD_SAVE,
            onPrimary = { onSave(password) },
            secondary = "Отмена",
            onSecondary = onDismiss,
            primaryEnabled = BackupRules.passwordOk(password, repeat),
        )
    }
}

/** Пароль от выбранной копии: ошибка — «Неверный пароль» под полем. */
@Composable
private fun OpenPasswordPanel(wrong: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Panel(BackupFormat.OPEN_PASSWORD_TITLE, onDismiss = onDismiss) {
        Field(
            BackupFormat.PASSWORD, password, { password = it },
            error = BackupFormat.WRONG_PASSWORD.takeIf { wrong },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            tag = PASSWORD_FIELD_TAG, visualTransformation = PasswordVisualTransformation(),
        )
        PanelActions(BackupFormat.OPEN, { onOpen(password) }, "Отмена", onDismiss, primaryEnabled = password.isNotEmpty())
    }
}

/** Предпросмотр копии перед заменой: дата, клиенты, тренировки и предупреждение (7.6). */
@Composable
private fun RestorePanel(copy: BackupRead.Ready, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Panel(BackupFormat.RESTORE_TITLE, onDismiss = onDismiss) {
        Text(BackupFormat.previewTitle(copy.createdAt), style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
        Text(BackupFormat.previewCounts(copy.clients, copy.trainings), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.textMuted)
        Text(BackupFormat.RESTORE_WARNING, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.error)
        PanelActions(BackupFormat.RESTORE_CONFIRM, onConfirm, "Отмена", onDismiss)
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

/** Строка раздела в «Настройках». */

private const val ZIP = "application/zip"
private const val ICS = "text/calendar"
