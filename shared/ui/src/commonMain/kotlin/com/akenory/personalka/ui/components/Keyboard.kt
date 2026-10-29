package com.akenory.personalka.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Нажатие мимо поля ввода убирает клавиатуру (ТЗ 5.20, шаг 5.3д): на iPhone у цифровой клавиатуры нет кнопки
 * «Готово», и без этого кнопку под клавиатурой не нажать. Ставится один раз на корень приложения.
 *
 * Нажатие только «подсматривается» в последнем проходе и ничего не съедает. Если его взял кто-то внутри — поле ввода
 * (поставить курсор), кнопка, чип, перетаскивание или прокрутка, — клавиатура остаётся: кнопка делает своё,
 * прокрутка не прячет то, что набирают. Пустое место, текст, фон — фокус снимается, клавиатура уходит.
 */
fun Modifier.hideKeyboardOnTap(): Modifier = composed {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    pointerInput(focus, keyboard) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.isConsumed) return@awaitEachGesture
            val up = waitForUpOrCancellation(PointerEventPass.Final) ?: return@awaitEachGesture
            if (up.isConsumed) return@awaitEachGesture
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}

/**
 * Карточка окна поверх экрана ловит нажатия, чтобы они не ушли на затемнение под ней (оно закрывает окно). Пустое
 * место карточки при этом тоже убирает клавиатуру — как везде в приложении.
 */
fun Modifier.swallowTaps(): Modifier = composed {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    pointerInput(focus, keyboard) {
        detectTapGestures {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}
