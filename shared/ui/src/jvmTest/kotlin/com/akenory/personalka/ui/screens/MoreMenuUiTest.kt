package com.akenory.personalka.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.MoreFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test

/** «Ещё» одним экраном (шаг 3.15а): группы, пояснение под каждым пунктом, без отдельных «Настроек». */
@OptIn(ExperimentalTestApi::class)
class MoreMenuUiTest {
    private val settings = TestData.ready()

    @Test
    fun groupsAndNotesWithoutSettingsItem() = runComposeUiTest {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.GROUPS)))

        onNodeWithText(MoreFormat.WORK).assertIsDisplayed()
        onNodeWithTag(moreRowTag(MoreItem.VACATION)).assertTextContains(MoreFormat.VACATION_NOTE, substring = true)
        onNodeWithText(MoreFormat.SETUP).performScrollTo().assertIsDisplayed()
        onNodeWithTag(moreRowTag(MoreItem.REMINDERS)).performScrollTo().assertTextContains(MoreFormat.REMINDERS_NOTE, substring = true)
        // Без совместного ведения нет «Коллег», без сервера — «Кабинетов», без копий на платформе — «Данных» (10.7).
        onNodeWithTag(moreRowTag(MoreItem.COLLEAGUES)).assertDoesNotExist()
        onNodeWithTag(moreRowTag(MoreItem.CABINETS)).assertDoesNotExist()
        onNodeWithText(MoreFormat.DATA).assertDoesNotExist()
        onNodeWithText("Настройки").assertDoesNotExist()
    }
}
