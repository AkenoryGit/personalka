package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.SptFormat
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.todoTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * СПТ в приложении (шаг 3.7а, ТЗ 4.9, 5.2, 5.9; макеты HomeEvening, ClCardInfo). «Сейчас» — вт 20.10.2026 15:40.
 * Имена придуманы. Проверка шага: «Купил блок со скидкой» закрывает СПТ — её доплата идёт в «Зарплату».
 */
@OptIn(ExperimentalTestApi::class)
class SptUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private var millis = 1_000L

    /** Проведённая СПТ клиента в [start] — как после «Проведено» на другом телефоне. */
    private fun sptDone(last: String, first: String, start: LocalDateTime): String = runBlocking {
        val client = clients.create(ClientDraft(last, first))
        val coach = db.coachDao().currentCoach()!!.id
        val id = Ids.newId(millis++)
        val type = settings.types.first().single { it.shortName == "СПТ" }.id
        val place = settings.places.first().single { it.shortName == "ББ" }.id
        db.trainingDao().upsertTraining(
            TrainingEntity(id = id, sync = SyncFields.new(coach, millis++), typeId = type, locationId = place, start = start, durationMin = 50, status = TrainingStatus.DONE),
        )
        db.trainingDao().upsertParticipation(
            ParticipationEntity(id = Ids.newId(millis++), sync = SyncFields.new(coach, millis++), trainingId = id, clientId = client, status = ParticipationStatus.ATTENDED),
        )
        client
    }

    private fun case() = runBlocking { db.trainingDao().sptCases().single() }

    @Test
    fun homeTodoOpensInfoAndPurchaseCloses() = runComposeUiTest {
        sptDone("Яковлева", "Татьяна", LocalDateTime(2026, 10, 12, 10, 0))
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }

        // «Главная»: дело появилось само, пункт «СПТ ждут покупки» с ближайшим сроком.
        awaitNode(hasTestTag(todoTag(TodoKind.SPT_PURCHASE)))
        onNodeWithText(SptFormat.TODO).assertIsDisplayed()
        onNodeWithText(SptFormat.todoNote(LocalDate(2026, 11, 12), ClientName("Яковлева", "Татьяна"))).assertIsDisplayed()

        // Нажатие — карточка клиента сразу на «Инфо» с блоком «Стартовая тренировка».
        onNodeWithTag(todoTag(TodoKind.SPT_PURCHASE)).performClick()
        awaitNode(hasTestTag(SPT_BLOCK_TAG))
        onNodeWithTag(SPT_BLOCK_TAG).performScrollTo()
        onNodeWithText(SptFormat.status(SptCaseStatus.OPEN, LocalDate(2026, 11, 12), null)).assertIsDisplayed()

        onNodeWithTag(SPT_BUTTON_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(SPT_SAVE_TAG))
        onNodeWithText(SptFormat.window(LocalDate(2026, 10, 12), LocalDate(2026, 11, 12))).assertIsDisplayed()
        onNodeWithTag(SPT_SAVE_TAG).performClick()

        // По умолчанию — сегодня: блок куплен 20.10, СПТ закрыта.
        waitUntil(timeoutMillis = 5_000) { case().status == SptCaseStatus.CLOSED }
        assertEquals(LocalDate(2026, 10, 20), case().purchaseDate)
        awaitNode(hasText(SptFormat.status(SptCaseStatus.CLOSED, LocalDate(2026, 11, 12), LocalDate(2026, 10, 20))))
        awaitNode(hasText(SptFormat.CHANGE))
    }
}
