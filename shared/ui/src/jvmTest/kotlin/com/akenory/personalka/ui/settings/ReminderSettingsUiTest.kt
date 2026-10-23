package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Ещё → Настройки → Дневник и напоминания» (шаг 2.5, ТЗ 5.16, 5.19). Имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class ReminderSettingsUiTest {
    private val settings = TestData.ready()
    private val calendar = TestData.calendarOf(settings)
    private val prefs = MemoryPrefs()
    private val reminders = Reminders(calendar, TestData.clientsOf(settings), null, prefs, TestData.clock)

    /** Уведомления телефона в тесте: выключены, пока не нажали «Включить». */
    private val access = object : NotificationAccess {
        var on = false
        var changes = 0

        override fun enabled() = on

        override fun enable() {
            on = true
        }

        override fun changed() {
            changes++
        }
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun saved() = runBlocking { calendar.reminders.prefs.first() }

    private fun ComposeUiTest.openReminders(notifications: NotificationAccess? = null) {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0",
                    settings,
                    TestData.clientsOf(settings),
                    TestData.groupsOf(settings),
                    calendar,
                    prefs,
                    reminders = reminders,
                    notifications = notifications,
                    clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasText(s("Дневник · сводка в_21:00")))
        onNodeWithTag(REMINDERS_ROW_TAG).performClick()
        awaitNode(hasTestTag(DIGEST_TAG))
    }

    @Test
    fun defaultsAndDigestOffHidesTime() = runComposeUiTest {
        openReminders()
        onNodeWithTag(DIARY_SWITCH_TAG).assertIsOn()
        onNodeWithTag(AUTO_DONE_TAG).assertIsOn()
        onNodeWithTag(DIGEST_TAG).assertIsOn()
        onNodeWithTag(DIGEST_TIME_TAG).assertIsDisplayed()
        onNodeWithTag(followupDaysTag(14)).onChild().assertIsSelected()
        // Мгновенные — под «Дополнительно», свёрнутым, пока всё выключено.
        assertEquals(0, onAllNodes(hasTestTag(INSTANT_DIARY_TAG)).fetchSemanticsNodes().size)

        onNodeWithTag(DIGEST_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { !saved().digest }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(DIGEST_TIME_TAG)).fetchSemanticsNodes().isEmpty() }

        onNodeWithTag(followupDaysTag(21)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { saved().followupDays == 21 }
    }

    @Test
    fun moreHoldsInstantAndDayPlan() = runComposeUiTest {
        openReminders(access)
        onNodeWithTag(MORE_TAG).performScrollTo().performClick()
        onNodeWithTag(INSTANT_DIARY_TAG).performScrollTo().assertIsOff().performClick()
        waitUntil(timeoutMillis = 5_000) { saved().instantDiary }
        onNodeWithTag(INSTANT_GROUP_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { saved().instantGroup }
        onNodeWithTag(DAY_PLAN_TAG).performScrollTo().assertIsOff().performClick()
        onNodeWithTag(DAY_PLAN_TAG).assertIsOn()
        assertTrue(reminders.dayPlan)
        // Каждое изменение переставляет будильник.
        waitUntil(timeoutMillis = 5_000) { access.changes >= 3 }
    }

    @Test
    fun blockedNotificationsShowEnable() = runComposeUiTest {
        openReminders(access)
        awaitNode(hasTestTag(BLOCKED_TAG))
        onNodeWithTag(BLOCKED_TAG).assertIsDisplayed()
        onNode(hasText("Включить")).performClick()
        assertTrue(access.on)
        // Строка уходит сама: экран сверяется с телефоном после нажатия и при возврате в приложение.
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(BLOCKED_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun diaryOffHidesInstantDiary() = runComposeUiTest {
        openReminders()
        onNodeWithTag(DIARY_SWITCH_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { !saved().diary }
        onNodeWithTag(MORE_TAG).performScrollTo().performClick()
        onNodeWithTag(INSTANT_GROUP_TAG).performScrollTo().assertIsDisplayed()
        assertFalse(onAllNodes(hasTestTag(INSTANT_DIARY_TAG)).fetchSemanticsNodes().isNotEmpty())
    }
}
