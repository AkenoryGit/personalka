package com.akenory.personalka.ui.reconcile

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
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/** «Ещё → Сверки» (шаг 3.9в, ТЗ 5.14): месяц зарплаты и дежурства октября. «Сейчас» — вт 20.10.2026. */
@OptIn(ExperimentalTestApi::class)
class ReconcileUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val october = YearMonth(2026, 10)

    private fun duty(id: String, day: Int, hour: Int, status: TrainingStatus, coverFor: String? = null) = runBlocking {
        val type = settings.types.first().first { it.kind == TrainingKind.DUTY }.id
        val place = settings.places.first().single { it.shortName == "ББ" }.id
        val coach = db.coachDao().currentCoach()!!.id
        db.trainingDao().upsertTraining(
            TrainingEntity(id, SyncFields.new(coach, TestData.clock()), type, place, LocalDateTime(2026, 10, day, hour, 0), 240, status = status, coverFor = coverFor),
        )
    }

    private fun ComposeUiTest.start() {
        runBlocking {
            val type = settings.types.first().first { it.kind == TrainingKind.DUTY }.id
            settings.rates.saveRates(type, LocalDate(2026, 1, 1), mapOf(null to RateAmounts(20_000)))
        }
        duty("d1", 6, 8, TrainingStatus.DONE, coverFor = "Александра")
        duty("d2", 13, 15, TrainingStatus.DONE)
        duty("d3", 27, 15, TrainingStatus.PLANNED)
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.RECONCILE)).assertTextContains(ReconcileFormat.TITLE).performClick()
        awaitNode(hasTestTag(RECONCILE_SALARY_TAG))
    }

    // Сообщение внизу может стоять поверх кнопки — нажатие действием, а не касанием.
    private fun SemanticsNodeInteraction.press() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun salaryMonthAndDuties() = runComposeUiTest {
        start()
        onNodeWithTag(RECONCILE_TITLE_TAG).assertTextContains(SalaryFormat.month(october))

        // «Месяц сверен» — здесь, а не в «Зарплате».
        onNodeWithTag(RECONCILE_CLOSE_TAG).press()
        awaitNode(hasTestTag(RECONCILE_CLOSED_TAG))
        assertEquals(true, runBlocking { db.moneyDao().monthRecord(october) }?.closed)

        // Дежурства: две проведённые по 4 ч × 200 ₽, одна впереди.
        onNodeWithTag(RECONCILE_DUTY_TOTAL_TAG).performScrollTo().assertTextContains("Проведено 8", substring = true)
        onNodeWithText(ReconcileFormat.coverFor("Александра"), substring = true).assertExists()
        onNodeWithText(ReconcileFormat.plannedTotal(240)).assertExists()
        onNodeWithTag(RECONCILE_DUTY_CHECK_TAG).press()
        awaitNode(hasTestTag(RECONCILE_DUTY_CHECKED_TAG))
        assertEquals(480, runBlocking { db.moneyDao().monthRecord(october) }?.dutiesCheckedMin)

        // Ещё одно проведённое — отметка показывает, что часы изменились.
        duty("d4", 15, 8, TrainingStatus.DONE)
        awaitNode(hasText(ReconcileFormat.changed(480, 720)))

        // Ноябрь ещё не начался — сверять нечего.
        onNodeWithTag(RECONCILE_NEXT_TAG).performClick()
        awaitNode(hasText(ReconcileFormat.future(YearMonth(2026, 11))))
    }
}
