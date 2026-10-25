package com.akenory.personalka.ui.salary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Зарплата»: месяц и выплаты (шаг 3.7, ТЗ 5.12). «Сейчас» — вт 20.10.2026. */
@OptIn(ExperimentalTestApi::class)
class SalaryMonthUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val october = YearMonth(2026, 10)

    private fun rub(r: Long) = r * 100
    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun prepare() = runBlocking {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        settings.salary.finish(
            SalarySetupDraft(
                category = CoachCategory.MASTER,
                scheme = PaySchemeKind.FULL_OFFICIAL,
                advance = rub(10_000),
                schemeFrom = YearMonth(2026, 1),
                rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)))),
                services = emptyMap(),
                tiers = TierDraft(true, 101, 151, setOf(pt)),
            ),
            TestData.now.date,
        )
        val coach = db.coachDao().currentCoach()!!.id
        val now = TestData.clock()
        listOf(15 to TrainingStatus.DONE, 16 to TrainingStatus.DONE, 28 to TrainingStatus.PLANNED).forEachIndexed { i, (day, status) ->
            db.trainingDao().upsertTraining(TrainingEntity("t$i", SyncFields.new(coach, now), pt, bb, LocalDateTime(2026, 10, day, 9, 0), 50, status = status))
            val p = if (status == TrainingStatus.DONE) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED
            db.trainingDao().upsertParticipation(ParticipationEntity("p$i", SyncFields.new(coach, now), "t$i", "client", p))
        }
    }

    private fun ComposeUiTest.start() {
        prepare()
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.SALARY)))
        onNodeWithTag(navTag(Section.SALARY)).performClick()
        awaitNode(hasTestTag(SALARY_EARNED_TAG))
    }

    @Test
    fun monthPlanAndAdjustment() = runComposeUiTest {
        start()
        onNodeWithText(SalaryFormat.month(october)).assertExists()
        onNodeWithTag(SALARY_EARNED_TAG).assertTextContains(MoneyFormat.rub(rub(1840)))
        onNodeWithText(SalaryFormat.forecast(rub(2760))).assertExists()

        onNodeWithTag(SALARY_ADD_ADJUST_TAG).performScrollTo().performClick()
        onNodeWithTag(ADJUST_AMOUNT_TAG).performTextReplacement("1200")
        onNodeWithText(SalaryFormat.addAdjustment(false)).performClick()
        // Без «за что» не сохраняется.
        awaitNode(hasText(SalaryFormat.ADJUST_FOR_EMPTY))
        onNodeWithTag(ADJUST_FOR_TAG).performTextReplacement("Судейство")
        onNodeWithText(SalaryFormat.earnedWillBe(rub(3040))).assertExists()
        onNodeWithText(SalaryFormat.addAdjustment(false)).performClick()
        awaitNode(hasText("Судейство"))
        onNodeWithTag(SALARY_TOTAL_TAG).performScrollTo().assertTextContains(MoneyFormat.rub(rub(3040)))

        onNodeWithTag(SALARY_PLAN_TAG).performScrollTo().performClick()
        onNodeWithTag(SALARY_PLAN_FIELD_TAG).performTextReplacement("3680")
        onNodeWithText(SalaryFormat.SAVE).performClick()
        awaitNode(hasText(SalaryFormat.done(82)))
        assertEquals(rub(3680), runBlocking { db.moneyDao().monthRecord(october) }?.planAmount)
    }

    @Test
    fun payoutsAndAdvance() = runComposeUiTest {
        start()
        onNodeWithTag(salaryTabTag(SalaryTab.PAYOUTS)).performClick()
        awaitNode(hasTestTag(SALARY_ON_HAND_TAG))
        // Полностью официально: 1 840 − 13 % = 1 600,80 на руки.
        onNodeWithTag(SALARY_ON_HAND_TAG).assertTextContains(MoneyFormat.rub(160_080))
        onNodeWithText(SalaryFormat.ADVANCE_EXPECTED).assertExists()
        onNodeWithTag(SALARY_ADVANCE_TAG).performClick()
        onNodeWithTag(ADVANCE_FIELD_TAG).assertTextContains("10000")
        onNodeWithTag(ADVANCE_FIELD_TAG).performTextReplacement("950")
        onNodeWithText(SalaryFormat.SAVE).performClick()
        awaitNode(hasText(SalaryFormat.ADVANCE_RECEIVED))
        assertEquals(rub(950), runBlocking { db.moneyDao().monthRecord(october) }?.advanceActual)
    }
}
