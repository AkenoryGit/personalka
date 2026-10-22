package com.akenory.personalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular

/**
 * Пустой экран каркаса (шаг 0.10): проверяет, что приложение ставится, открывается
 * и берёт тему «Персоналки» в светлом и тёмном режиме телефона. Оболочка с разделами — шаг 1.5.
 */
@Composable
fun App(versionName: String) {
    PersonalkaTheme {
        val colors = PersonalkaTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .safeDrawingPadding()
                .padding(Dimens.space4),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Text(
                    text = "Персоналка",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                )
                Text(
                    text = "Версия $versionName · экраны появятся на этапе 1",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
