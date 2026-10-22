package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

const val PANEL_TAG = "panel"

/**
 * Окно поверх экрана (дизайн-система, Dialog): затемнение и карточка по центру, скругление 20, своя одна
 * главная кнопка (10.7). Нажатие мимо карточки закрывает окно.
 *
 * Окно — часть экрана, а не отдельное окно системы: при сложении Fold оно остаётся на месте вместе
 * с экраном, а тесты экранов видят его так же, как всё остальное.
 */
@Composable
fun Panel(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = PersonalkaTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(Dimens.space6)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(colors.surface)
                // Нажатие внутри карточки окно не закрывает.
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = title }
                .testTag(PANEL_TAG)
                .padding(Dimens.space5),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.text,
            )
            content()
        }
    }
}

/** Низ окна: главная кнопка во всю ширину и под ней второе действие текстом — «Да, выбрать» / «Нет, создать нового». */
@Composable
fun PanelActions(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    primaryEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
        PrimaryButton(primary, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
        TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
            Text(secondary, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
        }
    }
}
