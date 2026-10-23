package com.akenory.personalka.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.settings.PLACES_ROW_TAG
import com.akenory.personalka.ui.settings.PROFILE_ROW_TAG
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.TRAINING_FORM_TAG
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Оболочка на экране (шаг 1.5). Сложенный Fold — 412 dp в ширину, разложенный — 760 dp, как в макетах.
 * Главная проверка шага: сложение и разложение не теряет открытый экран и прокрутку.
 */
@OptIn(ExperimentalTestApi::class)
class ShellUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 740.dp
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private val clock: () -> Long = { ClubTime.toMillis(now) }
    private val settings = TestData.ready()

    /** Оболочка видна, когда база ответила, что тренер есть. */
    private fun ComposeUiTest.awaitShell() = awaitNode(hasTestTag(navTag(Section.HOME)))

    @Test
    fun homeOpensFirstWithTodayInTitle() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App(versionName = "0.1.0", settings = settings, clients = TestData.clientsOf(settings), groups = TestData.groupsOf(settings), calendar = TestData.calendarOf(settings), clock = clock) } }
        awaitShell()

        onNodeWithTag(navTag(Section.HOME)).assertIsSelected()
        onNodeWithText(DateFormat.header(now.date)).assertIsDisplayed()
        onNodeWithTag(FAB_TAG).assertIsDisplayed()
    }

    @Test
    fun foldedHasBottomBarUnfoldedHasRail() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent { Box(Modifier.size(width, height)) { App(versionName = "0.1.0", settings = settings, clients = TestData.clientsOf(settings), groups = TestData.groupsOf(settings), calendar = TestData.calendarOf(settings), clock = clock) } }
        awaitShell()

        val home = onNodeWithTag(navTag(Section.HOME)).getBoundsInRoot()
        val more = onNodeWithTag(navTag(Section.MORE)).getBoundsInRoot()
        assertEquals(home.top, more.top, "Разделы в один ряд")
        assertTrue(home.top > height - 80.dp, "Панель внизу")

        width = unfolded
        waitForIdle()
        val homeWide = onNodeWithTag(navTag(Section.HOME)).getBoundsInRoot()
        val moreWide = onNodeWithTag(navTag(Section.MORE)).getBoundsInRoot()
        assertEquals(homeWide.left, moreWide.left, "Разделы столбиком")
        assertTrue(homeWide.right <= 80.dp, "Панель слева")
    }

    @Test
    fun foldingAndUnfoldingKeepsOpenScreen() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent { Box(Modifier.size(width, height)) { App(versionName = "0.1.0", settings = settings, clients = TestData.clientsOf(settings), groups = TestData.groupsOf(settings), calendar = TestData.calendarOf(settings), clock = clock) } }
        awaitShell()

        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasTestTag(PROFILE_ROW_TAG))
        onNodeWithTag(PLACES_ROW_TAG).assertIsDisplayed()
        onNodeWithContentDescription("Назад").assertIsDisplayed()

        // Разложили: список «Ещё» слева, «Настройки» справа, «Назад» не нужен.
        width = unfolded
        waitForIdle()
        onNodeWithTag(navTag(Section.MORE)).assertIsSelected()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).assertIsDisplayed()
        onNodeWithTag(PROFILE_ROW_TAG).assertIsDisplayed()
        onNodeWithContentDescription("Назад").assertDoesNotExist()

        // Сложили обратно: снова «Настройки» с «Назад», а «Назад» ведёт к списку.
        width = folded
        waitForIdle()
        onNodeWithTag(PROFILE_ROW_TAG).assertIsDisplayed()
        onNodeWithContentDescription("Назад").performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).assertIsDisplayed()
    }

    @Test
    fun scrollSurvivesFolding() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent {
            PersonalkaTheme {
                Box(Modifier.size(width, height)) {
                    AppShell(
                        shell = remember { ShellState(Section.CLIENTS) },
                        messages = remember { MessageState() },
                        todoCount = 0,
                        onNewTraining = {},
                    ) {
                        LazyColumn(Modifier.fillMaxSize().testTag("list"), state = rememberLazyListState()) {
                            items(200) { Text("Клиент $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }

        onNodeWithTag("list").performScrollToIndex(120)
        onNodeWithText("Клиент 120").assertIsDisplayed()

        width = unfolded
        waitForIdle()
        onNodeWithText("Клиент 120").assertIsDisplayed()

        width = folded
        waitForIdle()
        onNodeWithText("Клиент 120").assertIsDisplayed()
    }

    @Test
    fun sectionsRememberTheirScreens() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App(versionName = "0.1.0", settings = settings, clients = TestData.clientsOf(settings), groups = TestData.groupsOf(settings), calendar = TestData.calendarOf(settings), clock = clock) } }
        awaitShell()

        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).performClick()
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        onNodeWithText(DateFormat.monthYear(now.date)).assertIsDisplayed()

        onNodeWithTag(navTag(Section.MORE)).performClick()
        // «Группы» с шага 1.8 — настоящий экран: он ждёт ответа базы.
        awaitNode(hasText("Групп пока нет"))

        // Повторное нажатие на раздел — к его началу.
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).assertIsDisplayed()
    }

    @Test
    fun systemBackClosesScreenThenGoesHome() = runComposeUiTest {
        var enabled = false
        var onBack: () -> Unit = {}
        setContent {
            Box(Modifier.size(folded, height)) {
                App(
                    versionName = "0.1.0",
                    settings = settings,
                    clients = TestData.clientsOf(settings),
                    groups = TestData.groupsOf(settings),
                    calendar = TestData.calendarOf(settings),
                    backHandler = { e, back -> SideEffect { enabled = e; onBack = back } },
                    clock = clock,
                )
            }
        }
        awaitShell()
        runOnIdle { assertFalse(enabled, "С «Главной» «Назад» отдаётся системе") }

        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).performClick()
        runOnIdle { assertTrue(enabled); onBack() }
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).assertIsDisplayed()

        runOnIdle { onBack() }
        onNodeWithTag(navTag(Section.HOME)).assertIsSelected()
        runOnIdle { assertFalse(enabled) }
    }

    @Test
    fun plusOnlyOnHomeAndCalendar() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App(versionName = "0.1.0", settings = settings, clients = TestData.clientsOf(settings), groups = TestData.groupsOf(settings), calendar = TestData.calendarOf(settings), clock = clock) } }
        awaitShell()

        // «+» на «Главной» — форма новой тренировки; «Закрыть» возвращает на «Главную».
        onNodeWithTag(FAB_TAG).performClick()
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        onNodeWithContentDescription("Закрыть").performClick()
        onNodeWithTag(TRAINING_FORM_TAG).assertDoesNotExist()

        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        onNodeWithTag(FAB_TAG).assertIsDisplayed()
        for (section in listOf(Section.CLIENTS, Section.SALARY, Section.MORE)) {
            onNodeWithTag(navTag(section)).performClick()
            onNodeWithTag(FAB_TAG).assertDoesNotExist()
        }
    }
}
