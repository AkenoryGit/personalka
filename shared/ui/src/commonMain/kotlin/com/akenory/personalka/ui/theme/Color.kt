package com.akenory.personalka.ui.theme

import com.akenory.personalka.domain.format.WhoKind
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

/**
 * Тона цвета места (шаг 1.13в): полоса 3 dp и точка, текст блока и четыре фона — по одному на оттенок типа,
 * от светлого (1) до тёмного (4). Текст читается на любом из четырёх не хуже 5,5 : 1, соседние оттенки
 * различаются на ΔE ≥ 5,9 (OKLab × 100) — значения посчитаны скриптом шага и проверяются тестом.
 */
@Immutable
data class PlaceTone(val stripe: Color, val text: Color, val shades: List<Color>) {
    /** Фон блока тренировки, у типа которой оттенок [shade]: 1 — светлый … 4 — тёмный. */
    fun block(shade: Int): Color = shades[(shade - 1).coerceIn(0, shades.lastIndex)]
}

/**
 * 16 цветов мест (ТЗ 3.3, 5.3). Блок тренировки красится цветом её места, а оттенок фона выбирает тип:
 * в синем ББ ПТ — светлая, группа — тёмная. В базе хранится key (`Location.color`), а не ARGB: у каждой темы
 * свои тона. Полосы и название цветов — из дизайн-системы 0.2.
 */
