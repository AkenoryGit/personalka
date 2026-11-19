package com.akenory.personalka.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/*
 * Стиль «Сияние» (шаг 5.13а, `docs/mockups/redesign/README.md`, раздел «Шаги 5.13а–5.13в»): три уровня —
 * сверху (+1, с тенью), ровно (0, `surface` без тени) и вдавлено (−1, углубление с тенью внутри), и перелив
 * «Сияние» у главного на экране. Все цвета уровней — из темы, поэтому одни и те же элементы в обеих темах.
 */

/** «Сияние»: бирюза → индиго → фиолет; одинаково в обеих темах, текст на нём белый (не ниже 7,8 : 1). */
val AuroraColors = listOf(Color(0xFF0E3A48), Color(0xFF145A66), Color(0xFF2E3F86), Color(0xFF4B2F8C))

/** Перелив «Сияние» по диагонали (около 140°). */
val AuroraBrush: Brush = Brush.linearGradient(AuroraColors)

/** Средний цвет «Сияния» — для мест, где нужен один цвет (кольцо, курсор, иконка поверх). */
val AuroraSolid = Color(0xFF2E3F86)

/** Текст и значки на «Сиянии». */
val OnAurora = Color.White

/**
 * Уровень «сверху» (+1): мягкая тень под элементом и светлая черта по верхнему краю. Блоки тренировок, «Деньги»,
 * «Сейчас», главные кнопки, «+», нижняя панель. Ставится до заливки: `raised(shape, colors).background(…)`.
 */
fun Modifier.raised(shape: Shape, colors: PersonalkaColors, elevation: Int = 8): Modifier {
    val shadowColor = if (colors.isDark) Color.Black.copy(alpha = 0.55f) else Color(0xFF15171A).copy(alpha = 0.22f)
    val line = Color.White.copy(alpha = if (colors.isDark) 0.16f else 0.55f)
    return this
        .shadow(elevation.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(line, topLeft = Offset.Zero, size = Size(size.width, 1.dp.toPx()))
        }
}

/**
 * Уровень «вдавлено» (−1): дно [PersonalkaColors.inset], тень по верхнему краю внутри и светлая черта снизу.
 * Поля ввода, плитки-счётчики, дорожка сегментов, выбранный раздел нижней панели, запреты в календаре.
 */
fun Modifier.insetLevel(shape: Shape, colors: PersonalkaColors, fill: Color = colors.inset): Modifier {
    val shade = if (colors.isDark) Color.Black.copy(alpha = 0.6f) else Color(0xFF15171A).copy(alpha = 0.16f)
    val line = Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.9f)
    return this.clip(shape).drawBehind {
        drawRect(fill)
        drawRect(Brush.verticalGradient(listOf(shade, Color.Transparent), startY = 0f, endY = 6.dp.toPx()))
        drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
    }
}

/**
 * Фон экрана: в ночной теме сверху мягкое свечение — бирюзовое пятно слева и фиолетовое справа, как на холсте
 * «Сияние». В светлой — ровный `bg`.
 */
fun Modifier.screenBackground(colors: PersonalkaColors): Modifier = drawBehind {
    drawRect(colors.bg)
    if (!colors.isDark) return@drawBehind
    glow(Color(0xFF145A66).copy(alpha = 0.55f), center = Offset(0f, 0f), rx = size.width * 1.2f * 0.65f, ry = size.height * 0.30f * 0.65f)
    glow(Color(0xFF4B2F8C).copy(alpha = 0.45f), center = Offset(size.width, size.height * 0.04f), rx = size.width * 0.9f * 0.65f, ry = size.height * 0.28f * 0.65f)
}

/** Эллиптическое пятно: круговой градиент, сжатый по высоте до [ry]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.glow(color: Color, center: Offset, rx: Float, ry: Float) {
    if (rx <= 0f || ry <= 0f) return
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = rx), radius = rx, center = center)
    }
}
