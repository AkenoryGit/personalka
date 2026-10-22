package com.akenory.personalka.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.theme.Motion
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.delay

/** Одно сообщение внизу экрана: текст, который называет результат, и, если действие обратимо, «Отменить». */
data class Message(
    val id: Long,
    val text: String,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

/**
 * Сообщения с «Отменить» (ТЗ 5.20): перенос, отмена, удаление, «Проведено» и перенос плана подтверждаются
 * сообщением, и 5 секунд действие можно вернуть. Подтверждение заранее спрашивается только для необратимого.
 *
 * Одно сообщение за раз: новое заменяет старое, а действие старого остаётся сделанным. Действие уже
 * сохранено, когда сообщение появилось; «Отменить» вызывает обратное действие, а не откладывает запись —
 * так ничего не теряется, если приложение закроют в эти 5 секунд.
 */
@Stable
class MessageState {
    var current: Message? by mutableStateOf(null)
        private set

    private var lastId = 0L

    /** Сообщение без кнопки: «Сохранено». */
    fun show(text: String) {
        current = Message(++lastId, text, actionLabel = null, onAction = null)
    }

    /** Сообщение с «Отменить»: [undo] возвращает всё как было. */
    fun showUndo(text: String, undo: () -> Unit) {
        current = Message(++lastId, text, actionLabel = UNDO_LABEL, onAction = undo)
    }

    /** Нажата кнопка сообщения [id]. Нажатие на уже сменённое сообщение ничего не делает. */
    fun act(id: Long) {
        val message = current ?: return
        if (message.id != id) return
        current = null
        message.onAction?.invoke()
    }

    /** Время сообщения [id] вышло. Если его уже сменило новое, новое остаётся. */
    fun expire(id: Long) {
        if (current?.id == id) current = null
    }

    /** Убрать сообщение без действия — например, тренер ушёл с экрана, к которому оно относилось. */
    fun dismiss() {
        current = null
    }

    companion object {
        const val UNDO_LABEL = "Отменить"

        /** 5 секунд (ТЗ 5.20). С TalkBack система может дать больше — см. [MessageHost]. */
        const val TIMEOUT_MS = 4_000L
    }
}

/**
 * Место сообщения: над нижней панелью с отступом 12 dp, по 16 dp от краёв (макет шага 0.4, «После отмены»).
 * Кнопку «+» оболочка поднимает над сообщением. Фон `inverseSurface`, «Отменить» — `inverseAction`.
 */
@Composable
fun MessageHost(state: MessageState, modifier: Modifier = Modifier) {
    val message = state.current
    val accessibility = LocalAccessibilityManager.current

    if (message != null) {
        LaunchedEffect(message.id) {
            // С TalkBack и другими спецвозможностями Android советует держать сообщение дольше — берём его время.
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                MessageState.TIMEOUT_MS,
                containsIcons = false,
                containsText = true,
                containsControls = message.actionLabel != null,
            ) ?: MessageState.TIMEOUT_MS
            delay(timeout)
            state.expire(message.id)
        }
    }

    // Пока сообщение уходит, на месте остаётся последнее показанное, а не пустая плашка.
    val shown = remember { ShownMessage() }
    if (message != null) shown.value = message

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        // Только прозрачность и высота: так же выглядит и при системной настройке «Уменьшить движение».
        enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM), expandFrom = Alignment.Top),
        exit = fadeOut(tween(Motion.MEDIUM)) + shrinkVertically(tween(Motion.MEDIUM), shrinkTowards = Alignment.Top),
    ) {
        shown.value?.let { MessageBar(it, onAction = { state.act(it.id) }) }
    }
}

private class ShownMessage {
    var value: Message? = null
}

@Composable
private fun MessageBar(message: Message, onAction: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Surface(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .testTag(MESSAGE_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = colors.inverseSurface,
        contentColor = colors.inverseText,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = colors.inverseText,
            )
            if (message.actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.inverseAction),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(message.actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

const val MESSAGE_TAG = "message"
