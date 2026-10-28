package com.akenory.personalka.ui.tour

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TourFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.tour.TourStop
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.clients.clientRowTag
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.shell.FAB_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.FORM_SEARCH_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Знакомство с приложением (шаг 5.3б, ТЗ 5.18): шесть остановок по порядку, жест засчитывается сам,
 * «Пропустить» без папки копии спрашивает «Без копии?», повтор — из «Ещё».
 */
@OptIn(ExperimentalTestApi::class)
class TourUiTest {
    private val folded = 412.dp
    private val height = 900.dp

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private class Folder : BackupFolder {
        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean = true

        override suspend fun list(folder: String): List<String> = emptyList()

        override suspend fun delete(folder: String, name: String) = Unit
    }

    /** Системный выбор папки отвечает сразу. */
    private class Files : PlatformFiles {
        var picked = 0

        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) {
            picked++
            onPicked("content://copies", "Копии")
        }

        override fun openFile(onOpened: (ByteArray) -> Unit) = Unit

        override fun share(name: String, mime: String, bytes: ByteArray) = Unit
    }

    private fun ComposeUiTest.show(
        settings: SettingsRepository,
        prefs: MemoryPrefs,
        backup: BackupTools? = null,
    ) {
        setContent {
            Box(Modifier.size(folded, height)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    prefs = prefs, backup = backup, clock = TestData.clock,
                )
            }
        }
    }

    private fun ComposeUiTest.atStop(stop: TourStop, title: String) {
        awaitNode(hasText(TourFormat.step(stop)))
        onNodeWithText(title).assertIsDisplayed()
    }

    private fun startedAt(stop: TourStop) = MemoryPrefs().apply { putString(TourState.KEY, stop.name) }

    @Test
    fun sixStopsWithRealActions() = runComposeUiTest {
        val settings = TestData.ready()
        val prefs = startedAt(TourStop.CALENDAR)
        val backup = BackupTools(BackupRepository(TestData.dbOf(settings), MemoryPrefs(), Folder(), clock = TestData.clock), Files())
        show(settings, prefs, backup)

        // 1. Календарь: тренировка с новым клиентом прямо в форме; пока форма открыта, карточки знакомства нет.
        atStop(TourStop.CALENDAR, TourFormat.CALENDAR_TITLE)
        onNodeWithTag(FAB_TAG).performClick()
        awaitNode(hasTestTag(FORM_SEARCH_TAG))
        onNodeWithTag(TOUR_CARD_TAG).assertDoesNotExist()
        onNodeWithTag(FORM_SEARCH_TAG).performTextInput("Петров Игорь")
        onNodeWithText("Новый клиент «Петров Игорь»").performScrollTo().performClick()
        onNodeWithText(TrainingFormat.SAVE).performClick()

        // 2. Сама открылась карточка этой тренировки; правило списания — из настройки, 1 час.
        atStop(TourStop.CARD, TourFormat.CARD_TITLE)
        onNodeWithText(TourFormat.cardCancel(60)).assertIsDisplayed()
        onNodeWithTag(TOUR_NEXT_TAG).performClick()

        // 3. Клиенты: клиент из тренировки уже в списке — открыть карточку, тогда «Дальше».
        atStop(TourStop.CLIENTS, TourFormat.CLIENTS_TITLE)
        onNodeWithTag(navTag(Section.CLIENTS)).assertIsSelected()
        onNodeWithText(TourFormat.CLIENTS_TEXT).assertIsDisplayed()
        onNodeWithTag(TOUR_NEXT_TAG).assertDoesNotExist()
        onNodeWithTag(clientRowTag("Петров Игорь")).performClick()
        awaitNode(hasText(TourFormat.CLIENTS_OPEN_TEXT))
        onNodeWithTag(TOUR_NEXT_TAG).performClick()

        // 4. Главная.
        atStop(TourStop.HOME, TourFormat.HOME_TITLE)
        onNodeWithTag(navTag(Section.HOME)).assertIsSelected()
        onNodeWithTag(TOUR_NEXT_TAG).performClick()

        // 5. Зарплата не настроена — остановка есть; «Позже» ведёт к копии.
        atStop(TourStop.SALARY, TourFormat.SALARY_TITLE)
        onNodeWithTag(navTag(Section.SALARY)).assertIsSelected()
        onNodeWithTag(TOUR_SKIP_TAG).performClick()

        // 6. Копия: «Выбрать папку» — системный выбор, папка выбрана — знакомство кончилось.
        atStop(TourStop.BACKUP, TourFormat.BACKUP_TITLE)
        onNodeWithTag(navTag(Section.MORE)).assertIsSelected()
        onNodeWithTag(TOUR_NEXT_TAG).performClick()
        awaitNode(hasText(TourFormat.DONE))
        onNodeWithTag(TOUR_CARD_TAG).assertDoesNotExist()
        assertEquals("", prefs.string(TourState.KEY))
        assertEquals("content://copies", backup.repository.state.value.folder)
    }

    @Test
    fun skipWithoutFolderAsksAboutBackup() = runComposeUiTest {
        val settings = TestData.ready()
        val prefs = startedAt(TourStop.HOME)
        val files = Files()
        val backup = BackupTools(BackupRepository(TestData.dbOf(settings), MemoryPrefs(), Folder(), clock = TestData.clock), files)
        show(settings, prefs, backup)

        atStop(TourStop.HOME, TourFormat.HOME_TITLE)
        onNodeWithTag(TOUR_SKIP_TAG).performClick()
        awaitNode(hasText(TourFormat.NO_BACKUP_TITLE))
        onNodeWithTag(TOUR_CARD_TAG).assertDoesNotExist()
        onNodeWithText(TourFormat.NO_BACKUP).performClick()
        awaitNode(hasText(TourFormat.DONE))
        assertEquals("", prefs.string(TourState.KEY))
        assertEquals(0, files.picked, "Без копии — папка не спрашивается")
    }

    @Test
    fun skipWithoutFolderCanPickIt() = runComposeUiTest {
        val settings = TestData.ready()
        val prefs = startedAt(TourStop.CARD)
        val files = Files()
        val backup = BackupTools(BackupRepository(TestData.dbOf(settings), MemoryPrefs(), Folder(), clock = TestData.clock), files)
        show(settings, prefs, backup)

        awaitNode(hasTestTag(TOUR_SKIP_TAG))
        onNodeWithTag(TOUR_SKIP_TAG).performClick()
        awaitNode(hasText(TourFormat.NO_BACKUP_TITLE))
        onNodeWithText(TourFormat.PICK_FOLDER).performClick()
        awaitNode(hasText(TourFormat.DONE))
        assertEquals(1, files.picked)
        assertEquals("", prefs.string(TourState.KEY))
    }

    @Test
    fun laterWithoutBackupFinishes() = runComposeUiTest {
        // Платформа без резервной копии (как в тестах): после «Зарплаты» знакомство кончается.
        val settings = TestData.ready()
        val prefs = startedAt(TourStop.HOME)
        show(settings, prefs)
        atStop(TourStop.HOME, TourFormat.HOME_TITLE)
        onNodeWithTag(TOUR_NEXT_TAG).performClick()
        atStop(TourStop.SALARY, TourFormat.SALARY_TITLE)
        onNodeWithTag(TOUR_SKIP_TAG).performClick()
        awaitNode(hasText(TourFormat.DONE))
        assertEquals("", prefs.string(TourState.KEY))
    }

    @Test
    fun emptyClientsAskToAddFirst() = runComposeUiTest {
        val settings = TestData.ready()
        show(settings, startedAt(TourStop.CLIENTS))
        awaitNode(hasTestTag(navTag(Section.HOME)))
        // Остановка «Клиенты» открывается своим разделом, когда к ней перешли; здесь начинаем прямо с неё.
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasText(TourFormat.CLIENTS_EMPTY_TITLE))
        onNodeWithText(TourFormat.CLIENTS_EMPTY_GESTURE).assertIsDisplayed()
    }

    @Test
    fun repeatFromMore() = runComposeUiTest {
        val settings = TestData.ready()
        val prefs = MemoryPrefs()
        show(settings, prefs)
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(TOUR_CARD_TAG).assertDoesNotExist()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.TOUR)))
        onNodeWithText(TourFormat.HELP).performScrollTo().assertIsDisplayed()
        onNodeWithTag(moreRowTag(MoreItem.TOUR)).performScrollTo().performClick()
        atStop(TourStop.CALENDAR, TourFormat.CALENDAR_TITLE)
        onNodeWithTag(navTag(Section.CALENDAR)).assertIsSelected()
        assertTrue(prefs.string(TourState.KEY) == TourStop.CALENDAR.name)
    }
}
