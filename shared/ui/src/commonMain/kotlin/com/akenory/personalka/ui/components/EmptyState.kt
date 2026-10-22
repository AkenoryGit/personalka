package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

/**
 * Пустой экран (дизайн-система, EmptyState; ТЗ 5.20): круг 64 со значком 28, заголовок, пояснение
 * не длиннее двух строк и главная кнопка с глаголом — «Добавьте первого клиента» + «Добавить клиента».
 * Не для ошибок: у ошибки свой текст и способ исправить.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = PersonalkaTheme.colors
    Column(
        modifier = modifier.widthIn(max = 360.dp).padding(horizontal = Dimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(colors.surfaceAlt, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = colors.textMuted)
        }
        Spacer(Modifier.height(Dimens.space2))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Dimens.space2))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = Dimens.button),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
