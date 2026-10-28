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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.ui.groups.sharingConductedTag
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.CardMenu
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.CALENDAR_GRID_TAG
import com.akenory.personalka.ui.calendar.CANCEL_TRAINING
import com.akenory.personalka.ui.calendar.CalendarBlocks
import com.akenory.personalka.ui.calendar.DayBlocks
import com.akenory.personalka.ui.calendar.calendarDayTag
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth

/**
 * Карточка тренировки и отмена (шаг 1.14, ТЗ 5.4, 5.6, макеты «ПТ идёт», «Отмена ПТ», «Группа идёт», «Разложенный
 * Fold · сплит»). «Сейчас» — вт 20.10.2026 15:40.
 */
@OptIn(ExperimentalTestApi::class)
class TrainingCardUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 960.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun at(time: String): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(today, LocalTime(h, m)) }

    /** Тренировка типа [type] в ББ сегодня; id — по времени начала. */
    private fun training(time: String, vararg clientIds: String, type: String = "ПТ"): String = runBlocking {
        calendar.forms.create(TrainingDraft(at(time), typeId(type), clientIds.toList(), null, placeId("ББ"), 50))
        db.trainingDao().trainingsBetween(at(time), at("23:59")).first { it.start == at(time) }.id
    }

    private fun statusOf(id: String, clientId: String) = runBlocking { db.trainingDao().participationRow(id, clientId)!!.status }

    private fun ComposeUiTest.openCalendar(width: () -> Dp = { folded }) {
        setContent {
            Box(Modifier.size(width(), height)) {
                App("0.1.0", settings, clients, groups, calendar, MemoryPrefs(), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    private fun ComposeUiTest.day(date: LocalDate = today): DayBlocks =
        onNodeWithTag(calendarDayTag(date)).fetchSemanticsNode().config[CalendarBlocks]

    /** Точка дня [x], [y] dp от верха колонки дня — на сетке, px: колонка времени 48 dp слева, прокрутка вычитается. */
    private fun ComposeUiTest.gridPoint(x: Float, y: Float): Offset {
        val scrolled = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        return with(density) { Offset((48f + x).dp.toPx(), (y - scrolled).dp.toPx()) }
    }

    /** Нажать на блок, в первой строке которого — [name]. */
    private fun ComposeUiTest.tapBlock(name: String, hold: Boolean = false) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(calendarDayTag(today))).fetchSemanticsNodes().isNotEmpty() && day().blocks.any { it.lines.first() == s(name) }
        }
        val block = day().blocks.first { it.lines.first() == s(name) }
        val point = gridPoint(block.x + 20f, block.y + 15f)
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { if (hold) longClick(point) else click(point) }
    }

    private fun inCard(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(TRAINING_CARD_TAG))

    private fun ComposeUiTest.cardButton(action: CardAction) = onNode(inCard(TrainingCardFormat.action(action)))

    @Test
    fun closedMonthAsksBeforeChange() = runComposeUiTest {
        training("16:20", client("Орлов", "Максим"))
        runBlocking { settings.months.setClosed(YearMonth(2026, 10), true) }
        openCalendar()
        tapBlock("Орлов Максим")
        awaitNode(inCard(TrainingCardFormat.action(CardAction.EDIT)))

        // Октябрь сверен (шаг 3.7б, ТЗ 4.1): «Изменить» сначала спрашивает; «Не менять» — карточка остаётся.
        cardButton(CardAction.EDIT).performClick()
        awaitNode(hasTestTag(CLOSED_MONTH_TAG))
        onNodeWithText(TrainingCardFormat.CLOSED_TITLE).assertExists()
        onNode(hasText(TrainingCardFormat.CLOSED_KEEP) and hasAnyAncestor(hasTestTag(CLOSED_MONTH_TAG))).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(CLOSED_MONTH_TAG)).fetchSemanticsNodes().isEmpty() }
        onAllNodes(hasTestTag(TRAINING_FORM_TAG)).assertCountEquals(0)

        // «Изменить» в вопросе — форма открывается; второй раз в этой карточке не спрашивает.
        cardButton(CardAction.EDIT).performClick()
        awaitNode(hasTestTag(CLOSED_MONTH_TAG))
        onNode(hasText(TrainingCardFormat.CLOSED_CHANGE) and hasAnyAncestor(hasTestTag(CLOSED_MONTH_TAG))).performClick()
        awaitNode(hasTestTag(TRAINING_FORM_TAG))
    }

    @Test
    fun cancelFortyMinutesBeforeIsChargedAndReturns() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        val id = training("16:20", orlov)
        openCalendar()

        // Карточка: время, статус, кнопки «Отменить» и «Изменить» (макет «Позже сегодня»).
        tapBlock("Орлов Максим")
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        // Панель появляется раньше данных карточки — ждём саму строку, а не только панель.
        awaitNode(hasText(s("вт_20.10 · 16:20–17:10 · 50_мин")))
        onNodeWithText(s("вт_20.10 · 16:20–17:10 · 50_мин")).assertIsDisplayed()
        onNode(inCard("Запланировано")).assertIsDisplayed()
        onNode(inCard(s("через 40_мин"))).assertIsDisplayed()
        cardButton(CardAction.EDIT).assertIsDisplayed()

        // «Отменить» → панель отмены: «Не сможет», «Сейчас, 15:40», итог — со списанием за 40 минут (проверка шага).
        cardButton(CardAction.CANCEL).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        onNodeWithText("Сейчас, 15:40").assertIsDisplayed()
        onNode(inCard(s("предупредили за_40_мин"))).assertIsDisplayed()
        onNodeWithTag(CHARGE_SWITCH_TAG).assertIsOn()
        onNode(hasText(TrainingCardFormat.CANCEL_TRAINING) and hasClickAction()).performClick()
        awaitNode(hasText(s("Отмена со списанием: вт_20.10, 16:20")))
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, statusOf(id, orlov))

        // Карточка отменённой: причина, когда предупредили, одна кнопка «Вернуть».
        awaitNode(inCard("Не сможет"))
        onNode(inCard(s("вт_20.10, 15:40 — за_40_мин"))).assertIsDisplayed()
        cardButton(CardAction.EDIT).assertDoesNotExist()
        cardButton(CardAction.RETURN).performClick()
        awaitNode(hasText(TrainingCardFormat.RETURNED))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, orlov))
    }

    @Test
    fun sixtyOneMinutesBeforeIsNotChargedSwitchChangesIt() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        val id = training("16:41", orlov)
        openCalendar()
        tapBlock("Орлов Максим")
        // Карточка выезжает: в Actions нажатие сразу после касания блока через раз не находило кнопку (5.1).
        awaitNode(inCard(TrainingCardFormat.action(CardAction.CANCEL)))
        cardButton(CardAction.CANCEL).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        onNode(inCard(s("предупредили за_1_ч 1_мин"))).assertIsDisplayed()
        onNode(inCard("Отменено")).assertIsDisplayed()
        onNodeWithTag(CHARGE_SWITCH_TAG).assertIsOff()

        // Переключатель — итог меняется сразу; «По инициативе тренера» снова без списания.
        onNodeWithTag(CHARGE_SWITCH_TAG).performClick()
        onNode(inCard("Отмена со списанием")).assertIsDisplayed()
        onNodeWithText("По инициативе тренера").performScrollTo().performClick()
        onNodeWithTag(CHARGE_SWITCH_TAG).assertIsOff()

        // «Перенести вместо отмены» — форма тренировки, ничего не отменено.
        onNodeWithText(TrainingCardFormat.MOVE_INSTEAD).performClick()
        awaitNode(hasTestTag(TRAINING_FORM_TAG))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, orlov))
    }

    @Test
    fun ongoingMarkedDoneOnUnfoldedSide() = runComposeUiTest {
        val nikitin = client("Никитин", "Артём")
        val id = training("15:30", nikitin)
        var width by mutableStateOf(folded)
        openCalendar { width }
        tapBlock("Никитин Артём")
        awaitNode(hasTestTag(CARD_SCRIM_TAG))
        // Разложили — та же карточка справа (она хранится выше ветки по ширине).
        width = unfolded

        // Разложенный: карточка справа без затемнения, «Идёт · до конца 40 мин», «Проведено» — главная.
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        onNodeWithTag(CARD_SCRIM_TAG).assertDoesNotExist()
        onNode(inCard("Идёт")).assertIsDisplayed()
        onNode(inCard(s("до конца 40_мин"))).assertIsDisplayed()
        cardButton(CardAction.EDIT).assertDoesNotExist()
        cardButton(CardAction.DONE).performClick()
        awaitNode(inCard("отмечено в 15:40"))
        assertEquals(TrainingStatus.DONE, runBlocking { db.trainingDao().training(id)!!.status })
        assertEquals(ParticipationStatus.ATTENDED, statusOf(id, nikitin))
    }

    @Test
    fun groupMarksChildAndAllCame() = runComposeUiTest {
        val group = runBlocking { groups.create(GroupDraft("Вт 15:30 ББ", typeId("Группа"), placeId("ББ"))) }
        val kids = listOf(client("Белов", "Артём"), client("Гришин", "Матвей"), client("Данилова", "Ева"))
        runBlocking {
            kids.forEach { groups.addMember(group, it, LocalDate(2026, 9, 1)) }
            calendar.forms.create(TrainingDraft(at("15:30"), typeId("Группа"), emptyList(), group, placeId("ББ"), 60))
        }
        val id = runBlocking { db.trainingDao().trainingsOfGroup(group).single().id }
        openCalendar()
        tapBlock("Вт 15:30 ББ (3)")

        // «Идёт · пришли 0 из 3»; нажатие на ребёнка — «Пришли · 1».
        awaitNode(inCard("пришли 0 из 3"))
        onNodeWithText(TrainingCardFormat.MARK_HINT).assertIsDisplayed()
        onNodeWithTag(cardClientTag(kids[1])).performScrollTo().performClick()
        awaitNode(inCard(s("Пришли · 1")))
        assertEquals(ParticipationStatus.ATTENDED, statusOf(id, kids[1]))

        // «Все пришли» — группа проведена, все трое пришли.
        cardButton(CardAction.ALL_CAME).performClick()
        awaitNode(inCard("Проведено"))
        assertEquals(List(3) { ParticipationStatus.ATTENDED }, kids.map { statusOf(id, it) })
    }

    @Test
    fun groupWithColleagueInCard() = runComposeUiTest {
        // Шаг 3.7г: с совместным ведением в карточке группы — «Числится на» и «Проводим», правка пишется в тренировку.
        val group = runBlocking { groups.create(GroupDraft("Вт 15:30 ББ", typeId("Группа"), placeId("ББ"))) }
        val lena = runBlocking {
            calendar.forms.create(TrainingDraft(at("15:30"), typeId("Группа"), emptyList(), group, placeId("ББ"), 60))
            settings.salary.saveCoCoaching(true)
            settings.colleagues.add("Лена", PaySchemeKind.NONE, today)
        }
        val id = runBlocking { db.trainingDao().trainingsOfGroup(group).single().id }
        openCalendar()
        tapBlock("Вт 15:30 ББ (0)")

        awaitNode(inCard("Числится на: я"))
        onNodeWithText("Проводим: один").assertIsDisplayed()
        onNodeWithTag(CARD_SHARING_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(SHARING_PANEL_TAG))
        onNodeWithTag(sharingConductedTag(ConductedBy.COLLEAGUE)).performClick()
        // Коллега один — выбран сам.
        onNodeWithText("Лена").assertIsSelected()
        onNodeWithText("Сохранить").performClick()

        awaitNode(inCard(s("Проводим: коллега (замена) · Лена")))
        val t = runBlocking { db.trainingDao().training(id)!! }
        assertEquals(ConductedBy.COLLEAGUE, t.conductedBy)
        assertEquals(lena, t.colleagueId)
    }

    @Test
    fun guestOnceInGroup() = runComposeUiTest {
        // Шаг 1.14а: «Добавить на раз» — поиск клиента, гость сразу «пришёл» (группа идёт), «×» убирает его.
        val group = runBlocking { groups.create(GroupDraft("Вт 15:30 ББ", typeId("Группа"), placeId("ББ"))) }
        val kids = listOf(client("Белов", "Артём"), client("Гришин", "Матвей"))
        val vera = client("Зуева", "Вера")
        runBlocking {
            kids.forEach { groups.addMember(group, it, LocalDate(2026, 9, 1)) }
            calendar.forms.create(TrainingDraft(at("15:30"), typeId("Группа"), emptyList(), group, placeId("ББ"), 60))
        }
        val id = runBlocking { db.trainingDao().trainingsOfGroup(group).single().id }
        openCalendar()
        tapBlock("Вт 15:30 ББ (2)")

        awaitNode(hasTestTag(ADD_GUEST_TAG))
        onNodeWithTag(ADD_GUEST_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(GUEST_PANEL_TAG))
        onNodeWithTag(GUEST_SEARCH_TAG).performTextInput("Зуе")
        awaitNode(hasTestTag(guestPickTag(vera)))
        onNodeWithTag(guestPickTag(kids[0])).assertDoesNotExist()
        onNodeWithTag(guestPickTag(vera)).performClick()
        awaitNode(hasText(s("Добавили на раз: Зуева_В.")))
        awaitNode(inCard(TrainingCardFormat.members(2, 1)))
        onNode(inCard(TrainingCardFormat.GUEST)).assertIsDisplayed()
        assertEquals(ParticipationStatus.ATTENDED, statusOf(id, vera))

        // «×» в строке гостя — убран; у детей из состава вместо него значок отмены.
        onNodeWithTag(cardClientActionTag(vera)).performScrollTo().performClick()
        awaitNode(hasText(s("Убрали: Зуева_В.")))
        awaitNode(inCard(TrainingCardFormat.members(2)))
        assertEquals(true, runBlocking { db.trainingDao().participationRow(id, vera)!!.sync.deletedAt != null })

        // Нет в списке — «Новый клиент» с фамилией и именем из поиска и «Добавить».
        onNodeWithTag(ADD_GUEST_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(GUEST_SEARCH_TAG))
        onNodeWithTag(GUEST_SEARCH_TAG).performTextInput("ершов тимофей")
        onNodeWithTag(NEW_GUEST_TAG).performClick()
        awaitNode(hasTestTag(NEW_GUEST_LAST_TAG))
        onNodeWithText(TrainingCardFormat.ADD).performClick()
        awaitNode(hasText(s("Добавили на раз: Ершов_Т.")))
        awaitNode(inCard(TrainingCardFormat.members(2, 1)))

        // Набрали заново того, кто уже есть, — «Похожий клиент уже есть», «Да, выбрать»: второй Зуевой нет (шаг 3.9о).
        val before = runBlocking { db.clientDao().observeAllClients().first().size }
        onNodeWithTag(ADD_GUEST_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(GUEST_SEARCH_TAG))
        onNodeWithTag(GUEST_SEARCH_TAG).performTextInput("Зуева Вера")
        onNodeWithTag(NEW_GUEST_TAG).performClick()
        awaitNode(hasTestTag(NEW_GUEST_LAST_TAG))
        onNodeWithText(TrainingCardFormat.ADD).performClick()
        awaitNode(hasText(ClientFormat.SIMILAR_TITLE))
        onNodeWithText(ClientFormat.SIMILAR_PICK).performClick()
        awaitNode(hasText(s("Добавили на раз: Зуева_В.")))
        assertEquals(before, runBlocking { db.clientDao().observeAllClients().first().size })
    }

    @Test
    fun cancelOneClientOfSplit() = runComposeUiTest {
        val ilin = client("Ильин", "Глеб")
        val nosova = client("Носова", "Елена")
        val id = training("16:00", ilin, nosova, type = "Сплит")
        openCalendar()
        tapBlock("Ильин Глеб / Носова Елена")

        // Значок отмены у клиента — панель с этим клиентом, кнопка «Отменить: Носова Е.», без переноса.
        awaitNode(hasTestTag(cardClientActionTag(nosova)))
        onNodeWithTag(cardClientActionTag(nosova)).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        onNodeWithText(TrainingCardFormat.MOVE_INSTEAD).assertDoesNotExist()
        onNodeWithText(s("Отменить: Носова_Е.")).performClick()
        awaitNode(hasText(s("Отменено: Носова_Е.")))
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, statusOf(id, nosova))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, ilin))
        // Тренировка осталась, у Носовой — «Вернуть».
        awaitNode(hasTestTag(cardClientActionTag(nosova)))
        onNodeWithTag(cardClientActionTag(nosova)).performClick()
        awaitNode(hasText(s("Вернуть: Носова_Е.")))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, nosova))
    }

    @Test
    fun deleteFromMenuWithUndo() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        val id = training("17:00", orlov)
        openCalendar()
        tapBlock("Орлов Максим")
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(TrainingCardFormat.menu(CardMenu.DELETE)).performClick()
        onNodeWithTag(PANEL_TAG).assertIsDisplayed()
        onNode(hasText(TrainingCardFormat.DELETE) and hasAnyAncestor(hasTestTag(PANEL_TAG))).performClick()
        awaitNode(hasText(TrainingCardFormat.DELETED))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(TRAINING_CARD_TAG)).fetchSemanticsNodes().isEmpty() }
        assertEquals(null, runBlocking { db.trainingDao().trainingsBetween(at("00:00"), at("23:59")).firstOrNull { it.id == id } })

        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().training(id)!!.sync.deletedAt == null } }
    }

    @Test
    fun deleteDoneTraining() = runComposeUiTest {
        // Шаг 5.3в: прошедшая ПТ закрылась сама «Проведено» — её тоже можно удалить, со своим окном.
        val orlov = client("Орлов", "Максим")
        val id = training("10:00", orlov)
        openCalendar()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().training(id)!!.status == TrainingStatus.DONE } }
        tapBlock("Орлов Максим")
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(TrainingCardFormat.menu(CardMenu.DELETE)).performClick()
        onNodeWithText(TrainingCardFormat.DELETE_DONE_TITLE).assertIsDisplayed()
        onNode(hasText(TrainingCardFormat.DELETE) and hasAnyAncestor(hasTestTag(PANEL_TAG))).performClick()
        awaitNode(hasText(TrainingCardFormat.DELETED))
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().training(id)!!.sync.deletedAt != null } }
    }

    @Test
    fun deleteSeriesTrainingAndFollowing() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        val id = runBlocking {
            calendar.forms.create(TrainingDraft(at("17:00"), typeId("ПТ"), listOf(orlov), null, placeId("ББ"), 50, Repeat(setOf(at("17:00").date.dayOfWeek))))
            db.trainingDao().trainingsBetween(at("17:00"), at("23:59")).single().id
        }
        val seriesId = runBlocking { db.trainingDao().training(id)!!.seriesId!! }
        val count = runBlocking { db.trainingDao().trainingsOfSeries(seriesId).size }
        openCalendar()
        tapBlock("Орлов Максим")
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(TrainingCardFormat.menu(CardMenu.DELETE)).performClick()
        // У тренировки серии — «Только эту — …» и «Эту и все следующие», затем подтверждение с числом.
        awaitNode(hasTestTag(DELETE_FOLLOWING_TAG))
        onNodeWithText(TrainingCardFormat.deleteOnly(at("17:00").date)).assertIsDisplayed()
        onNodeWithTag(DELETE_FOLLOWING_TAG).performClick()
        awaitNode(hasText(TrainingCardFormat.FOLLOWING_TITLE))
        onNodeWithText(TrainingCardFormat.deleteFollowing(count)).performClick()
        awaitNode(hasText(TrainingCardFormat.deletedFollowing(count, at("17:00").date)))
        assertEquals(emptyList(), runBlocking { db.trainingDao().trainingsOfSeries(seriesId) })

        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().trainingsOfSeries(seriesId).size == count } }
    }

    @Test
    fun holdMenuCancelOpensCancelPanel() = runComposeUiTest {
        val orlov = client("Орлов", "Максим")
        training("17:00", orlov)
        openCalendar()
        tapBlock("Орлов Максим", hold = true)
        onNodeWithText(CANCEL_TRAINING).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        onNode(inCard(s("предупредили за_1_ч 20_мин"))).assertIsDisplayed()
        // Нажатие мимо панели закрывает карточку.
        onNodeWithTag(CARD_SCRIM_TAG).performTouchInput { click(Offset(centerX, 20f)) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(TRAINING_CARD_TAG)).fetchSemanticsNodes().isEmpty() }
    }
}
