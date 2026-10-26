package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.report1c.Report1c
import com.akenory.personalka.domain.report1c.Row1c
import com.akenory.personalka.domain.report1c.Service1c
import com.akenory.personalka.domain.report1c.Status1c
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.home.homeTodos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Экран сверки с 1С (шаг 4.2в, ТЗ 8.4): три списка, «Исправить в приложении», «Отправить администратору» и пункт
 * «Нужно сделать». «Сейчас» — вт 20.10.2026. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class Report1cResultUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val repo = settings.report1c
    private val group = "Детская групповая тренировка в бассейне 50 минут"

    private fun prepare(): String = runBlocking {
        val coach = db.coachDao().currentCoach()!!.id
        val sync = SyncFields.new(coach, TestData.clock())
        val typeId = settings.types.first().single { it.shortName == "Группа" }.id
        val placeId = settings.places.first().single { it.shortName == "ДБ" }.id
        repo.saveService(group, typeId, placeId)
        db.clientDao().upsertClient(ClientEntity("egor", sync, "Пестов", "Егор"))
        db.clientDao().upsertClient(ClientEntity("vera", sync, "Чернова", "Вера"))
        val at = LocalDateTime(2026, 10, 8, 9, 0)
        db.trainingDao().upsertTraining(TrainingEntity("g1", sync, typeId, placeId, at, 60, status = TrainingStatus.DONE))
        db.trainingDao().upsertParticipation(ParticipationEntity("p1", sync, "g1", "egor", ParticipationStatus.ATTENDED))
        db.trainingDao().upsertParticipation(ParticipationEntity("p2", sync, "g1", "vera", ParticipationStatus.ATTENDED))
        val rows = listOf(
            Row1c(at, Service1c.GROUP, group, "Пестов Егор Романович", Status1c.CANCELLED, 1, 1, 0),
        )
        repo.check(Report1c("Тренеров Иван", LocalDate(2026, 10, 1), LocalDate(2026, 10, 31), rows, 1, null), "report-20261018T104841.pdf").first
    }

    @Test
    fun listsActionsAndAdminText() = runComposeUiTest {
        val id = prepare()
        val items = runBlocking { repo.view(id).first() }
        val status = items.single { it.item.kind == ReconcileItemKind.STATUS_DIFF }
        val onlyApp = items.single { it.item.kind == ReconcileItemKind.ONLY_APP }
        val sent = mutableListOf<String>()
        val messages = MessageState()
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                Report1cResultScreen(repo, id, messages, rememberCoroutineScope(), onBack = null, shareText = { sent += it })
            }
        }
        awaitNode(hasTestTag(report1cItemTag(status.item.id)))
        onNodeWithText(ReconcileFormat.summary(0, 2)).assertExists()
        onNodeWithTag(report1cItemTag(status.item.id)).assertTextContains("в приложении: Группа · ДБ · был(а) на тренировке · в 1С «Отменил»", substring = true)

        // «Отправить администратору» — обе строки, пункты отмечены.
        onNodeWithTag(REPORT1C_SEND_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { repo.unresolved().first() }.all { it.resolution == ReconcileResolution.ASKED_ADMIN } }
        assertEquals(
            "08.10 09:00, группа, Чернова Вера — был(а) на тренировке, в 1С нет. Прошу добавить\n" +
                "08.10 09:00, группа, Пестов Егор — был(а) на тренировке, в 1С «Отменил». Прошу исправить",
            sent.single(),
        )
        awaitNode(hasTestTag(REPORT1C_SEND_TAG).not())

        // «Исправить в приложении» у статуса — пункт уходит из списка.
        onNodeWithTag(report1cItemTag(status.item.id)).performClick()
        onNodeWithTag(REPORT1C_FIX_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().participationRow("g1", "egor") }!!.status == ParticipationStatus.CANCELLED }
        awaitNode(hasTestTag(report1cItemTag(status.item.id)).not())
        awaitNode(hasText(ReconcileFormat.summary(0, 1)))

        // «Игнорировать» — и второй уходит.
        onNodeWithTag(report1cItemTag(onlyApp.item.id)).performClick()
        onNodeWithTag(REPORT1C_IGNORE_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { repo.unresolved().first() }.isEmpty() }
    }

    @Test
    fun homeTodoShowsOpenAndLateAsked() {
        val id = prepare()
        val now = TestData.now
        val open = runBlocking { repo.unresolved().first() }
        assertEquals(2, homeTodos(emptyList(), null, now, report1c = open).single { it.kind == TodoKind.RECONCILE }.count)
        // Написал администратору: 3 дня молчим, потом напоминаем.
        val asked = open.map { it.copy(resolution = ReconcileResolution.ASKED_ADMIN, resolvedAt = ClubTime.toMillis(now) - 60_000) }
        assertTrue(homeTodos(emptyList(), null, now, report1c = asked).none { it.kind == TodoKind.RECONCILE })
        val later = LocalDateTime(2026, 10, 23, 16, 0)
        val todo = homeTodos(emptyList(), null, later, report1c = asked).single { it.kind == TodoKind.RECONCILE }
        assertEquals(ReconcileFormat.todoNote(0, 2), todo.note)
        assertTrue(id.isNotEmpty())
    }
}
