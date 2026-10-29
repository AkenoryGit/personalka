package com.akenory.personalka.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Нажатие мимо поля ввода убирает клавиатуру (шаг 5.3д): на экране и в окне поверх него. */
@OptIn(ExperimentalTestApi::class)
class KeyboardUiTest {
    @Test
    fun tapOutsideClearsFocusButFieldAndButtonKeepIt() = runComposeUiTest {
        var pressed by mutableStateOf(0)
        var text by mutableStateOf("")
        setContent {
            PersonalkaTheme {
                Box(Modifier.size(400.dp, 800.dp).hideKeyboardOnTap()) {
                    Column {
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().height(56.dp).testTag("field"))
                        Button(onClick = { pressed++ }, modifier = Modifier.testTag("button")) { Text("Далее") }
                        Box(Modifier.fillMaxWidth().height(300.dp).testTag("empty"))
                    }
                }
            }
        }
        onNodeWithTag("field").performClick().assertIsFocused()
        // По самому полю — фокус остаётся (курсор ставится).
        onNodeWithTag("field").performClick().assertIsFocused()
        // Кнопка срабатывает; клавиатура остаётся — кнопка делает своё.
        onNodeWithTag("button").performClick()
        assertEquals(1, pressed)
        // Пустое место — фокус снят, клавиатура уходит.
        onNodeWithTag("empty").performClick()
        onNodeWithTag("field").assertIsNotFocused()
    }

    @Test
    fun tapOnPanelCardClearsFocusAndKeepsPanel() = runComposeUiTest {
        var open by mutableStateOf(true)
        setContent {
            PersonalkaTheme {
                Box(Modifier.size(400.dp, 800.dp).hideKeyboardOnTap()) {
                    if (open) {
                        Panel("Окно", onDismiss = { open = false }) {
                            BasicTextField("", {}, Modifier.fillMaxWidth().height(56.dp).testTag("field"))
                            Box(Modifier.fillMaxWidth().height(120.dp).testTag("inside"))
                        }
                    }
                }
            }
        }
        onNodeWithTag("field").performClick().assertIsFocused()
        onNodeWithTag("inside").performClick()
        onNodeWithTag("field").assertIsNotFocused()
        assertEquals(true, open, "Нажатие внутри окна его не закрывает")
    }
}
