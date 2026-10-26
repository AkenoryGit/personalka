package com.akenory.personalka.ui.theme

import androidx.compose.ui.graphics.Color
import com.akenory.personalka.domain.client.AvatarRules
import com.akenory.personalka.ui.components.avatarCap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Контраст темы (ТЗ 10.4, 10.5): текст не ниже 4,5 : 1, графика не ниже 3 : 1 в обеих темах.
 * Те же пары, что проверены скриптом на шаге 0.2; тест не даст случайно испортить цвет.
 */
class ThemeContrastTest {

    private val themes = listOf("светлая" to LightColors, "тёмная" to DarkColors)

    @Test
    fun textPairsAreReadable() {
        for ((name, c) in themes) {
            val pairs = listOf(
                "text / bg" to (c.text to c.bg),
                "text / surface" to (c.text to c.surface),
                "text / surfaceAlt" to (c.text to c.surfaceAlt),
                "textMuted / bg" to (c.textMuted to c.bg),
                "textMuted / surface" to (c.textMuted to c.surface),
                "textMuted / surfaceAlt" to (c.textMuted to c.surfaceAlt),
                "onPrimary / primary" to (c.onPrimary to c.primary),
                "onAccent / accent" to (c.onAccent to c.accent),
                "accentText / surface" to (c.accentText to c.surface),
                "accentText / surfaceAlt" to (c.accentText to c.surfaceAlt),
                "done / surfaceAlt" to (c.done to c.surfaceAlt),
                "charged / surfaceAlt" to (c.charged to c.surfaceAlt),
                "over / surfaceAlt" to (c.over to c.surfaceAlt),
                "error / surfaceAlt" to (c.error to c.surfaceAlt),
                "onDoneContainer / doneContainer" to (c.onDoneContainer to c.doneContainer),
                "onChargedContainer / chargedContainer" to (c.onChargedContainer to c.chargedContainer),
                "onOverContainer / overContainer" to (c.onOverContainer to c.overContainer),
                "onErrorContainer / errorContainer" to (c.onErrorContainer to c.errorContainer),
                "inverseText / inverseSurface" to (c.inverseText to c.inverseSurface),
                "inverseAction / inverseSurface" to (c.inverseAction to c.inverseSurface),
            )
            for ((pair, colors) in pairs) {
                assertAtLeast(4.5, colors.first, colors.second, "$name тема, $pair")
            }
        }
    }

    @Test
    fun graphicsAreVisible() {
        for ((name, c) in themes) {
            assertAtLeast(3.0, c.accentGraphic, c.surface, "$name тема, линия «сейчас» на сетке")
            assertAtLeast(3.0, c.accentGraphic, c.pastTint, "$name тема, линия «сейчас» на прошедшем")
            assertAtLeast(3.0, c.outline, c.bg, "$name тема, рамка поля на фоне экрана")
            assertAtLeast(3.0, c.outline, c.surface, "$name тема, рамка поля на карточке")
        }
    }

    @Test
    fun allSixteenPlaceColorsWorkInBothThemesAndEveryShade() {
        assertEquals(16, PlaceColor.entries.size)
        for ((name, c) in themes) {
            for (place in PlaceColor.entries) {
                val tone = place.tone(c.isDark)
                assertEquals(4, tone.shades.size, "${place.key}: четыре оттенка")
                tone.shades.forEachIndexed { i, fill ->
                    assertAtLeast(5.5, tone.text, fill, "$name тема, ${place.key}: текст блока на оттенке ${i + 1}")
                }
                assertAtLeast(3.0, tone.stripe, c.surface, "$name тема, ${place.key}: полоса на сетке")
                assertAtLeast(4.5, tone.text, dutyFill(place, c), "$name тема, ${place.key}: текст на дежурстве")
                // Оттенки идут от светлого к тёмному: в светлой теме фон темнеет, в тёмной — светлеет к четвёртому,
                // и соседние заметно разные.
                tone.shades.zipWithNext().forEachIndexed { i, (a, b) ->
                    val step = luminance(a) - luminance(b)
                    assertTrue(if (c.isDark) step < 0 else step > 0, "$name тема, ${place.key}: порядок оттенков ${i + 1} → ${i + 2}")
                    assertTrue(contrast(a, b) >= 1.15, "$name тема, ${place.key}: оттенки ${i + 1} и ${i + 2} различимы")
                }
            }
        }
    }

    @Test
    fun placeColorIsStoredByKeyAndShadeIsClamped() {
        assertEquals(PlaceColor.BLUE, PlaceColor.of("blue"))
        assertEquals(PlaceColor.SLATE, PlaceColor.of("нет такого"), "неизвестный ключ — сланцевый")
        assertEquals(PlaceColor.SLATE, PlaceColor.of(null))
        assertEquals(16, PlaceColor.entries.map { it.key }.toSet().size, "ключи не повторяются")
        val blue = PlaceColor.BLUE.light
        assertEquals(blue.shades[0], blue.block(0), "оттенок меньше 1 — первый")
        assertEquals(blue.shades[3], blue.block(9), "оттенок больше 4 — четвёртый")
    }

    @Test
    fun avatarInitialsReadOnEveryCap() {
        // Белые инициалы на шапочке аватарки (шаг 3.13) — в любом из её цветов.
        for (tone in 0 until AvatarRules.TONES) assertAtLeast(4.5, Color.White, avatarCap(tone), "аватарка, цвет $tone")
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, what: String) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= min, "$what: ${(ratio * 100).toInt() / 100.0} : 1, нужно не ниже $min")
    }

    /** Контраст по WCAG 2.x. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
