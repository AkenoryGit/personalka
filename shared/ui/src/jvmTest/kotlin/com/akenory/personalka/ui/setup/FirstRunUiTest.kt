package com.akenory.personalka.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Первый запуск на экране (шаг 1.6, ТЗ 5.18): ФИО → места и типы → календарь, и новый тип в конструкторе
 * без подсказок — проверка шага по плану.
 */
@OptIn(ExperimentalTestApi::class)
class FirstRunUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 900.dp
    private val settings = TestData.fresh()

    @Test
    fun nameThenPlacesAndTypesThenCalendar() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock) } }

        awaitNode(hasText("Начать на телефоне"))
        onNodeWithText("Войти").assertDoesNotExist()
        onNodeWithText("Начать на телефоне").performClick()

        // Шаг 1: без имени дальше нельзя, отчество необязательно.
        onNodeWithText("Как вас зовут?").assertIsDisplayed()
        onNodeWithText("Далее").assertIsNotEnabled()
        onNodeWithTag(LAST_NAME_TAG).performTextInput("Соколов")
        onNodeWithTag(FIRST_NAME_TAG).performTextInput("Андрей ")
        onNodeWithText("Далее").assertIsEnabled().performClick()

        // Шаг 2 (5.3а): набор только предложен — ничего не отмечено, тренер отмечает своё.
        onNodeWithText("Места и типы").assertIsDisplayed()
        onNodeWithText("Зал").assertIsOff()
        onNodeWithText("ББ").assertIsOff().performClick()
        onNodeWithText("ДБ").performClick()
        onNodeWithText("ББ").assertIsOn()
        onNodeWithTag(setupTypeTag("Кинезио")).performScrollTo().assertIsOff()
        onNodeWithTag(setupTypeTag("ПТ")).performScrollTo().assertIsOff().performClick()
        onNodeWithTag(setupTypeTag("ПТ")).assertIsOn()
        onNodeWithTag(setupTypeTag("Группа")).performScrollTo().performClick()

        // Новый тип прямо здесь — тот же конструктор, что в настройках.
        onNodeWithTag(NEW_TYPE_ROW_TAG).performScrollTo().performClick()
        onNodeWithTag(TYPE_NAME_TAG).assertIsDisplayed()
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Аквааэробика")
        onNodeWithTag(TYPE_SHORT_TAG).performTextInput("Аква")
        onNodeWithTag(kindTag(TrainingKind.GROUP)).performClick()
        onNodeWithText("Длительность, оплата и дневник заполнены как у группы — ниже их можно поправить.").assertIsDisplayed()
        // Цвета у типа нет — оттенок; образцы блока — в каждом отмеченном месте (шаг 1.13в).
        onNodeWithTag(shadeTag(3)).performScrollTo().performClick()
        onNodeWithText("19:00 · Аква · ДБ").performScrollTo().assertIsDisplayed()
        onNodeWithText("Сохранить").performClick()

        onNodeWithTag(setupTypeTag("Аквааэробика")).performScrollTo().assertIsOn()
        onNodeWithText("Готово").performClick()

        // После «Готово» — календарь.
        awaitNode(hasTestTag(navTag(Section.CALENDAR)))
        onNodeWithTag(navTag(Section.CALENDAR)).assertIsSelected()

        runBlocking {
            val coach = assertNotNull(settings.coach.first())
            assertEquals("Андрей", coach.firstName, "Пробелы по краям убраны")
            assertNull(coach.middleName)
            assertEquals(listOf("ББ", "ДБ"), settings.places.first().map { it.shortName })
            val types = settings.types.first()
            assertTrue(types.none { it.name == "Кинезио" }, "Неотмеченный тип не создан")
            val aqua = types.single { it.name == "Аквааэробика" }
            assertEquals(TrainingKind.GROUP, aqua.kind)
            assertEquals(PayUnit.PER_CLIENT, aqua.payUnit)
            assertEquals(3, aqua.shade)
            assertEquals(setOf("ПТ", "Группа", "Аквааэробика"), types.map { it.name }.toSet())
        }
    }

    @Test
    fun doneNeedsAPlaceAndAType() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock) } }
        awaitNode(hasText("Начать на телефоне"))
        onNodeWithText("Начать на телефоне").performClick()
        onNodeWithTag(LAST_NAME_TAG).performTextInput("Соколов")
        onNodeWithTag(FIRST_NAME_TAG).performTextInput("Андрей")
        onNodeWithText("Далее").performClick()

        // Сначала ничего не отмечено (5.3а).
        onNodeWithText("Готово").assertIsNotEnabled()
        onNodeWithText("Отметьте хотя бы одно место и один тип.").assertIsDisplayed()

        onNodeWithText("ДБ").performClick()
        onNodeWithText("Готово").assertIsNotEnabled()
        onNodeWithTag(setupTypeTag("ПТ")).performScrollTo().performClick()
        onNodeWithText("Готово").assertIsEnabled()
    }

    @Test
    fun newTypeShowsWhatIsMissing() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent { Box(Modifier.size(width, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock) } }
        awaitNode(hasText("Начать на телефоне"))
        onNodeWithText("Начать на телефоне").performClick()
        onNodeWithTag(LAST_NAME_TAG).performTextInput("Соколов")
        onNodeWithTag(FIRST_NAME_TAG).performTextInput("Андрей")
        onNodeWithText("Далее").performClick()
        onNodeWithTag(NEW_TYPE_ROW_TAG).performScrollTo().performClick()

        // Пустое «Сохранить» не закрывает форму, а говорит, чего не хватает.
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите название").assertIsDisplayed()
        onNodeWithText("Введите короткое имя").assertIsDisplayed()

        // Повтор названия запрещён; о том же оттенке — только подсказка.
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Группа")
        onNodeWithText("Такой тип уже есть").assertIsDisplayed()
        onNodeWithTag(shadeTag(4)).performScrollTo().performClick()
        onNodeWithText("Такой же оттенок у типа «Группа»").performScrollTo().assertIsDisplayed()

        // Разложили Fold: набранное не пропало.
        width = unfolded
        waitForIdle()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Группа")
        width = folded
        waitForIdle()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Группа")
    }
}
