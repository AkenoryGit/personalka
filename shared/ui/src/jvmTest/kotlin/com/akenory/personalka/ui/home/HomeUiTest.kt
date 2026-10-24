package com.akenory.personalka.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.CALENDAR_GRID_TAG
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.CREATE_NOW_TAG
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * «Главная» без денег (шаг 1.16, ТЗ 5.2, макеты «День», «Вечер», «Утро», «Разложенный Fold»). «Сейчас» — вт 20.10.2026
 * 15:40. Имена придуманы.
 */
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
    private val tomorrow = today.plus(1, DateTimeUnit.DAY)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun at(time: String, day: LocalDate = today): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(day, LocalTime(h, m)) }

    private fun training(time: String, vararg clientIds: String, day: LocalDate = today, plan: String = ""): String = runBlocking {
        calendar.forms.create(TrainingDraft(at(time, day), typeId("ПТ"), clientIds.toList(), null, placeId("ББ"), 50, plan = plan))
        db.trainingDao().trainingsBetween(at(time, day), at("23:59", day)).first { it.start == at(time, day) }.id
    }

    private fun group(time: String, name: String): Pair<String, List<String>> = runBlocking {
        val group = groups.create(GroupDraft(name, typeId("Группа"), placeId("ББ")))
        val kids = listOf(client("Белов", "Артём"), client("Гришин", "Матвей"), client("Данилова", "Ева"))
        kids.forEach { groups.addMember(group, it, LocalDate(2026, 9, 1)) }
        calendar.forms.create(TrainingDraft(at(time), typeId("Группа"), emptyList(), group, placeId("ББ"), 60))
        db.trainingDao().trainingsOfGroup(group).single().id to kids
    }

    private fun statusOf(id: String) = runBlocking { db.trainingDao().training(id)!!.status }

    private fun ComposeUiTest.openHome(width: () -> Dp = { folded }, backup: BackupTools? = null) {
        setContent {
            Box(Modifier.size(width(), height)) {
                App("0.1.0", settings, clients, groups, calendar, MemoryPrefs(), backup = backup, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(NEAREST_TAG))
    }

    private fun inNearest(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(NEAREST_TAG))

    private fun inCard(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(TRAINING_CARD_TAG))

    @Test
    fun nearestWithPlanAndLaterTodayOpensCardAndDay() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        training("16:20", orlov, plan = "Кроль на груди с доской")
        training("18:00", client("Петров", "Игорь"))
        training("19:00", client("Соколова", "Вера"))
        openHome()

        awaitNode(inNearest(s("Орлов_М.")))
        onNodeWithText("Ближайшая · 16:20–17:10").assertIsDisplayed()
        onNodeWithText(s("через 40_мин")).assertIsDisplayed()
        onNode(inNearest("ПТ · ББ")).assertIsDisplayed()
        onNodeWithText("План Кроль на груди с доской").assertIsDisplayed()
        // До начала минус 15 минут кнопки нет — нажатие на блок открывает карточку.
        assertEquals(0, onAllNodes(hasTestTag(NEAREST_DONE_TAG)).fetchSemanticsNodes().size)
        onNodeWithText(s("Ещё сегодня: 2, последняя в_19:00")).assertIsDisplayed()
        // Дел нет — блока «Нужно сделать» и счётчика нет.
        assertEquals(0, onAllNodes(hasTestTag(TODO_TAG)).fetchSemanticsNodes().size)

        // Нажатие на имя — карточка тренировки прямо на «Главной».
        onNodeWithTag(NEAREST_OPEN_TAG).performClick()
        awaitNode(inCard(s("Орлов_М.")))
    }

    @Test
    fun laterTodayOpensCalendar() = runComposeUiTest {
        training("16:20", client("Орлов", "Максим"))
        training("18:00", client("Петров", "Игорь"))
        openHome()
        awaitNode(hasTestTag(LATER_TAG))
        onNodeWithTag(LATER_TAG).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    @Test
    fun doneFromHomeFifteenMinutesBefore() = runComposeUiTest {
        // ПТ в 15:50 — через 10 минут: «Проведено» прямо на «Главной», 1 нажатие (10.7).
        val id = training("15:50", client("Морозов", "Даниил"))
        training("17:00", client("Петров", "Игорь"))
        openHome()
        awaitNode(hasTestTag(NEAREST_DONE_TAG))
        onNode(hasTestTag(NEAREST_DONE_TAG) and hasText(TrainingCardFormat.action(CardAction.DONE))).performClick()
        waitUntil(timeoutMillis = 5_000) { statusOf(id) == TrainingStatus.DONE }
        // Проведённая уходит, ближайшая — следующая.
        awaitNode(inNearest("Ближайшая · 17:00–17:50"))
    }

    @Test
    fun eveningTodayDoneShowsTomorrow() = runComposeUiTest {
        training("07:00", client("Ковалёва", "Наталья"), day = tomorrow)
        openHome()
        awaitNode(inNearest(s("Сегодня всё. Завтра первая в_07:00")))
        onNodeWithText(s("ср_21.10 · Ковалёва_Н. · ПТ · ББ")).assertIsDisplayed()
        onNodeWithTag(NEXT_TAG).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    @Test
    fun unmarkedGroupIsTodoUntilAllCame() = runComposeUiTest {
        // Группа в 12:00 закончилась, детей не отметили — пункт «Группа без отметки» и счётчик дел 1.
        val (id, kids) = group("12:00", "Вт 12:00 ББ")
        openHome()
        awaitNode(hasTestTag(todoTag(TodoKind.UNMARKED_GROUPS)))
        onNodeWithText("Группа без отметки").assertIsDisplayed()
        onNodeWithText("12:00 · Вт 12:00 ББ (3)").assertIsDisplayed()
        onNode(hasContentDescription("Нужно сделать: 1") and hasAnyAncestor(hasTestTag(TODO_TAG))).assertIsDisplayed()
        onNode(hasContentDescription("Нужно сделать: 1") and hasAnyAncestor(hasTestTag(navTag(Section.HOME))), useUnmergedTree = true).assertIsDisplayed()

        // Нажатие — карточка группы; «Все пришли» — пункт уходит сам, а проведённая группа ждёт дневника (шаг 2.1).
        onNodeWithTag(todoTag(TodoKind.UNMARKED_GROUPS)).performClick()
        awaitNode(inCard(TrainingCardFormat.action(CardAction.ALL_CAME)))
        onNode(inCard(TrainingCardFormat.action(CardAction.ALL_CAME))).performClick()
        waitUntil(timeoutMillis = 5_000) { statusOf(id) == TrainingStatus.DONE }
        assertEquals(List(3) { ParticipationStatus.ATTENDED }, runBlocking { kids.map { db.trainingDao().participationRow(id, it)!!.status } })
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(todoTag(TodoKind.UNMARKED_GROUPS))).fetchSemanticsNodes().isEmpty() }
        awaitNode(hasTestTag(todoTag(TodoKind.NO_DIARY)))
        onNode(hasContentDescription("Нужно сделать: 1") and hasAnyAncestor(hasTestTag(navTag(Section.HOME))), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun backupItemAppearsAndGoesAwayItself() = runComposeUiTest {
        // Проверка шага: пункт про копию появляется и исчезает сам.
        val backup = BackupRepository(db, MemoryPrefs(), Folder(), clock = TestData.clock)
        openHome(backup = BackupTools(backup, Files()))
        awaitNode(hasText("Выберите папку для копии"))
        onNodeWithText("Пока данные только в этом телефоне").assertIsDisplayed()
        onNode(hasContentDescription("Нужно сделать: 1") and hasAnyAncestor(hasTestTag(navTag(Section.HOME))), useUnmergedTree = true).assertIsDisplayed()

        // Нажатие ведёт в «Настройки → Данные».
        onNodeWithTag(todoTag(TodoKind.BACKUP)).performClick()
        awaitNode(hasTestTag(CREATE_NOW_TAG))

        // Папка выбрана и копия сделана — пункт ушёл и с «Главной», и со значка.
        backup.chooseFolder("content://copies", "Копии")
        backup.setEncrypt(false)
        runBlocking { backup.saveNow() }
        onNodeWithTag(navTag(Section.HOME)).performClick()
        awaitNode(hasTestTag(NEAREST_TAG))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(TODO_TAG)).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, onAllNodes(hasContentDescription("Нужно сделать: 1"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun unfoldedDayScheduleKeepsPastOpenAcrossFold() = runComposeUiTest {
        training("09:00", client("Яковлева", "Таисия"))
        training("15:30", client("Соколова", "Вера"))
        training("16:20", client("Орлов", "Максим"))
        training("07:00", client("Ковалёва", "Наталья"), day = tomorrow)
        training("08:00", client("Петров", "Игорь"), day = tomorrow)
        var width by mutableStateOf(unfolded)
        openHome(width = { width })

        // Слева расписание дня: прошедшая 09:00 свёрнута, 15:30 идёт, 16:20 — ближайшая; внизу — завтра.
        awaitNode(hasText("Показать прошедшие · 1"))
        onNodeWithText("идёт").assertIsDisplayed()
        onNodeWithText(s("ПТ · ББ · через 40_мин")).assertIsDisplayed()
        onNodeWithText(s("Завтра: 2_тренировки, первая в_07:00")).assertIsDisplayed()
        // На разложенном строки «Ещё сегодня» нет.
        assertEquals(0, onAllNodes(hasTestTag(LATER_TAG)).fetchSemanticsNodes().size)
        onNodeWithTag(PAST_TAG).performClick()
        awaitNode(hasText(s("Яковлева_Т.")))

        // Сложили и разложили — прошедшие так и раскрыты.
        width = folded
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(PAST_TAG)).fetchSemanticsNodes().isEmpty() }
        width = unfolded
        awaitNode(hasText("Скрыть прошедшие"))
        // Имя есть и в пункте «Клиенты без записи» (шаг 2.4) — здесь нужна строка расписания дня.
        onNode(hasText(s("Яковлева_Т.")) and inDaySchedule).assertIsDisplayed()
    }

    private val inDaySchedule = SemanticsMatcher("строка расписания дня") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-day-") == true
    }

    private class Folder : BackupFolder {
        val files = mutableMapOf<String, ByteArray>()

        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean {
            files[name] = bytes
            return true
        }

        override suspend fun list(folder: String): List<String> = files.keys.toList()

        override suspend fun delete(folder: String, name: String) {
            files.remove(name)
        }
    }

    private class Files : PlatformFiles {
        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) = onPicked("content://copies", "Копии")

        override fun openFile(onOpened: (ByteArray) -> Unit) = Unit

        override fun share(name: String, mime: String, bytes: ByteArray) = Unit
    }
}
