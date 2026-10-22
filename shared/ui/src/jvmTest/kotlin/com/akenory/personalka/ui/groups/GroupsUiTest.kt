package com.akenory.personalka.ui.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Ещё → Группы» (шаг 1.8, ТЗ 5.10): группа, состав с датами, «исключить с даты». «Сейчас» — вт 20.10.2026. */
@OptIn(ExperimentalTestApi::class)
class GroupsUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 900.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun kid(last: String, first: String, birth: LocalDate? = LocalDate(2016, 5, 14)): String =
        runBlocking { clients.create(ClientDraft(last, first, sex = Sex.FEMALE, birthDate = birth)) }

    private fun group(name: String = "Сб 9:00 ББ"): String = runBlocking {
        val type = settings.types.first().first { it.shortName == "Группа" }
        val place = settings.places.first().first { it.shortName == "ББ" }
        groups.create(GroupDraft(name, type.id, place.id))
    }

    private fun ComposeUiTest.openGroups(width: () -> Dp = { folded }) {
        setContent { Box(Modifier.size(width(), height)) { App("0.1.0", settings, clients, groups, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).performClick()
    }

    @Test
    fun newGroupAndFirstChild() = runComposeUiTest {
        kid("Белова", "Ева")
        openGroups()
        awaitNode(hasText("Групп пока нет"))
        onNodeWithText("Добавить группу").performClick()
        // Форма во весь экран: нижней панели нет.
        onNodeWithTag(navTag(Section.HOME)).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите название").assertIsDisplayed()
        // Тип и место выбраны сами: первый тип-группа и первое место.
        onNodeWithText("Мини-группа").assertIsSelected()
        onNodeWithText("Большой бассейн").assertIsSelected()
        onNodeWithText("Группа").performClick()
        onNodeWithTag(GROUP_NAME_TAG).performTextInput("Сб 9:00 ББ")
        onNodeWithText("Сохранить").performClick()

        // Открылась карточка группы.
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Сб 9:00 ББ").assertIsDisplayed()
        onNodeWithText("Группа · Большой бассейн · без расписания").assertIsDisplayed()

        onNodeWithTag(MEMBER_ADD_TAG).performClick()
        onNodeWithTag(MEMBER_DATE_TAG).assertTextContains("20.10.2026")
        onNodeWithTag(memberPickTag("Белова Ева")).performClick()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        onNodeWithTag(memberRowTag("Белова Ева")).assertTextContains("с 20.10.2026", substring = true)
        onNodeWithText("Состав · 1").assertIsDisplayed()

        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).assertTextContains(s("без расписания · ББ · 1_ребёнок"), substring = true)
    }

    @Test
    fun excludeFromDateMovesChildToLeft() = runComposeUiTest {
        val eva = kid("Белова", "Ева")
        val id = group()
        val member = runBlocking { groups.addMember(id, eva, LocalDate(2026, 9, 1)) }
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        onNodeWithTag(memberRowTag("Белова Ева")).performClick()

        onNodeWithTag(MEMBER_FROM_TAG).assertTextContains("01.09.2026")
        onNodeWithText("Не ходит с сегодня").performClick()
        onNodeWithTag(MEMBER_NOT_FROM_TAG).assertTextContains("20.10.2026")
        onNodeWithText("Сохранить").performClick()

        // Последний день — вчера: Ева среди ушедших, состав пуст.
        awaitNode(hasText("Ушли · 1"))
        onNodeWithTag(memberRowTag("Белова Ева")).assertTextContains("01.09–19.10.2026", substring = true)
        onNodeWithText("Состав · 0").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { groups.rows(today).first().single().members.single { it.id == member }.toDate } == LocalDate(2026, 10, 19)
        }
    }

    @Test
    fun wrongDatesAreExplained() = runComposeUiTest {
        val eva = kid("Белова", "Ева")
        val id = group()
        runBlocking { groups.addMember(id, eva, LocalDate(2026, 9, 1)) }
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        onNodeWithTag(memberRowTag("Белова Ева")).performClick()

        onNodeWithTag(MEMBER_NOT_FROM_TAG).performTextInput("01.09.2026")
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Позже, чем «В группе с»").assertIsDisplayed()
        onNodeWithTag(MEMBER_FROM_TAG).performTextClearance()
        onNodeWithText("Укажите дату").assertIsDisplayed()
    }

    @Test
    fun newClientStraightIntoGroup() = runComposeUiTest {
        group()
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(MEMBER_ADD_TAG))
        onNodeWithTag(MEMBER_ADD_TAG).performClick()

        onNodeWithTag(MEMBER_SEARCH_TAG).performTextInput("Смирнова Аня")
        onNodeWithTag(NEW_MEMBER_TAG).performClick()
        onNodeWithTag(NEW_MEMBER_LAST_TAG).assertTextContains("Смирнова")
        onNodeWithTag(NEW_MEMBER_FIRST_TAG).assertTextContains("Аня")
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(memberRowTag("Смирнова Аня")))
        val anya = runBlocking { clients.rows(TestData.now).first().single().client }
        assertEquals(Sex.FEMALE, anya.sex)
    }

    @Test
    fun addedByMistakeRemovedWithUndo() = runComposeUiTest {
        val eva = kid("Белова", "Ева")
        val id = group()
        runBlocking { groups.addMember(id, eva, today) }
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        onNodeWithTag(memberRowTag("Белова Ева")).performClick()

        onNodeWithText("Добавлен по ошибке — убрать из состава").performClick()
        onNodeWithText("Белова Ева убрана из состава").assertIsDisplayed()
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Отменить").performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
    }

    @Test
    fun emptyGroupDeletedFromMenu() = runComposeUiTest {
        group()
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(GROUP_MENU_TAG))
        onNodeWithTag(GROUP_MENU_TAG).performClick()
        awaitNode(hasText("Удалить группу"))
        onNodeWithText("Удалить группу").performClick()
        onNodeWithText("Удалить группу?").assertIsDisplayed()
        onNodeWithText("Удалить").performClick()

        onNodeWithText("Группа удалена").assertIsDisplayed()
        awaitNode(hasText("Групп пока нет"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { groups.rows(today).first().isEmpty() } }
    }

    @Test
    fun groupFormSurvivesUnfolding() = runComposeUiTest {
        var width by mutableStateOf(folded)
        openGroups { width }
        awaitNode(hasText("Добавить группу"))
        onNodeWithText("Добавить группу").performClick()
        onNodeWithTag(GROUP_NAME_TAG).performTextInput("Вт 18:00 ДБ")
        onNodeWithText("Детский бассейн").performClick()

        width = unfolded
        waitForIdle()
        onNodeWithTag(GROUP_NAME_TAG).assertTextContains("Вт 18:00 ДБ")
        onNodeWithText("Детский бассейн").assertIsSelected()
        width = folded
        waitForIdle()
        onNodeWithTag(GROUP_NAME_TAG).assertTextContains("Вт 18:00 ДБ")
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText("В группе пока никого"))
        assertNull(runBlocking { groups.rows(today).first().single().schedule.firstOrNull() })
    }
}
