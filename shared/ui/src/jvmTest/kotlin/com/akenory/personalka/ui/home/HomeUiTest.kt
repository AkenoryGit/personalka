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
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TodoListFormat
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
import com.akenory.personalka.ui.calendar.calendarDayTag
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.CREATE_NOW_TAG
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.CANCEL_PANEL_TAG
import com.akenory.personalka.ui.training.CARD_BIRTHDAY_TAG
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
    private val settings = TestData.ready(diary = true)
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
    fun ongoingTrainingStaysWithItsPlan() = runComposeUiTest {
        // Шаг 3.9ф: ПТ 15:00 идёт — на «Главной» она и её план до конца, а не следующая в 16:20.
        training("15:00", client("Орлов", "Максим"), plan = "Брасс, 4 по 25")
        training("16:20", client("Петров", "Игорь"))
        openHome()

        awaitNode(inNearest(s("Орлов_М.")))
        onNodeWithText("Сейчас · 15:00–15:50").assertIsDisplayed()
        onNodeWithText(s("до конца 10_мин")).assertIsDisplayed()
        onNodeWithText("План Брасс, 4 по 25").assertIsDisplayed()
        onNodeWithTag(NEAREST_DONE_TAG).assertIsDisplayed()
        onNodeWithText(s("Ещё сегодня: 1, в_16:20")).assertIsDisplayed()
    }

    /** Прошедшая ПТ клиента [daysAgo] дней назад: пришёл или отменил («Не сможет»). */
    private fun past(clientId: String, daysAgo: Int, came: Boolean, time: String = "10:00") = runBlocking {
        val day = today.plus(-daysAgo, DateTimeUnit.DAY)
        val id = training(time, clientId, day = day)
        val t = db.trainingDao().training(id)!!
        db.trainingDao().upsertTraining(t.copy(status = if (came) TrainingStatus.DONE else TrainingStatus.CANCELLED, cancelReason = if (came) null else "Не сможет"))
        val p = db.trainingDao().participationsOf(id).single()
        db.trainingDao().upsertParticipation(p.copy(status = if (came) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED, cancelReason = if (came) null else "Не сможет"))
    }

    @Test
    fun unreliableClientTodayAskedToConfirm() = runComposeUiTest {
        // Шаг 3.14: у Орлова 6 из 10 — 60 %, красная; у Петрова 10 из 10 — зелёная, его не спрашивают.
        val orlov = client("Орлов", "Максим")
        val petrov = client("Петров", "Игорь")
        (1..10).forEach { past(orlov, it, came = it > 4) }
        (1..10).forEach { past(petrov, it, came = true, time = "12:00") }
        training("17:00", orlov)
        training("18:00", petrov)
        openHome()

        awaitNode(hasTestTag(todoTag(TodoKind.CONFIRM_VISIT)))
        onNodeWithText("Уточнить, придёт ли").assertIsDisplayed()
        onNodeWithText(s("Орлов_М., 17:00")).assertIsDisplayed()

        // В карточке ближайшей — «Надёжность 60 %» и из чего посчитано.
        onNodeWithTag(NEAREST_OPEN_TAG).performClick()
        awaitNode(inCard(s("60_%")))
        onNode(inCard(s("4_отмены из 10"))).assertIsDisplayed()

        // Имя ведёт в карточку клиента: там та же надёжность.
        onNode(inCard(s("Орлов_М."))).performClick()
        awaitNode(hasText(s("Надёжность 60_%")))
    }

    @Test
    fun confirmTodoOpensListComingAndNotComing() = runComposeUiTest {
        // Шаг 3.16: пункт открывает окно — пояснение, время, тип и место, надёжность. «Придёт» убирает Орлова до начала
        // этой тренировки, «Не придёт» у Белова — окно отмены его тренировки.
        val orlov = client("Орлов", "Максим")
        val belov = client("Белов", "Кирилл")
        (1..10).forEach { past(orlov, it, came = it > 4) }
        (1..10).forEach { past(belov, it, came = it > 3, time = "12:00") }
        val orlovTraining = training("17:00", orlov)
        training("18:30", belov)
        openHome()

        awaitNode(hasTestTag(todoTag(TodoKind.CONFIRM_VISIT)))
        onNodeWithTag(todoTag(TodoKind.CONFIRM_VISIT)).performClick()
        val panel = hasAnyAncestor(hasTestTag(todoPanelTag(TodoKind.CONFIRM_VISIT)))
        awaitNode(hasText(TodoListFormat.CONFIRM_WHY) and panel)
        onNode(hasText(s("Орлов_М.")) and panel).assertIsDisplayed()
        onNode(hasText("сегодня 17:00") and panel).assertIsDisplayed()
        onNode(hasText(s("Надёжность 60_% · 4_отмены из 10")) and panel).assertIsDisplayed()
        onNode(hasText(s("Надёжность 70_% · 3_отмены из 10")) and panel).assertIsDisplayed()

        onNodeWithTag(confirmComingTag(orlov)).performClick()
        awaitNode(hasText(TodoListFormat.comingMessage(s("Орлов_М."))))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(s("Орлов_М.")) and panel).fetchSemanticsNodes().isEmpty() }
        val p = runBlocking { db.trainingDao().participationsOf(orlovTraining).single() }
        assertEquals(at("17:00"), p.confirmedStart)

        onNodeWithTag(confirmNotComingTag(belov)).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        assertEquals(0, onAllNodes(hasTestTag(todoPanelTag(TodoKind.CONFIRM_VISIT))).fetchSemanticsNodes().size)
    }

    @Test
    fun reliabilityOnCalendarBlockAndSwitchedOff() = runComposeUiTest {
        // Шаг 3.14а: «Надёжность 60 %» на блоке ПТ в календаре; выключатель в настройках убирает её отовсюду.
        val orlov = client("Орлов", "Максим")
        (1..10).forEach { past(orlov, it, came = it > 4) }
        training("17:00", orlov)
        openHome()
        awaitNode(hasTestTag(todoTag(TodoKind.CONFIRM_VISIT)))

        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(calendarDayTag(today)) and hasContentDescription(s("Надёжность 60_%"), substring = true))

        runBlocking { calendar.reminders.saveReliability(false) }
        awaitNode(hasTestTag(calendarDayTag(today)) and hasContentDescription("Орлов", substring = true))
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(calendarDayTag(today)) and hasContentDescription("Надёжность", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(navTag(Section.HOME)).performClick()
        awaitNode(hasTestTag(NEAREST_TAG))
        assertEquals(0, onAllNodes(hasTestTag(todoTag(TodoKind.CONFIRM_VISIT))).fetchSemanticsNodes().size)
    }

    @Test
    fun birthdayBlockAndCakeLineInCard() = runComposeUiTest {
        // Шаг 3.9р: Ковалёву сегодня 9 — блок «День рождения сегодня» и строка с тортом в карточке его тренировки.
        val kovalev = runBlocking { clients.create(ClientDraft("Ковалёв", "Артём", birthDate = LocalDate(2017, 10, 20))) }
        runBlocking { clients.create(ClientDraft("Петров", "Игорь", birthDate = LocalDate(2017, 10, 21))) }
        training("16:20", kovalev)
        openHome()

        awaitNode(hasTestTag(BIRTHDAYS_TAG))
        onNode(hasText(ClientFormat.BIRTHDAY_TODAY) and hasAnyAncestor(hasTestTag(BIRTHDAYS_TAG))).assertIsDisplayed()
        onNode(hasText(s("Ковалёв Артём · 9_лет")) and hasAnyAncestor(hasTestTag(BIRTHDAYS_TAG))).assertIsDisplayed()
        assertEquals(0, onAllNodes(hasText("Петров", substring = true) and hasAnyAncestor(hasTestTag(BIRTHDAYS_TAG))).fetchSemanticsNodes().size)

        onNodeWithTag(NEAREST_OPEN_TAG).performClick()
        awaitNode(inCard(s("День рождения · Ковалёв_А. · 9_лет")))
    }

    @Test
    fun noBirthdayNoBlock() = runComposeUiTest {
        training("16:20", client("Орлов", "Максим"))
        openHome()
        assertEquals(0, onAllNodes(hasTestTag(BIRTHDAYS_TAG)).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasTestTag(CARD_BIRTHDAY_TAG)).fetchSemanticsNodes().size)
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

        // Слева расписание дня: прошедшая 09:00 свёрнута, 15:30 идёт — она и выделена (шаг 3.9ф); внизу — завтра.
        awaitNode(hasText("Показать прошедшие · 1"))
        onNodeWithText(s("ПТ · ББ · до конца 40_мин")).assertIsDisplayed()
        assertEquals(0, onAllNodes(hasText("идёт")).fetchSemanticsNodes().size)
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
