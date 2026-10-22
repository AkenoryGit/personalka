package com.akenory.personalka.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.shell.AppShell
import com.akenory.personalka.ui.shell.FAB_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сообщение с «Отменить» на экране (ТЗ 5.20): живёт 5 секунд, «Отменить» срабатывает, «+» поднимается над ним. */
@OptIn(ExperimentalTestApi::class)
class MessageUiTest {

    private fun ComposeUiTest.setShell(messages: MessageState) {
        setContent {
            PersonalkaTheme {
                Box(Modifier.size(412.dp, 740.dp)) {
                    AppShell(
                        shell = remember { ShellState(Section.CALENDAR) },
                        messages = messages,
                        todoCount = 0,
                        onNewTraining = {},
                    ) {}
                }
            }
        }
    }

    @Test
    fun messageLeavesAfterFiveSeconds() = runComposeUiTest {
        val messages = MessageState()
        var undone = 0
        setShell(messages)
        mainClock.autoAdvance = false

        runOnUiThread { messages.showUndo("Тренировка отменена") { undone++ } }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Тренировка отменена").assertIsDisplayed()
        onNodeWithText(MessageState.UNDO_LABEL).assertIsDisplayed()

        mainClock.advanceTimeBy(4_300) // 4,8 с — ещё на месте
        onNodeWithText("Тренировка отменена").assertIsDisplayed()

        mainClock.advanceTimeBy(400) // 5,2 с — ушло, действие осталось сделанным
        runOnUiThread { assertNull(messages.current) }
        mainClock.advanceTimeBy(500)
        onNodeWithTag(MESSAGE_TAG).assertDoesNotExist()
        assertEquals(0, undone)
    }

    @Test
    fun undoButtonRunsUndo() = runComposeUiTest {
        val messages = MessageState()
        var undone = 0
        setShell(messages)
        mainClock.autoAdvance = false

        runOnUiThread { messages.showUndo("Отменено. План перенесён на вт 27.10") { undone++ } }
        mainClock.advanceTimeBy(500)
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        mainClock.advanceTimeBy(500)

        assertEquals(1, undone)
        onNodeWithTag(MESSAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun plusMovesAboveMessage() = runComposeUiTest {
        val messages = MessageState()
        setShell(messages)
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(100)

        val before = onNodeWithTag(FAB_TAG).getBoundsInRoot()
        runOnUiThread { messages.show("Сохранено") }
        mainClock.advanceTimeBy(500)
        val after = onNodeWithTag(FAB_TAG).getBoundsInRoot()
        val message = onNodeWithTag(MESSAGE_TAG).getBoundsInRoot()

        assertTrue(after.bottom < before.bottom, "«+» поднялся")
        assertTrue(after.bottom <= message.top, "«+» не закрывает сообщение")
    }
}
