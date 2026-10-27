package com.akenory.personalka.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Поле держит текст и курсор само (шаг 5.1, опыт Planovo): ответ экрана, запоздавший на кадр, не сбрасывает курсор —
 * буквы идут по порядку; поправка экрана и замена снаружи видны в поле. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class HeldTextUiTest {
    @Test
    fun lettersStayInOrderWhenScreenLagsOneFrame() = runComposeUiTest {
        var model by mutableStateOf("")
        setContent {
            PersonalkaTheme {
                // Поле получает текст экрана кадром позже — как дневник через базу или шторка на iPhone.
                var lagged by remember { mutableStateOf("") }
                LaunchedEffect(model) {
                    withFrameNanos {}
                    lagged = model
                }
                Field(label = "Заметка", value = lagged, onValueChange = { model = it }, tag = "field")
            }
        }
        onNodeWithTag("field").performClick()
        for (letter in "Привет") {
            onNodeWithTag("field").performTextInput(letter.toString())
            waitForIdle()
        }
        assertEquals("Привет", model)
    }

    @Test
    fun screenCorrectionAndOutsideChangeAreShown() = runComposeUiTest {
        var model by mutableStateOf("12.10")
        setContent {
            // Экран обрезает ввод до 5 знаков, как дату рождения.
            PersonalkaTheme { Field(label = "Дата", value = model, onValueChange = { model = it.take(5) }, tag = "field") }
        }
        onNodeWithTag("field").performClick()
        onNodeWithTag("field").performTextInput("9")
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals("12.10", model)
        onNodeWithTag("field").assert(hasText("12.10", substring = false))
        // Снаружи очистили — поле пустое.
        runOnIdle { model = "" }
        waitForIdle()
        onNodeWithTag("field").assert(hasText("", substring = false))
    }
}
