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
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.home.todoTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.TRAINING_FORM_TAG
import com.akenory.personalka.ui.training.formClientTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * «Без записи» (шаг 2.4, ТЗ 4.15, 5.8, макет ClNoBooking). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы.
 * Проверка шага: после «Записать» клиент уходит из списка.
 */
@OptIn(ExperimentalTestApi::class)
class FollowupUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private var millis = 1_000L

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    /** Клиент, который был на проведённой тренировке [start]. */
    private fun visited(last: String, first: String, start: LocalDateTime, type: String = "ПТ", sex: Sex? = null): String =
        runBlocking {
            val client = clients.create(ClientDraft(last, first, sex = sex))
            val coach = db.coachDao().currentCoach()!!.id
            val id = Ids.newId(millis++)
            db.trainingDao().upsertTraining(
                TrainingEntity(
                    id = id,
                    sync = SyncFields.new(coach, millis++),
                    typeId = typeId(type),
                    locationId = placeId("ББ"),
                    start = start,
                    durationMin = 50,
                    status = TrainingStatus.DONE,
                ),
            )
            db.trainingDao().upsertParticipation(
                ParticipationEntity(
                    id = Ids.newId(millis++),
                    sync = SyncFields.new(coach, millis++),
                    trainingId = id,
                    clientId = client,
                    status = ParticipationStatus.ATTENDED,
                ),
            )
            client
        }

    private fun planned() = runBlocking {
        db.trainingDao().trainingsBetween(LocalDateTime(2026, 10, 20, 15, 41), LocalDateTime(2026, 12, 31, 0, 0))
    }

    private fun ComposeUiTest.openNoBooking() {
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(filterChipTag(ClientFilter.NO_BOOKING)))
        onNodeWithTag(filterChipTag(ClientFilter.NO_BOOKING)).performClick()
    }

    @Test
    fun bookOpensFormAndClientLeavesList() = runComposeUiTest {
        // Пт 16.10 в 08:00, ПТ в ББ → «Записать» — на пт 23.10 в 08:00, ПТ, ББ.
        val zakharov = visited("Захаров", "Никита", LocalDateTime(2026, 10, 16, 8, 0), sex = Sex.MALE)
        openNoBooking()
        awaitNode(hasTestTag(followupRowTag("Захаров Никита")))
        onNodeWithText(FollowupFormat.chip(1)).assertIsDisplayed()
        onNodeWithText("был пт 16.10 · ПТ ББ").assertIsDisplayed()

        onNodeWithTag(followupBookTag("Захаров Никита")).performClick()
        awaitNode(hasTestTag(formClientTag(zakharov)))
        onNodeWithTag(formClientTag(zakharov)).onChild().assertIsSelected()
        onNodeWithText(TrainingFormat.SAVE).performClick()
        waitUntil(timeoutMillis = 5_000) { planned().isNotEmpty() }
        val booked = planned().single()
        assertEquals(LocalDateTime(2026, 10, 23, 8, 0), booked.start)
        assertEquals(typeId("ПТ") to placeId("ББ"), booked.typeId to booked.locationId)

        // Проверка шага: клиент ушёл из «Без записи» сам.
        onNodeWithTag(TRAINING_FORM_TAG).assertDoesNotExist()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(followupRowTag("Захаров Никита"))).fetchSemanticsNodes().isEmpty() }
        onNodeWithText(FollowupFormat.EMPTY).assertIsDisplayed()
        onNodeWithText(FollowupFormat.chip(0)).assertIsDisplayed()
    }

    @Test
    fun afterSptFirstAndContactedHides() = runComposeUiTest {
        visited("Захаров", "Никита", LocalDateTime(2026, 10, 9, 8, 0), sex = Sex.MALE)
        val yakovleva = visited("Яковлева", "Татьяна", LocalDateTime(2026, 10, 12, 10, 0), type = "СПТ", sex = Sex.FEMALE)
        openNoBooking()
        awaitNode(hasTestTag(followupRowTag("Яковлева Татьяна")))
        onNodeWithText(FollowupFormat.afterSpt(LocalDate(2026, 10, 12))).assertIsDisplayed()

        onNodeWithTag(followupContactedTag("Яковлева Татьяна")).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(followupRowTag("Яковлева Татьяна"))).fetchSemanticsNodes().isEmpty() }
        awaitNode(hasText(FollowupFormat.contactedMessage("Яковлева Татьяна")))
        onNodeWithTag(followupRowTag("Захаров Никита")).assertIsDisplayed()
        assertEquals(TestData.clock(), runBlocking { db.clientDao().client(yakovleva)!!.followupContactedAt })
    }

    @Test
    fun pauseUntilDayWithReason() = runComposeUiTest {
        val zakharov = visited("Захаров", "Никита", LocalDateTime(2026, 10, 16, 8, 0))
        openNoBooking()
        awaitNode(hasTestTag(followupMenuTag("Захаров Никита")))
        onNodeWithTag(followupMenuTag("Захаров Никита")).performClick()
        onNodeWithText(FollowupFormat.PAUSE).performClick()
        awaitNode(hasTestTag(PANEL_TAG))
        // По умолчанию — через неделю.
        onNodeWithText(TrainingFormat.date(LocalDate(2026, 10, 27))).assertIsDisplayed()
        onNodeWithText(FollowupFormat.PAUSE_SAVE).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.clientDao().client(zakharov)!!.followupPausedUntil } != null }
        assertEquals(LocalDate(2026, 10, 27), runBlocking { db.clientDao().client(zakharov)!!.followupPausedUntil })
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(followupRowTag("Захаров Никита"))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun cardLabelAndHomeTodo() = runComposeUiTest {
        visited("Соколова", "Вера", LocalDateTime(2026, 10, 20, 15, 0), sex = Sex.FEMALE)
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        // «Главная»: пункт «Клиент без записи» с именем — ведёт в «Без записи».
        awaitNode(hasTestTag(todoTag(TodoKind.NOT_BOOKED)))
        onNodeWithText(FollowupFormat.todo(1)).assertIsDisplayed()
        onNodeWithTag(todoTag(TodoKind.NOT_BOOKED)).performClick()
        awaitNode(hasTestTag(followupRowTag("Соколова Вера")))
        onNodeWithText("была сегодня 15:00 · ПТ ББ").assertIsDisplayed()

        // Имя открывает карточку с меткой «Нет записи».
        onNodeWithText("Соколова Вера").performClick()
        awaitNode(hasText(FollowupFormat.NO_BOOKING))
        onNodeWithText(FollowupFormat.NO_BOOKING).assertIsDisplayed()
    }
}