enum class PlaceColor(val key: String, val title: String, val light: PlaceTone, val dark: PlaceTone) {
    RED("red", "Красный",
        light = PlaceTone(stripe = Color(0xFFE44B4A), text = Color(0xFF542523), shades = listOf(Color(0xFFFFEBE9), Color(0xFFFED0CB), Color(0xFFF9B5AF), Color(0xFFEF9D96))),
        dark = PlaceTone(stripe = Color(0xFFF66C66), text = Color(0xFFFFE4E1), shades = listOf(Color(0xFF3F2220), Color(0xFF582C29), Color(0xFF723632), Color(0xFF8A423D)))),
    ORANGE("orange", "Оранжевый",
        light = PlaceTone(stripe = Color(0xFFD45E00), text = Color(0xFF522912), shades = listOf(Color(0xFFFFECE3), Color(0xFFFCD2BE), Color(0xFFF5BA9C), Color(0xFFEBA27D))),
        dark = PlaceTone(stripe = Color(0xFFE97D3F), text = Color(0xFFFDE6DA), shades = listOf(Color(0xFF3E2417), Color(0xFF562F1A), Color(0xFF6F3A1D), Color(0xFF874723)))),
    AMBER("amber", "Янтарный",
        light = PlaceTone(stripe = Color(0xFFB47500), text = Color(0xFF49300B), shades = listOf(Color(0xFFFCEEDD), Color(0xFFF1D8BA), Color(0xFFE6C297), Color(0xFFD9AD76))),
        dark = PlaceTone(stripe = Color(0xFFD58D19), text = Color(0xFFF8E8D6), shades = listOf(Color(0xFF392710), Color(0xFF4F340E), Color(0xFF664105), Color(0xFF7D5002)))),
    MUSTARD("mustard", "Горчичный",
        light = PlaceTone(stripe = Color(0xFF978200), text = Color(0xFF3E350C), shades = listOf(Color(0xFFF4F1DE), Color(0xFFE4DDBB), Color(0xFFD4CA98), Color(0xFFC4B778))),
        dark = PlaceTone(stripe = Color(0xFFB59E23), text = Color(0xFFF0ECD5), shades = listOf(Color(0xFF312B0F), Color(0xFF443A0A), Color(0xFF574A03), Color(0xFF6A5A03)))),
    LIME("lime", "Салатовый",
        light = PlaceTone(stripe = Color(0xFF6A9000), text = Color(0xFF2C3C0F), shades = listOf(Color(0xFFEBF4DF), Color(0xFFD3E4BC), Color(0xFFBCD39A), Color(0xFFA6C27B))),
        dark = PlaceTone(stripe = Color(0xFF87AE39), text = Color(0xFFE6EFDA), shades = listOf(Color(0xFF262F16), Color(0xFF324018), Color(0xFF3F5219), Color(0xFF4D641E)))),
    GREEN("green", "Зелёный",
        light = PlaceTone(stripe = Color(0xFF149848), text = Color(0xFF163F21), shades = listOf(Color(0xFFE3F6E6), Color(0xFFC4E7CA), Color(0xFFA4D8AE), Color(0xFF86C894))),
        dark = PlaceTone(stripe = Color(0xFF52B76D), text = Color(0xFFDEF1E1), shades = listOf(Color(0xFF1A321F), Color(0xFF1F4428), Color(0xFF225731), Color(0xFF296A3B)))),
    EMERALD("emerald", "Изумрудный",
        light = PlaceTone(stripe = Color(0xFF2B9475), text = Color(0xFF173E31), shades = listOf(Color(0xFFE3F5EE), Color(0xFFC4E5D8), Color(0xFFA5D5C3), Color(0xFF88C5AE))),
        dark = PlaceTone(stripe = Color(0xFF5BB294), text = Color(0xFFDEF0E8), shades = listOf(Color(0xFF1A3128), Color(0xFF1E4336), Color(0xFF215544), Color(0xFF286853)))),
    TEAL("teal", "Бирюзовый",
        light = PlaceTone(stripe = Color(0xFF00929A), text = Color(0xFF103D40), shades = listOf(Color(0xFFE1F5F6), Color(0xFFC1E4E7), Color(0xFF9FD4D8), Color(0xFF80C3C8))),
        dark = PlaceTone(stripe = Color(0xFF2CB2BB), text = Color(0xFFD9F1F2), shades = listOf(Color(0xFF103133), Color(0xFF0B4347), Color(0xFF00555A), Color(0xFF07686D)))),
    SKY("sky", "Голубой",
        light = PlaceTone(stripe = Color(0xFF3A8BB4), text = Color(0xFF1B394A), shades = listOf(Color(0xFFE4F3FC), Color(0xFFC6E1F1), Color(0xFFA8CFE7), Color(0xFF8CBEDA))),
        dark = PlaceTone(stripe = Color(0xFF62A8CE), text = Color(0xFFDEEEF7), shades = listOf(Color(0xFF1B2E39), Color(0xFF213E4E), Color(0xFF265065), Color(0xFF2F617C)))),
    BLUE("blue", "Синий",
        light = PlaceTone(stripe = Color(0xFF2D84E0), text = Color(0xFF193658), shades = listOf(Color(0xFFE7F1FE), Color(0xFFC5DFFE), Color(0xFFA4CDFC), Color(0xFF88BAF5))),
        dark = PlaceTone(stripe = Color(0xFF57A1F6), text = Color(0xFFDFEDFE), shades = listOf(Color(0xFF1B2C41), Color(0xFF213C5C), Color(0xFF264C77), Color(0xFF2E5C91)))),
    INDIGO("indigo", "Индиго",
        light = PlaceTone(stripe = Color(0xFF7377E1), text = Color(0xFF2E3159), shades = listOf(Color(0xFFEDEFFE), Color(0xFFD4DAFF), Color(0xFFBDC4FD), Color(0xFFA8AFF5))),
        dark = PlaceTone(stripe = Color(0xFF8C93F4), text = Color(0xFFE7EAFE), shades = listOf(Color(0xFF262941), Color(0xFF33365C), Color(0xFF414478), Color(0xFF4F5392)))),
    VIOLET("violet", "Фиолетовый",
        light = PlaceTone(stripe = Color(0xFF9B66E6), text = Color(0xFF3B2C54), shades = listOf(Color(0xFFF2EDFE), Color(0xFFE2D5FC), Color(0xFFD1BEF6), Color(0xFFC0A7ED))),
        dark = PlaceTone(stripe = Color(0xFFAF82F6), text = Color(0xFFEEE7FD), shades = listOf(Color(0xFF2F263E), Color(0xFF403257), Color(0xFF523F71), Color(0xFF644C8A)))),
    MAGENTA("magenta", "Пурпурный",
        light = PlaceTone(stripe = Color(0xFFBB59C1), text = Color(0xFF472849), shades = listOf(Color(0xFFFBEAFB), Color(0xFFF0D0F1), Color(0xFFE4B7E6), Color(0xFFD7A0D9))),
        dark = PlaceTone(stripe = Color(0xFFD077D5), text = Color(0xFFF6E5F6), shades = listOf(Color(0xFF372338), Color(0xFF4C2E4D), Color(0xFF613963), Color(0xFF774579)))),
    PINK("pink", "Розовый",
        light = PlaceTone(stripe = Color(0xFFCF588D), text = Color(0xFF512537), shades = listOf(Color(0xFFFEEAF1), Color(0xFFFBCEDE), Color(0xFFF4B4CC), Color(0xFFE99BB9))),
        dark = PlaceTone(stripe = Color(0xFFE275A3), text = Color(0xFFFDE3EC), shades = listOf(Color(0xFF3D222C), Color(0xFF552B3C), Color(0xFF6D354D), Color(0xFF85415E)))),
    BROWN("brown", "Коричневый",
        light = PlaceTone(stripe = Color(0xFFA2795E), text = Color(0xFF423127), shades = listOf(Color(0xFFF7EEE8), Color(0xFFEAD9CE), Color(0xFFDCC4B4), Color(0xFFCCAF9C))),
        dark = PlaceTone(stripe = Color(0xFFBC957C), text = Color(0xFFF3E9E3), shades = listOf(Color(0xFF342922), Color(0xFF47372C), Color(0xFF5B4537), Color(0xFF6F5443)))),
    SLATE("slate", "Сланцевый",
        light = PlaceTone(stripe = Color(0xFF738497), text = Color(0xFF2F363E), shades = listOf(Color(0xFFEDF1F5), Color(0xFFD6DDE5), Color(0xFFC0CAD5), Color(0xFFABB7C5))),
        dark = PlaceTone(stripe = Color(0xFF90A0B2), text = Color(0xFFE8ECF0), shades = listOf(Color(0xFF272C31), Color(0xFF353B43), Color(0xFF424B56), Color(0xFF515C69)))),
    ;

