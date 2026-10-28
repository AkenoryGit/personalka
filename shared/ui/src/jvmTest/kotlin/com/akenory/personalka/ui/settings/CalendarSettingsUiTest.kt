package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.NOTICE_PANEL_TAG
import com.akenory.personalka.ui.home.noticeDoneTag
import com.akenory.personalka.ui.home.todoTag
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
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

/** «Ещё → Настройки → Календарь» (шаг 1.13г, ТЗ 5.16, 4.4). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class CalendarSettingsUiTest {
    private val settings = TestData.ready()
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun day(n: Int): LocalDate = today.plus(n, DateTimeUnit.DAY)

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun trainings() = runBlocking {
        db.trainingDao().trainingsBetween(LocalDateTime(day(-30), LocalTime(0, 0)), LocalDateTime(day(90), LocalTime(0, 0)))
    }

    private fun ComposeUiTest.openCalendarSettings() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), calendar, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(CALENDAR_ROW_TAG))
        onNodeWithTag(CALENDAR_ROW_TAG).performClick()
        awaitNode(hasTestTag(WORK_START_TAG))
        // «Отпуск» с шага 3.15а — свой пункт «Ещё», в «Календаре» его нет.
        onNodeWithTag(VACATION_TAG).assertDoesNotExist()
    }

    private fun ComposeUiTest.openVacation() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), calendar, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.VACATION)))
        onNodeWithTag(moreRowTag(MoreItem.VACATION)).performClick()
        awaitNode(hasTestTag(VACATION_TAG))
        onNodeWithTag(WORK_START_TAG).assertDoesNotExist()
    }

    @Test
    fun stepAndHorizonSavedAtOnce() = runComposeUiTest {
        openCalendarSettings()
        onNodeWithTag(WORK_START_TAG).assertIsDisplayed()
        onNodeWithText("с 07:00").assertIsDisplayed()
        onNodeWithText("до 23:00").assertIsDisplayed()
        onNodeWithTag(stepTag(15)).onChild().assertIsSelected()

        onNodeWithTag(stepTag(5)).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { calendar.dragStep.first() } == 5 }
        onNodeWithTag(horizonTag(12)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { calendar.settings.prefs.first().horizonWeeks } == 12 }
        onNodeWithTag(horizonTag(12)).onChild().assertIsSelected()

        // Часы, записанные в базу, сразу видны в полях.
        runBlocking { calendar.settings.saveWorkHours(LocalTime(6, 0), LocalTime(22, 0)) }
        awaitNode(hasText("с 06:00"))
        onNodeWithText("до 22:00").assertIsDisplayed()
    }

    @Test
    fun vacationDeletesSeriesDaysAndUndo() = runComposeUiTest {
        val client = runBlocking { TestData.clientsOf(settings).create(ClientDraft("Орлова", "Ксения")) }
        val pt = runBlocking { settings.types.first().single { it.shortName == "ПТ" }.id }
        // Серия по четвергам 13:30 с чт 22.10: в неделю отпуска попадает одна тренировка.
        runBlocking { calendar.forms.create(TrainingDraft(LocalDateTime(day(2), LocalTime(13, 30)), pt, listOf(client), null, placeId("ББ"), 50, Repeat(setOf(DayOfWeek.THURSDAY)))) }
        val before = trainings().size
        openVacation()

        onNodeWithTag(VACATION_TAG).performScrollTo().performClick()
        onNodeWithTag(VACATION_FROM_TAG).assertIsDisplayed()
        onNodeWithText(s("вт_20.10.2026")).assertIsDisplayed()
        onNodeWithText(s("пн_26.10.2026")).assertIsDisplayed()
        // До записи видно, кого предупредить, а на кнопке — сколько удалится; в базе пока всё на месте.
        awaitNode(hasTestTag(vacationRowTag(client)))
        onNodeWithText("Орлова Ксения").assertIsDisplayed()
        onNodeWithText(s("1_тренировка · чт_22.10")).assertIsDisplayed()
        assertEquals(before, trainings().size)
        onNodeWithText(s("Удалить 1_тренировку")).performScrollTo().performClick()

        awaitNode(hasText(s("Отпуск_20.10–26.10: удалена 1_тренировка")))
        assertEquals(before - 1, trainings().size)
        onNodeWithText("Отменить").performClick()
        waitUntil(timeoutMillis = 5_000) { trainings().size == before }
    }

    @Test
    fun vacationLeavesWarnTodoOnHome() = runComposeUiTest {
        val client = runBlocking { TestData.clientsOf(settings).create(ClientDraft("Орлова", "Ксения")) }
        val pt = runBlocking { settings.types.first().single { it.shortName == "ПТ" }.id }
        runBlocking { calendar.forms.create(TrainingDraft(LocalDateTime(day(2), LocalTime(13, 30)), pt, listOf(client), null, placeId("ББ"), 50, Repeat(setOf(DayOfWeek.THURSDAY)))) }
        openVacation()
        onNodeWithTag(VACATION_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(vacationRowTag(client)))
        onNodeWithText(s("Удалить 1_тренировку")).performScrollTo().performClick()
        awaitNode(hasText(s("Отпуск_20.10–26.10: удалена 1_тренировка")))

        // На «Главной» — «Предупредить об отпуске»; «Предупредил» — пункт уходит.
        onNodeWithTag(navTag(Section.HOME)).performClick()
        awaitNode(hasTestTag(todoTag(TodoKind.VACATION_NOTICE)))
        onNodeWithTag(todoTag(TodoKind.VACATION_NOTICE)).performScrollTo().performClick()
        awaitNode(hasTestTag(NOTICE_PANEL_TAG))
        onNodeWithText(s("Отпуск_20.10–26.10")).assertIsDisplayed()
        val id = runBlocking { calendar.settings.notices(today).first().single().id }
        onNodeWithTag(noticeDoneTag(id)).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { calendar.settings.notices(today).first().isEmpty() } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(todoTag(TodoKind.VACATION_NOTICE))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun listsOpenOwnRestrictionClubOnlyShown() = runComposeUiTest {
        runBlocking {
            calendar.restrictions.save(
                null,
                RestrictionDraft(placeId("ДБ"), LocalTime(17, 0), LocalTime(18, 0), "Групповая тренировка", true, setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalDate(2026, 9, 1)),
            )
        }
        openCalendarSettings()

        awaitNode(hasText("ДБ 17:00–18:00 — групповая тренировка"))
        onNodeWithText(CalendarSettingsFormat.OWN_RESTRICTIONS).performScrollTo().assertIsDisplayed()
        onNodeWithText("Каждый вт и чт").performScrollTo().assertIsDisplayed()
        // Запретов клуба и дежурств нет — их списков тоже.
        onNodeWithText(CalendarSettingsFormat.CLUB_RESTRICTIONS).assertDoesNotExist()
        onNodeWithText(CalendarSettingsFormat.DUTIES).assertDoesNotExist()

        onNodeWithText("ДБ 17:00–18:00 — групповая тренировка").performClick()
        awaitNode(hasText(TrainingFormat.EDIT_RESTRICTION_TITLE))
    }
}
