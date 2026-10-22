package com.akenory.personalka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private const val TNUM = "tnum"

/** Шрифт системный. title 22, subtitle 17, body 15, caption 13, number 28 (только сумма на «Деньгах»). */
val PersonalkaTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),      // title
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),        // subtitle
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),          // body
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),         // body-strong, кнопки
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),          // caption
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),        // caption-strong
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),                                        // number
)

/** Для времени, сумм и счётчиков: моноширинные цифры. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TNUM)

val PersonalkaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // блок календаря
    small = RoundedCornerShape(8.dp),        // чип, метка статуса
    medium = RoundedCornerShape(12.dp),      // карточка, кнопка, поле, сообщение
    large = RoundedCornerShape(16.dp),       // кнопка «+»
    extraLarge = RoundedCornerShape(20.dp),  // нижняя панель, диалог
)

object Dimens {
    val space1 = 4.dp; val space2 = 8.dp; val space3 = 12.dp; val space4 = 16.dp
    val space5 = 20.dp; val space6 = 24.dp; val space8 = 32.dp
    val touch = 48.dp; val button = 48.dp; val chip = 36.dp; val field = 56.dp; val row = 64.dp
    val topBar = 56.dp; val navBar = 72.dp; val fab = 56.dp
    val timeColumn = 48.dp; val hourDefault = 64.dp; val hourMin = 40.dp; val hourMax = 160.dp
    val blockStripe = 3.dp; val zoneStrip = 4.dp; val blockInset = 8.dp; val badge = 16.dp
    val blockMinWidth = 36.dp; val nowLine = 2.dp; val nowDot = 8.dp
    val hatchStroke = 1.5.dp; val hatchStep = 8.dp
}

object Motion {
    const val SHORT = 150   // переключатель, чип
    const val MEDIUM = 200  // сообщение, смена вкладки
    const val LONG = 250    // нижняя панель, раскрытие
    const val DRAG_SCALE = 1.02f
}

private fun PersonalkaColors.toScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = surfaceAlt, onPrimaryContainer = text,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = surfaceAlt, onSecondaryContainer = text,   // индикатор раздела в нижней панели
        tertiary = accent, onTertiary = onAccent,                          // только «+», «сегодня», счётчик дел
        tertiaryContainer = accent, onTertiaryContainer = onAccent,
        background = bg, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = surfaceAlt, onSurfaceVariant = textMuted,
        surfaceTint = Color.Transparent,                                   // без тонового оттенка Material
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surfaceAlt, surfaceContainerHighest = surfaceAlt,
        surfaceBright = surface, surfaceDim = bg,
        outline = outline, outlineVariant = divider,
        error = error, onError = if (isDark) bg else surface,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseText, inversePrimary = inverseAction,
        scrim = scrim,
    )
}

@Composable
fun PersonalkaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalPersonalkaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = PersonalkaTypography,
            shapes = PersonalkaShapes,
            content = content,
        )
    }
}

object PersonalkaTheme {
    val colors: PersonalkaColors @Composable get() = LocalPersonalkaColors.current
}
