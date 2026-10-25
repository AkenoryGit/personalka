package com.akenory.personalka.ui.salary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.format.SettlementFormat
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

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

    @Test
    fun chartAndCloseMonth() = runComposeUiTest {
        start()
        // График по дням кончается на «Заработано» (1 840 ₽ к 20.10), прогноз — 2 760 ₽.
        onNodeWithTag(SALARY_CHART_TAG).performScrollTo().assertExists()
        onNode(hasContentDescription(SalaryFormat.chartDescription(rub(1840), LocalDate(2026, 10, 20), rub(2760), null))).assertExists()

        onNodeWithTag(SALARY_CLOSE_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(SALARY_CLOSED_TAG))
        onNodeWithText(SalaryFormat.closedNote(october)).assertExists()
        assertEquals(true, runBlocking { db.moneyDao().monthRecord(october) }?.closed)

        // Сообщение «Октябрь сверен» ещё стоит внизу поверх кнопки — нажатие действием, а не касанием.
        onNodeWithTag(SALARY_CLOSE_TAG).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.moneyDao().monthRecord(october) }?.closed == false }
        awaitNode(hasText(SalaryFormat.closeNote(october)))
        assertEquals(false, runBlocking { db.moneyDao().monthRecord(october) }?.closed)
    }

    @Test
    fun dayAndPeriod() = runComposeUiTest {
        start()
        onNodeWithText(SalaryFormat.DAY_HINT).performScrollTo().assertExists()
        // Нажатие на 15-е: одна ПТ 920 ₽.
        onNodeWithTag(SALARY_CHART_TAG).performScrollTo().performTouchInput {
            val left = 44.dp.toPx()
            val right = width - 4.dp.toPx()
            click(Offset(left + 15f / 31f * (right - left), height / 2f))
        }
        awaitNode(hasTestTag(SALARY_DAY_TAG))
        onNodeWithTag(SALARY_DAY_TAG).assertTextContains(SalaryFormat.dayTitle(LocalDate(2026, 10, 15), rub(920)))
        onNodeWithText("09:00 ПТ · ББ").assertExists()

        // «Период» по умолчанию — с 1-го по сегодня: обе ПТ.
        onNodeWithTag(SALARY_PERIOD_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(PERIOD_TOTAL_TAG))
        onNodeWithTag(PERIOD_TOTAL_TAG).assertTextContains(MoneyFormat.rub(rub(1840)))
        onNodeWithTag(PERIOD_FROM_TAG).assertTextContains("01.10.2026")
        onNodeWithTag(PERIOD_TO_TAG).assertTextContains("20.10.2026")
        onNodeWithText(SalaryFormat.CLOSE).performScrollTo().performClick()
        awaitNode(hasTestTag(SALARY_DAY_TAG))
    }

    /** «Коллеги» (3.8): вкладка только с совместным ведением; замена у Лены — итог, тренировки, «Рассчитались». */
    @Test
    fun colleaguesTab() = runComposeUiTest {
        prepare()
        val lena = runBlocking {
            val group = typeId("Группа")
            val bb = placeId("ББ")
            settings.rates.saveRates(group, LocalDate(2026, 1, 1), mapOf(bb to RateAmounts(rub(270))))
            val lena = settings.colleagues.add("Лена", PaySchemeKind.HALF_OFFICIAL, TestData.now.date)
            val coach = db.coachDao().currentCoach()!!.id
            val now = TestData.clock()
            // Группа Лены 14.10, провёл я, 8 детей: Лена отдаёт 2 160 × (1 − 0,065) = 2 019,60 ₽.
            db.trainingDao().upsertTraining(
                TrainingEntity(
                    "g1", SyncFields.new(coach, now), group, bb, LocalDateTime(2026, 10, 14, 18, 0), 60,
                    status = TrainingStatus.DONE, owner = Owner.COLLEAGUE, ownerColleagueId = lena,
                ),
            )
            repeat(8) { db.trainingDao().upsertParticipation(ParticipationEntity("pg$it", SyncFields.new(coach, now), "g1", "c$it", ParticipationStatus.ATTENDED)) }
            lena
        }
        var shared: String? = null
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    shareText = { shared = it }, clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.SALARY)))
        onNodeWithTag(navTag(Section.SALARY)).performClick()
        awaitNode(hasTestTag(SALARY_EARNED_TAG))
        onNodeWithTag(salaryTabTag(SalaryTab.COLLEAGUES)).assertDoesNotExist()

        runBlocking { settings.salary.saveCoCoaching(true) }
        awaitNode(hasTestTag(salaryTabTag(SalaryTab.COLLEAGUES)))
        onNodeWithTag(salaryTabTag(SalaryTab.COLLEAGUES)).performClick()
        awaitNode(hasTestTag(colleagueTag(lena)))
        onNodeWithText(SettlementFormat.total("Лена", 201_960)).assertExists()
        onNodeWithText(SettlementFormat.caseTitle(GroupCase.THEIRS_BY_ME)).performClick()
        awaitNode(hasText("(1 − 0,065) от каждой"))

        onNodeWithTag(shareTag(lena)).performScrollTo().performClick()
        assertTrue(shared!!.endsWith("Итого: ты отдаёшь мне 2\u202f019,60\u00a0₽"), shared)

        onNodeWithTag(settledTag(lena)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.moneyDao().settlement(october, lena) }?.amount == 201_960L }
        awaitNode(hasText(SettlementFormat.UNSETTLE))

        // Выключено — вкладки нет, записанное остаётся.
        runBlocking { settings.salary.saveCoCoaching(false) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(salaryTabTag(SalaryTab.COLLEAGUES))).fetchSemanticsNodes().isEmpty() }
        awaitNode(hasTestTag(SALARY_EARNED_TAG))
    }

    @Test
    fun yearAndBackToMonth() = runComposeUiTest {
        start()
        onNodeWithTag(salaryTabTag(SalaryTab.YEAR)).performClick()
        awaitNode(hasTestTag(SALARY_YEAR_EARNED_TAG))
        // Год — с октября (до него пусто), итог равен октябрю во вкладке «Месяц».
        onNodeWithText("2026").assertExists()
        onNodeWithTag(SALARY_YEAR_EARNED_TAG).assertTextContains(MoneyFormat.rub(rub(1840)))
        onNodeWithText("октябрь ${SalaryFormat.MONTH_GOING}").assertExists()
        onNodeWithTag(SALARY_YEAR_CHART_TAG).assertExists()
        onNodeWithTag(salaryYearRowTag(YearMonth(2026, 9))).assertDoesNotExist()
        // Прошлый год пуст.
        onNodeWithTag(SALARY_PREV_TAG).performClick()
        awaitNode(hasText(SalaryFormat.YEAR_EMPTY))
        onNodeWithTag(SALARY_NEXT_TAG).performClick()
        awaitNode(hasTestTag(salaryYearRowTag(october)))
        onNodeWithTag(salaryYearRowTag(october)).performScrollTo().performClick()
        awaitNode(hasTestTag(SALARY_EARNED_TAG))
        onNodeWithText(SalaryFormat.month(october)).assertExists()
    }
}
