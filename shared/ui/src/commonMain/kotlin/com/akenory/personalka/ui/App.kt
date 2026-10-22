package com.akenory.personalka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.screens.SectionScreen
import com.akenory.personalka.ui.shell.AppShell
import com.akenory.personalka.ui.shell.rememberClubNow
import com.akenory.personalka.ui.shell.rememberShellState
import com.akenory.personalka.ui.shell.systemMillis
import com.akenory.personalka.ui.theme.PersonalkaTheme

/**
 * Приложение целиком (шаг 1.5): тема 0.2 и оболочка с пятью разделами.
 *
 * @param backHandler «Назад» системы. Платформа передаёт свой обработчик (на Android — `BackHandler`
 *   из activity-compose): общий код не зависит от Android. Пока есть куда вернуться внутри приложения,
 *   «Назад» закрывает открытый экран, иначе ведёт на «Главную»; с «Главной» приложение сворачивается.
 * @param clock часы телефона, миллисекунды UTC; тесты подставляют свои.
 */
@Composable
fun App(
    versionName: String,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    clock: () -> Long = ::systemMillis,
) {
    PersonalkaTheme {
        val shell = rememberShellState()
        val messages = remember { MessageState() }
        val now = rememberClubNow(clock)

        backHandler(shell.canGoBack) { shell.back() }

        AppShell(
            shell = shell,
            messages = messages,
            // Счётчик дел на «Главной» появится вместе с «Нужно сделать» (шаг 1.16).
            todoCount = 0,
            onNewTraining = { messages.show(NEW_TRAINING_LATER) },
        ) { section ->
            SectionScreen(section = section, shell = shell, today = now.date, versionName = versionName)
        }
    }
}

/** Что «+» отвечает до шага 1.13, где появится форма новой тренировки. */
internal const val NEW_TRAINING_LATER = "Новая тренировка — в шаге 1.13"
