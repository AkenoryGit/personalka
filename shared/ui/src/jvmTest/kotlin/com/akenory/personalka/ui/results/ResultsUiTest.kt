package com.akenory.personalka.ui.results

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.results.ResultDraft
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.SwimEvent
import com.akenory.personalka.domain.training.CardMenu
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.clients.CARD_ADD_RESULT_TAG
import com.akenory.personalka.ui.clients.CardTab
import com.akenory.personalka.ui.clients.cardTabTag
import com.akenory.personalka.ui.clients.clientRowTag
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.home.NEAREST_OPEN_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.CARD_MENU_TAG
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Результаты (шаг 2.3, ТЗ 5.11, 5.9, макет ClCardResults). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class ResultsUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val today = TestData.now.date
    private val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 25)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun results(clientId: String) = runBlocking { clients.results.results(clientId).first() }

    private fun ComposeUiTest.openApp() {
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, MemoryPrefs(), clock = TestData.clock) } }
    }

    private fun ComposeUiTest.openCard(name: String) {
        openApp()
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(clientRowTag(name)))
        onNodeWithTag(clientRowTag(name)).performClick()
        awaitNode(hasTestTag(CARD_ADD_RESULT_TAG))
    }

    private fun ComposeUiTest.typeTime(text: String) {
        onNodeWithTag(RESULT_TIME_TAG).performScrollTo().performTextInput(text)
    }

    private fun ComposeUiTest.save() {
        onNode(hasText(ResultFormat.SAVE) and hasAnyAncestor(hasTestTag(PANEL_TAG))).performScrollTo().performClick()
    }

    @Test
    fun firstResultOpensTab() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        openCard("Орлова Ксения")
        // Результатов нет — нет и вкладки «Результаты» (10.7).
        assertEquals(0, onAllNodes(hasTestTag(cardTabTag(CardTab.RESULTS))).fetchSemanticsNodes().size)

        onNodeWithTag(CARD_ADD_RESULT_TAG).performClick()
        awaitNode(hasText(ResultFormat.NEW_TITLE))
        // Критерий шага: «1:03,17», набранное с двоеточием и запятой, читается как «10317».
        typeTime("1:03,17")
        save()
        awaitNode(hasText(ResultFormat.SAVED))
        waitUntil(timeoutMillis = 5_000) { results(orlova).isNotEmpty() }
        val saved = results(orlova).single()
        assertEquals(6317, saved.timeCs)
        assertEquals(today, saved.date)
        assertEquals(free50, saved.event)
        // Вкладка появилась и открыта: рекорд и строка в «Все результаты».
        awaitNode(hasTestTag(cardTabTag(CardTab.RESULTS)))
        onNodeWithTag(cardTabTag(CardTab.RESULTS)).assertIsSelected()
        awaitNode(hasText(ResultFormat.RECORDS))
        awaitNode(hasTestTag(resultRowTag(saved.id)))
    }

    @Test
    fun recordShownWhileTyping() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        runBlocking { clients.results.save(ResultDraft(orlova, LocalDate(2026, 10, 1), free50, 4662, ResultKind.TRAINING)) }
        openCard("Орлова Ксения")
        onNodeWithTag(CARD_ADD_RESULT_TAG).performClick()
        awaitNode(hasText(ResultFormat.NEW_TITLE))
        // Дистанция — как у последнего результата; «3962» быстрее 46,62 на 7 секунд.
        typeTime("3962")
        awaitNode(hasTestTag(RESULT_RECORD_TAG))
        onNodeWithText(s("Личный рекорд, −_7,00")).assertExists()
        save()
        awaitNode(hasText(s("Личный рекорд, −_7,00")) and hasAnyAncestor(hasTestTag(PANEL_TAG)).not())
        waitUntil(timeoutMillis = 5_000) { results(orlova).size == 2 }
        // Цифры подряд и с запятой — одно и то же время.
        assertEquals(listOf(4662, 3962), results(orlova).map { it.timeCs })
        // График есть: на дистанции два результата.
        awaitNode(hasTestTag(RESULT_CHART_TAG))
    }

    @Test
    fun editDeleteAndUndo() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        runBlocking { clients.results.save(ResultDraft(orlova, LocalDate(2026, 10, 10), free50, 3962, ResultKind.COMPETITION, "Кубок города")) }
        val id = results(orlova).single().id
        openCard("Орлова Ксения")
        // Вкладка появляется, когда результаты прочитаны из базы.
        awaitNode(hasTestTag(cardTabTag(CardTab.RESULTS)))
        onNodeWithTag(cardTabTag(CardTab.RESULTS)).performClick()
        awaitNode(hasTestTag(resultRowTag(id)))
        onNodeWithTag(resultRowTag(id)).performScrollTo().performClick()
        awaitNode(hasText(ResultFormat.EDIT_TITLE))
        onNodeWithTag(RESULT_DELETE_TAG).performScrollTo().performClick()
        // Сначала окно «Удалить результат?» с тем, что удаляется; до «Удалить» в нём ничего не стёрто.
        awaitNode(hasText(ResultFormat.DELETE_TITLE))
        onNodeWithText(s("50_м вольный стиль · 39,62 · 10.10.2026")).assertExists()
        assertEquals(1, results(orlova).size)
        onNodeWithTag(RESULT_DELETE_CONFIRM_TAG).performClick()
        awaitNode(hasText(ResultFormat.DELETED))
        waitUntil(timeoutMillis = 5_000) { results(orlova).isEmpty() }
        onNodeWithText(MessageState.UNDO_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { results(orlova).isNotEmpty() }
        assertEquals("Кубок города", results(orlova).single().eventName)
    }

    @Test
    fun editFromRecord() = runComposeUiTest {
        // Рекорд тоже открывает форму: время поправлено, запись та же.
        val orlova = client("Орлова", "Ксения")
        runBlocking { clients.results.save(ResultDraft(orlova, LocalDate(2026, 10, 10), free50, 3962, ResultKind.TRAINING)) }
        val id = results(orlova).single().id
        openCard("Орлова Ксения")
        awaitNode(hasTestTag(cardTabTag(CardTab.RESULTS)))
        onNodeWithTag(cardTabTag(CardTab.RESULTS)).performClick()
        awaitNode(hasText(ResultFormat.EDIT_HINT))
        onNodeWithTag(recordRowTag(id)).performScrollTo().performClick()
        awaitNode(hasText(ResultFormat.EDIT_TITLE) and hasAnyAncestor(hasTestTag(PANEL_TAG)))
        onNodeWithTag(RESULT_TIME_TAG).performScrollTo().performTextClearance()
        typeTime("4012")
        save()
        waitUntil(timeoutMillis = 5_000) { results(orlova).single().timeCs == 4012 }
        assertEquals(id, results(orlova).single().id)
    }

    @Test
    fun addFromTrainingCard() = runComposeUiTest {
        // Тренировка в 15:50, с 15:35 её можно отметить — в «…» карточки есть «Добавить результат» с днём и клиентом тренировки.
        val orlov = client("Орлов", "Максим")
        runBlocking {
            val pt = settings.types.first().single { it.shortName == "ПТ" }.id
            val bb = settings.places.first().single { it.shortName == "ББ" }.id
            calendar.forms.create(TrainingDraft(LocalDateTime(today, LocalTime(15, 50)), pt, listOf(orlov), null, bb, 50))
        }
        openApp()
        awaitNode(hasTestTag(NEAREST_OPEN_TAG))
        onNodeWithTag(NEAREST_OPEN_TAG).performClick()
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(TrainingCardFormat.menu(CardMenu.RESULT)).performClick()
        awaitNode(hasText(ResultFormat.NEW_TITLE))
        typeTime("5216")
        save()
        waitUntil(timeoutMillis = 5_000) { results(orlov).isNotEmpty() }
        val saved = results(orlov).single()
        assertEquals(5216 to today, saved.timeCs to saved.date)
    }
}
