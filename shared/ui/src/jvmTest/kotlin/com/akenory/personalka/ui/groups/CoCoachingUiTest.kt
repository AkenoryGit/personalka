package com.akenory.personalka.ui.groups

import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.AccountRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.Sharing
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.settings.COLLEAGUES_ROW_TAG
import com.akenory.personalka.ui.settings.COLLEAGUE_ADD_TAG
import com.akenory.personalka.ui.settings.COLLEAGUE_NAME_TAG
import com.akenory.personalka.ui.settings.colleagueRowTag
import com.akenory.personalka.ui.settings.colleagueSchemeTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Совместное ведение групп (шаг 3.7г, ТЗ 5.16, 5.10): «Коллеги» в настройках и поля группы — только с выключателем
 * «Совместное ведение групп». «Сейчас» — вт 20.10.2026. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class CoCoachingUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val today = TestData.now.date

    /** [account] — вошедший администратор: у него открыты виды оплаты (решение Дмитрия 27.10.2026). */
    private fun ComposeUiTest.openMore(item: MoreItem, account: AccountRepository? = null) {
        setContent { Box(Modifier.size(412.dp, 900.dp)) { App("0.1.0", settings, clients, groups, TestData.calendarOf(settings), account = account, clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(item)).performClick()
    }

    private fun group(): String = runBlocking {
        val type = settings.types.first().first { it.shortName == "Группа" }
        val place = settings.places.first().first { it.shortName == "ББ" }
        groups.create(GroupDraft("Сб 9:00 ББ", type.id, place.id))
    }

    @Test
    fun switchedOffShowsNothing() = runComposeUiTest {
        val id = group()
        openMore(MoreItem.GROUPS)
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasText("В группе пока никого"))
        onNodeWithTag(GROUP_SHARING_TAG).assertDoesNotExist()
        onNodeWithTag(GROUP_MENU_TAG).performClick()
        onNodeWithText("Изменить").performClick()
        awaitNode(hasTestTag(GROUP_NAME_TAG))
        onNodeWithText(CoCoachingFormat.OWNER).assertDoesNotExist()
        assertEquals(Owner.SELF, runBlocking { groups.rows(today).first().single { it.group.id == id }.group.owner })
    }

    @Test
    fun colleagueAddedInSettings() = runComposeUiTest {
        openMore(MoreItem.SETTINGS)
        awaitNode(hasText("Профиль"))
        // Выключено — раздела нет (10.7).
        onNodeWithTag(COLLEAGUES_ROW_TAG).assertDoesNotExist()
        runBlocking { settings.salary.saveCoCoaching(true) }
        awaitNode(hasTestTag(COLLEAGUES_ROW_TAG))
        onNodeWithTag(COLLEAGUES_ROW_TAG).assertTextContains("Пока нет", substring = true).performClick()

        awaitNode(hasText(CoCoachingFormat.EMPTY))
        onNodeWithText(CoCoachingFormat.NEW).performClick()
        // Форма открывается не в тот же кадр — ждём кнопку.
        awaitNode(hasText("Сохранить"))
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите имя").assertIsDisplayed()
        onNodeWithTag(COLLEAGUE_NAME_TAG).performTextInput("Лена")
        // Видов оплаты нет (не открыты в админке) — и выбора схемы коллеги нет: у всех «100 % 10-го».
        onNodeWithTag(colleagueSchemeTag(PaySchemeKind.NONE)).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(colleagueRowTag("Лена")))
        onNodeWithText(SalarySetupFormat.scheme(PaySchemeKind.NONE)).assertDoesNotExist()
        onNodeWithTag(COLLEAGUE_ADD_TAG).assertIsDisplayed()
        val row = runBlocking { settings.colleagues.rows(today).first().single() }
        assertEquals(PaySchemeKind.NONE, row.kind)
    }

    @Test
    fun colleagueSchemeWithPayTypesOpen() = runComposeUiTest {
        // Администратор — у него «Официально с налогом 6,5 %» открыта, значит, и выбор схемы коллеги.
        val server = FakeServer().apply {
            signedUp("coach@example.ru", "длинный-пароль", "Андрей")
            admin = "coach@example.ru"
        }
        val account = AccountRepository(server.api(), MemoryTokens(), MemoryPrefs(), "Samsung SM-F976B") { "coach-1" }
        runBlocking { account.login("coach@example.ru", "длинный-пароль") }
        runBlocking { settings.salary.saveCoCoaching(true) }
        openMore(MoreItem.SETTINGS, account)
        awaitNode(hasTestTag(COLLEAGUES_ROW_TAG))
        onNodeWithTag(COLLEAGUES_ROW_TAG).performClick()
        awaitNode(hasText(CoCoachingFormat.EMPTY))
        onNodeWithText(CoCoachingFormat.NEW).performClick()
        awaitNode(hasText("Сохранить"))
        onNodeWithTag(COLLEAGUE_NAME_TAG).performTextInput("Лена")
        onNodeWithTag(colleagueSchemeTag(PaySchemeKind.NONE)).assertIsOn()
        onNodeWithTag(colleagueSchemeTag(PaySchemeKind.HALF_OFFICIAL)).performClick()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(colleagueRowTag("Лена")))
        onNodeWithTag(colleagueRowTag("Лена")).assertTextContains("Официально с налогом", substring = true)
        onNodeWithTag(COLLEAGUE_ADD_TAG).assertIsDisplayed()
        val row = runBlocking { settings.colleagues.rows(today).first().single() }
        assertEquals(PaySchemeKind.HALF_OFFICIAL, row.kind)
    }

    @Test
    fun groupLedWithColleague() = runComposeUiTest {
        val id = group()
        val lena = runBlocking {
            settings.salary.saveCoCoaching(true)
            settings.colleagues.add("Лена", PaySchemeKind.NONE, today)
        }
        openMore(MoreItem.GROUPS)
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(GROUP_SHARING_TAG))
        onNodeWithTag(GROUP_SHARING_TAG).assertTextContains("Числится на: я · Кто обычно ведёт: один")
        onNodeWithTag(GROUP_MENU_TAG).performClick()
        onNodeWithText("Изменить").performClick()

        awaitNode(hasTestTag(sharingConductedTag(ConductedBy.SELF_AND_COLLEAGUE)))
        onNodeWithText(CoCoachingFormat.ME).assertIsSelected()
        onNodeWithTag(sharingConductedTag(ConductedBy.SELF_AND_COLLEAGUE)).performScrollTo().performClick()
        onNodeWithText("Лена").performScrollTo().assertIsSelected()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasText("Числится на: я · Кто обычно ведёт: с коллегой · Лена"))
        val saved = runBlocking { groups.rows(today).first().single { it.group.id == id }.group }
        assertEquals(ConductedBy.SELF_AND_COLLEAGUE, saved.defaultConductedBy)
        assertEquals(lena, saved.defaultColleagueId)

        // Группа коллеги — в списке «числится: Лена».
        runBlocking {
            groups.update(id, GroupDraft(saved.name, saved.typeId, saved.locationId, Sharing(Owner.COLLEAGUE, lena)))
        }
        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).assertTextContains("числится: Лена", substring = true)
    }
}
