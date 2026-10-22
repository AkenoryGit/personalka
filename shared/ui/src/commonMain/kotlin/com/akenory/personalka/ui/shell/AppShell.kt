package com.akenory.personalka.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.components.MessageHost
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.PersonalkaTheme

/**
 * Широкий ли экран: разложенный Fold или планшет, от 600 dp (ТЗ 5.1). Экраны читают его, чтобы показать
 * две колонки — список слева, подробности справа.
 *
 * Правило для экранов: состояние, которое должно пережить сложение и разложение (прокрутка, выбранная строка),
 * поднимается выше ветки «широкий / узкий» — `rememberSaveable` над `if (wide)`, а не внутри веток. Иначе
 * при смене ширины ветка создаётся заново и прокрутка сбрасывается.
 */
val LocalWide = compositionLocalOf { false }

const val FAB_TAG = "fab"

/**
 * Оболочка приложения (ТЗ 5.1): на узком экране — нижняя панель из пяти разделов, на широком — боковая
 * слева; кнопка «+» на «Главной» и в «Календаре»; сообщение с «Отменить» над панелью (5.20).
 *
 * Открытый экран не теряется при сложении и разложении Fold: раздел и стопка экранов лежат в [ShellState],
 * а содержимое раздела всегда стоит на одном и том же месте дерева — меняется только отступ под панель,
 * поэтому Compose не пересоздаёт экран и его прокрутку. Окно при этом не пересоздаётся (`configChanges`
 * в манифесте), а на случай пересоздания (смена темы, выгрузка из памяти) всё сохранено через `rememberSaveable`.
 */
@Composable
fun AppShell(
    shell: ShellState,
    messages: MessageState,
    todoCount: Int,
    onNewTraining: () -> Unit,
    content: @Composable (Section) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val holder = rememberSaveableStateHolder()
    val direction = LocalLayoutDirection.current
    // Системные панели и вырез камеры; клавиатура сюда не входит — нижняя панель под ней не поднимается.
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val safeStart = safe.calculateStartPadding(direction)
    val safeEnd = safe.calculateEndPadding(direction)
    val safeTop = safe.calculateTopPadding()
    val safeBottom = safe.calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
        val wide = maxWidth >= WideFrom
        val contentPadding = if (wide) {
            PaddingValues(start = safeStart + RailWidth, top = safeTop, end = safeEnd, bottom = safeBottom)
        } else {
            PaddingValues(start = safeStart, top = safeTop, end = safeEnd, bottom = safeBottom + NavBarHeight)
        }

        // ПРОВЕРОЧНАЯ ПОЛОМКА: на широком и узком экране содержимое вызывается из разных мест кода.
        if (wide) {
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                CompositionLocalProvider(LocalWide provides wide) {
                    holder.SaveableStateProvider(shell.section.name) { content(shell.section) }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                CompositionLocalProvider(LocalWide provides wide) {
                    holder.SaveableStateProvider(shell.section.name) { content(shell.section) }
                }
            }
        }

        if (wide) {
            NavRail(
                selected = shell.section,
                todoCount = todoCount,
                insets = PaddingValues(start = safeStart, top = safeTop, bottom = safeBottom),
                onSelect = shell::select,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        } else {
            BottomNav(
                selected = shell.section,
                todoCount = todoCount,
                insets = PaddingValues(start = safeStart, end = safeEnd, bottom = safeBottom),
                onSelect = shell::select,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // «+» и сообщение внизу справа: «+» в 16 dp над панелью, а когда есть сообщение — в 16 dp над ним.
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(contentPadding),
            horizontalAlignment = Alignment.End,
        ) {
            if (shell.section.hasFab) {
                NewTrainingButton(onClick = onNewTraining, modifier = Modifier.padding(end = 16.dp, bottom = 16.dp))
            }
            MessageHost(messages)
        }
    }
}

/**
 * Кнопка «+» — новая тренировка (дизайн-система, Fab): оранжевая заливка, «+» графитом, 56 dp, скругление 16.
 * Одна на экран и не меняется: другое действие — это обычная кнопка.
 */
@Composable
private fun NewTrainingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp).testTag(FAB_TAG),
        shape = MaterialTheme.shapes.large,
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 3.dp,
            pressedElevation = 3.dp,
            focusedElevation = 3.dp,
            hoveredElevation = 3.dp,
        ),
    ) {
        Icon(PIcons.Add, contentDescription = "Новая тренировка", modifier = Modifier.size(28.dp))
    }
}

/**
 * Верхняя панель экрана: заголовок 22 sp слева; у открытого поверх экрана — «Назад».
 * Действия справа (индикатор синхронизации, «…») добавят экраны, которым они нужны.
 */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
