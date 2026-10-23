package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.WaitStatus
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.home.todoTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.FORM_WEEKLY_TAG
import com.akenory.personalka.ui.training.formClientTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime

/** Лист ожидания (шаг 2.4а, ТЗ 4.16, 5.8, макеты ClNoBooking, ClWaitAdd, ClBook). «Сейчас» — вт 20.10.2026 15:40. */
@OptIn(ExperimentalTestApi::class)
class WaitUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun ptId() = runBlocking { settings.types.first().single { it.shortName == "ПТ" }.id }

    private fun entryOf(clientId: String) = runBlocking { db.clientDao().waitEntriesOf(clientId).single() }

    private fun ComposeUiTest.openNoBooking() {
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(filterChipTag(ClientFilter.NO_BOOKING)))
        onNodeWithTag(filterChipTag(ClientFilter.NO_BOOKING)).performClick()
    }

    @Test
    fun addToWaitlistThenBookSeriesClosesEntry() = runComposeUiTest {
        val petrov = client("Петров", "Кирилл")
        openNoBooking()
        // Раздел виден и пустой.
        awaitNode(hasText(WaitFormat.EMPTY))
        onNodeWithTag(WAIT_ADD_TAG).performClick()

        // «+ В ожидание»: клиент поиском, пн и ср, утро — тип ПТ и любое место по умолчанию.
        awaitNode(hasTestTag(WAIT_SEARCH_TAG))
        onNodeWithTag(WAIT_SEARCH_TAG).performTextInput("пет")
        awaitNode(hasTestTag(waitClientTag(petrov)))
        onNodeWithTag(waitClientTag(petrov)).performClick()
        onNodeWithTag(waitDayTag(DayOfWeek.MONDAY)).performClick()
        onNodeWithTag(waitDayTag(DayOfWeek.WEDNESDAY)).performClick()
        onNodeWithTag(waitRangeTag(WaitRules.MORNING)).performScrollTo().performClick()
        onNodeWithText(WaitFormat.SAVE).performClick()

        awaitNode(hasTestTag(waitRowTag("Петров Кирилл")))
        onNodeWithText("постоянно · пн, ср · 07–12 · ПТ").assertIsDisplayed()
        onNodeWithText(FollowupFormat.chip(1)).assertIsDisplayed()

        // «Записать» — форма с ним, ПТ, «Каждую неделю» и подписью «Из листа ожидания».
        onNodeWithTag(waitBookTag("Петров Кирилл")).performClick()
        awaitNode(hasTestTag(formClientTag(petrov)))
        onNodeWithText(WaitFormat.FORM_BANNER).assertIsDisplayed()
        onNodeWithTag(formClientTag(petrov)).onChild().assertIsSelected()
        onNodeWithTag(FORM_WEEKLY_TAG).performScrollTo().onChild().assertIsSelected()
        onNodeWithText(TrainingFormat.SAVE).performClick()

        // Серия для клиента закрыла его запись «постоянно» — он ушёл из листа сам.
        waitUntil(timeoutMillis = 5_000) { entryOf(petrov).status == WaitStatus.BOOKED }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(waitRowTag("Петров Кирилл"))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun cardLabelAndCloseOffersErase() = runComposeUiTest {
        val smirnova = client("Смирнова", "Ирина")
        runBlocking { clients.waitlist.save(WaitDraft(smirnova, WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = ptId()))) }
        openNoBooking()
        awaitNode(hasTestTag(waitRowTag("Смирнова Ирина")))
        onNodeWithText("разово · любой день · 17–23 · ПТ").assertIsDisplayed()

        // Карточка — метка «Ждёт».
        onNodeWithText("Смирнова Ирина").performClick()
        awaitNode(hasText(WaitFormat.LABEL))
        onNodeWithText(WaitFormat.LABEL).assertIsDisplayed()
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()

        // «Больше не ждёт»: тренировок нет — вопрос «Удалить и карточку?»; «Оставить» карточку не трогает.
        awaitNode(hasTestTag(waitMenuTag("Смирнова Ирина")))
        onNodeWithTag(waitMenuTag("Смирнова Ирина")).performClick()
        onNodeWithText(WaitFormat.CLOSE).performClick()
        awaitNode(hasTestTag(PANEL_TAG))
        onNodeWithText(WaitFormat.ERASE_TITLE).assertIsDisplayed()
        onNodeWithText(WaitFormat.KEEP).performClick()
        assertEquals(WaitStatus.CLOSED, entryOf(smirnova).status)
        awaitNode(hasText(WaitFormat.EMPTY))
        assertEquals("Смирнова", runBlocking { db.clientDao().client(smirnova)!!.lastName })
    }

    @Test
    fun freedTimeOnHomeAndBookOnceRemovesFromList() = runComposeUiTest {
        // Смирнова ждёт разово вечером; ПТ Орлова сегодня в 17:00 отменили — время освободилось.
        val smirnova = client("Смирнова", "Ирина")
        val orlov = client("Орлов", "Максим")
        val db = TestData.dbOf(settings)
        runBlocking {
            clients.waitlist.save(WaitDraft(smirnova, WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = ptId())))
            val dbPlace = settings.places.first().single { it.shortName == "ДБ" }.id
            calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 20, 17, 0), ptId(), listOf(orlov), null, dbPlace, 50))
            val training = db.trainingDao().trainingsAfter(TestData.now).single()
            assertEquals("Это время ждут: Смирнова И. (разово)", calendar.cards.waitingLine(training.id))
            calendar.cards.cancel(training.id, emptyList(), "Не сможет", TestData.now)
        }
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }

        // «Главная»: «Освободилось вт 20.10 17:00 · ждут 1» — ведёт в «Без записи».
        awaitNode(hasTestTag(todoTag(TodoKind.FREED_TIME)))
        onNodeWithTag(todoTag(TodoKind.FREED_TIME)).performClick()
        awaitNode(hasTestTag(waitFreedTag("Смирнова Ирина")))

        // «Записать» — на освободившееся время, без повтора; после «Сохранить» запись «только разово» закрыта.
        onNodeWithTag(waitBookTag("Смирнова Ирина")).performClick()
        awaitNode(hasTestTag(formClientTag(smirnova)))
        onNodeWithText(TrainingFormat.SAVE).performClick()
        waitUntil(timeoutMillis = 5_000) { entryOf(smirnova).status == WaitStatus.BOOKED }
        val booked = runBlocking { db.trainingDao().trainingsAfter(TestData.now).filter { it.status == TrainingStatus.PLANNED } }
        assertEquals(LocalDateTime(2026, 10, 20, 17, 0), booked.single().start)
        awaitNode(hasText(WaitFormat.BOOKED))
    }
}
