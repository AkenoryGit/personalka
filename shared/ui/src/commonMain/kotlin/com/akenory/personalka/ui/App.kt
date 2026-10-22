package com.akenory.personalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.screens.SectionScreen
import com.akenory.personalka.ui.settings.SettingsRoute
import com.akenory.personalka.ui.setup.FirstRun
import com.akenory.personalka.ui.shell.AppShell
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.rememberClubNow
import com.akenory.personalka.ui.shell.rememberShellState
import com.akenory.personalka.ui.shell.systemMillis
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.flow.map

/**
 * Приложение целиком: тема 0.2; пока тренера нет — первый запуск (шаг 1.6), потом оболочка с пятью
 * разделами (шаг 1.5). Пока база отвечает, виден только фон — это доли секунды при открытии.
 *
 * @param settings тренер, места и типы в базе телефона.
 * @param backHandler «Назад» системы. Платформа передаёт свой обработчик (на Android — `BackHandler`
 *   из activity-compose): общий код не зависит от Android. Пока есть куда вернуться внутри приложения,
 *   «Назад» закрывает открытый экран, иначе ведёт на «Главную»; с «Главной» приложение сворачивается.
 * @param clock часы телефона, миллисекунды UTC; тесты подставляют свои.
 */
@Composable
fun App(
    versionName: String,
    settings: SettingsRepository,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    clock: () -> Long = ::systemMillis,
) {
    PersonalkaTheme {
        val shell = rememberShellState()
        val messages = remember { MessageState() }
        val now = rememberClubNow(clock)
        // Записи из экранов идут здесь: экран закрывается сразу, запись и «Отменить» доживают до конца.
        val scope = rememberCoroutineScope()
        val hasCoach by remember(settings) { settings.coach.map { it != null } }.collectAsState(initial = null)

        Box(Modifier.fillMaxSize().background(PersonalkaTheme.colors.bg)) {
            when (hasCoach) {
                null -> Unit
                false -> FirstRun(settings, backHandler, onDone = { shell.select(Section.CALENDAR) })
                true -> {
                    backHandler(shell.canGoBack) { shell.back() }
                    AppShell(
                        shell = shell,
                        messages = messages,
                        // Счётчик дел на «Главной» появится вместе с «Нужно сделать» (шаг 1.16).
                        todoCount = 0,
                        onNewTraining = { messages.show(NEW_TRAINING_LATER) },
                        immersive = shell.section == Section.MORE && SettingsRoute.isForm(shell.top(Section.MORE)),
                    ) { section ->
                        SectionScreen(
                            section = section,
                            shell = shell,
                            today = now.date,
                            versionName = versionName,
                            settings = settings,
                            messages = messages,
                            scope = scope,
                        )
                    }
                }
            }
        }
    }
}

/** Что «+» отвечает до шага 1.13, где появится форма новой тренировки. */
internal const val NEW_TRAINING_LATER = "Новая тренировка — в шаге 1.13"
