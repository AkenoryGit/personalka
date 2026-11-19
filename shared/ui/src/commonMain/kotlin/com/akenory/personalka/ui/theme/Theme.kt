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

/**
 * Шрифт системный. title 24, subtitle 17, body 15, caption 13, label-small 11; number 28 и number-large 40
 * (сумма месяца на «Главной» и в «Зарплате»). Шаг 5.11а: заголовки и цифры — полужирные (600), чтобы на экране
 * было главное и второстепенное, а не всё одного веса.
 */
val PersonalkaTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),    // title
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),      // subtitle
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),          // body
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),         // body-strong, кнопки
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),          // caption
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),        // caption-strong
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),         // суммы в клетках календаря
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = TNUM),                                                                    // цифра плитки
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),                                        // number
    displayMedium = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),                                        // number-large
)

/** Для времени, сумм и счётчиков: моноширинные цифры. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TNUM)

val PersonalkaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // блок календаря (шаг 5.11а: было 6)
    small = RoundedCornerShape(8.dp),        // чип, метка статуса
    medium = RoundedCornerShape(14.dp),      // карточка, плитка, кнопка, поле, сообщение (было 12)
    large = RoundedCornerShape(16.dp),       // кнопка «+»
    extraLarge = RoundedCornerShape(20.dp),  // цветная карточка, нижняя панель, диалог
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
