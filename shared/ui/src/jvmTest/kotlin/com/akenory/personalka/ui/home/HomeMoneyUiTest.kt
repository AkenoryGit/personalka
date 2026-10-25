package com.akenory.personalka.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.home.TodoKind
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
import com.akenory.personalka.ui.salary.ADVANCE_FIELD_TAG
import com.akenory.personalka.ui.salary.categoryTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/** «Деньги» и аванс на «Главной» (шаг 3.9, ТЗ 5.2, 5.19). Две ПТ проведены, одна впереди; имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class HomeMoneyUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val october = YearMonth(2026, 10)

    private fun rub(r: Long) = r * 100
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun prepare() = runBlocking {
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val bb = settings.places.first().single { it.shortName == "ББ" }.id
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

    private fun ComposeUiTest.openHome(now: LocalDateTime = TestData.now) {
        setContent {
            Box(Modifier.size(412.dp, 960.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = { ClubTime.toMillis(now) })
            }
        }
        awaitNode(hasTestTag(MONEY_TAG))
    }

    @Test
    fun notSetInvitesToSetup() = runComposeUiTest {
        openHome()
        awaitNode(hasText(HomeFormat.SALARY_NOT_SET))
        onNodeWithTag(MONEY_SETUP_TAG).performScrollTo().performClick()
        // «Настроить зарплату» — мастер в «Зарплате» (5.18).
        awaitNode(hasTestTag(categoryTag(CoachCategory.MASTER)))
    }

    @Test
    fun earnedPlanAndTier() = runComposeUiTest {
        prepare()
        openHome()
        // Карточка — одна кнопка: её строки собраны в одном узле.
        awaitNode(hasTestTag(MONEY_TAG) and hasText(MoneyFormat.rub(rub(1840))))
        awaitNode(hasText(HomeFormat.moneyTitle(TestData.now.date)))
        // Плана нет — вместо полосы прогноз суммой.
        awaitNode(hasText(SalaryFormat.forecast(rub(2760))))
        awaitNode(hasText(s("2_ПТ · ступень 1 · до_101 ещё 99_ПТ")))
        // План 3 680 ₽: проведено 50 %, прогноз 75 %.
        runBlocking { settings.months.savePlan(october, rub(3680)) }
        awaitNode(hasTestTag(MONEY_TAG) and hasText(SalaryFormat.ofPlan(50)))
        awaitNode(hasText(HomeFormat.forecastPercent(75)))
        // Нажатие — «Зарплата».
        onNodeWithTag(MONEY_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(com.akenory.personalka.ui.salary.SALARY_EARNED_TAG))
    }

    @Test
    fun advanceFromTodoInTwoTaps() = runComposeUiTest {
        prepare()
        // До 25-го пункта нет.
        openHome(LocalDateTime(2026, 10, 25, 10, 0))
        awaitNode(hasTestTag(todoTag(TodoKind.ADVANCE)))
        awaitNode(hasText(HomeFormat.ADVANCE))
        onNodeWithTag(todoTag(TodoKind.ADVANCE)).performClick()
        // Сразу «Выплаты» с окном аванса: сумма подставлена из ожидаемой.
        awaitNode(hasTestTag(ADVANCE_FIELD_TAG))
        onNodeWithTag(ADVANCE_FIELD_TAG).assertTextContains("10000")
        onNodeWithTag(ADVANCE_FIELD_TAG).performTextReplacement("9500")
        awaitNode(hasText(SalaryFormat.SAVE))
        onNode(hasText(SalaryFormat.SAVE)).performClick()
        awaitNode(hasText(SalaryFormat.ADVANCE_RECEIVED))
        assertEquals(rub(9500), runBlocking { db.moneyDao().monthRecord(october) }?.advanceActual)
        // Сумма введена — пункт уходит.
        onNodeWithTag(navTag(Section.HOME)).performClick()
        awaitNode(hasTestTag(MONEY_TAG))
        assertEquals(0, onAllNodes(hasTestTag(todoTag(TodoKind.ADVANCE))).fetchSemanticsNodes().size)
    }

    @Test
    fun noAdvanceTodoBeforeAdvanceDay() = runComposeUiTest {
        prepare()
        openHome(LocalDateTime(2026, 10, 24, 10, 0))
        awaitNode(hasTestTag(MONEY_TAG) and hasText(MoneyFormat.rub(rub(1840))))
        assertEquals(0, onAllNodes(hasTestTag(todoTag(TodoKind.ADVANCE))).fetchSemanticsNodes().size)
    }
}
