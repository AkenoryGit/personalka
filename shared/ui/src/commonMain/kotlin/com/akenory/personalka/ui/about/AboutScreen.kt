package com.akenory.personalka.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.domain.about.AppTheme
import com.akenory.personalka.domain.about.CrashLogRules
import com.akenory.personalka.domain.about.UpdateCheck
import com.akenory.personalka.domain.about.UpdateRules
import com.akenory.personalka.domain.format.AboutFormat
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.components.SettingsGroup
import com.akenory.personalka.ui.components.ValueRow
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val ABOUT_THEME_TAG = "about-theme"
internal const val ABOUT_CHECK_TAG = "about-check"
internal const val ABOUT_DOWNLOAD_TAG = "about-download"
internal const val ABOUT_SEND_TAG = "about-send"
internal const val ABOUT_CLEAR_TAG = "about-clear"

/**
 * Что умеет платформа для «О приложении» (шаг 5.33). Есть только на Android: на iPhone обновления приходят
 * через TestFlight, а журнал ошибок не пишется — там экран показывает тему и версию.
 */
interface AboutTools {
    /** Номер этой сборки (`versionCode`): с ним сравнивается последняя сборка. */
    val build: Int

    /** Ответ GitHub о релизе «Последняя сборка» ([UpdateRules.RELEASE_URL]); нет связи — `null`. */
    suspend fun release(): String?

    /** Скачать APK последней сборки в браузере ([UpdateRules.APK_URL]). */
    fun download()

    /** Журнал ошибок ([CrashLogRules]); ошибок не было — `null`. */
    suspend fun crashLog(): String?

    suspend fun clearCrashLog()
}

/**
 * «Ещё → О приложении» (ТЗ 5.16, шаг 5.33): тема (как в телефоне, светлая, тёмная) — меняется сразу и запоминается
 * в этом телефоне; версия; «Проверить обновление» (Android) — «Скачать», если есть сборка новее; журнал ошибок —
 * только когда в нём что-то есть, «Отправить разработчику» системным «Поделиться».
 */
@Composable
internal fun AboutScreen(
    versionName: String,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    tools: AboutTools?,
    shareText: ((String) -> Unit)?,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
) {
    val colors = PersonalkaTheme.colors
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<UpdateCheck?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tools) { log = tools?.crashLog()?.takeIf { CrashLogRules.entries(it).isNotEmpty() } }

    Column(Modifier.fillMaxSize()) {
        TopBar(AboutFormat.TITLE, onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            SectionLabel(AboutFormat.THEME, Modifier.padding(start = Dimens.space1))
            Segmented(AppTheme.entries, theme, AboutFormat::theme, onTheme, Modifier.testTag(ABOUT_THEME_TAG))

            SettingsGroup(Modifier.padding(top = Dimens.space3)) {
                ValueRow(AboutFormat.VERSION, versionName)
            }
            if (tools != null) {
                when (val result = check) {
                    is UpdateCheck.Available -> {
                        Text(AboutFormat.available(result.build), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                        PrimaryButton(AboutFormat.DOWNLOAD, tools::download, Modifier.fillMaxWidth().testTag(ABOUT_DOWNLOAD_TAG))
                        Text(AboutFormat.DOWNLOAD_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    }
                    else -> {
                        if (result != null && !checking) {
                            val text = if (result == UpdateCheck.Fresh) AboutFormat.FRESH else AboutFormat.FAILED
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        }
                        OutlineButton(
                            text = if (checking) AboutFormat.CHECKING else AboutFormat.CHECK,
                            onClick = {
                                if (!checking) {
                                    checking = true
                                    scope.launch {
                                        check = UpdateRules.check(tools.build, runCatching { tools.release() }.getOrNull())
                                        checking = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag(ABOUT_CHECK_TAG),
                        )
                    }
                }
            }

            val crashes = log
            if (tools != null && crashes != null) {
                SettingsGroup(Modifier.padding(top = Dimens.space3)) {
                    ValueRow(AboutFormat.CRASHES, null, subtitle = AboutFormat.crashes(crashes))
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = Dimens.space3),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    ) {
                        if (shareText != null) {
                            OutlineButton(
                                AboutFormat.SEND,
                                { shareText(AboutFormat.crashReport(versionName, crashes)) },
                                Modifier.weight(1f).testTag(ABOUT_SEND_TAG),
                            )
                        }
                        OutlineButton(
                            AboutFormat.CLEAR,
                            {
                                log = null
                                scope.launch { tools.clearCrashLog() }
                                messages.show(AboutFormat.CLEARED)
                            },
                            Modifier.testTag(ABOUT_CLEAR_TAG),
                        )
                    }
                }
            }
        }
    }
}
