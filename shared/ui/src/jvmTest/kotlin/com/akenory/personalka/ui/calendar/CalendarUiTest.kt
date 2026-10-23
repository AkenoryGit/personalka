package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.calendar.BlockRows
import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.calendar.CalendarGrid
import com.akenory.personalka.domain.calendar.GridHours
import com.akenory.personalka.domain.calendar.MonthDay
import com.akenory.personalka.domain.calendar.MonthDot
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.shell.FAB_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.theme.LightColors
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.training.FORM_TIME_TAG
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import com.akenory.personalka.ui.training.TRAINING_FORM_TAG
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * «Календарь»: вид «День» (шаги 1.9–1.11), «Несколько дней», шапка и фильтр мест (шаг 1.12), «Месяц», «Список» и мини-календарь (шаг 1.12а), ТЗ 5.3. «Сейчас» — вт 20.10.2026 15:40. Тренировки пишутся через DAO,
 * форма тренировки (1.13) — в `TrainingFormUiTest`. Окно тестов на компьютере — 1024 × 768, поэтому экран ниже, чем у Fold, и сетке
 * остаётся 768 − панель 72 − шапка 56 − неделя 64 = 576 dp; что видно при той же высоте, что у Fold, —
 * в `CalendarGridTest`.
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
        overRestriction: Boolean = false,
        fact: String? = null,
        charged: Boolean = false,
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
                overRestriction = overRestriction,
                fact = fact,
            ),
        )
        names.forEach { name ->
            val (last, first) = name.split(' ')
            val client = clients.create(ClientDraft(last, first))
            val participation = ParticipationEntity(Ids.newId(millis++), SyncFields.new(coach, millis++), id, client)
            db.trainingDao().upsertParticipation(if (charged) participation.copy(status = ParticipationStatus.CANCELLED_CHARGED) else participation)
        }
        id
    }

    /** Запрет клуба «Групповая тренировка» по вт и чт. */
    private fun restriction(place: String, from: String, to: String): String = runBlocking {
        fun time(text: String) = text.split(':').map { it.toInt() }.let { (h, m) -> LocalTime(h, m) }
        val coach = db.coachDao().currentCoach()!!.id
        val id = Ids.newId(millis++)
        db.scheduleDao().upsertBlock(
            ScheduleBlockEntity(
                id = id,
                sync = SyncFields.new(coach, millis++),
                scope = Scope.CLUB,
                locationId = settings.places.first().single { it.shortName == place }.id,
                reason = "Групповая тренировка",
                weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                startTime = time(from),
                endTime = time(to),
                validFrom = LocalDate(2026, 9, 1),
            ),
        )
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

    /** Ждать, пока в дне будет [count] зон. */
    private fun ComposeUiTest.awaitZones(count: Int, date: LocalDate = today) {
        waitUntil(timeoutMillis = 5_000) { exists(calendarDayTag(date)) && day(date).zones.size == count }
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

    /** Высота сетки на экране, dp. */
    private fun ComposeUiTest.gridHeight(): Float = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().size.height / density.density

    /** Верх сетки при открытии в 15:40 — по правилу `shared/domain` для этой высоты экрана. */
    private fun ComposeUiTest.openTop(): Float = CalendarGrid.open(TestData.now.time, GridHours(7, 23), gridHeight()).top

    /**
     * Нажать на день в точку [x], [y] dp от левого и верхнего края всей колонки дня. Колонка выше экрана и сдвинута
     * прокруткой, поэтому нажатие идёт в сетку: колонка времени 48 dp слева, прокрутка вычитается.
     */
    private fun ComposeUiTest.tapDay(x: Float, y: Float) {
        val scrolled = grid()[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { click(Offset((48f + x).dp.toPx(), (y - scrolled).dp.toPx())) }
    }

    /** Точка дня [x], [y] dp от левого и верхнего края всей колонки дня — на сетке, px (как в [tapDay]). */
    private fun ComposeUiTest.gridPoint(x: Float, y: Float): Offset {
        val scrolled = grid()[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        return with(density) { Offset((48f + x).dp.toPx(), (y - scrolled).dp.toPx()) }
    }

    /** Длительное нажатие на точку дня. */
    private fun ComposeUiTest.holdDay(x: Float, y: Float) {
        val point = gridPoint(x, y)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { longClick(point) }
    }

    /**
     * Удержать точку дня [x], [y] 600 мс и вести палец на [dx], [dy] dp — четырьмя шагами, как настоящий;
     * [release] — отпустить.
     */
    private fun ComposeUiTest.dragDay(x: Float, y: Float, dx: Float, dy: Float, release: Boolean = true) {
        val point = gridPoint(x, y)
        val shift = with(density) { Offset(dx.dp.toPx(), dy.dp.toPx()) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput {
            down(point)
            advanceEventTime(600)
            for (i in 1..4) moveTo(point + shift * (i / 4f))
            if (release) up()
        }
    }

    /** Время → dp от верха колонки дня при часе 64 dp; сетка — с [fromHour]. */
    private fun yOf(time: String, fromHour: Int = 7): Float {
        val (h, m) = time.split(':').map { it.toInt() }
        return (h * 60 + m - fromHour * 60) * 64f / 60f
    }

    private fun at(date: LocalDate, time: String): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(date, LocalTime(h, m)) }

    private fun startOf(id: String): LocalDateTime = runBlocking { db.trainingDao().training(id)!!.start }

    /** Выбранные дни строки недели — для понятного сообщения, если тест упадёт. */
    private fun ComposeUiTest.selectedDays(): List<String> =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter { it.startsWith("week-") }

    private fun ComposeUiTest.assertSelected(date: LocalDate, what: String) {
        val pager = onNodeWithTag(CALENDAR_PAGER_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        assertEquals(listOf(weekDayTag(date)), selectedDays(), "$what; листание: ${pager?.value?.invoke()} из ${pager?.maxValue?.invoke()}")
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
        // В 15:40 «сейчас − 1 час» упирается в конец часов работы: в 576 dp — 14:00–23:00, на Fold — 11:00–23:00.
        near(openTop(), topMinute(), "Верх сетки")
        near(840f, openTop(), "Верх сетки в окне теста")
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
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        onNodeWithText(s("Егорова_Л.")).assertIsDisplayed()
    }

    @Test
    fun tapOnBlockOpensCard() = runComposeUiTest {
        // Нажатие на блок — карточка тренировки (шаг 1.14), подробно — TrainingCardUiTest.
        training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()
        tapDay(block.x + 20f, block.y + 20f)
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        onNodeWithText(s("вт_20.10 · 16:00–16:50 · 50_мин")).assertIsDisplayed()
        onNodeWithText(s("через 20_мин")).assertIsDisplayed()
    }

    @Test
    fun swipeWeekStripAndTodayChangeDay() = runComposeUiTest {
        training("16:00", 50, "Белова Ева", date = LocalDate(2026, 10, 21))
        openCalendar()

        onNodeWithTag(CALENDAR_PAGER_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 21), "Свайп влево — следующий день")
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
        onNodeWithTag(CALENDAR_PAGER_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 26), "Свайп с воскресенья")
        onNodeWithTag(weekDayTag(LocalDate(2026, 11, 1))).assertIsDisplayed()
        onNodeWithTag(weekDayTag(today)).assertDoesNotExist()
    }

    @Test
    fun swipeOnDaysRowMovesByWeek() = runComposeUiTest {
        openCalendar()
        // Свайп по строке недели влево — тот же вторник неделей позже, строка — уже 26.10–01.11.
        onNodeWithTag(DAYS_ROW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 27), "Свайп по строке недели влево")
        onNodeWithTag(weekDayTag(LocalDate(2026, 11, 1))).assertIsDisplayed()
        // Вправо — неделей раньше: дважды — 13.10.
        onNodeWithTag(DAYS_ROW_TAG).performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithTag(DAYS_ROW_TAG).performTouchInput { swipeRight() }
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 13), "Два свайпа вправо")
        // Короткое движение — не свайп, а нажатие на день остаётся нажатием.
        onNodeWithTag(DAYS_ROW_TAG).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 30f) }
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 13), "Короткое движение")
        onNodeWithTag(weekDayTag(LocalDate(2026, 10, 15))).performClick()
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 15), "Нажатие на день")

        // В «3 днях» — строка над колонками листает так же: первая колонка — четверг неделей позже.
        chooseView("3_дня")
        onNodeWithTag(dayHeaderTag(LocalDate(2026, 10, 15))).assertIsDisplayed()
        onNodeWithTag(DAYS_ROW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithTag(dayHeaderTag(LocalDate(2026, 10, 22))).assertIsDisplayed()
        onNodeWithTag(dayHeaderTag(LocalDate(2026, 10, 15))).assertDoesNotExist()
    }

    @Test
    fun scrollStaysInsideGrid() = runComposeUiTest {
        openCalendar()
        val bottom = topMinute()
        near(openTop(), bottom, "Верх при открытии")
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
        // Дальше конца часов работы не уезжает: внизу экрана — 23:00.
        near(1380f - gridHeight() * 60f / 64f, topMinute(), "Конец сетки")
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

    /** Ширина колонки дня и всей сетки с колонкой времени, dp. */
    private fun ComposeUiTest.columnWidth(date: LocalDate): Float = onNodeWithTag(calendarDayTag(date)).fetchSemanticsNode().size.width / density.density

    private fun ComposeUiTest.gridWidth(): Float = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().size.width / density.density

    private fun ComposeUiTest.chooseView(label: String) {
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        onNodeWithText(s(label)).performClick()
        waitForIdle()
    }

    @Test
    fun viewRememberedForEachFoldState() = runComposeUiTest {
        var width by mutableStateOf(folded)
        val thursday = LocalDate(2026, 10, 22)
        training("16:00", 50, "Белова Ева", date = thursday)
        training("16:00", 50, "Гусев Борис", date = thursday)
        openCalendar { width }
        onNodeWithTag(weekDayTag(thursday)).performClick()
        waitForIdle()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { swipeDown(durationMillis = 100) }
        waitForIdle()
        near(420f, topMinute(), "Начало сетки")
        awaitBlocks(2, thursday)
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals("День")

        // Разложили: по умолчанию «3 дня» с того же четверга, прокрутка на месте.
        width = unfolded
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals(s("3_дня"))
        onNodeWithTag(weekDayTag(thursday)).assertDoesNotExist()
        listOf(thursday, LocalDate(2026, 10, 23), LocalDate(2026, 10, 24)).forEach { date ->
            onNodeWithTag(dayHeaderTag(date)).assertIsDisplayed()
            onNodeWithTag(calendarDayTag(date)).assertIsDisplayed()
        }
        near(420f, topMinute(), "Начало сетки после разложения")
        val column = columnWidth(thursday)
        near((gridWidth() - 48f) / 3, column, "Колонка трёх дней")
        // Блоки разложены по ширине колонки: две поровну.
        day(thursday).blocks.forEach { near((column - 16f) / 2, it.width, "Ширина блока в колонке") }

        // На разложенном выбрали «5 дней» — запомнено для разложенного.
        chooseView("5_дней")
        near((gridWidth() - 48f) / 5, columnWidth(thursday), "Колонка пяти дней")
        onNodeWithTag(calendarDayTag(LocalDate(2026, 10, 26))).assertIsDisplayed()
        assertEquals("days5", prefs.string(DevicePrefs.CALENDAR_VIEW_WIDE))

        // Проверка шага: сложили — снова «День» на том же четверге; разложили — снова «5 дней».
        width = folded
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals("День")
        onNodeWithTag(weekDayTag(thursday)).assertIsSelected()
        near(420f, topMinute(), "Начало сетки после сложения")
        width = unfolded
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals(s("5_дней"))
        near((gridWidth() - 48f) / 5, columnWidth(thursday), "Колонка пяти дней после разложения")
    }

    @Test
    fun threeDaysFoldedDragToNextColumnAndOpenDay() = runComposeUiTest {
        val id = training("16:00", 50, "Белова Ева")
        val wednesday = LocalDate(2026, 10, 21)
        openCalendar()
        chooseView("3_дня")
        assertEquals("days3", prefs.string(DevicePrefs.CALENDAR_VIEW_FOLDED))
        awaitBlocks(1)
        // Макет «3 дня · сложенный»: колонки по (412 − 48) / 3 dp, вместо строки недели — дни над колонками.
        val column = columnWidth(today)
        near((412f - 48f) / 3, column, "Колонка трёх дней на сложенном")
        onNodeWithTag(weekDayTag(today)).assertDoesNotExist()
        onNodeWithTag(dayHeaderTag(LocalDate(2026, 10, 22))).assertIsDisplayed()
        val block = day().blocks.single()

        // Проверка шага: блок повели в соседнюю колонку и вниз — среда 17:15, без задержки у края.
        dragDay(block.x + 20f, block.y + 20f, column, yOf("17:17") - yOf("16:00"), release = false)
        onNodeWithText(s("Ср_21.10 · 17:15–18:05")).assertIsDisplayed()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { up() }
        awaitNode(hasText(s("Перенесено на ср_21.10, 17:15")))
        assertEquals(at(wednesday, "17:15"), startOf(id))
        awaitBlocks(1, wednesday)
        // Колонки не листались: первая — по-прежнему сегодня.
        onNodeWithTag(dayHeaderTag(today)).assertIsDisplayed()

        // Нажатие на день над колонкой — этот день в виде «День».
        onNodeWithTag(dayHeaderTag(wednesday)).performClick()
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals("День")
        assertSelected(wednesday, "День из строки над колонками")
    }

    @Test
    fun placeFilterHidesOtherPlacesAndTheirRestrictions() = runComposeUiTest {
        var width by mutableStateOf(folded)
        restriction("ДБ", "17:00", "18:00")
        training("16:00", 50, "Белова Ева", place = "ББ")
        training("16:00", 50, "Орлов Максим", place = "ДБ")
        openCalendar { width }
        awaitBlocks(2)
        awaitZones(1)
        fun names() = day().blocks.map { it.lines.first() }

        // Сложенный: «Все места ▾» → «Детский бассейн» — остаются тренировка ДБ и запрет ДБ.
        onNodeWithTag(CALENDAR_PLACE_TAG).assertTextEquals(CalendarFormat.ALL_PLACES)
        onNodeWithTag(CALENDAR_PLACE_TAG).performClick()
        onNodeWithText("Детский бассейн").performClick()
        awaitBlocks(1)
        assertEquals(listOf(s("Орлов_М.")), names())
        assertEquals(1, day().zones.size)
        onNodeWithTag(CALENDAR_PLACE_TAG).assertTextEquals("ДБ")

        // «Большой бассейн» — запрет ДБ скрыт вместе с её тренировкой.
        onNodeWithTag(CALENDAR_PLACE_TAG).performClick()
        onNodeWithText("Большой бассейн").performClick()
        awaitBlocks(1)
        assertEquals(listOf(s("Белова_Е.")), names())
        awaitZones(0)

        // Разложили: фильтр тот же, теперь чипами; «Все» возвращает всё.
        width = unfolded
        waitForIdle()
        onNodeWithTag(CALENDAR_PLACE_TAG).assertDoesNotExist()
        onNodeWithText("ББ").assertIsSelected()
        awaitBlocks(1)
        onNodeWithText(CalendarFormat.ALL).performClick()
        awaitBlocks(2)
        awaitZones(1)
    }

    @Test
    fun hideCancelledFromViewMenu() = runComposeUiTest {
        training("10:00", 50, "Зайцева Мария", status = TrainingStatus.CANCELLED)
        training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(2)

        // Показ отменённых — в меню вида, по умолчанию включён; выключение помнит этот телефон.
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        onNodeWithText(SHOW_CANCELLED).assertIsOn()
        onNodeWithText(SHOW_CANCELLED).performClick()
        awaitBlocks(1)
        assertEquals(listOf(s("Белова_Е.")), day().blocks.map { it.lines.first() })
        assertEquals(false, prefs.boolean(DevicePrefs.CALENDAR_SHOW_CANCELLED))

        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        onNodeWithText(SHOW_CANCELLED).assertIsOff()
        onNodeWithText(SHOW_CANCELLED).performClick()
        awaitBlocks(2)
    }

    @Test
    fun restrictionVisibleUnderTraining() = runComposeUiTest {
        restriction("ДБ", "17:00", "18:00")
        training("17:00", 50, "Орлов Максим", place = "ДБ", overRestriction = true)
        openCalendar()
        awaitBlocks(1)
        awaitZones(1)

        // Проверка шага: запрет 17:00–18:00 лежит под тренировкой 17:00 — та же высота, полоска у края.
        val zone = day().zones.single()
        val block = day().blocks.single()
        assertEquals(ZoneKind.RESTRICTION, zone.kind)
        assertEquals(s("ДБ · групповая тренировка"), zone.label)
        assertEquals(0, zone.lane)
        near(640f, zone.y, "Верх запрета")
        near(64f, zone.height, "Высота запрета")
        near(zone.y, block.y, "Тренировка на запрете")
        assertEquals(listOf(CalendarBadge.OVER_RESTRICTION), block.badges)

        // На картинке: у края — полоска цвета рамок, справа от блока и под ним — штриховка, а сам блок её закрывает.
        val image = onNodeWithTag(CALENDAR_GRID_TAG).captureToImage().toPixelMap()
        val d = density.density
        val scrolled = grid()[SemanticsProperties.VerticalScrollAxisRange].value() / d
        fun pixel(x: Float, y: Float): Color = image[((48f + x) * d).toInt(), ((y - scrolled) * d).toInt()]
        fun close(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) < 0.04f
        assertTrue(close(LightColors.outline, pixel(3.5f, zone.y + 20f)), "Полоска запрета: ${pixel(3.5f, zone.y + 20f)}")
        // Справа от блока, между линиями сетки, — хоть одна точка штриховки на фоне.
        val margin = (357..363).flatMap { x -> (zone.y.toInt() + 4..zone.y.toInt() + 28).map { y -> pixel(x.toFloat(), y.toFloat()) } }
        assertTrue(margin.any { !close(LightColors.surface, it) }, "Штриховки справа от блока нет")
        // Низ блока без текста — ровный цвет блока: штриховка под ним. ПТ в ДБ — розовый светлого оттенка (шаг 1.13в).
        val blockColor = PlaceColor.PINK.light.block(1)
        val inside = (150..330 step 10).flatMap { x -> listOf(pixel(x.toFloat(), block.y + block.height - 6f), pixel(x.toFloat(), block.y + block.height - 4f)) }
        assertTrue(inside.all { close(blockColor, it) }, "Штриховка поверх блока: ${inside.firstOrNull { !close(blockColor, it) }}")
    }

    @Test
    fun tapOnRestrictionShowsItAndSkipsDay() = runComposeUiTest {
        restriction("ДБ", "17:00", "18:00")
        training("17:00", 50, "Орлов Максим", place = "ДБ", overRestriction = true)
        openCalendar()
        awaitZones(1)
        val zone = day().zones.single()

        // Нажатие на полоску у края — окно запрета.
        tapDay(2f, zone.y + 30f)
        onNodeWithTag(PANEL_TAG).assertIsDisplayed()
        onNodeWithText(ZoneFormat.RESTRICTION).assertIsDisplayed()
        onNodeWithText("ДБ 17:00–18:00 — групповая тренировка").assertIsDisplayed()
        onNodeWithText("Каждый вт и чт").assertIsDisplayed()
        // Запрет клуба меняет только администратор (этап 2) — «Изменить» нет.
        onNodeWithText(EDIT_ZONE).assertDoesNotExist()

        // «Пропустить этот день»: запрет исчезает, флаг тренировки пересчитан — треугольника нет.
        onNodeWithText(SKIP_DAY).performClick()
        onNodeWithTag(PANEL_TAG).assertDoesNotExist()
        onNodeWithText(ZoneFormat.skipped(today)).assertIsDisplayed()
        awaitZones(0)
        waitUntil(timeoutMillis = 5_000) { day().blocks.single().badges.isEmpty() }

        // «Отменить» — запрет и треугольник на месте.
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        awaitZones(1)
        waitUntil(timeoutMillis = 5_000) { day().blocks.single().badges == listOf(CalendarBadge.OVER_RESTRICTION) }
    }

    @Test
    fun dutyIsFillWithTrainingsOverIt() = runComposeUiTest {
        training("11:00", 240, type = "Деж")
        training("06:00", 60, type = "Деж")
        training("14:00", 50, "Никитин Артём", status = TrainingStatus.DONE, fact = "Кроль 8 × 25 м")
        openCalendar()
        awaitBlocks(1)
        awaitZones(2)

        // Дежурства — не блоки, а заливка; раннее расширяет сетку, как тренировка.
        assertTrue(day().zones.all { it.kind == ZoneKind.DUTY })
        waitUntil(timeoutMillis = 5_000) { grid()[CalendarHours] == GridHours(6, 23) }
        assertEquals(listOf(CalendarBadge.OVER_DUTY), day().blocks.single().badges)

        // Нажатие на видимую часть дежурства — справа от блока. Пропустить у дежурства нечего.
        val duty = day().zones.single { it.y > 0f && it.height > 200f }
        tapDay(360f, duty.y + duty.height - 20f)
        onNodeWithText("Дежурство").assertIsDisplayed()
        onNodeWithText("ББ 11:00–15:00 — дежурство").assertIsDisplayed()
        onNodeWithText(SKIP_DAY).assertDoesNotExist()

        // «Изменить» — форма дежурства (шаг 1.13а) с его временем.
        onNodeWithText(EDIT_ZONE).performClick()
        awaitNode(hasText(TrainingFormat.EDIT_DUTY_TITLE))
        onNodeWithText("с 11:00").assertIsDisplayed()
        onNodeWithText("до 15:00").assertIsDisplayed()
    }

    @Test
    fun badgesByHeightAndImportance() = runComposeUiTest {
        training("09:00", 60, "Морозов Даниил", status = TrainingStatus.DONE)
        training("11:00", 50, "Ковалёв Иван", status = TrainingStatus.DONE)
        training("13:00", 50, "Зайцева Мария", status = TrainingStatus.CANCELLED, charged = true)
        training("15:00", 50, "Белова Ева", status = TrainingStatus.DONE, fact = "Кроль 8 × 25 м")
        openCalendar()
        awaitBlocks(4)

        // 60 минут — до двух значков: точка дневника и галочка; 50 минут — один, самый важный.
        assertEquals(
            listOf(
                listOf(CalendarBadge.NO_DIARY, CalendarBadge.DONE),
                listOf(CalendarBadge.NO_DIARY),
                listOf(CalendarBadge.CHARGED),
                listOf(CalendarBadge.DONE),
            ),
            day().blocks.sortedBy { it.y }.map { it.badges },
        )
    }

    @Test
    fun holdOnEmptyPlaceCreatesAtStepBelow() = runComposeUiTest {
        openCalendar()
        // ТЗ 5.3: время под пальцем, вниз до шага — 17:23 даёт 17:15; форма новой тренировки на это время.
        holdDay(150f, yOf("17:23") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        awaitNode(hasText("17:15–18:05"))
        onNodeWithTag(FORM_TIME_TAG).assertIsDisplayed()
    }

    @Test
    fun holdOnEmptyPlaceDragsDashedBlockThenOpensForm() = runComposeUiTest {
        openCalendar()
        // Удержание — пунктирный блок новой тренировки с подсказкой; формы ещё нет.
        dragDay(150f, yOf("17:23") + 0.5f, 0f, 0f, release = false)
        onNodeWithContentDescription(NEW_BLOCK).assertIsDisplayed()
        onNodeWithText(s("Вт_20.10 · 17:15–18:05")).assertIsDisplayed()
        onNodeWithText(TrainingFormat.NEW_TITLE).assertDoesNotExist()

        // Блок повели вниз до 18:32 — начало 18:30 с привязкой к шагу; отпустили — форма на это время.
        val point = gridPoint(150f, yOf("18:40") + 0.5f)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput {
            moveTo(point)
            up()
        }
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        awaitNode(hasText("18:30–19:20"))
        onNodeWithContentDescription(NEW_BLOCK).assertDoesNotExist()
    }

    @Test
    fun holdOnTrainingOpensMenu() = runComposeUiTest {
        val id = training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()

        // Без сдвига — меню: «Новая тренировка на это время», «Отменить», «Копировать» (макет «Удержание на тренировке»).
        holdDay(block.x + 40f, block.y + 20f)
        onNodeWithText(NEW_AT_THIS_TIME).assertIsDisplayed()
        onNodeWithText(CANCEL_TRAINING).assertIsDisplayed()
        onNodeWithText(COPY_TRAINING).performClick()
        awaitNode(hasText(COPIED))
        assertEquals(50, TestData.calendarOf(settings).copied.value?.durationMin)

        holdDay(block.x + 40f, block.y + 20f)
        onNodeWithText(NEW_AT_THIS_TIME).performClick()
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        awaitNode(hasText("16:00–16:50"))
        assertEquals(at(today, "16:00"), startOf(id))
    }

    @Test
    fun dragSnapsToStepWithHintAndUndo() = runComposeUiTest {
        val id = training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()

        // Проверка шага: верх блока дотянули до 17:17 — начало 17:15, над пальцем подсказка с новым временем.
        dragDay(block.x + 40f, block.y + 20f, 0f, yOf("17:17") - yOf("16:00"), release = false)
        onNodeWithText(s("Вт_20.10 · 17:15–18:05")).assertIsDisplayed()
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { up() }
        // Сообщение — после записи в базу, из её потока: дождаться его.
        awaitNode(hasText(s("Перенесено на вт_20.10, 17:15")))
        assertEquals(at(today, "17:15"), startOf(id))

        // «Отменить» — на прежнем месте.
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { startOf(id) == at(today, "16:00") }
    }

    @Test
    fun dragToRightEdgeMovesToNextDay() = runComposeUiTest {
        val id = training("16:00", 50, "Белова Ева")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()
        val y = block.y + 20f + yOf("17:17") - yOf("16:00")

        // Проверка шага: палец у правого края 600 мс — листается среда, блок остаётся под пальцем и встаёт на 17:15.
        dragDay(block.x + 40f, block.y + 20f, 0f, yOf("17:17") - yOf("16:00"), release = false)
        val edge = gridPoint(358f, y)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { moveTo(edge) }
        mainClock.advanceTimeBy(800)
        waitForIdle()
        assertSelected(LocalDate(2026, 10, 21), "Палец у правого края")
        val middle = gridPoint(180f, y)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput {
            moveTo(middle)
            up()
        }
        awaitNode(hasText(s("Перенесено на ср_21.10, 17:15")))
        assertEquals(at(LocalDate(2026, 10, 21), "17:15"), startOf(id))
    }

    @Test
    fun dragUnderRestrictionAsksFirst() = runComposeUiTest {
        restriction("ДБ", "17:00", "18:00")
        val id = training("16:00", 50, "Орлов Максим", place = "ДБ")
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()
        val dy = yOf("17:15") - yOf("16:00")

        // ТЗ 4.5: под запрет того же места — сначала окно; «Отмена» оставляет тренировку на месте.
        dragDay(block.x + 40f, block.y + 20f, 0f, dy)
        awaitNode(hasText(ZoneFormat.CONFLICT_QUESTION))
        onNodeWithText("Детский бассейн 17:00–18:00: групповая тренировка").assertIsDisplayed()
        onNodeWithText(ZoneFormat.CONFLICT_CANCEL).performClick()
        assertEquals(at(today, "16:00"), startOf(id))

        // «Поставить» — перенесено с флагом «поверх запрета».
        dragDay(block.x + 40f, block.y + 20f, 0f, dy)
        awaitNode(hasText(ZoneFormat.CONFLICT_QUESTION))
        onNodeWithText(ZoneFormat.CONFLICT_PLACE).performClick()
        waitUntil(timeoutMillis = 5_000) { startOf(id) == at(today, "17:15") }
        assertTrue(runBlocking { db.trainingDao().training(id)!!.overRestriction })
        awaitNode(hasText(s("Перенесено на вт_20.10, 17:15")))
    }

    @Test
    fun cancelledHasMenuButDoesNotMove() = runComposeUiTest {
        val id = training("16:00", 50, "Зайцева Мария", status = TrainingStatus.CANCELLED)
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()

        // Отменённую не тащат — сначала «Вернуть» (5.4); меню есть, но без «Отменить».
        dragDay(block.x + 40f, block.y + 20f, 0f, 80f)
        waitForIdle()
        assertEquals(at(today, "16:00"), startOf(id))
        holdDay(block.x + 40f, block.y + 20f)
        onNodeWithText(COPY_TRAINING).assertIsDisplayed()
        onNodeWithText(CANCEL_TRAINING).assertDoesNotExist()
    }

    // «Месяц», «Список» и мини-календарь (шаг 1.12а).

    private fun ComposeUiTest.summary(date: LocalDate): MonthDay = onNodeWithTag(monthDayTag(date)).fetchSemanticsNode().config[MonthSummary]

    /** Ждать, пока в клетке месяца будет [count] тренировок. */
    private fun ComposeUiTest.awaitCount(date: LocalDate, count: Int) {
        waitUntil(timeoutMillis = 5_000) { exists(monthDayTag(date)) && summary(date).count == count }
    }

    /**
     * Клетка месяца выбрана — та, что на экране. Нажатая мышью теста клетка соседнего месяца остаётся в фокусе,
     * и пролистанная страница держит её в дереве за краем экрана (касание на телефоне фокус не ставит).
     */
    private fun ComposeUiTest.assertMonthSelected(date: LocalDate) {
        val shown = onAllNodes(hasTestTag(monthDayTag(date))).fetchSemanticsNodes().filter { it.boundsInRoot.left >= 0f && it.boundsInRoot.height > 0f }
        assertEquals(listOf(true), shown.map { it.config.getOrNull(SemanticsProperties.Selected) }, "Выбран $date")
    }

    private fun ComposeUiTest.top(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top / density.density

    private fun ComposeUiTest.left(tag: String): Float = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left / density.density

    @Test
    fun monthShowsCountDotsAndDayList() = runComposeUiTest {
        val morozov = training("08:00", 50, "Морозов Даниил", status = TrainingStatus.DONE)
        val split = training("09:00", 50, "Ковалёв Илья", "Ковалёва Мира", type = "Сплит")
        val zaitseva = training("10:00", 50, "Зайцева Мария", status = TrainingStatus.CANCELLED, charged = true)
        val belova = training("10:00", 30, "Белова Ева", type = "Груд", place = "ДБ")
        val duty = training("11:00", 240, type = "Деж")
        val thursday = LocalDate(2026, 10, 22)
        val orlov = training("16:00", 50, "Орлов Максим", place = "ДБ", date = thursday)
        openCalendar()
        chooseView("Месяц")
        assertEquals("month", prefs.string(DevicePrefs.CALENDAR_VIEW_FOLDED))
        onNodeWithText(s("Октябрь_2026")).assertIsDisplayed()

        // Макет «Месяц»: число — без отменённой и без дежурства; точки — цвет места оттенком типа, поровну — в порядке дня.
        awaitCount(today, 3)
        assertEquals(listOf(MonthDot("blue", 1), MonthDot("blue", 2), MonthDot("pink", 2)), summary(today).dots)
        assertEquals(MonthDay(1, listOf(MonthDot("pink", 1))), summary(thursday))
        assertEquals(MonthDay.EMPTY, summary(LocalDate(2026, 10, 21)))
        onNodeWithTag(monthDayTag(today)).assertIsSelected()
        // Дни соседних месяцев в первой и последней строке: октябрь 2026 — с пн 28.09 по вс 01.11.
        onNodeWithTag(monthDayTag(LocalDate(2026, 9, 28))).assertIsDisplayed()
        onNodeWithTag(monthDayTag(LocalDate(2026, 11, 1))).assertIsDisplayed()

        // Под сеткой — список выбранного дня: заголовок с числом, строки по времени, длинная на одно время — выше.
        onNodeWithTag(dayTitleTag(today)).assert(hasText(DateFormat.header(today))).assert(hasText(s("3_тренировки")))
        onNodeWithTag(calendarRowTag(split)).assert(hasText("Ковалёв / Ковалёва")).assert(hasText("Сплит · ББ"))
        onNodeWithTag(calendarRowTag(zaitseva)).assert(hasText("ПТ · ББ · со списанием"))
        onNodeWithTag(calendarRowTag(belova)).assert(hasText(s("Грудничковое · ДБ · 30_мин")))
        onNodeWithTag(calendarRowTag(duty)).assert(hasText("Дежурство")).assert(hasText(s("ББ · до_15:00")))
        val order = listOf(morozov, split, zaitseva, belova, duty).map { top(calendarRowTag(it)) }
        assertEquals(order.sorted(), order, "Строки по времени: $order")

        // Нажатие на другой день — его список.
        onNodeWithTag(monthDayTag(thursday)).performClick()
        waitForIdle()
        onNodeWithTag(monthDayTag(thursday)).assertIsSelected()
        onNodeWithTag(dayTitleTag(thursday)).assert(hasText(s("1_тренировка")))
        onNodeWithTag(calendarRowTag(orlov)).assert(hasText(s("Орлов_М.")))
        onNodeWithTag(calendarRowTag(morozov)).assertDoesNotExist()

        // Нажатие на заголовок дня — этот день в виде «День».
        onNodeWithTag(dayTitleTag(thursday)).performClick()
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals("День")
        assertSelected(thursday, "День из заголовка в месяце")
        awaitBlocks(1, thursday)
    }

    @Test
    fun dayChosenInMonthOpensInDayAndDays() = runComposeUiTest {
        val day28 = LocalDate(2026, 10, 28)
        openCalendar()
        chooseView("Месяц")
        onNodeWithTag(monthDayTag(day28)).performClick()
        waitForIdle()
        onNodeWithTag(dayTitleTag(day28)).assertIsDisplayed()

        // Проверка шага: день, выбранный в месяце, — первая колонка «3 дней» и выбранный день «Дня».
        chooseView("3_дня")
        near(left(CALENDAR_GRID_TAG) + 48f, left(dayHeaderTag(day28)), "Первая колонка — 28.10")
        onNodeWithTag(calendarDayTag(day28)).assertIsDisplayed()
        chooseView("День")
        assertSelected(day28, "День после месяца")

        // Обратно в месяц — выбран тот же день; свайп — следующий месяц, выбран тот же день в нём.
        chooseView("Месяц")
        onNodeWithTag(monthDayTag(day28)).assertIsSelected()
        onNodeWithTag(MONTH_PAGER_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        val november28 = LocalDate(2026, 11, 28)
        onNodeWithText(DateFormat.monthYear(november28)).assertIsDisplayed()
        onNodeWithTag(monthDayTag(november28)).assertIsSelected()
        chooseView("День")
        assertSelected(november28, "День после свайпа месяца")

        // Нажатие на день соседнего месяца открывает его месяц; «Сегодня» — снова октябрь и сегодня.
        chooseView("Месяц")
        val december1 = LocalDate(2026, 12, 1)
        onNodeWithTag(monthDayTag(december1)).performClick()
        waitForIdle()
        onNodeWithText(DateFormat.monthYear(december1)).assertIsDisplayed()
        assertMonthSelected(december1)
        onNodeWithContentDescription(TODAY_LABEL).performClick()
        waitForIdle()
        onNodeWithText(DateFormat.monthYear(today)).assertIsDisplayed()
        onNodeWithTag(monthDayTag(today)).assertIsSelected()
    }

    @Test
    fun monthOnFoldedAndUnfolded() = runComposeUiTest {
        var width by mutableStateOf(folded)
        val day28 = LocalDate(2026, 10, 28)
        openCalendar { width }
        chooseView("Месяц")
        onNodeWithTag(monthDayTag(day28)).performClick()
        waitForIdle()

        // Разложили: свой вид — «3 дня», первая колонка — день, выбранный в месяце.
        width = unfolded
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals(s("3_дня"))
        near(left(CALENDAR_GRID_TAG) + 48f, left(dayHeaderTag(day28)), "Первая колонка после разложения")

        // «Месяц» на разложенном: сетка слева на всю высоту, список дня — справа.
        chooseView("Месяц")
        onNodeWithTag(monthDayTag(day28)).assertIsSelected()
        onNodeWithTag(dayTitleTag(day28)).assertIsDisplayed()
        assertTrue(left(MONTH_DAY_LIST_TAG) > left(MONTH_PAGER_TAG) + 200f, "Список дня справа от месяца")

        // Сложили — снова свой «Месяц» с тем же днём.
        width = folded
        waitForIdle()
        onNodeWithTag(CALENDAR_VIEW_TAG).assertTextEquals("Месяц")
        onNodeWithTag(monthDayTag(day28)).assertIsSelected()
        assertTrue(top(MONTH_DAY_LIST_TAG) > top(MONTH_PAGER_TAG) + 300f, "Список дня под месяцем")
    }

    @Test
    fun agendaListsDaysFromSelected() = runComposeUiTest {
        val belova = training("16:00", 50, "Белова Ева")
        val monday = LocalDate(2026, 10, 19)
        training("16:00", 50, "Гусев Борис", date = monday)
        val friday = LocalDate(2026, 10, 23)
        training("09:00", 50, "Орлов Максим", place = "ДБ", date = friday)
        openCalendar()
        chooseView("Список")
        assertEquals("list", prefs.string(DevicePrefs.CALENDAR_VIEW_FOLDED))

        // С выбранного дня: сегодня, потом пятница; пустые дни не показываются, вчерашние — выше и ещё не загружены.
        awaitNode(hasTestTag(dayTitleTag(friday)))
        onNodeWithTag(dayTitleTag(today)).assert(hasText(s("1_тренировка")))
        onNodeWithTag(calendarRowTag(belova)).assert(hasText("ПТ · ББ"))
        onNodeWithTag(dayTitleTag(LocalDate(2026, 10, 21))).assertDoesNotExist()
        onNodeWithTag(dayTitleTag(monday)).assertDoesNotExist()
        assertTrue(top(dayTitleTag(today)) < top(dayTitleTag(friday)))

        // Потянули вниз у самого начала — подгрузились четыре недели раньше: понедельник выше.
        onNodeWithTag(AGENDA_TAG).performTouchInput { swipeDown() }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(AGENDA_TAG).performScrollToKey(agendaTitleKey(monday)) }.isSuccess }
        onNodeWithTag(dayTitleTag(monday)).assertIsDisplayed()

        // Мини-календарь: пятница — список с неё; «День» открывается на ней.
        onNodeWithTag(CALENDAR_MONTH_TAG).performClick()
        onNodeWithTag(miniDayTag(friday)).performClick()
        waitUntil(timeoutMillis = 5_000) { exists(dayTitleTag(friday)) && !exists(dayTitleTag(today)) }
        chooseView("День")
        assertSelected(friday, "День после списка")

        // Снова «Список» — с пятницы; «Сегодня» — с сегодня; нажатие на строку — карточка тренировки.
        chooseView("Список")
        waitUntil(timeoutMillis = 5_000) { exists(dayTitleTag(friday)) && !exists(dayTitleTag(today)) }
        onNodeWithContentDescription(TODAY_LABEL).performClick()
        awaitNode(hasTestTag(dayTitleTag(today)))
        onNodeWithTag(calendarRowTag(belova)).performClick()
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
    }

    @Test
    fun miniCalendarOpensDayInCurrentView() = runComposeUiTest {
        openCalendar()
        // На месяце — «▾»: мини-календарь на месяце выбранного дня, выбран сегодняшний.
        onNodeWithTag(CALENDAR_MONTH_TAG).performClick()
        onNodeWithTag(miniDayTag(today)).assertIsSelected()
        onNodeWithContentDescription(CalendarFormat.NEXT_MONTH).performClick()
        waitForIdle()
        val november10 = LocalDate(2026, 11, 10)
        onNodeWithText(DateFormat.monthYear(november10)).assertIsDisplayed()
        // Нажатие действием, а не жестом: окно закрывается посреди жеста, и Compose на компьютере иногда падает
        // с «LayoutNode … not found in RectList» (сборки 101 и 120).
        onNodeWithTag(miniDayTag(november10)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithTag(miniDayTag(november10)).assertDoesNotExist()
        assertSelected(november10, "День из мини-календаря")
        onNodeWithText(DateFormat.monthYear(november10)).assertIsDisplayed()

        // В «Месяце» — тот же мини-календарь: выбран день, страница — его месяц.
        chooseView("Месяц")
        onNodeWithTag(CALENDAR_MONTH_TAG).performClick()
        onNodeWithContentDescription(CalendarFormat.PREVIOUS_MONTH).performClick()
        waitForIdle()
        onNodeWithTag(miniDayTag(LocalDate(2026, 10, 5))).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithTag(monthDayTag(LocalDate(2026, 10, 5))).assertIsSelected()
        onNodeWithText(DateFormat.monthYear(today)).assertIsDisplayed()
    }
}
