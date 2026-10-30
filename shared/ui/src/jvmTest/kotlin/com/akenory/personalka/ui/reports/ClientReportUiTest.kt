package com.akenory.personalka.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.format.ClientReportFormat
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.clients.CARD_MENU_TAG
import com.akenory.personalka.ui.clients.clientRowTag
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/** «Отчёты → Клиент» (шаг 5.3к, ТЗ 5.15). «Сейчас» — вт 20.10.2026, 15:40. Клиенты придуманы. */
@OptIn(ExperimentalTestApi::class)
class ClientReportUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val october = YearMonth(2026, 10)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun prepare() = runBlocking {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        val coach = db.coachDao().currentCoach()!!.id
        val now = TestData.clock()
        db.clientDao().upsertClient(ClientEntity("kid", SyncFields.new(coach, now), "Орлова", "Ксения"))
        db.clientDao().upsertClient(ClientEntity("old", SyncFields.new(coach, now), "Зайцев", "Олег", status = ClientStatus.ARCHIVED))
        listOf(
            5 to (TrainingStatus.DONE to ParticipationStatus.ATTENDED),
            9 to (TrainingStatus.CANCELLED to ParticipationStatus.CANCELLED_CHARGED),
            10 to (TrainingStatus.CANCELLED to ParticipationStatus.CANCELLED),
            28 to (TrainingStatus.PLANNED to ParticipationStatus.EXPECTED),
        ).forEachIndexed { i, (day, s) ->
            db.trainingDao().upsertTraining(TrainingEntity("t$i", SyncFields.new(coach, now), pt, bb, LocalDateTime(2026, 10, day, 9, 0), 50, status = s.first))
            val charged = s.second == ParticipationStatus.CANCELLED_CHARGED
            db.trainingDao().upsertParticipation(
                ParticipationEntity(
                    "p$i", SyncFields.new(coach, now), "t$i", "kid", s.second,
                    cancelReason = if (charged) "Не сможет" else null,
                    notifiedAt = if (charged) LocalDateTime(2026, 10, day, 8, 20) else null,
                ),
            )
        }
    }

    private fun ComposeUiTest.start() {
        prepare()
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
    }

    private fun SemanticsNodeInteraction.press() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun pickClientRowsAndTrainings() = runComposeUiTest {
        start()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.REPORTS)).performScrollTo().assertTextContains(ReportFormat.TITLE).performClick()
        awaitNode(hasTestTag(reportTabTag(ReportTab.CLIENT)))
        onNodeWithTag(reportTabTag(ReportTab.CLIENT)).performClick()

        // Выбор: архивные тоже, с пометкой.
        awaitNode(hasTestTag(reportPickTag("old")))
        onNodeWithTag(reportPickTag("old")).assertTextContains(ClientReportFormat.ARCHIVED, substring = true)
        onNodeWithTag(REPORTS_CLIENT_SEARCH_TAG).performTextInput("Орл")
        awaitNode(hasTestTag(reportPickTag("kid")))
        onNodeWithTag(reportPickTag("kid")).press()

        awaitNode(hasTestTag(REPORTS_CLIENT_TOTAL_TAG))
        onNodeWithTag(REPORTS_CLIENT_NAME_TAG).assertTextContains("Орлова Ксения")
        onNodeWithTag(REPORTS_TITLE_TAG).assertTextContains(ReportFormat.month(october))
        onNodeWithTag(reportClientRowTag("ПТ · ББ")).assertTextContains("проведено 1 · со списанием 1 · отменено 1", substring = true)
        onNodeWithTag(REPORTS_CLIENT_TOTAL_TAG).assertTextContains("В зарплату: 2 из 3", substring = true)
        awaitNode(hasText(ReportFormat.ahead(1)))
        onNodeWithTag(reportVisitTag("t1")).assertTextContains("предупредили за", substring = true)
        onNodeWithTag(reportVisitTag("t1")).assertTextContains("Отмена со списанием")

        // Тренировка — её карточка.
        onNodeWithTag(reportVisitTag("t0")).press()
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
    }

    @Test
    fun openFromClientCard() = runComposeUiTest {
        start()
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(clientRowTag("Орлова Ксения")))
        onNodeWithTag(clientRowTag("Орлова Ксения")).press()
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(ClientReportFormat.MENU).performClick()

        // «Отчёты» сразу на «Клиенте» с ней и текущим месяцем.
        awaitNode(hasTestTag(REPORTS_CLIENT_TOTAL_TAG))
        onNodeWithTag(REPORTS_CLIENT_NAME_TAG).assertTextContains("Орлова Ксения")
        onNodeWithTag(REPORTS_TITLE_TAG).assertTextContains(ReportFormat.month(october))
        onNodeWithTag(REPORTS_CLIENT_TOTAL_TAG).assertTextContains("В зарплату: 2 из 3", substring = true)

        // «Другой клиент» — снова выбор.
        onNodeWithTag(REPORTS_CLIENT_CHANGE_TAG).performClick()
        awaitNode(hasTestTag(REPORTS_CLIENT_SEARCH_TAG))
    }
}
