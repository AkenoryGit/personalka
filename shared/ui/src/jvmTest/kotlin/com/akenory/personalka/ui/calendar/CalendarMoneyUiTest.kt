package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.CalendarMoneyFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.DayMoney
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Деньги в календаре (ТЗ 5.3, шаг 5.10г): суммы дней в «Месяце» и в строке недели, строка месяца, «Деньги по
 * месяцам» и флажок в меню вида. Сегодня — вт 20.10.2026; ПТ по 920 ₽ проведены 15-го, 16-го и 19-го, на 28-е
 * запланирована ещё одна. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarMoneyUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val prefs = MemoryPrefs()
    private val october = YearMonth(2026, 10)

    private fun rub(r: Long) = r * 100
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun prepare(setup: Boolean = true) = runBlocking {
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val bb = settings.places.first().single { it.shortName == "ББ" }.id
        if (setup) {
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
        }
        val coach = db.coachDao().currentCoach()!!.id
        val now = TestData.clock()
        listOf(15 to TrainingStatus.DONE, 16 to TrainingStatus.DONE, 19 to TrainingStatus.DONE, 28 to TrainingStatus.PLANNED)
            .forEachIndexed { i, (day, status) ->
                db.trainingDao().upsertTraining(TrainingEntity("t$i", SyncFields.new(coach, now), pt, bb, LocalDateTime(2026, 10, day, 9, 0), 50, status = status))
                val p = if (status == TrainingStatus.DONE) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED
                db.trainingDao().upsertParticipation(ParticipationEntity("p$i", SyncFields.new(coach, now), "t$i", "client", p))
            }
    }

    private fun ComposeUiTest.openCalendar() {
        setContent {
            Box(Modifier.size(412.dp, 960.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), prefs, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
    }

    private fun ComposeUiTest.chooseView(label: String) {
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        onNodeWithText(s(label)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.exists(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.money(date: LocalDate): DayMoney? =
        onAllNodes(hasTestTag(monthDayTag(date))).fetchSemanticsNodes().first().config.getOrElseNullable(MonthMoney) { null }

    private fun ComposeUiTest.awaitMoney(date: LocalDate) {
        waitUntil(timeoutMillis = 5_000) { exists(monthDayTag(date)) && money(date) != null }
    }

    @Test
    fun monthShowsDayMoneyLineAndMonths() = runComposeUiTest {
        prepare()
        openCalendar()
        chooseView("Месяц")

        // Прошедший день — заработано жирным, будущий — «будет» бледным; день без тренировок — без денег.
        val fifteenth = LocalDate(2026, 10, 15)
        val planned = LocalDate(2026, 10, 28)
        awaitMoney(fifteenth)
        assertEquals(DayMoney(rub(920), forecast = false, level = 4), money(fifteenth))
        awaitMoney(planned)
        assertTrue(money(planned)!!.forecast)
        assertNull(money(LocalDate(2026, 10, 21)))
        // Соседний месяц в крайних строках — без денег.
        assertNull(money(LocalDate(2026, 9, 28)))

        // Строка месяца над сеткой: заработано и «будет».
        awaitNode(hasTestTag(CALENDAR_MONEY_LINE_TAG))
        val line = onNodeWithTag(CALENDAR_MONEY_LINE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(line.startsWith("Октябрь: 2${RuText.THIN}760${RuText.NBSP}₽ заработано"), line)

        // Выбран день с деньгами — строка под заголовком дня.
        onNodeWithTag(monthDayTag(fifteenth)).performClick()
        waitForIdle()
        onNodeWithTag(dayMoneyTag(fifteenth)).assertExists()

        // «Деньги по месяцам»: нажатие на месяц закрывает окно.
        onNodeWithTag(CALENDAR_MONEY_LINE_TAG).performClick()
        awaitNode(hasTestTag(CALENDAR_MONTHS_PANEL_TAG))
        awaitNode(hasText(CalendarMoneyFormat.yearTotal(2026)))
        onNodeWithTag(monthsRowTag(october)).performClick()
        waitForIdle()
        assertFalse(exists(CALENDAR_MONTHS_PANEL_TAG))
    }

    @Test
    fun toggleHidesMoneyAndIsRemembered() = runComposeUiTest {
        prepare()
        openCalendar()
        chooseView("Месяц")
        awaitNode(hasTestTag(CALENDAR_MONEY_LINE_TAG))
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        onNodeWithTag(CALENDAR_MONEY_TOGGLE_TAG).performClick()
        waitForIdle()
        assertFalse(exists(CALENDAR_MONEY_LINE_TAG))
        assertNull(money(LocalDate(2026, 10, 15)))
        assertEquals(false, prefs.boolean(DevicePrefs.CALENDAR_SHOW_MONEY))
    }

    @Test
    fun weekStripShowsDayMoney() = runComposeUiTest {
        prepare()
        openCalendar()
        // «День»: под числом понедельника 19.10 — заработано.
        val monday = LocalDate(2026, 10, 19)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(weekDayTag(monday))).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.singleOrNull()?.contains("920") == true
        }
    }

    @Test
    fun noMoneyWithoutSalary() = runComposeUiTest {
        prepare(setup = false)
        openCalendar()
        chooseView("Месяц")
        waitForIdle()
        assertFalse(exists(CALENDAR_MONEY_LINE_TAG))
        // Флажка в меню нет: считать нечего.
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        waitForIdle()
        assertFalse(exists(CALENDAR_MONEY_TOGGLE_TAG))
    }
}
