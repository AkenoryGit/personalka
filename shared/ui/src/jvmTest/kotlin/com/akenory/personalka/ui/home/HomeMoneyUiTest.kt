package com.akenory.personalka.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
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
import com.akenory.personalka.ui.salary.SOURCE_EXAMPLE
import com.akenory.personalka.ui.salary.sourceTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/** «Деньги» и аванс на «Главной» (шаги 3.9 и 3.9а, ТЗ 5.2, 5.19). Две ПТ проведены, одна впереди; имена придуманы. */
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
                scheme = PaySchemeKind.FULL_OFFICIAL,
                advance = rub(10_000),
                schemeFrom = YearMonth(2026, 1),
                rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)))),
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
        awaitNode(hasTestTag(sourceTag(SOURCE_EXAMPLE)))
    }

    @Test
    fun earnedPlanAndTier() = runComposeUiTest {
        prepare()
        openHome()
        // Карточка — одна кнопка: её строки собраны в одном узле.
        awaitNode(hasTestTag(MONEY_TAG) and hasText(MoneyFormat.rub(rub(1840))))
        awaitNode(hasText(HomeFormat.moneyTitle(TestData.now.date)))
        // Месяц: проведено 2 ПТ, по расписанию будет 3; плана нет — полосы нет.
        awaitIn(MONEY_MONTH_TAG, HomeFormat.willBe(rub(1840), rub(2760)))
        awaitIn(MONEY_MONTH_TAG, s("2_ПТ · будет 3"))
        // 20.10 тренировок нет.
        awaitIn(MONEY_TODAY_TAG, HomeFormat.willBe(0, 0))
        awaitIn(MONEY_TODAY_TAG, "без ПТ")
        // Плашка ступени (шаг 3.9с): до 101-й ещё 99 ПТ; плана нет — ни полосы, ни оценки.
        awaitIn(MONEY_TIER_TAG, s("Ступень 1 · до бонуса 99_ПТ"))
        assertEquals(0, onAllNodes(hasTestTag(MONEY_TAG) and hasText("Поднажать", substring = true)).fetchSemanticsNodes().size)
        // План 3 680 ₽: прогноз 2 760 ₽ ниже — «Поднажать: до плана ещё 1 ПТ» (одна ПТ ББ добирает 920 ₽).
        runBlocking { settings.months.savePlan(october, rub(3680)) }
        awaitNode(hasTestTag(MONEY_TAG) and hasText(s("Поднажать: до плана ещё 1_ПТ")))
        // План 2 000 ₽: по расписанию выполняется; 1 500 ₽ — уже выполнен.
        runBlocking { settings.months.savePlan(october, rub(2000)) }
        awaitNode(hasTestTag(MONEY_TAG) and hasText(s("Хорошо идёте: прогноз 138_% плана")))
        runBlocking { settings.months.savePlan(october, rub(1500)) }
        awaitNode(hasTestTag(MONEY_TAG) and hasText(s("План выполнен: 122_%")))
        // Нажатие — «Зарплата». Проверяется заголовок месяца: месяц в этом длинном тесте через раз не успевал
        // посчитаться в Actions; сам «Заработано» проверяют тесты «Зарплаты».
        onNodeWithTag(MONEY_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(com.akenory.personalka.ui.salary.SALARY_TITLE_TAG))
    }

    @Test
    fun todayColumn() = runComposeUiTest {
        prepare()
        // Сегодня (20.10, 15:40) одна ПТ проведена утром, вторая — вечером.
        runBlocking {
            val pt = settings.types.first().single { it.shortName == "ПТ" }.id
            val bb = settings.places.first().single { it.shortName == "ББ" }.id
            val coach = db.coachDao().currentCoach()!!.id
            val now = TestData.clock()
            listOf(9 to TrainingStatus.DONE, 18 to TrainingStatus.PLANNED).forEachIndexed { i, (hour, status) ->
                db.trainingDao().upsertTraining(TrainingEntity("d$i", SyncFields.new(coach, now), pt, bb, LocalDateTime(2026, 10, 20, hour, 0), 50, status = status))
                val p = if (status == TrainingStatus.DONE) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED
                db.trainingDao().upsertParticipation(ParticipationEntity("dp$i", SyncFields.new(coach, now), "d$i", "client", p))
            }
            // Отменены: сегодня в 12:00 и 10-го — в колонках «1 отмена» и «2 отмены», деньги не меняются.
            listOf(LocalDateTime(2026, 10, 20, 12, 0), LocalDateTime(2026, 10, 10, 9, 0)).forEachIndexed { i, start ->
                db.trainingDao().upsertTraining(TrainingEntity("c$i", SyncFields.new(coach, now), pt, bb, start, 50, status = TrainingStatus.CANCELLED))
                db.trainingDao().upsertParticipation(ParticipationEntity("cp$i", SyncFields.new(coach, now), "c$i", "client", ParticipationStatus.CANCELLED))
            }
        }
        openHome()
        awaitIn(MONEY_TODAY_TAG, MoneyFormat.rub(rub(920)))
        awaitIn(MONEY_TODAY_TAG, HomeFormat.willBe(rub(920), rub(1840)))
        awaitIn(MONEY_TODAY_TAG, s("1_ПТ · будет 2"))
        awaitIn(MONEY_MONTH_TAG, MoneyFormat.rub(rub(2760)))
        awaitIn(MONEY_MONTH_TAG, HomeFormat.willBe(rub(2760), rub(4600)))
        awaitIn(MONEY_MONTH_TAG, s("3_ПТ · будет 5"))
        awaitIn(MONEY_TODAY_TAG, s("1_отмена"))
        awaitIn(MONEY_MONTH_TAG, s("2_отмены"))
    }

    /** Строка внутри колонки «Денег»: карточка — одна кнопка, поэтому ищем в несклеенном дереве. */
    private fun ComposeUiTest.awaitIn(column: String, text: String) {
        val matcher = hasAnyAncestor(hasTestTag(column)) and hasText(text)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
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
