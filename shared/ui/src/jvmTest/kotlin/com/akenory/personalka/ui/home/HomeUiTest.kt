package com.akenory.personalka.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.calendarDayTag
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Главная» без денег (шаг 1.16, ТЗ 5.2, макеты «Главная» 0.6). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class HomeUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 960.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date
    private val tomorrow = LocalDate(2026, 10, 21)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    /** Тренировка в ББ; id — по времени начала. */
    private fun training(start: LocalDateTime, clientIds: List<String>, type: String = "ПТ", groupId: String? = null): String = runBlocking {
        calendar.forms.create(TrainingDraft(start, typeId(type), clientIds, groupId, placeId("ББ"), 50))
        db.trainingDao().trainingsBetween(start, LocalDateTime(start.date, LocalTime(23, 59))).first { it.start == start }.id
    }

    private fun at(h: Int, m: Int = 0, date: LocalDate = today) = LocalDateTime(date, LocalTime(h, m))

    private class Folder : BackupFolder {
        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean = true
        override suspend fun list(folder: String): List<String> = emptyList()
        override suspend fun delete(folder: String, name: String) = Unit
    }

    private object NoFiles : PlatformFiles {
        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) = Unit
        override fun openFile(onOpened: (ByteArray) -> Unit) = Unit
        override fun share(name: String, mime: String, bytes: ByteArray) = Unit
    }

    private fun ComposeUiTest.open(width: Dp = folded, backup: BackupTools? = null) {
        setContent {
            Box(Modifier.size(width, height)) {
                App("0.1.0", settings, clients, groups, calendar, MemoryPrefs(), backup = backup, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(NEXT_TAG))
    }

    private fun inNext(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(NEXT_TAG))

    @Test
    fun nextTrainingMarkedFromHomeThenTomorrowInCalendar() = runComposeUiTest {
        val orlov = training(at(15, 50), listOf(client("Орлов", "Максим")))
        training(at(18, 0), listOf(client("Белов", "Артём")))
        training(at(7, 0, tomorrow), listOf(client("Ковалёва", "Нина")))
        open()

        // 15:40: ближайшая — Орлов в 15:50, «Проведено» уже можно (с 15:35); ещё сегодня одна, в 18:00.
        awaitNode(inNext(s("Орлов_М.")))
        onNode(inNext("Ближайшая · 15:50–16:40")).assertIsDisplayed()
        onNode(inNext(s("через 10_мин"))).assertIsDisplayed()
        onNode(inNext("Ещё сегодня: 1, последняя в 18:00")).assertIsDisplayed()
        onNodeWithTag(MARK_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().training(orlov)!!.status } == TrainingStatus.DONE }

        // Ближайшая — Белов в 18:00, кнопки ещё нет; под ним — завтрашняя.
        awaitNode(inNext(s("Белов_А.")))
        assertEquals(0, onAllNodes(hasTestTag(MARK_TAG)).fetchSemanticsNodes().size)
        onNode(inNext("Завтра первая в 07:00")).performClick()
        awaitNode(hasTestTag(calendarDayTag(tomorrow)))
    }

    @Test
    fun unmarkedGroupOpensCardOnHomeAndGoesAway() = runComposeUiTest {
        val group = runBlocking { groups.create(GroupDraft("Вт 12:00 ББ", typeId("Группа"), placeId("ББ"))) }
        runBlocking {
            groups.addMember(group, client("Белов", "Артём"), LocalDate(2026, 9, 1))
            groups.addMember(group, client("Аксёнова", "Вера"), LocalDate(2026, 9, 1))
        }
        training(at(12, 0), emptyList(), type = "Группа", groupId = group)
        open()

        awaitNode(hasTestTag(todoTag(TodoKind.UNMARKED)))
        onNode(hasText("Группа без отметки")).assertIsDisplayed()
        onNode(hasText("12:00 · Вт 12:00 ББ (2)")).assertIsDisplayed()
        onNode(hasContentDescription("Нужно сделать: 1")).assertIsDisplayed()

        // Нажатие — карточка группы прямо на «Главной»; «Все пришли» — пункт исчезает вместе со счётчиком.
        onNodeWithTag(todoTag(TodoKind.UNMARKED)).performClick()
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        onNode(hasText(TrainingCardFormat.action(CardAction.ALL_CAME)) and hasAnyAncestor(hasTestTag(TRAINING_CARD_TAG))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(todoTag(TodoKind.UNMARKED))).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, onAllNodes(hasContentDescription("Нужно сделать: 1")).fetchSemanticsNodes().size)
    }

    @Test
    fun backupItemAppearsAndGoesAwayByItself() = runComposeUiTest {
        val backup = BackupRepository(db, MemoryPrefs(), Folder(), TestData.clock)
        open(backup = BackupTools(backup, NoFiles))

        // Папка не выбрана — «Выберите папку для копии» и счётчик дел 1.
        awaitNode(hasTestTag(todoTag(TodoKind.BACKUP_FOLDER)))
        onNode(hasText(HomeFormat.BACKUP_FOLDER_NOTE)).assertIsDisplayed()
        onNode(hasContentDescription("Нужно сделать: 1")).assertIsDisplayed()

        // Папку выбрали и копию сделали (как автокопия при открытии) — пункта нет, блока «Нужно сделать» тоже.
        runBlocking {
            backup.setEncrypt(false)
            backup.chooseFolder("content://copies", "Копии")
            backup.saveNow()
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(TODO_TAG)).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, onAllNodes(hasContentDescription("Нужно сделать: 1")).fetchSemanticsNodes().size)
    }

    @Test
    fun unfoldedDayWithPastCollapsed() = runComposeUiTest {
        val past = training(at(9, 0), listOf(client("Соколова", "Вера")))
        val next = training(at(16, 0), listOf(client("Орлов", "Максим")))
        training(at(7, 0, tomorrow), listOf(client("Ковалёва", "Нина")))
        open(unfolded)

        awaitNode(hasTestTag(dayRowTag(next)))
        assertEquals(0, onAllNodes(hasTestTag(dayRowTag(past))).fetchSemanticsNodes().size)
        onNodeWithTag(PAST_TAG).assertIsDisplayed()
        onNode(hasText(s("Завтра: 1_тренировка, первая в 07:00"))).assertIsDisplayed()
        // Справа — те же блоки: ближайшая — Орлов.
        onNode(inNext(s("Орлов_М."))).assertIsDisplayed()

        onNodeWithTag(PAST_TAG).performClick()
        awaitNode(hasTestTag(dayRowTag(past)))
        onNode(hasText(HomeFormat.HIDE_PAST)).assertIsDisplayed()
    }
}
