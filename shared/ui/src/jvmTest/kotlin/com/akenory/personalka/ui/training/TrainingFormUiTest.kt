package com.akenory.personalka.ui.training

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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.CALENDAR_GRID_TAG
import com.akenory.personalka.ui.calendar.COPY_TRAINING
import com.akenory.personalka.ui.calendar.CalendarBlocks
import com.akenory.personalka.ui.calendar.DayBlocks
import com.akenory.personalka.ui.calendar.EDIT_ZONE
import com.akenory.personalka.ui.calendar.calendarDayTag
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.shell.FAB_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Форма тренировки (шаг 1.13, ТЗ 5.5, макеты «Новая тренировка» и «Изменение тренировки серии»), дежурства
 * и запрета (шаг 1.13а, макет «Запрет»). «Сейчас» —
 * вт 20.10.2026 15:40. Окно тестов — 1024 × 768: часть формы ниже края, поэтому до неё прокручивают.
 */
@OptIn(ExperimentalTestApi::class)
class TrainingFormUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 960.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val forms = calendar.forms
    private val db = TestData.dbOf(settings)
    private val prefs = MemoryPrefs()
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun at(date: LocalDate, time: String): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(date, LocalTime(h, m)) }

    /** ПТ в ББ, записанная через форму: клиент становится недавним. */
    private fun pt(start: LocalDateTime, clientId: String, minutes: Int = 50) = runBlocking {
        forms.create(TrainingDraft(start, typeId("ПТ"), listOf(clientId), null, placeId("ББ"), minutes))
    }

    private fun trainings() = runBlocking {
        db.trainingDao().trainingsBetween(at(today.plus(-30, DateTimeUnit.DAY), "00:00"), at(today.plus(90, DateTimeUnit.DAY), "00:00"))
    }

    private fun ComposeUiTest.openCalendar(width: () -> Dp = { folded }) {
        setContent {
            Box(Modifier.size(width(), height)) {
                App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, prefs, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    private fun ComposeUiTest.day(date: LocalDate = today): DayBlocks =
        onNodeWithTag(calendarDayTag(date)).fetchSemanticsNode().config[CalendarBlocks]

    private fun ComposeUiTest.awaitBlocks(count: Int) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(calendarDayTag(today))).fetchSemanticsNodes().isNotEmpty() && day().blocks.size == count
        }
    }

    /** Точка дня [x], [y] dp от верха колонки дня — на сетке, px: колонка времени 48 dp слева, прокрутка вычитается. */
    private fun ComposeUiTest.gridPoint(x: Float, y: Float): Offset {
        val scrolled = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        return with(density) { Offset((48f + x).dp.toPx(), (y - scrolled).dp.toPx()) }
    }

    private fun ComposeUiTest.holdDay(x: Float, y: Float) {
        val point = gridPoint(x, y)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { longClick(point) }
    }

    private fun ComposeUiTest.tapDay(x: Float, y: Float) {
        val point = gridPoint(x, y)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { click(point) }
    }

    /** Время → dp от верха колонки дня при часе 64 dp; сетка — с 07:00. */
    private fun yOf(time: String): Float {
        val (h, m) = time.split(':').map { it.toInt() }
        return (h * 60 + m - 7 * 60) * 64f / 60f
    }

    private fun ComposeUiTest.tap(tag: String) {
        awaitNode(hasTestTag(tag))
        onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun ComposeUiTest.save() = onNodeWithText(TrainingFormat.SAVE).performClick()

    // У чипа метка теста — на зоне нажатия 48 dp, а «выбран» и «включён» — на самом чипе внутри: `onChild()`.

    @Test
    fun createOnEmptyPlaceInThreeTaps() = runComposeUiTest {
        val belova = client("Белова", "Ева")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), belova)
        openCalendar()

        // ТЗ 10.7: удержание → клиент из недавних → «Сохранить»; тип, место и 50 минут подставлены.
        holdDay(150f, yOf("17:23") + 0.5f)
        awaitNode(hasText("17:15–18:05"))
        onNodeWithText(s("вт_20.10.2026")).assertIsDisplayed()
        tap(formClientTag(belova))
        save()
        awaitNode(hasText(s("Создано: вт_20.10, 17:15")))
        onNodeWithTag(TRAINING_FORM_TAG).assertDoesNotExist()
        awaitBlocks(1)
        assertEquals("Белова Ева", day().blocks.single().lines.first())

        // «Отменить» — тренировки нет.
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        awaitBlocks(0)
    }

    @Test
    fun typeChangeIsFourthTap() = runComposeUiTest {
        val ilin = client("Ильин", "Глеб")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), ilin)
        openCalendar()
        holdDay(150f, yOf("19:20"))
        tap(formTypeTag(typeId("Сплит")))
        tap(formClientTag(ilin))
        save()
        awaitBlocks(1)
        assertEquals(typeId("Сплит"), trainings().single { it.start.date == today }.typeId)
    }

    @Test
    fun requiredClientShownUnderField() = runComposeUiTest {
        openCalendar()
        // «+» — ближайший свободный час: 16:00.
        onNodeWithTag(FAB_TAG).performClick()
        awaitNode(hasText("16:00–16:50"))
        save()
        awaitNode(hasText("Выберите клиента"))
        onNodeWithTag(TRAINING_FORM_TAG).assertIsDisplayed()
        assertEquals(emptyList(), trainings())
    }

    @Test
    fun newClientFromSearchWithSimilarCheck() = runComposeUiTest {
        val alisa = client("Дельфинурова", "Алиса")
        openCalendar()
        onNodeWithTag(FAB_TAG).performClick()
        awaitNode(hasTestTag(FORM_SEARCH_TAG))

        // Похожий уже есть (5.8): «Да, выбрать» — тот же клиент.
        onNodeWithTag(FORM_SEARCH_TAG).performTextInput("Дельфенурова Алиса")
        onNodeWithText("Новый клиент «Дельфенурова Алиса»").performScrollTo().performClick()
        awaitNode(hasText("Похожий клиент уже есть"))
        onNodeWithText("Да, выбрать").performClick()
        onNodeWithTag(formClientTag(alisa)).onChild().assertIsSelected()

        // Нового нет среди похожих — создаётся сразу и выбирается.
        tap(formTypeTag(typeId("Сплит")))
        onNodeWithTag(FORM_SEARCH_TAG).performTextInput("Петров Игорь")
        onNodeWithText("Новый клиент «Петров Игорь»").performScrollTo().performClick()
        // У сплита клиентов двое — чипы-флажки.
        awaitNode(hasText(s("Петров_И.")))
        onNodeWithText(s("Петров_И.")).assertIsOn()
        onNodeWithTag(formClientTag(alisa)).onChild().assertIsOn()
        save()
        awaitNode(hasText(s("Создано: вт_20.10, 16:00")))
        val petrov = runBlocking { db.clientDao().observeAllClients().first().single { it.lastName == "Петров" } }
        assertEquals("Игорь", petrov.firstName)
    }

    @Test
    fun weeklySeriesThenFollowingAfterQuestion() = runComposeUiTest {
        val morozov = client("Морозов", "Даниил")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), morozov)
        openCalendar()
        holdDay(150f, yOf("17:23") + 0.5f)
        tap(formClientTag(morozov))
        tap(FORM_WEEKLY_TAG)
        // День повтора по умолчанию — день даты.
        onNodeWithTag(formWeekdayTag(DayOfWeek.TUESDAY)).onChild().assertIsOn()
        onNodeWithTag(formWeekdayTag(DayOfWeek.THURSDAY)).onChild().assertIsOff()
        save()
        awaitNode(hasText("Создано: каждый вт, 17:15"))
        // До горизонта 8 недель — по вт 15.12: 9 тренировок, и прошлая разовая.
        assertEquals(10, trainings().size)

        // Изменить место у серии: вопрос «Только эту / Эту и следующие».
        awaitBlocks(1)
        val block = day().blocks.single()
        // Нажатие на блок — карточка (шаг 1.14), в ней «Изменить» — форма.
        tapDay(block.x + 20f, block.y + 20f)
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        onNodeWithText(TrainingCardFormat.action(CardAction.EDIT)).performClick()
        awaitNode(hasText(TrainingFormat.EDIT_TITLE))
        awaitNode(hasText("Каждый вт · бессрочно"))
        tap(formPlaceTag(placeId("ДБ")))
        save()
        awaitNode(hasText(TrainingFormat.SCOPE_EDIT_TITLE))
        onNodeWithText(s("Морозов_Д. · каждый вт, 17:15. Новое место — ДБ.")).assertIsDisplayed()
        onNodeWithText(s("Только эту — вт_20.10")).assertIsDisplayed()
        onNodeWithText(TrainingFormat.FOLLOWING).performClick()
        awaitNode(hasText(TrainingFormat.SAVED_FOLLOWING))
        val series = trainings().filter { it.seriesId != null }
        assertEquals(9, series.size)
        assertTrue(series.all { it.locationId == placeId("ДБ") })
    }

    @Test
    fun restrictionAsksBeforeSaving() = runComposeUiTest {
        runBlocking {
            val coach = db.coachDao().currentCoach()!!.id
            db.scheduleDao().upsertBlock(
                ScheduleBlockEntity(
                    id = Ids.newId(ClubTime.toMillis(TestData.now)),
                    sync = SyncFields.new(coach, ClubTime.toMillis(TestData.now)),
                    scope = Scope.CLUB,
                    locationId = placeId("ДБ"),
                    reason = "Групповая тренировка",
                    weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                    startTime = LocalTime(17, 0),
                    endTime = LocalTime(18, 0),
                    validFrom = LocalDate(2026, 9, 1),
                ),
            )
        }
        val orlov = client("Орлов", "Максим")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), orlov)
        openCalendar()
        holdDay(150f, yOf("17:23") + 0.5f)
        tap(formClientTag(orlov))
        tap(formPlaceTag(placeId("ДБ")))
        save()

        // ТЗ 4.5: окно до записи; «Поставить» — с треугольником «поверх запрета».
        awaitNode(hasText(ZoneFormat.CONFLICT_QUESTION))
        onNodeWithText("Детский бассейн 17:00–18:00: групповая тренировка").assertIsDisplayed()
        assertEquals(1, trainings().size)
        onNodeWithText(ZoneFormat.CONFLICT_PLACE).performClick()
        awaitNode(hasText(s("Создано: вт_20.10, 17:15")))
        awaitBlocks(1)
        assertEquals(listOf(CalendarBadge.OVER_RESTRICTION), day().blocks.single().badges)
    }

    @Test
    fun pasteCopiedTraining() = runComposeUiTest {
        val belova = client("Белова", "Ева")
        pt(at(today, "16:00"), belova, minutes = 60)
        openCalendar()
        awaitBlocks(1)
        val block = day().blocks.single()
        holdDay(block.x + 40f, block.y + 20f)
        onNodeWithText(COPY_TRAINING).performClick()

        // «+» — ближайший свободный час после 16:00–17:00 — 17:00; «Вставить скопированную» — клиент и 60 минут.
        onNodeWithTag(FAB_TAG).performClick()
        awaitNode(hasText(TrainingFormat.PASTE))
        onNodeWithText(TrainingFormat.PASTE).performClick()
        onNodeWithTag(formClientTag(belova)).onChild().assertIsSelected()
        awaitNode(hasText("17:00–18:00"))
        save()
        awaitNode(hasText(s("Создано: вт_20.10, 17:00")))
        awaitBlocks(2)
    }

    @Test
    fun formSurvivesFolding() = runComposeUiTest {
        val gusev = client("Гусев", "Борис")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), gusev)
        var width by mutableStateOf(folded)
        openCalendar { width }
        onNodeWithTag(FAB_TAG).performClick()
        tap(formClientTag(gusev))
        onNodeWithTag(FORM_PLAN_TAG).performScrollTo().performTextInput("Кроль 8 × 25 м")

        // Разложили Fold — выбранный клиент и недописанный план на месте.
        width = unfolded
        waitForIdle()
        onNodeWithTag(formClientTag(gusev)).onChild().assertIsSelected()
        onNodeWithTag(FORM_PLAN_TAG).assert(hasText("Кроль 8 × 25 м"))
        save()
        awaitNode(hasText(s("Создано: вт_20.10, 16:00")))
        assertEquals("Кроль 8 × 25 м", trainings().single { it.start.date == today }.plan)
    }

    // Дежурство и запрет (шаг 1.13а).

    private fun ComposeUiTest.awaitZones(count: Int) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(calendarDayTag(today))).fetchSemanticsNodes().isNotEmpty() && day().zones.size == count
        }
    }

    /** Вид записи в переключателе формы. */
    private fun ComposeUiTest.mode(label: String) {
        awaitNode(hasTestTag(FORM_MODE_TAG))
        onNode(hasText(label) and hasAnyAncestor(hasTestTag(FORM_MODE_TAG))).performClick()
    }

    @Test
    fun restrictionFromFormHatchesAndAsks() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), orlov)
        openCalendar()

        // Удержание на 17:00 → «Запрет»: время из места нажатия, на час (макет «Запрет»).
        holdDay(150f, yOf("17:00") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        mode(TrainingFormat.MODE_RESTRICTION)
        awaitNode(hasText(TrainingFormat.NEW_RESTRICTION_TITLE))
        onNodeWithText("с 17:00").assertIsDisplayed()
        onNodeWithText("до 18:00").assertIsDisplayed()
        tap(formPlaceTag(placeId("ДБ")))
        onNodeWithTag(FORM_REASON_TAG).performScrollTo().performTextInput("Групповая тренировка")
        tap(FORM_WEEKLY_TAG)
        tap(formWeekdayTag(DayOfWeek.THURSDAY))
        // «Для всего клуба» — только администратору (этап 2): у тренера его нет.
        onNodeWithText("Для всего клуба").assertDoesNotExist()
        save()
        awaitNode(hasText(TrainingFormat.RESTRICTION_CREATED))
        val block = runBlocking { db.scheduleDao().blocks().single() }
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), block.weekdays)
        assertEquals(Scope.COACH, block.scope)

        // Проверка шага: запрет виден штриховкой и спрашивает при постановке тренировки.
        awaitZones(1)
        assertEquals(s("ДБ · групповая тренировка"), day().zones.single().label)
        holdDay(150f, yOf("17:23") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        tap(formClientTag(orlov))
        tap(formPlaceTag(placeId("ДБ")))
        save()
        awaitNode(hasText(ZoneFormat.CONFLICT_QUESTION))
        onNodeWithText("Детский бассейн 17:00–18:00: групповая тренировка").assertIsDisplayed()
    }

    @Test
    fun restrictionOnAllPlacesAtOnce() = runComposeUiTest {
        openCalendar()
        holdDay(150f, yOf("18:00") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        mode(TrainingFormat.MODE_RESTRICTION)
        awaitNode(hasText(TrainingFormat.NEW_RESTRICTION_TITLE))
        // Место не выбрано — сохранить нельзя.
        save()
        onNodeWithText("Выберите место").assertIsDisplayed()
        // Шаг 1.13д: «Все места» — по запрету на каждое место, одно «Отменить».
        tap(FORM_ALL_PLACES_TAG)
        onNodeWithTag(FORM_REASON_TAG).performScrollTo().performTextInput("Свои дела")
        save()
        awaitNode(hasText(s("Запреты созданы в 3_местах")))
        val blocks = runBlocking { db.scheduleDao().blocks() }
        assertEquals(3, blocks.map { it.locationId }.toSet().size)
        assertTrue(blocks.all { it.reason == "Свои дела" && it.startTime == LocalTime(18, 0) })
        onNodeWithText("Отменить").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.scheduleDao().blocks().isEmpty() } }
    }

    @Test
    fun dutySeriesFromSwitchAndEditFromZone() = runComposeUiTest {
        openCalendar()
        holdDay(150f, yOf("18:00") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        mode(TrainingFormat.MODE_DUTY)
        // Дежурство — на длительность типа: 4 часа, место — первое.
        awaitNode(hasText(TrainingFormat.NEW_DUTY_TITLE))
        onNodeWithText("с 18:00").assertIsDisplayed()
        onNodeWithText("до 22:00").assertIsDisplayed()
        tap(FORM_WEEKLY_TAG)
        save()
        awaitNode(hasText("Создано: каждый вт, 18:00"))
        // По вт до горизонта 8 недель — 9 дежурств; в календаре это заливка, а не блок.
        assertEquals(9, trainings().size)
        awaitZones(1)
        awaitBlocks(0)

        // Нажатие на заливку → «Изменить» → форма дежурства с повтором серии строкой.
        val zone = day().zones.single()
        tapDay(300f, zone.y + 30f)
        onNodeWithText(EDIT_ZONE).performClick()
        awaitNode(hasText(TrainingFormat.EDIT_DUTY_TITLE))
        onNodeWithText("Каждый вт · бессрочно").performScrollTo().assertIsDisplayed()
        onNodeWithTag(FORM_MODE_TAG).assertDoesNotExist()
    }

    @Test
    fun dutyForColleague() = runComposeUiTest {
        openCalendar()
        holdDay(150f, yOf("18:00") + 0.5f)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        mode(TrainingFormat.MODE_DUTY)
        awaitNode(hasText(TrainingFormat.NEW_DUTY_TITLE))
        onNodeWithTag(FORM_COVER_FOR_TAG).performScrollTo().performTextInput("Александра")
        // У повторяющегося дежурства поля нет; «Не повторять» — снова есть.
        tap(FORM_WEEKLY_TAG)
        onNodeWithTag(FORM_COVER_FOR_TAG).assertDoesNotExist()
        onNodeWithText(TrainingFormat.NO_REPEAT).performClick()
        onNodeWithTag(FORM_COVER_FOR_TAG).performScrollTo().assertIsDisplayed()
        save()
        waitUntil(timeoutMillis = 5_000) { trainings().singleOrNull()?.coverFor == "Александра" }

        // Подпись на заливке и окно по нажатию — «за Александра».
        awaitZones(1)
        assertEquals("Дежурство · ББ · за${RuText.NBSP}Александра", day().zones.single().label)
        val zone = day().zones.single()
        tapDay(300f, zone.y + 30f)
        awaitNode(hasText("ББ 18:00–22:00 — дежурство за${RuText.NBSP}Александра"))
    }

    @Test
    fun switchKeepsWhatWasChosen() = runComposeUiTest {
        val gusev = client("Гусев", "Борис")
        pt(at(today.plus(-7, DateTimeUnit.DAY), "10:00"), gusev)
        openCalendar()
        onNodeWithTag(FAB_TAG).performClick()
        tap(formClientTag(gusev))

        // «Запрет» и обратно — выбранный клиент на месте.
        mode(TrainingFormat.MODE_RESTRICTION)
        awaitNode(hasText(TrainingFormat.NEW_RESTRICTION_TITLE))
        mode(TrainingFormat.MODE_TRAINING)
        awaitNode(hasText(TrainingFormat.NEW_TITLE))
        onNodeWithTag(formClientTag(gusev)).onChild().assertIsSelected()
    }

    @Test
    fun ownRestrictionEditAndDelete() = runComposeUiTest {
        runBlocking {
            calendar.restrictions.save(
                null,
                RestrictionDraft(placeId("ББ"), LocalTime(19, 0), LocalTime(20, 0), "Секция", weekly = true, weekdays = setOf(DayOfWeek.TUESDAY), date = LocalDate(2026, 9, 1)),
            )
        }
        openCalendar()
        awaitZones(1)
        val zone = day().zones.single()
        tapDay(2f, zone.y + 30f)
        onNodeWithText(EDIT_ZONE).performClick()
        awaitNode(hasText(TrainingFormat.EDIT_RESTRICTION_TITLE))
        onNodeWithTag(FORM_REASON_TAG).assert(hasText("Секция"))
        onNodeWithText("с 01.09.2026").performScrollTo().assertIsDisplayed()

        // «Удалить запрет» — штриховки нет; «Отменить» — снова есть.
        onNodeWithText(TrainingFormat.DELETE_RESTRICTION).performScrollTo().performClick()
        awaitNode(hasText(TrainingFormat.RESTRICTION_DELETED))
        awaitZones(0)
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        awaitZones(1)
    }
}
