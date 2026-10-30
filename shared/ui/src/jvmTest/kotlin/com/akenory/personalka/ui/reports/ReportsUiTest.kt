package com.akenory.personalka.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.report.ReportKey
import com.akenory.personalka.domain.report.ReportPeriod
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
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

/** «Ещё → Отчёты» (шаг 5.3и, ТЗ 5.15). «Сейчас» — вт 20.10.2026, 15:40. Клиенты придуманы. */
@OptIn(ExperimentalTestApi::class)
class ReportsUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val october = YearMonth(2026, 10)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }
    private val key by lazy { ReportKey(typeId("ПТ"), placeId("ББ")) }

    private fun prepare() = runBlocking {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        val coach = db.coachDao().currentCoach()!!.id
        val now = TestData.clock()
        listOf(
            5 to (TrainingStatus.DONE to ParticipationStatus.ATTENDED),
            6 to (TrainingStatus.DONE to ParticipationStatus.ATTENDED),
            9 to (TrainingStatus.CANCELLED to ParticipationStatus.CANCELLED_CHARGED),
            10 to (TrainingStatus.CANCELLED to ParticipationStatus.CANCELLED),
            28 to (TrainingStatus.PLANNED to ParticipationStatus.EXPECTED),
        ).forEachIndexed { i, (day, s) ->
            db.trainingDao().upsertTraining(TrainingEntity("t$i", SyncFields.new(coach, now), pt, bb, LocalDateTime(2026, 10, day, 9, 0), 50, status = s.first))
            db.trainingDao().upsertParticipation(ParticipationEntity("p$i", SyncFields.new(coach, now), "t$i", "client", s.second))
        }
    }

    private fun ComposeUiTest.start(width: () -> Dp = { 412.dp }) {
        prepare()
        setContent {
            Box(Modifier.size(width(), 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.REPORTS)).performScrollTo().assertTextContains(ReportFormat.TITLE).performClick()
    }

    private fun SemanticsNodeInteraction.press() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun monthRowsListAndCard() = runComposeUiTest {
        start()
        awaitNode(hasTestTag(reportRowTag(key)))
        onNodeWithTag(REPORTS_TITLE_TAG).assertTextContains(ReportFormat.month(october))
        onNodeWithTag(reportRowTag(key)).assertTextContains("проведено 2 · со списанием 1 · отменено 1", substring = true)
        onNodeWithTag(reportRowTag(key)).assertTextContains("3")
        onNodeWithTag(REPORTS_TOTAL_TAG).assertTextContains(ReportFormat.TOTAL, substring = true)
        awaitNode(hasText(ReportFormat.ahead(1)))

        // Строка — список её тренировок, тренировка — её карточка.
        onNodeWithTag(reportRowTag(key)).press()
        awaitNode(hasTestTag(reportItemTag("t2")))
        onNodeWithTag(reportItemTag("t2")).assertTextContains(ReportFormat.CHARGED)
        onNodeWithTag(reportItemTag("t2")).press()
        awaitNode(hasTestTag(TRAINING_CARD_TAG))

        // Ноябрь пуст.
        onNodeWithTag(REPORTS_NEXT_TAG).performClick()
        awaitNode(hasTestTag(REPORTS_EMPTY_TAG))
    }

    @Test
    fun yearAndCustom() = runComposeUiTest {
        start()
        awaitNode(hasTestTag(reportRowTag(key)))
        onNodeWithTag(reportPeriodTag(ReportPeriod.YEAR)).performClick()
        awaitNode(hasTestTag(reportMonthTag(october)))
        onNodeWithTag(reportMonthTag(october)).assertTextContains("3")
        onNodeWithTag(reportWhatTag(key)).performClick()
        onNodeWithTag(REPORTS_TOTAL_TAG).assertTextContains("проведено 2 · со списанием 1 · отменено 1", substring = true)
        // Месяц года — открывает его отчёт.
        onNodeWithTag(reportMonthTag(october)).press()
        awaitNode(hasTestTag(reportRowTag(key)))
        onNodeWithTag(REPORTS_TITLE_TAG).assertTextContains(ReportFormat.month(october))

        // Свой период по умолчанию — с 1-го по сегодня.
        onNodeWithTag(reportPeriodTag(ReportPeriod.CUSTOM)).performClick()
        awaitNode(hasTestTag(REPORTS_FROM_TAG))
        awaitNode(hasTestTag(reportRowTag(key)))
        onNodeWithTag(reportRowTag(key)).assertTextContains("3")
    }

    @Test
    fun wideYearGrid() = runComposeUiTest {
        var width by mutableStateOf(760.dp)
        start { width }
        awaitNode(hasTestTag(reportRowTag(key)))
        onNodeWithTag(reportPeriodTag(ReportPeriod.YEAR)).performClick()
        awaitNode(hasTestTag(REPORTS_GRID_TAG))
        awaitNode(hasText("2 / 1 / 1"))
        // Сложили — выбранный «Год» остаётся, таблица сменяется месяцами.
        width = 412.dp
        awaitNode(hasTestTag(reportMonthTag(october)))
    }
}
