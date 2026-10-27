package com.akenory.personalka.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay

/**
 * Текст и курсор, которые поле держит само (шаг 5.1, опыт Planovo): экран получает каждый новый текст, но его ответ
 * иногда приходит кадром позже — дневник пишет черновик в базу и получает текст обратно, а на iPhone содержимое
 * шторки — отдельная композиция. Поле со старым текстом сбрасывало курсор в начало, и буквы шли не по порядку
 * («риветп» вместо «привет»). Поэтому, пока человек печатает, прежний текст экрана поле не сбрасывает; текст экрана
 * берётся, когда он новый снаружи (очистили, подставили) или когда экран поправил ввод по-своему (обрезал до 12 знаков)
 * — через [SETTLE_MS] после последней буквы.
 */
internal class HeldText(val shown: TextFieldValue, val onChange: (TextFieldValue) -> Unit)

@Composable
internal fun rememberHeldText(value: String, onValueChange: (String) -> Unit): HeldText {
    var local by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Человек печатает, а экран ещё не ответил: его прежний текст — не повод сбрасывать поле.
    var typing by remember { mutableStateOf(false) }
    val external = !typing && value != local.text
    val shown = if (external) TextFieldValue(value, TextRange(value.length)) else local
    if (external) SideEffect { local = shown }
    LaunchedEffect(local.text, typing) {
        if (!typing) return@LaunchedEffect
        delay(SETTLE_MS)
        typing = false
    }
    return HeldText(shown) { v ->
        val changed = v.text != shown.text
        local = v
        if (changed) {
            typing = true
            onValueChange(v.text)
        }
    }
}

private const val SETTLE_MS = 400L
