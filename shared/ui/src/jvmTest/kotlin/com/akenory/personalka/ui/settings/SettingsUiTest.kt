package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.setup.TYPE_NAME_TAG
import com.akenory.personalka.ui.setup.TYPE_SHORT_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ещё → Настройки → Места и типы» (шаг 1.6, ТЗ 5.16, макет TypesList) и конструктор из настроек. */
@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 900.dp
    private val settings = TestData.ready()

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun ComposeUiTest.openPlacesAndTypes() {
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasTestTag(PLACES_ROW_TAG))
        onNodeWithText(s("3_места · 9_типов")).assertIsDisplayed()
        // ФИО тренера приходит из своего потока базы, позже мест и типов, — его ждём, а не проверяем сразу.
        awaitNode(hasText("Соколов Андрей Петрович"))
        onNodeWithTag(PLACES_ROW_TAG).performClick()
        awaitNode(hasTestTag(typeRowTag("ПТ")))
    }

    @Test
    fun listShowsPlacesAndTypesLikeMockup() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()

        onNodeWithText("Большой бассейн").assertIsDisplayed()
        onNodeWithText("Тренажёрный зал").assertIsDisplayed()
        // Та же строка у гидрореабилитации и кинезио — проверяется строка ПТ.
        onNodeWithTag(typeRowTag("ПТ")).assertTextContains(s("Персональная · 50_мин · как ПТ"))
        onNodeWithTag(typeRowTag("Дежурство")).performScrollTo()
        onNodeWithText("Дежурство · за час").assertIsDisplayed()
    }

    @Test
    fun editTypeAndSeeNewLine() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()

        onNodeWithTag(typeRowTag("ПТ")).performClick()
        // Форма во весь экран: нижняя панель на сложенном спрятана.
        onNodeWithTag(navTag(Section.HOME)).assertDoesNotExist()
        onNodeWithText("60").performScrollTo().performClick()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasText(s("Персональная · 60_мин · как ПТ")))
        onNodeWithTag(navTag(Section.HOME)).assertIsDisplayed()
        runBlocking { assertEquals(60, settings.types.first().single { it.name == "ПТ" }.defaultDurationMin) }
    }

    @Test
    fun newTypeFromSettings() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()

        onNodeWithTag(ADD_TYPE_TAG).performClick()
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Аквааэробика")
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите короткое имя").assertIsDisplayed()
        onNodeWithTag(TYPE_SHORT_TAG).performTextInput("Аква")
        onNodeWithText("Сохранить").performClick()

        onNodeWithText("Тип «Аквааэробика» добавлен").assertIsDisplayed()
        awaitNode(hasTestTag(typeRowTag("Аквааэробика")))
        runBlocking { assertTrue(settings.types.first().any { it.shortName == "Аква" }) }
    }

    @Test
    fun archiveTypeAndUndo() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()

        onNodeWithTag(typeRowTag("Кинезио")).performScrollTo().performClick()
        onNodeWithText("Убрать в архив").performScrollTo().performClick()
        onNodeWithText("«Кинезио» в архиве").assertIsDisplayed()
        awaitNode(hasText(s("Персональная · 50_мин · как ПТ · в архиве")))

        onNodeWithText("Отменить").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.types.first().none { it.archived } } }
    }

    @Test
    fun placesReorderAndAdd() = runComposeUiTest {
        setContent { Box(Modifier.size(folded, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()

        // «Выше» у ДБ — то же, что перетащить его на первое место (так же работает TalkBack).
        runOnIdle {
            onNodeWithTag(placeRowTag("ДБ")).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .single { it.label == "Выше" }.action()
        }
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { settings.places.first().map { it.shortName } } == listOf("ДБ", "ББ", "Зал")
        }

        onNodeWithTag(ADD_PLACE_TAG).performClick()
        onNodeWithText("Новое место").assertIsDisplayed()
        onNodeWithTag(com.akenory.personalka.ui.setup.PLACE_NAME_TAG).performTextInput("Открытая вода")
        onNodeWithTag(com.akenory.personalka.ui.setup.PLACE_SHORT_TAG).performTextInput("ДБ")
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Уже есть у места «Детский бассейн»").assertIsDisplayed()
        onNodeWithTag(com.akenory.personalka.ui.setup.PLACE_SHORT_TAG).performTextInput("2")
        onNodeWithText("Не больше 4 знаков").assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasTestTag(placeRowTag("ДБ2")))
    }

    @Test
    fun typeFormSurvivesUnfolding() = runComposeUiTest {
        var width by mutableStateOf(folded)
        setContent { Box(Modifier.size(width, height)) { App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), clock = TestData.clock) } }
        openPlacesAndTypes()
        onNodeWithTag(ADD_TYPE_TAG).performClick()
        onNodeWithTag(TYPE_NAME_TAG).performTextInput("Аквааэробика")

        // Разложили: «Ещё» слева, форма справа с тем же текстом; закрыть — к «Местам и типам».
        width = unfolded
        waitForIdle()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).assertIsDisplayed()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Аквааэробика")

        width = folded
        waitForIdle()
        onNodeWithTag(TYPE_NAME_TAG).assertTextContains("Аквааэробика")
        onNodeWithContentDescription("Закрыть").performClick()
        onNodeWithTag(ADD_TYPE_TAG).assertIsDisplayed()
    }
}
