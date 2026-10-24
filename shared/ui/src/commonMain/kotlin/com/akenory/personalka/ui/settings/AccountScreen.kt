package com.akenory.personalka.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.Account
import com.akenory.personalka.data.account.AccountException
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.AccountResult
import com.akenory.personalka.data.account.DeviceSession
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime

internal const val ACCOUNT_NAME_TAG = "account-name"
internal const val ACCOUNT_EMAIL_TAG = "account-email"
internal const val ACCOUNT_PASSWORD_TAG = "account-password"
internal const val ACCOUNT_CODE_TAG = "account-code"
internal const val ACCOUNT_SUBMIT_TAG = "account-submit"
internal const val ACCOUNT_FORGOT_TAG = "account-forgot"
internal const val ACCOUNT_RESEND_TAG = "account-resend"
internal const val ACCOUNT_BACK_TAG = "account-back"
internal const val ACCOUNT_ERROR_TAG = "account-error"
internal const val ACCOUNT_SIGN_OUT_TAG = "account-sign-out"
internal fun deviceTag(id: Long) = "device-$id"

/** Шаг формы входа: вход или регистрация, код из письма, сброс пароля. */
internal enum class AccountStep { SIGN_IN, REGISTER, CODE, FORGOT, RESET }

/**
 * «Настройки → Данные → Аккаунт» (шаг 2.8а; ТЗ 7.3, 5.16, 1.5). Без входа — «Вход» и «Регистрация»
 * (имя, почта, пароль → код из письма), «Забыли пароль?» (почта → код и новый пароль). После входа — имя, почта,
 * открытые возможности (закрытые не видны, 10.7), устройства с «Выйти на этом устройстве» и «Выйти».
 * Ответы сервера показываются как есть: тексты ошибок он пишет по-русски для тренера.
 */
@Composable
internal fun AccountScreen(
    account: AccountRepository,
    settings: SettingsRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val signedIn by account.account.collectAsState()
    // Окно «Выйти на этом устройстве» — поверх экрана, вне прокрутки.
    var closing by remember { mutableStateOf<DeviceSession?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(AccountFormat.TITLE, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                val current = signedIn
                if (current != null) {
                    SignedIn(current, account, now, messages, scope) { closing = it }
                } else {
                    SignInForm(account, settings, messages, scope)
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        closing?.let { session ->
            Panel(session.name, onDismiss = { closing = null }) {
                PanelActions(
                    primary = AccountFormat.SIGN_OUT_DEVICE,
                    onPrimary = {
                        closing = null
                        scope.launch {
                            when (val result = account.closeSession(session)) {
                                is AccountResult.Failed -> messages.show(result.message)
                                else -> messages.show(AccountFormat.SIGNED_OUT_DEVICE)
                            }
                        }
                    },
                    secondary = "Отмена",
                    onSecondary = { closing = null },
                )
            }
        }
    }
}

/** Аккаунт, в который вошли: профиль, открытое, устройства и выход. Профиль обновляется при каждом открытии. */
@Composable
private fun SignedIn(
    account: Account,
    repository: AccountRepository,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: (DeviceSession) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    LaunchedEffect(repository) {
        // Без связи остаётся сохранённый профиль; вход устарел — экран сам вернётся к форме входа.
        val result = repository.refresh()
        if (result is AccountResult.Failed && result.code == AccountException.UNAUTHORIZED) messages.show(AccountFormat.EXPIRED)
    }
    val grants = account.features
    Text(account.name, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.padding(top = Dimens.space3))
    Text(account.email, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
    AccountFormat.open(AccountRules.effective(grants), AccountRules.endingSoon(grants, now.date).keys)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.padding(top = Dimens.space3))
    }

    if (account.sessions.isNotEmpty()) {
        SectionLabel(AccountFormat.DEVICES)
        ListCard {
            account.sessions.forEachIndexed { i, session ->
                if (i > 0) RowDivider()
                val used = session.lastUsed.toLocalDateTime(ClubTime.defaultZone).date
                val text = AccountFormat.device(session.name, session.current, used, now.date)
                // Своё устройство — просто строка: выход отсюда — кнопка «Выйти» ниже.
                val row = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(deviceTag(session.id))
                Box(
                    if (session.current) row else row.clickable(role = Role.Button) { onClose(session) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space2),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                    )
                }
            }
        }
    }

    OutlineButton(
        AccountFormat.SIGN_OUT,
        {
            scope.launch {
                repository.logout()
                messages.show(AccountFormat.SIGNED_OUT_HERE)
            }
        },
        Modifier.fillMaxWidth().padding(top = Dimens.space5).testTag(ACCOUNT_SIGN_OUT_TAG),
    )
    Text(AccountFormat.SIGN_OUT_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.padding(top = Dimens.space2))
}

/**
 * Форма входа по шагам. Шаг и почта переживают смену темы; пароль и код — только в памяти экрана, в сохраняемое
 * состояние не кладутся.
 */
