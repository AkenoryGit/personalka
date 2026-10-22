package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.calendar.BlockRows
import com.akenory.personalka.domain.calendar.GridHours
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.NEW_TRAINING_LATER
import com.akenory.personalka.ui.SAMPLE_OFF
import com.akenory.personalka.ui.SAMPLE_ON
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.shell.FAB_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Календарь», вид «День» (шаг 1.9, ТЗ 5.3). «Сейчас» — вт 20.10.2026 15:40. Тренировки пишутся через DAO:
 * формы ещё нет (1.13). Сложенный Fold — 412 × 960: сетке остаётся 960 − панель 72 − шапка 56 − неделя 64 = 768 dp.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 960.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val db = TestData.dbOf(settings)
    private val prefs = MemoryPrefs()
    private val today = TestData.now.date
    private var millis = ClubTime.toMillis(TestData.now)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    /** Тренировка с клиентами — фамилия и имя каждого. */
    private fun training(
        time: String,
        minutes: Int,
        vararg names: String,
        type: String = "ПТ",
        place: String = "ББ",
        date: LocalDate = today,
        status: TrainingStatus = TrainingStatus.PLANNED,
    ): String = runBlocking {
        val (h, m) = time.split(':').map { it.toInt() }
        val coach = db.coachDao().currentCoach()!!.id
        val id = Ids.newId(millis++)
        db.trainingDao().upsertTraining(
            TrainingEntity(
                id = id,
                sync = SyncFields.new(coach, millis++),
                typeId = settings.types.first().single { it.shortName == type }.id,
                locationId = settings.places.first().single { it.shortName == place }.id,
                start = LocalDateTime(date, LocalTime(h, m)),
                durationMin = minutes,
                status = status,
            ),
        )
        names.forEach { name ->
            val (last, first) = name.split(' ')
            val client = clients.create(ClientDraft(last, first))
            db.trainingDao().upsertParticipation(ParticipationEntity(Ids.newId(millis++), SyncFields.new(coach, millis++), id, client))
        }
        id
    }

    private fun ComposeUiTest.openCalendar(width: () -> Dp = { folded }) {
        setContent {
            Box(Modifier.size(width(), height)) {
                App("0.1.0", settings, clients, TestData.groupsOf(settings), TestData.calendarOf(settings), prefs, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    private fun ComposeUiTest.day(date: LocalDate = today): DayBlocks =
        onNodeWithTag(calendarDayTag(date)).fetchSemanticsNode().config[CalendarBlocks]

    /** Ждать, пока база ответит и в дне будет [count] блоков. */
    private fun ComposeUiTest.awaitBlocks(count: Int, date: LocalDate = today) {
        waitUntil(timeoutMillis = 5_000) {
            exists(calendarDayTag(date)) && day(date).blocks.size == count
        }
    }

    private fun ComposeUiTest.exists(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.grid() = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().config

    /** Верхняя минута сетки: начало сетки плюс прокрутка. */
    private fun ComposeUiTest.topMinute(): Float {
        val config = grid()
        val scrolledDp = config[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        return config[CalendarHours].startMinute + scrolledDp * 60f / config[CalendarHour]
    }

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.5f, "$what: ждали $expected, получили $actual")

    /** Нажать в колонке дня на точку [x], [y] dp от её левого и верхнего края. */
    private fun ComposeUiTest.tapDay(x: Float, y: Float, date: LocalDate = today) {
        onNodeWithTag(calendarDayTag(date)).performTouchInput { click(Offset(x.dp.toPx(), y.dp.toPx())) }
    }

    @Test
    fun opensTodayAtNowMinusHourUpToEndOfWork() = runComposeUiTest {
        openCalendar()
        onNodeWithText(DateFormat.monthYear(today)).assertIsDisplayed()
        onNodeWithTag(weekDayTag(today)).assertIsSelected()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 19))).assertIsDisplayed()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 25))).assertIsDisplayed()
        assertEquals(GridHours(7, 23), grid()[CalendarHours])
        assertEquals(64f, grid()[CalendarHour])
        // В 15:40 «сейчас − 1 час» упирается в конец часов работы: виден 11:00–23:00.
        near(660f, topMinute(), "Верх сетки")
    }

    @Test
    fun threeAtOneTimeShareWidth() = runComposeUiTest {
        training("16:00", 50, "Белова Ева")
        training("16:00", 50, "Гусев Борис")
        training("16:00", 50, "Ильин Глеб")
        openCalendar()
        awaitBlocks(3)

        // Проверка шага: колонка дня 412 − 48 = 364 dp, без отступов 8 dp по краям — 348, каждой по 116.
        val blocks = day().blocks.sortedBy { it.x }
        blocks.forEach { near(116f, it.width, "Ширина блока") }
        assertEquals(listOf(8f, 124f, 240f), blocks.map { it.x })
        assertEquals(setOf(s("Белова_Е."), s("Гусев_Б."), s("Ильин_Г.")), blocks.map { it.lines.first() }.toSet())
        // 50 минут при часе 64 dp — 53 dp: имя и «начало · место».
        assertTrue(blocks.all { it.rows == BlockRows.TWO && it.lines[1] == "16:00 · ББ" })
    }

    @Test
    fun blockLinesByHeightAndCancelled() = runComposeUiTest {
        training("12:00", 60, "Морозов Даниил")
        training("14:00", 30, "Белова Ева", type = "Груд")
        training("16:00", 50, "Зайцева Мария", status = TrainingStatus.CANCELLED)
        openCalendar()
        awaitBlocks(3)

        val (hour, half, cancelled) = day().blocks.sortedBy { it.y }
        assertEquals(listOf(s("Морозов_Д."), "12:00–13:00", "ПТ · ББ"), hour.lines)
        assertEquals(listOf(s("Белова_Е., 14:00")), half.lines)
        assertEquals(BlockRows.ONE, half.rows)
        assertTrue(cancelled.cancelled)
        assertEquals(listOf(s("Зайцева_М."), "16:00 · ББ"), cancelled.lines)
        // Во всю ширину: наложений нет.
        assertTrue(day().blocks.all { it.x == 8f && abs(it.width - 348f) < 0.5f })
    }

    @Test
    fun fiveAtOneTimeShowThreeAndMoreWithList() = runComposeUiTest {
        listOf("Ткаченко Роман", "Яковлева Софья", "Захаров Никита", "Лаптева Алина", "Егорова Лиза").forEach { training("16:00", 50, it) }
        openCalendar()
        awaitBlocks(3)

        val more = day().more.single()
        assertEquals("+2", more.label)
        // Четыре колонки по 87 dp: три блока и «+2» в последней.
        near(8f + 3 * 87f, more.x, "Место метки")
        near(87f, more.width, "Ширина метки")

        tapDay(more.x + more.width / 2, more.y + more.height / 2)
        onNodeWithTag(PANEL_TAG).assertIsDisplayed()
        onNodeWithText(s("Ещё 2_тренировки")).assertIsDisplayed()
        // Скрыты последние две: те же начало и длительность — порядок записи.
        onAllNodesWithText("16:00–16:50 · ББ").assertCountEquals(2)
        onNodeWithText(s("Лаптева_А.")).assertIsDisplayed()
        onNodeWithText(s("Егорова_Л.")).performClick()
        onNodeWithTag(PANEL_TAG).assertDoesNotExist()
        onNodeWithText(TRAINING_CARD_LATER).assertIsDisplayed()
    }

    @Test
    fun tapOnBlockSaysCardLater() = runComposeUiTest {
        training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()
        tapDay(block.x + 20f, block.y + 20f)
        onNodeWithText(TRAINING_CARD_LATER).assertIsDisplayed()
    }

    @Test
    fun swipeWeekStripAndTodayChangeDay() = runComposeUiTest {
        training("16:00", 50, "Белова Ева", date = LocalDate(2026, 10, 21))
        openCalendar()

        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 21))).assertIsSelected()
        awaitBlocks(1, LocalDate(2026, 10, 21))

        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 19))).performClick()
        waitForIdle()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 19))).assertIsSelected()

        onNodeWithContentDescription(TODAY_LABEL).performClick()
        waitForIdle()
        onNodeWithTag(weekDayTag(today)).assertIsSelected()

        // С воскресенья свайп ведёт в понедельник следующей недели: строка недели — уже 26.10–01.11.
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 25))).performClick()
        waitForIdle()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 26))).assertIsSelected()
        onNodeWithTag(weekDayTag(LocalDate(2026, 11, 1))).assertIsDisplayed()
        onNodeWithTag(weekDayTag(today)).assertDoesNotExist()
    }

    @Test
    fun scrollStaysInsideGrid() = runComposeUiTest {
        openCalendar()
        val bottom = topMinute()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(topMinute() < bottom, "Прокрутка вверх, к утру")
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        waitForIdle()
        near(420f, topMinute(), "Начало сетки")
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeUp(durationMillis = 100) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeUp(durationMillis = 100) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeUp(durationMillis = 100) }
        waitForIdle()
        // Дальше конца часов работы не уезжает.
        near(660f, topMinute(), "Конец сетки")
    }

    @Test
    fun pinchChangesHourAndRemembersIt() = runComposeUiTest {
        openCalendar()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(0f, 40f), end0 = center - Offset(0f, 200f),
                start1 = center + Offset(0f, 40f), end1 = center + Offset(0f, 200f),
            )
        }
        waitForIdle()
        // Втрое и больше — упирается в 160 dp.
        assertEquals(160f, grid()[CalendarHour])
        assertEquals(160f, prefs.float(DevicePrefs.CALENDAR_HOUR))
    }

    @Test
    fun rememberedHourOnOpen() = runComposeUiTest {
        prefs.putFloat(DevicePrefs.CALENDAR_HOUR, 100f)
        training("16:00", 30, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        assertEquals(100f, grid()[CalendarHour])
        // 30 минут при часе 100 dp — 50 dp: уже две строки.
        assertEquals(BlockRows.TWO, day().blocks.single().rows)
    }

    @Test
    fun earlyTrainingWidensGrid() = runComposeUiTest {
        training("06:30", 45, "Гусев Борис")
        openCalendar()
        awaitBlocks(1)
        waitUntil(timeoutMillis = 5_000) { grid()[CalendarHours] == GridHours(6, 23) }
    }

    @Test
    fun unfoldKeepsDayAndScroll() = runComposeUiTest {
        var width by mutableStateOf(folded)
        training("16:00", 50, "Белова Ева", date = LocalDate(2026, 10, 22))
        training("16:00", 50, "Гусев Борис", date = LocalDate(2026, 10, 22))
        openCalendar { width }
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 22))).performClick()
        waitForIdle()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        waitForIdle()
        near(420f, topMinute(), "Начало сетки")
        awaitBlocks(2, LocalDate(2026, 10, 22))

        width = unfolded
        waitForIdle()
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 22))).assertIsSelected()
        near(420f, topMinute(), "Начало сетки после разложения")
        // Блоки разложены по новой ширине: две колонки поровну.
        val dayWidth = onNodeWithTag(calendarDayTag(LocalDate(2026, 10, 22))).fetchSemanticsNode().size.width / density.density
        day(LocalDate(2026, 10, 22)).blocks.forEach { near((dayWidth - 16f) / 2, it.width, "Ширина блока на разложенном") }
    }

    @Test
    fun sampleDayFromPlus() = runComposeUiTest {
        openCalendar()
        onNodeWithTag(FAB_TAG).performClick()
        onNodeWithText(NEW_TRAINING_LATER).assertIsDisplayed()
        onNodeWithText(SAMPLE_ON).performClick()
        // 19 тренировок примера: пять на 20:00 — это три блока и «+2».
        awaitBlocks(17)
        assertEquals(listOf(2), day().more.map { it.ids.size })
        assertEquals(GridHours(6, 23), grid()[CalendarHours])

        onNodeWithTag(FAB_TAG).performClick()
        onNodeWithText(SAMPLE_OFF).performClick()
        awaitBlocks(0)
    }
}
