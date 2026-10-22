package com.akenory.personalka.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Цвета «Персоналки» (шаг 0.2). Все пары текста проверены: не ниже 4,5:1 в обеих темах. */
@Immutable
data class PersonalkaColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val divider: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentGraphic: Color,
    val accentText: Color,
    val done: Color,
    val doneContainer: Color,
    val onDoneContainer: Color,
    val charged: Color,
    val chargedContainer: Color,
    val onChargedContainer: Color,
    val over: Color,
    val overContainer: Color,
    val onOverContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val hatch: Color,
    val pastTint: Color,
    val inverseSurface: Color,
    val inverseText: Color,
    val inverseAction: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightColors = PersonalkaColors(
    bg = Color(0xFFF3EFE6),
    surface = Color(0xFFFBFAF7),
    surfaceAlt = Color(0xFFEAE6DC),
    divider = Color(0xFFDCD9D0),
    outline = Color(0xFF86837B),
    text = Color(0xFF15171A),
    textMuted = Color(0xFF69665E),
    primary = Color(0xFF15171A),
    onPrimary = Color(0xFFF3EFE6),
    accent = Color(0xFFFF5A1F),
    onAccent = Color(0xFF15171A),
    accentGraphic = Color(0xFFF55111),
    accentText = Color(0xFFBA3800),
    done = Color(0xFF007634),
    doneContainer = Color(0xFFD0F0D5),
    onDoneContainer = Color(0xFF1B5B2F),
    charged = Color(0xFF8E5B00),
    chargedContainer = Color(0xFFFCE2C3),
    onChargedContainer = Color(0xFF6B4300),
    over = Color(0xFF7C4DBD),
    overContainer = Color(0xFFEAE0FF),
    onOverContainer = Color(0xFF583A83),
    error = Color(0xFFC22540),
    errorContainer = Color(0xFFFFDDDD),
    onErrorContainer = Color(0xFF862430),
    hatch = Color(0xFFAAABAA),
    pastTint = Color(0xFFF3F2EF),
    inverseSurface = Color(0xFF15171A),
    inverseText = Color(0xFFF3EFE6),
    inverseAction = Color(0xFFFF5A1F),
    scrim = Color(0x6615171A),
    isDark = false,
)

val DarkColors = PersonalkaColors(
    bg = Color(0xFF15171A),
    surface = Color(0xFF1E2124),
    surfaceAlt = Color(0xFF2A2D31),
    divider = Color(0xFF34373B),
    outline = Color(0xFF72767C),
    text = Color(0xFFF3EFE6),
    textMuted = Color(0xFF98958D),
    primary = Color(0xFFF3EFE6),
    onPrimary = Color(0xFF15171A),
    accent = Color(0xFFFF5A1F),
    onAccent = Color(0xFF15171A),
    accentGraphic = Color(0xFFFF5A1F),
    accentText = Color(0xFFFE632F),
    done = Color(0xFF64B376),
    doneContainer = Color(0xFF1C3E24),
    onDoneContainer = Color(0xFFC2E1C7),
    charged = Color(0xFFCF9037),
    chargedContainer = Color(0xFF48300D),
    onChargedContainer = Color(0xFFEED3B2),
    over = Color(0xFFAD88EA),
    overContainer = Color(0xFF3A2E50),
    onOverContainer = Color(0xFFDDCFFA),
    error = Color(0xFFF26F78),
    errorContainer = Color(0xFF50282A),
    onErrorContainer = Color(0xFFFFC7C8),
    hatch = Color(0xFF696968),
    pastTint = Color(0xFF171A1C),
    inverseSurface = Color(0xFFF3EFE6),
    inverseText = Color(0xFF15171A),
    inverseAction = Color(0xFFBA3800),
    scrim = Color(0xA3000000),
    isDark = true,
)

val LocalPersonalkaColors = staticCompositionLocalOf { LightColors }

/** Три тона цвета типа: фон блока, полоса 3 dp и точка, текст блока. */
@Immutable
data class TypeTone(val block: Color, val stripe: Color, val text: Color)

