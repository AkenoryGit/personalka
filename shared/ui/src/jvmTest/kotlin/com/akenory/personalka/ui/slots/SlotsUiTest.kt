package com.akenory.personalka.ui.slots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
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
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.format.SlotFormat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.calendar.CALENDAR_SLOTS_TAG
import com.akenory.personalka.ui.calendar.CALENDAR_VIEW_TAG
import com.akenory.personalka.ui.clients.ClientFilter
import com.akenory.personalka.ui.clients.filterChipTag
import com.akenory.personalka.ui.clients.waitMenuTag
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import com.akenory.personalka.ui.training.FORM_SEARCH_TAG
import com.akenory.personalka.ui.training.formClientTag
import com.akenory.personalka.domain.restriction.RestrictionDraft
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * «Свободные слоты» (шаг 4.3, ТЗ 4.13, 5.13). «Сейчас» — вт 20.10.2026 15:40; ПТ в ББ в ср 21.10 в 10:00, часы
 * работы 07–23: окна ср — 07:00–10:00 и 11:00–23:00, в остальные дни тренировок нет.
 */
@OptIn(ExperimentalTestApi::class)
class SlotsUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val wednesday = LocalDate(2026, 10, 21)

    private fun ptId() = runBlocking { settings.types.first().single { it.shortName == "ПТ" }.id }

    private fun trainingOnWednesday() = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val bb = settings.places.first().single { it.shortName == "ББ" }.id
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 21, 10, 0), ptId(), listOf(orlov), null, bb, 50))
    }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    /** Время начала в ср в помещении [place] — по умолчанию ББ. */
    private fun at(hour: Int, minute: Int = 0, place: String = "ББ") = slotStartTag(wednesday, placeId(place), hour * 60 + minute)

    private fun ComposeUiTest.absent(tag: String) = assertTrue(onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty(), tag)

    private fun ComposeUiTest.start() {
        setContent { Box(Modifier.size(412.dp, 960.dp)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.MORE)))
    }

    @Test
    fun windowsGapAndNewTraining() = runComposeUiTest {
        trainingOnWednesday()
        start()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.SLOTS)))
        onNodeWithTag(moreRowTag(MoreItem.SLOTS)).performClick()

        // Время начала по :00 и :30: тренировка в 10:00 — до неё 7.00 … 9.00, после — с 11.00.
        awaitNode(hasTestTag(at(7)))
        onNodeWithTag(at(9)).performScrollTo().assertIsDisplayed()
        absent(at(9, 30))
        onNodeWithTag(at(11)).performScrollTo().assertIsDisplayed()
        assertTrue(onAllNodes(hasText("Среда (21.10.2026)")).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("ББ:").assertIsDisplayed()

        // Перерыв до 1 ч: ждать 2 часа не предлагается — только время у тренировки.
        onNodeWithTag(slotGapTag(60)).performScrollTo().performClick()
        awaitNode(hasTestTag(at(8, 30)))
        absent(at(7))
        absent(at(22))
        onNodeWithTag(at(11, 30)).performScrollTo().assertIsDisplayed()

        // Нажатие на время — новая тренировка на него.
        onNodeWithTag(at(8, 30)).performScrollTo().performClick()
        awaitNode(hasTestTag(FORM_SEARCH_TAG))
    }

    @Test
    fun copyList() = runComposeUiTest {
        trainingOnWednesday()
        start()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.SLOTS)))
        onNodeWithTag(moreRowTag(MoreItem.SLOTS)).performClick()
        awaitNode(hasTestTag(SLOTS_COPY_TAG))
        onNodeWithTag(SLOTS_COPY_TAG).performScrollTo().performClick()
        awaitNode(hasText(SlotFormat.COPIED))
    }

    @Test
    fun eachPlaceWithItsRestriction() = runComposeUiTest {
        // Шаг 4.3а: запрет ДБ в ср 07:00–10:00 — утреннего окна в ДБ нет, в ББ есть; помещения подписаны.
        trainingOnWednesday()
        runBlocking {
            calendar.restrictions.save(
                null,
                RestrictionDraft(placeId("ДБ"), LocalTime(7, 0), LocalTime(10, 0), "Группа", weekly = true, weekdays = setOf(DayOfWeek.WEDNESDAY), date = LocalDate(2026, 9, 1)),
            )
        }
        start()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.SLOTS)))
        onNodeWithTag(moreRowTag(MoreItem.SLOTS)).performClick()
        awaitNode(hasTestTag(at(7)))
        onNodeWithTag(at(11, place = "ДБ")).performScrollTo().assertIsDisplayed()
        absent(at(7, place = "ДБ"))

        // Только ДБ — запрет по-прежнему учитывается.
        onNodeWithTag(slotPlaceTag(placeId("ДБ"))).performScrollTo().performClick()
        awaitNode(hasTestTag(at(11, place = "ДБ")))
        absent(at(7, place = "ДБ"))
        absent(at(7))
    }

    @Test
    fun calendarMenuOpensSlots() = runComposeUiTest {
        start()
        onNodeWithTag(navTag(Section.CALENDAR)).performClick()
        awaitNode(hasTestTag(CALENDAR_VIEW_TAG))
        onNodeWithTag(CALENDAR_VIEW_TAG).performClick()
        awaitNode(hasTestTag(CALENDAR_SLOTS_TAG))
        onNodeWithTag(CALENDAR_SLOTS_TAG).performClick()
        // Тренировок нет — окон нет, подсказка, что поменять.
        awaitNode(hasTestTag(SLOTS_NONE_TAG))
        onNodeWithText(SlotFormat.NONE_HINT).assertIsDisplayed()
    }

    @Test
    fun findTimeFromWaitlistMarksAndBooks() = runComposeUiTest {
        trainingOnWednesday()
        val petrov = runBlocking { clients.create(ClientDraft("Петров", "Кирилл")) }
        runBlocking {
            clients.waitlist.save(WaitDraft(petrov, WaitWant(days = setOf(DayOfWeek.WEDNESDAY), ranges = listOf(WaitRules.MORNING), typeId = ptId())))
        }
        start()
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(filterChipTag(ClientFilter.NO_BOOKING)))
        onNodeWithTag(filterChipTag(ClientFilter.NO_BOOKING)).performClick()
        awaitNode(hasTestTag(waitMenuTag("Петров Кирилл")))
        onNodeWithTag(waitMenuTag("Петров Кирилл")).performClick()
        onNodeWithText(SlotFormat.FIND_TIME).performClick()

        // Ср, утро — из записи: время до 12:00, день помечен «ждёт».
        awaitNode(hasTestTag(at(7)))
        onNodeWithText(SlotFormat.forWait("Петров К.")).assertIsDisplayed()
        absent(at(13))
        assertTrue(onAllNodes(hasText(SlotFormat.waiting(listOf("Петров К.")))).fetchSemanticsNodes().isNotEmpty())
        onNodeWithTag(at(7)).performScrollTo().performClick()

        // Форма — с Петровым, как «Записать» в листе ожидания.
        awaitNode(hasTestTag(formClientTag(petrov)))
        onNodeWithTag(formClientTag(petrov)).onChild().assertIsSelected()
    }
}
