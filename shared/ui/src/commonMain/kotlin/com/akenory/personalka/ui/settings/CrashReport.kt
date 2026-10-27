package com.akenory.personalka.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.akenory.personalka.domain.format.CrashFormat
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

/**
 * Прошлый запуск закончился ошибкой: её текст до того, как открыть приложение. Если ошибка повторяется на старте,
 * тренер всё равно увидит текст и сможет его прислать. Базы этот экран не касается.
 */
@Composable
fun CrashReport(text: String, onCopy: (String) -> Unit, onContinue: () -> Unit) {
    PersonalkaTheme {
        val colors = PersonalkaTheme.colors
        var copied by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().background(colors.bg).safeDrawingPadding().padding(Dimens.space4)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            Text(CrashFormat.TITLE, style = MaterialTheme.typography.titleLarge, color = colors.text)
            Text(CrashFormat.NOTE, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            PrimaryButton(if (copied) CrashFormat.COPIED else CrashFormat.COPY, { onCopy(text); copied = true }, Modifier.fillMaxWidth())
            OutlineButton(CrashFormat.CONTINUE, onContinue, Modifier.fillMaxWidth())
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}