    fun tone(dark: Boolean): PlaceTone = if (dark) this.dark else light

    companion object {
        fun of(key: String?): PlaceColor = entries.firstOrNull { it.key == key } ?: SLATE
    }
}

/** Оттенок цвета места, каким будет блок в календаре: фон блока и полоса (шаг 1.13в). */
@Immutable
data class Shade(val fill: Color, val ring: Color)

/** Блок типа с оттенком [shade] в месте этого цвета: для точек на чипах и строках типов. */
fun PlaceColor.shade(dark: Boolean, shade: Int): Shade = tone(dark).let { Shade(it.block(shade), it.stripe) }

/** Заливка дежурства: полоса цвета его места на 20 % поверх фона сетки (ТЗ 5.3). */
fun dutyFill(place: PlaceColor, colors: PersonalkaColors): Color =
    lerp(colors.surface, place.tone(colors.isDark).stripe, 0.20f)

/** Плашка «кто ведёт» на блоке группы: заливка и текст. */
@Immutable
data class WhoTone(val fill: Color, val text: Color)

/**
 * Цвета плашки «кто ведёт» (шаг 3.9п, просьба Дмитрия): своя один — зелёная, своя вдвоём — янтарная, группа
 * коллеги — пурпурная, ведёт коллега — сланцевая. В светлой теме тёмная заливка и белый текст, в тёмной — светлая
 * заливка и тёмный текст; контраст 5,4–6,9 : 1 и 8–8,7 : 1 (ТЗ 10.4). Оранжевого нет (10.5).
 */
fun whoTone(kind: WhoKind, dark: Boolean): WhoTone = when (kind) {
    WhoKind.MINE_ALONE -> if (dark) WhoTone(Color(0xFF7FD49A), Color(0xFF0F2A17)) else WhoTone(Color(0xFF1B7A3A), Color.White)
    WhoKind.MINE_TOGETHER -> if (dark) WhoTone(Color(0xFFE8B85C), Color(0xFF2E1F00)) else WhoTone(Color(0xFF8A5A00), Color.White)
    WhoKind.THEIRS -> if (dark) WhoTone(Color(0xFFE3A0E8), Color(0xFF33153A)) else WhoTone(Color(0xFF8E3A94), Color.White)
    WhoKind.NOT_ME -> if (dark) WhoTone(Color(0xFFB4C0CC), Color(0xFF1F262D)) else WhoTone(Color(0xFF4F5B67), Color.White)
}