/** 16 цветов типов. В базе хранится key (TrainingType.color), а не ARGB: у каждой темы свои тона. */
enum class TypeColor(val key: String, val title: String, val light: TypeTone, val dark: TypeTone) {
    RED("red", "Красный",
        light = TypeTone(block = Color(0xFFFFDBD7), stripe = Color(0xFFE44B4A), text = Color(0xFF7C322F)),
        dark = TypeTone(block = Color(0xFF592624), stripe = Color(0xFFF66C66), text = Color(0xFFF9CBC6))),
    ORANGE("orange", "Оранжевый",
        light = TypeTone(block = Color(0xFFFFDCCB), stripe = Color(0xFFD45E00), text = Color(0xFF753B19)),
        dark = TypeTone(block = Color(0xFF562A11), stripe = Color(0xFFE97D3F), text = Color(0xFFF3CFBD))),
    AMBER("amber", "Янтарный",
        light = TypeTone(block = Color(0xFFFFDFB8), stripe = Color(0xFFB47500), text = Color(0xFF6A4404)),
        dark = TypeTone(block = Color(0xFF4F3100), stripe = Color(0xFFD58D19), text = Color(0xFFEAD3B8))),
    MUSTARD("mustard", "Горчичный",
        light = TypeTone(block = Color(0xFFF0E6B2), stripe = Color(0xFF978200), text = Color(0xFF594D09)),
        dark = TypeTone(block = Color(0xFF423800), stripe = Color(0xFFB59E23), text = Color(0xFFDED8B9))),
    LIME("lime", "Салатовый",
        light = TypeTone(block = Color(0xFFD9EEBB), stripe = Color(0xFF6A9000), text = Color(0xFF415616)),
        dark = TypeTone(block = Color(0xFF2E3E0D), stripe = Color(0xFF87AE39), text = Color(0xFFD0DEBD))),
    GREEN("green", "Зелёный",
        light = TypeTone(block = Color(0xFFC4F2CC), stripe = Color(0xFF149848), text = Color(0xFF245A32)),
        dark = TypeTone(block = Color(0xFF154222), stripe = Color(0xFF52B76D), text = Color(0xFFC4E0C8))),
    EMERALD("emerald", "Изумрудный",
        light = TypeTone(block = Color(0xFFC7EFDF), stripe = Color(0xFF2B9475), text = Color(0xFF295748)),
        dark = TypeTone(block = Color(0xFF174133), stripe = Color(0xFF5BB294), text = Color(0xFFC5DED4))),
    TEAL("teal", "Бирюзовый",
        light = TypeTone(block = Color(0xFFBAF0F4), stripe = Color(0xFF00929A), text = Color(0xFF0E585D)),
        dark = TypeTone(block = Color(0xFF004145), stripe = Color(0xFF2CB2BB), text = Color(0xFFBEDFE1))),
    SKY("sky", "Голубой",
        light = TypeTone(block = Color(0xFFC8EAFE), stripe = Color(0xFF3A8BB4), text = Color(0xFF2C5267)),
        dark = TypeTone(block = Color(0xFF1B3C4E), stripe = Color(0xFF62A8CE), text = Color(0xFFC6DBE7))),
    BLUE("blue", "Синий",
        light = TypeTone(block = Color(0xFFD3E7FF), stripe = Color(0xFF2D84E0), text = Color(0xFF264F7C)),
        dark = TypeTone(block = Color(0xFF18395D), stripe = Color(0xFF57A1F6), text = Color(0xFFC3DAF5))),
    INDIGO("indigo", "Индиго",
        light = TypeTone(block = Color(0xFFDEE3FF), stripe = Color(0xFF7377E1), text = Color(0xFF43477B)),
        dark = TypeTone(block = Color(0xFF30335E), stripe = Color(0xFF8C93F4), text = Color(0xFFD1D5F5))),
    VIOLET("violet", "Фиолетовый",
        light = TypeTone(block = Color(0xFFE9DEFF), stripe = Color(0xFF9B66E6), text = Color(0xFF563E7C)),
        dark = TypeTone(block = Color(0xFF3E2E59), stripe = Color(0xFFAF82F6), text = Color(0xFFDCD0F6))),
    MAGENTA("magenta", "Пурпурный",
        light = TypeTone(block = Color(0xFFFDD5FF), stripe = Color(0xFFBB59C1), text = Color(0xFF67386A)),
        dark = TypeTone(block = Color(0xFF4B294D), stripe = Color(0xFFD077D5), text = Color(0xFFE9CDEA))),
    PINK("pink", "Розовый",
        light = TypeTone(block = Color(0xFFFFD9E6), stripe = Color(0xFFCF588D), text = Color(0xFF71374F)),
        dark = TypeTone(block = Color(0xFF55263A), stripe = Color(0xFFE275A3), text = Color(0xFFF1CCD9))),
    BROWN("brown", "Коричневый",
        light = TypeTone(block = Color(0xFFF5E0D3), stripe = Color(0xFFA2795E), text = Color(0xFF5D483A)),
        dark = TypeTone(block = Color(0xFF453428), stripe = Color(0xFFBC957C), text = Color(0xFFE2D5CC))),
    SLATE("slate", "Сланцевый",
        light = TypeTone(block = Color(0xFFDDE6EF), stripe = Color(0xFF738497), text = Color(0xFF454E57)),
        dark = TypeTone(block = Color(0xFF313941), stripe = Color(0xFF90A0B2), text = Color(0xFFD3D8DE))),
    ;

    fun tone(dark: Boolean): TypeTone = if (dark) this.dark else light

    companion object {
        fun of(key: String?): TypeColor = entries.firstOrNull { it.key == key } ?: SLATE
    }
}

/** Заливка дежурства: полоса типа на 20 % поверх фона сетки (ТЗ 5.3). */
fun dutyFill(type: TypeColor, colors: PersonalkaColors): Color =
    lerp(colors.surface, type.tone(colors.isDark).stripe, 0.20f)

/** Цвета типов по умолчанию для первого запуска (ТЗ 3.3). */
val DefaultTypeColors = mapOf(
    "ПТ" to TypeColor.BLUE,
    "Сплит" to TypeColor.INDIGO,
    "Мини-группа" to TypeColor.LIME,
    "Группа" to TypeColor.GREEN,
    "СПТ" to TypeColor.PINK,
    "Дежурство" to TypeColor.SLATE,
    "Грудничковое" to TypeColor.TEAL,
    "Гидрореабилитация" to TypeColor.MAGENTA,
    "Кинезио" to TypeColor.BROWN,
)