@Composable
private fun SignInForm(account: AccountRepository, settings: SettingsRepository, messages: MessageState, scope: CoroutineScope) {
    val colors = PersonalkaTheme.colors
    var step by rememberSaveable { mutableStateOf(AccountStep.SIGN_IN) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // Код пришёл после регистрации (повтор — регистрацией) или после входа (повтор — входом).
    var fromRegister by rememberSaveable { mutableStateOf(false) }
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var minutes by rememberSaveable { mutableStateOf(AccountRepository.CODE_MINUTES) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Имя для кабинета — как в «Профиле», его можно поправить.
    LaunchedEffect(settings) {
        if (name.isEmpty()) settings.coach.first()?.let { name = it.firstName }
    }

    fun go(to: AccountStep) {
        step = to
        error = null
        code = ""
    }

    fun work(action: suspend () -> AccountResult, onCode: AccountStep = AccountStep.CODE, onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                when (val result = action()) {
                    is AccountResult.Done -> onDone()
                    is AccountResult.CodeSent -> {
                        sentTo = result.email.ifEmpty { email.trim() }
                        minutes = result.minutes
                        if (step != onCode) go(onCode)
                    }
                    is AccountResult.Failed -> error = result.message
                }
            } finally {
                busy = false
            }
        }
    }

    val emailKeys = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
    val secret = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)

    @Composable
    fun emailField() = Field(AccountFormat.EMAIL, email, { email = it.trim() }, keyboardOptions = emailKeys, tag = ACCOUNT_EMAIL_TAG)

    @Composable
    fun passwordField(label: String, hint: String?) = Field(
        label, password, { password = it },
        hint = hint, keyboardOptions = secret, tag = ACCOUNT_PASSWORD_TAG, visualTransformation = PasswordVisualTransformation(),
    )

    @Composable
    fun codeField() = Field(
        AccountFormat.CODE, code, { text -> code = text.filter { it.isDigit() }.take(6) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next), tag = ACCOUNT_CODE_TAG,
    )

    @Composable
    fun link(text: String, tag: String, onClick: () -> Unit) = TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(tag),
        enabled = !busy,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.text)
    }

    Column(Modifier.padding(top = Dimens.space3), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        when (step) {
            AccountStep.SIGN_IN, AccountStep.REGISTER -> {
                Note(AccountFormat.WHY)
                Segmented(
                    options = listOf(AccountStep.SIGN_IN, AccountStep.REGISTER),
                    selected = step,
                    label = { if (it == AccountStep.SIGN_IN) AccountFormat.SIGN_IN else AccountFormat.REGISTER },
                    onSelect = { go(it) },
                )
                if (step == AccountStep.REGISTER) {
                    Field(
                        AccountFormat.NAME, name, { name = it },
                        hint = AccountFormat.NAME_HINT, tag = ACCOUNT_NAME_TAG,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                }
                emailField()
                passwordField(AccountFormat.PASSWORD, AccountFormat.PASSWORD_HINT.takeIf { step == AccountStep.REGISTER })
            }
            AccountStep.CODE -> {
                Text(AccountFormat.codeSent(sentTo ?: email, minutes), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                codeField()
            }
            AccountStep.FORGOT -> {
                Note(AccountFormat.RESET_NOTE)
                emailField()
            }
            AccountStep.RESET -> {
                Text(AccountFormat.codeSent(sentTo ?: email, minutes), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                codeField()
                passwordField(AccountFormat.NEW_PASSWORD, AccountFormat.PASSWORD_HINT)
            }
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.testTag(ACCOUNT_ERROR_TAG)) }

        val submit = when (step) {
            AccountStep.SIGN_IN -> AccountFormat.DO_SIGN_IN
            AccountStep.REGISTER -> AccountFormat.DO_REGISTER
            AccountStep.CODE -> AccountFormat.CONFIRM
            AccountStep.FORGOT -> AccountFormat.SEND_CODE
            AccountStep.RESET -> AccountFormat.SAVE_PASSWORD
        }
        val ready = when (step) {
            AccountStep.SIGN_IN -> email.isNotBlank() && password.isNotEmpty()
            AccountStep.REGISTER -> name.isNotBlank() && email.isNotBlank() && password.isNotEmpty()
            AccountStep.CODE -> code.length == 6
            AccountStep.FORGOT -> email.isNotBlank()
            AccountStep.RESET -> code.length == 6 && password.isNotEmpty()
        }
        PrimaryButton(
            text = if (busy) AccountFormat.WORKING else submit,
            onClick = {
                when (step) {
                    AccountStep.SIGN_IN -> {
                        fromRegister = false
                        work({ account.login(email, password) })
                    }
                    AccountStep.REGISTER -> {
                        fromRegister = true
                        work({ account.register(name, email, password) })
                    }
                    AccountStep.CODE -> work({ account.confirm(email, password, code) })
                    AccountStep.FORGOT -> work({ account.requestReset(email) }, onCode = AccountStep.RESET)
                    AccountStep.RESET -> work({ account.reset(email, code, password) }) { messages.show(AccountFormat.PASSWORD_SAVED) }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(ACCOUNT_SUBMIT_TAG),
            enabled = ready && !busy,
        )

        when (step) {
            AccountStep.SIGN_IN -> link(AccountFormat.FORGOT, ACCOUNT_FORGOT_TAG) { go(AccountStep.FORGOT) }
            AccountStep.REGISTER -> Unit
            AccountStep.CODE -> {
                link(AccountFormat.RESEND, ACCOUNT_RESEND_TAG) {
                    if (fromRegister) work({ account.register(name, email, password) }) else work({ account.login(email, password) })
                }
                link(AccountFormat.BACK_TO_SIGN_IN, ACCOUNT_BACK_TAG) { go(if (fromRegister) AccountStep.REGISTER else AccountStep.SIGN_IN) }
            }
            AccountStep.FORGOT -> link(AccountFormat.BACK_TO_SIGN_IN, ACCOUNT_BACK_TAG) { go(AccountStep.SIGN_IN) }
            AccountStep.RESET -> {
                link(AccountFormat.RESEND, ACCOUNT_RESEND_TAG) { work({ account.requestReset(email) }, onCode = AccountStep.RESET) }
                link(AccountFormat.BACK_TO_SIGN_IN, ACCOUNT_BACK_TAG) { go(AccountStep.SIGN_IN) }
            }
        }
    }
}
