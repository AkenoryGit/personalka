package com.akenory.personalka.ui.watch

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.akenory.personalka.domain.format.WatchFormat
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** «Часы» в «Дневнике и напоминаниях» (шаг 5.3т): только при подключённых часах, имена выключаются. */
@OptIn(ExperimentalTestApi::class)
class WatchSettingsUiTest {
    private class FakeWatch(connected: Boolean) : WatchTools {
        override val connected = MutableStateFlow(connected)
        override val names = MutableStateFlow(true)
        var todo = -1

        override fun setNames(on: Boolean) {
            names.value = on
        }

        override fun todoCount(count: Int) {
            todo = count
        }
    }

    @Test
    fun hiddenWithoutWatch() = runComposeUiTest {
        val watch = FakeWatch(connected = false)
        setContent { PersonalkaTheme { WatchSettings(watch) } }
        onNodeWithTag(WATCH_NAMES_TAG).assertDoesNotExist()
        onNodeWithText(WatchFormat.SETTINGS_TITLE).assertDoesNotExist()
        // Подключили часы — раздел появился.
        watch.connected.value = true
        waitForIdle()
        onNodeWithText(WatchFormat.SETTINGS_TITLE).assertExists()
    }

    @Test
    fun namesSwitchOff() = runComposeUiTest {
        val watch = FakeWatch(connected = true)
        setContent { PersonalkaTheme { WatchSettings(watch) } }
        onNodeWithTag(WATCH_NAMES_TAG).assertIsOn().performClick()
        waitForIdle()
        assertFalse(watch.names.value)
        onNodeWithTag(WATCH_NAMES_TAG).assertIsOff()
        assertEquals(-1, watch.todo)
    }
}
