package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.FORM_DATE_TAG
import com.akenory.personalka.ui.training.FORM_WEEKLY_TAG
import com.akenory.personalka.ui.training.TRAINING_FORM_TAG
import com.akenory.personalka.ui.training.TrainingFormKey
import com.akenory.personalka.ui.training.formClientTag
import com.akenory.personalka.ui.training.formWeekdayTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Расписание в карточке клиента (шаг 1.13б, ТЗ 5.9, 4.4, макет ClCardSchedule). «Сейчас» — вт 20.10.2026 15:40. */
@OptIn(ExperimentalTestApi::class)
class ClientScheduleUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val forms = calendar.forms
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun day(n: Int): LocalDate = today.plus(n, DateTimeUnit.DAY)

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    /** Серия ПТ в ББ вт и чт 13:30 с вт 06.10. */
    private fun series(clientId: String): String = runBlocking {
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val bb = settings.places.first().single { it.shortName == "ББ" }.id
        forms.create(TrainingDraft(LocalDateTime(day(-14), LocalTime(13, 30)), pt, listOf(clientId), null, bb, 50, Repeat(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))))
        db.scheduleDao().seriesOfClient(clientId).single().id
    }

    private fun trainings() = runBlocking {
        db.trainingDao().trainingsBetween(LocalDateTime(day(-30), LocalTime(0, 0)), LocalDateTime(day(90), LocalTime(0, 0)))
    }

    private fun ComposeUiTest.openCard(name: String) {
        setContent { Box(Modifier.size(412.dp, 900.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(clientRowTag(name)))
        onNodeWithTag(clientRowTag(name)).performClick()
        awaitNode(hasTestTag(CARD_ADD_TRAINING_TAG))
    }

    private fun ComposeUiTest.openSchedule() {
        awaitNode(hasTestTag(cardTabTag(CardTab.SCHEDULE)))
        onNodeWithTag(cardTabTag(CardTab.SCHEDULE)).performClick()
    }

    @Test
    fun formKeysCarryClientAndWeekly() {
        val start = LocalDateTime(today, LocalTime(17, 15))
        val key = TrainingFormKey.new(start, clientId = "c-1", weekly = true)
        assertEquals(start, TrainingFormKey.start(key))
        assertEquals("c-1", TrainingFormKey.clientId(key))
        assertTrue(TrainingFormKey.weekly(key))
        val plain = TrainingFormKey.new(clientId = "c-1")
        assertEquals(null, TrainingFormKey.start(plain))
        assertEquals("c-1", TrainingFormKey.clientId(plain))
        assertEquals(false, TrainingFormKey.weekly(plain))
        assertEquals(null, TrainingFormKey.clientId(TrainingFormKey.new(start)))
        assertEquals("s-1", TrainingFormKey.seriesId(TrainingFormKey.series("s-1")))
        assertEquals(null, TrainingFormKey.editId(TrainingFormKey.series("s-1")))
    }

    @Test
    fun addTrainingWithThisClient() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        openCard("Орлова Ксения")
        // Тренировок ещё нет — вкладок нет, а «+ Тренировка» уже есть.
        onNodeWithTag(cardTabTag(CardTab.SCHEDULE)).assertDoesNotExist()
        onNodeWithTag(CARD_ADD_TRAINING_TAG).performClick()

        // Форма с уже выбранной клиенткой: «Сохранить» — второе нажатие.
        awaitNode(hasTestTag(formClientTag(orlova)))
        onNodeWithText(TrainingFormat.NEW_TITLE).assertIsDisplayed()
        onNodeWithTag(formClientTag(orlova)).onChild().assertIsSelected()
        onNodeWithText(TrainingFormat.SAVE).performClick()
        waitUntil(timeoutMillis = 5_000) { trainings().size == 1 }
        val participants = runBlocking { db.trainingDao().participationsOf(trainings().single().id) }
        assertEquals(listOf(orlova), participants.map { it.clientId })
        onNodeWithTag(TRAINING_FORM_TAG).assertDoesNotExist()
    }

    @Test
    fun addSeriesIsWeekly() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        series(orlova)
        openCard("Орлова Ксения")
        openSchedule()
        onNodeWithTag(CARD_ADD_SERIES_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(formClientTag(orlova)))
        onNodeWithTag(formClientTag(orlova)).onChild().assertIsSelected()
        onNodeWithTag(FORM_WEEKLY_TAG).performScrollTo().onChild().assertIsSelected()
    }

    @Test
    fun changeFromDateKeepsPast() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        val id = series(orlova)
        val past = trainings().filter { it.start.date <= today }
        openCard("Орлова Ксения")
        openSchedule()
        awaitNode(hasText(s("Вт, Чт · 13:30–14:20")))
        onNodeWithText("ПТ · ББ · с 06.10.2026").assertIsDisplayed()

        onNodeWithTag(changeFromTag(id)).performScrollTo().performClick()
        awaitNode(hasText(TrainingFormat.SERIES_TITLE))
        onNodeWithTag(FORM_DATE_TAG).assertIsDisplayed()
        onNodeWithText(s("с вт_20.10.2026")).assertIsDisplayed()
        // Плюс среда.
        onNodeWithTag(formWeekdayTag(DayOfWeek.WEDNESDAY)).performScrollTo().performClick()
        onNodeWithText(TrainingFormat.SAVE).performClick()

        awaitNode(hasText(s("Расписание изменено с вт_20.10")))
        awaitNode(hasText(s("Вт, Ср, Чт · 13:30–14:20")))
        onNodeWithText("ПТ · ББ · 06.10–19.10.2026 · завершена").performScrollTo().assertIsDisplayed()
        // Прошлое не тронуто, дальше — и по средам.
        assertEquals(past.map { it.id to it.start }, trainings().filter { it.start.date <= today }.map { it.id to it.start })
        assertTrue(trainings().any { it.start.date == day(1) })
    }

    @Test
    fun endSeriesWithUndo() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        val id = series(orlova)
        val before = trainings().size
        openCard("Орлова Ксения")
        openSchedule()
        onNodeWithTag(endSeriesTag(id)).performScrollTo().performClick()

        awaitNode(hasText(ClientFormat.END_TITLE))
        onNodeWithText(s("вт_20.10.2026")).assertIsDisplayed()
        onNode(hasText(ClientFormat.END_SERIES) and hasAnyAncestor(hasTestTag(PANEL_TAG))).performClick()

        awaitNode(hasText(ClientFormat.SERIES_ENDED))
        waitUntil(timeoutMillis = 5_000) { trainings().none { it.start.date > today } }
        awaitNode(hasText("ПТ · ББ · 06.10–20.10.2026"))
        onNodeWithText("Отменить").performClick()
        waitUntil(timeoutMillis = 5_000) { trainings().size == before }
        awaitNode(hasText("ПТ · ББ · с 06.10.2026"))
    }

    @Test
    fun archivedClientHasNoButtons() = runComposeUiTest {
        val orlova = client("Орлова", "Ксения")
        series(orlova)
        runBlocking { clients.archive(orlova) }
        setContent { Box(Modifier.size(412.dp, 900.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(filterChipTag(ClientFilter.ARCHIVE)))
        onNodeWithTag(filterChipTag(ClientFilter.ARCHIVE)).performClick()
        awaitNode(hasTestTag(clientRowTag("Орлова Ксения")))
        onNodeWithTag(clientRowTag("Орлова Ксения")).performClick()
        openSchedule()
        // Серии при архиве пока не заканчиваются (4.4) — это ещё не сделано, см. PROGRESS.md.
        awaitNode(hasText("ПТ · ББ · с 06.10.2026"))
        onNodeWithTag(CARD_ADD_TRAINING_TAG).assertDoesNotExist()
        onNodeWithTag(CARD_ADD_SERIES_TAG).assertDoesNotExist()
    }
}
