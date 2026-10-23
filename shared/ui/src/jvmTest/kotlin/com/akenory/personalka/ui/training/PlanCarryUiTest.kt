package com.akenory.personalka.ui.training

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.CALENDAR_GRID_TAG
import com.akenory.personalka.ui.calendar.CalendarBlocks
import com.akenory.personalka.ui.calendar.calendarDayTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Перенос плана (шаг 2.2, ТЗ 4.3, макеты «После отмены» и «У следующей уже есть план»). «Сейчас» — вт 20.10.2026
 * 15:40. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class PlanCarryUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date

    private val brass = "Брасс: согласование рук и ног, 8 × 25 м."
    private val back = "Кроль на спине: 4 × 50 м, повороты."

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun training(start: LocalDateTime, clientId: String, plan: String): String = runBlocking {
        val type = settings.types.first().single { it.shortName == "ПТ" }.id
        val place = settings.places.first().single { it.shortName == "ББ" }.id
        calendar.forms.create(TrainingDraft(start, type, listOf(clientId), null, place, 50, null, plan))
        db.trainingDao().trainingsBetween(start, LocalDateTime(start.date, LocalTime(23, 59))).first { it.start == start }.id
    }

    private fun inCard(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(TRAINING_CARD_TAG))

    private fun inPanel(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag(CARRY_PANEL_TAG))

    /** Открыть календарь и нажать на блок «Орлов М.». */
    private fun ComposeUiTest.openOrlov() {
        setContent {
            Box(Modifier.size(412.dp, 960.dp)) {
                App("0.1.0", settings, clients, groups, calendar, MemoryPrefs(), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_GRID_TAG))
        val name = s("Орлов_М.")
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag(calendarDayTag(today))).fetchSemanticsNodes().isNotEmpty() &&
                onNodeWithTag(calendarDayTag(today)).fetchSemanticsNode().config[CalendarBlocks].blocks.any { it.lines.first() == name }
        }
        val block = onNodeWithTag(calendarDayTag(today)).fetchSemanticsNode().config[CalendarBlocks].blocks.first { it.lines.first() == name }
        val scrolled = onNodeWithTag(CALENDAR_GRID_TAG).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() / density.density
        val point = with(density) { Offset((48f + block.x + 20f).dp.toPx(), (block.y + 15f - scrolled).dp.toPx()) }
        onNodeWithTag(CALENDAR_GRID_TAG).performTouchInput { click(point) }
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
    }

    @Test
    fun cancelAsksAddThenReturnTakesItBack() = runComposeUiTest {
        val orlov = runBlocking { clients.create(ClientDraft("Орлов", "Максим")) }
        val id = training(LocalDateTime(2026, 10, 20, 17, 0), orlov, brass)
        val next = training(LocalDateTime(2026, 10, 27, 17, 0), orlov, back)
        openOrlov()

        // «Отменить» → «Отменить тренировку»: у следующей уже есть план — вопрос с обоими текстами.
        awaitNode(inCard(TrainingCardFormat.action(CardAction.CANCEL)))
        onNode(inCard(TrainingCardFormat.action(CardAction.CANCEL))).performClick()
        awaitNode(hasTestTag(CANCEL_PANEL_TAG))
        onNode(hasText(TrainingCardFormat.CANCEL_TRAINING) and hasClickAction()).performClick()
        awaitNode(hasTestTag(CARRY_PANEL_TAG))
        onNode(inPanel(TrainingCardFormat.CARRY_TITLE)).assertIsDisplayed()
        onNode(inPanel(s("Орлов_М. · вт_27.10, 17:00"))).assertIsDisplayed()
        onNode(inPanel(brass)).assertIsDisplayed()
        onNode(inPanel(back)).assertIsDisplayed()

        // «Добавить с новой строки» — главная; сообщение как в макете «После отмены».
        onNode(inPanel(TrainingCardFormat.CARRY_ADD)).performClick()
        awaitNode(hasText(s("Отменено. План перенесён на вт_27.10")))
        assertEquals("$back\n$brass", runBlocking { db.trainingDao().training(next)!!.plan })
        awaitNode(inCard(s("План перенесён на вт_27.10")))

        // «Вернуть»: перенесённое не меняли — уходит из следующей само.
        onNode(inCard(TrainingCardFormat.action(CardAction.RETURN))).performClick()
        awaitNode(hasText(s("Тренировка возвращена. План убран из вт_27.10")))
        assertEquals(TrainingStatus.PLANNED, runBlocking { db.trainingDao().training(id)!!.status })
        assertEquals(back, runBlocking { db.trainingDao().training(next)!!.plan })
    }
}
