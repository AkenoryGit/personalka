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
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, clock = TestData.clock) } }

        awaitNode(hasText("Начать на телефоне"))
        onNodeWithText("Войти").assertDoesNotExist()
        onNodeWithText("Начать на телефоне").performClick()

        // Шаг 1: без имени дальше нельзя, отчество необязательно.
        onNodeWithText("Как вас зовут?").assertIsDisplayed()
        onNodeWithText("Далее").assertIsNotEnabled()
        onNodeWithTag(LAST_NAME_TAG).performTextInput("Соколов")
        onNodeWithTag(FIRST_NAME_TAG).performTextInput("Андрей ")
        onNodeWithText("Далее").assertIsEnabled().performClick()

        // Шаг 2: всё отмечено, лишнее снимается.
        onNodeWithText("Места и типы").assertIsDisplayed()
        onNodeWithText("Зал").assertIsOn().performClick()
        onNodeWithText("Зал").assertIsOff()
        onNodeWithTag(setupTypeTag("Кинезио")).performScrollTo().assertIsOn().performClick()
        onNodeWithTag(setupTypeTag("Кинезио")).assertIsOff()

        // Новый тип прямо здесь — тот же конструктор, что в настройках.
        onNodeWithTag(NEW_TYPE_ROW_TAG).performScrollTo().performClick()
        onNodeWithTag(TYPE_NAME_TAG).assertIsDisplayed()
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Аквааэробика")
        onNodeWithTag(TYPE_SHORT_TAG).performTextInput("Аква")
        onNodeWithTag(kindTag(TrainingKind.GROUP)).performClick()
        onNodeWithText("Длительность, оплата и дневник заполнены как у группы — ниже их можно поправить.").assertIsDisplayed()
        onNodeWithTag(colorTag("sky")).performScrollTo().performClick()
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
            assertTrue(types.none { it.name == "Кинезио" }, "Снятый тип не создан")
            val aqua = types.single { it.name == "Аквааэробика" }
            assertEquals(TrainingKind.GROUP, aqua.kind)
            assertEquals(PayUnit.PER_CLIENT, aqua.payUnit)
            assertEquals("sky", aqua.color)
            assertEquals(9, types.size)
        }
    }

    @Test
    fun doneNeedsAPlaceAndAType() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, clock = TestData.clock) } }
        awaitNode(hasText("Начать на телефоне"))
        onNodeWithText("Начать на телефоне").performClick()
        onNodeWithTag(LAST_NAME_TAG).performTextInput("Соколов")
        onNodeWithTag(FIRST_NAME_TAG).performTextInput("Андрей")
        onNodeWithText("Далее").performClick()

        for (place in listOf("ББ", "ДБ", "Зал")) onNodeWithText(place).performClick()
        onNodeWithText("Готово").assertIsNotEnabled()
        onNodeWithText("Отметьте хотя бы одно место и один тип.").assertIsDisplayed()

        onNodeWithText("ДБ").performClick()
        onNodeWithText("Готово").assertIsEnabled()
    }

    @Test
    fun newTypeShowsWhatIsMissing() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent { Box(Modifier.size(width, height)) { App("0.1.0", settings, clock = TestData.clock) } }
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

        // Повтор названия и цвета: название запрещено, о цвете — только предупреждение.
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Группа")
        onNodeWithText("Такой тип уже есть").assertIsDisplayed()
        onNodeWithTag(colorTag("green")).performScrollTo().performClick()
        onNodeWithText("Этот цвет уже у типа «Группа»").assertIsDisplayed()

        // Разложили Fold: набранное не пропало.
        width = unfolded
        waitForIdle()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Группа")
        width = folded
        waitForIdle()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Группа")
    }
}
