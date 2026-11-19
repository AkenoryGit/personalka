package com.akenory.personalka.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaColors
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.theme.AuroraBrush
import com.akenory.personalka.ui.theme.OnAurora
import com.akenory.personalka.ui.theme.insetLevel
import com.akenory.personalka.ui.theme.raised

/**
 * Общие элементы нового оформления (шаг 5.11а, `docs/mockups/redesign`): цвет — у карточек, а не только у меток.
 * Зелёный — деньги и «проведено», янтарный — списание и «без записи», цвет места — всё про одну тренировку,
 * остальное — нейтральные плитки `surface` на фоне `bg`. Уровни и «Сияние» — `theme/Levels.kt` (шаг 5.13а).
 */
@Immutable
data class Tone(val fill: Color, val content: Color, val strong: Color, val muted: Color)

/** Нейтральная плитка или карточка: `surface`, текст — `text`, подписи — `textMuted`. */
fun PersonalkaColors.neutralTone() = Tone(surface, text, text, textMuted)

/** «Проведено», деньги в плане: зелёный фон метки, текст на нём (6,6 : 1 светлая, 8,4 : 1 тёмная). */
fun PersonalkaColors.doneTone() = Tone(doneContainer, onDoneContainer, text, onDoneContainer)

/** «Со списанием», «без записи»: янтарный фон (6,9 : 1 / 8,6 : 1). */
fun PersonalkaColors.chargedTone() = Tone(chargedContainer, onChargedContainer, onChargedContainer, onChargedContainer)

/** Тренировка: фон — светлый оттенок цвета места, текст — тон места (не хуже 5,5 : 1 на любом оттенке). */
fun PlaceColor.cardTone(colors: PersonalkaColors): Tone {
    val t = tone(colors.isDark)
    return Tone(t.block(1), t.text, if (colors.isDark) Color.White else colors.text, t.text)
}

/**
 * Цветная карточка: скругление 20, отступы 16. Сумма месяца, «Сейчас», шапка карточки тренировки.
 * Уровень «сверху» по умолчанию (шаги 5.13а, 5.14): цветные карточки «Сверок», «Отчётов», «Выплат» — объёмные.
 */
@Composable
fun ToneCard(tone: Tone, modifier: Modifier = Modifier, raised: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .fillMaxWidth()
            .then(if (raised) Modifier.raised(shape, PersonalkaTheme.colors) else Modifier.clip(shape))
            .background(tone.fill)
            .padding(Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        content = content,
    )
}

/** Нейтральная карточка: `surface`, скругление 14, отступы 16. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(PersonalkaTheme.colors.surface)
            .padding(Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        content = content,
    )
}

/**
 * Плитка с цифрой: «94 / 149» крупно (20 sp, 600), «ПТ проведено» подписью 12 sp. Три-четыре плитки
 * в ряд — [TileRow]. С [onClick] плитка нажимается; с [selected] она ещё и фильтр (клиенты): выбранная — «Сияние» (шаг 5.14).
 * Нейтральная плитка — вдавленная (уровень −1, шаг 5.13а), цветная — заливкой своего тона.
 */
@Composable
fun RowScope.Tile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: Tone = PersonalkaTheme.colors.neutralTone(),
    suffix: String? = null,
    note: String? = null,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
    compact: Boolean = false,
) {
    val colors = PersonalkaTheme.colors
    val shape = MaterialTheme.shapes.medium
    val onAurora = selected == true
    Column(
        modifier
            .weight(1f)
            .fillMaxHeight()
            // Шаг 5.14: плитка — вдавленная (цветная — вдавленная своим тоном); выбранный фильтр — «Сияние» сверху.
            .then(
                when {
                    selected == true -> Modifier.raised(shape, colors, elevation = 4).background(AuroraBrush)
                    else -> Modifier.insetLevel(shape, colors, fill = if (tone.fill == colors.surface) colors.inset else tone.fill)
                },
            )
            .then(
                when {
                    onClick == null -> Modifier
                    selected != null -> Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                    else -> Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            // Шаг 5.14в: плитка с меткой — один узел для диктора и тестов: цифра, подпись и третья строка вместе.
            .then(if (tag != null) Modifier.semantics(mergeDescendants = true) {}.testTag(tag) else Modifier)
            .heightIn(min = Dimens.touch)
            .padding(horizontal = Dimens.space3, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // [compact] — суммы в рублях (шаг 5.12б): 17 sp, чтобы «1 478 495 ₽» влезала в треть сложенного Fold.
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium.tabular() else MaterialTheme.typography.titleLarge,
                color = if (onAurora) OnAurora else tone.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (suffix != null) {
                Text(suffix, style = MaterialTheme.typography.bodySmall, color = if (onAurora) OnAurora else tone.muted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = if (onAurora) OnAurora.copy(alpha = 0.88f) else tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Третья строка — «сегодня …» (шаг 5.11д): цифрами, цветом текста, чтобы читалась отдельно от подписи.
        if (note != null) {
            Text(note, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium.tabular(), color = if (onAurora) OnAurora else tone.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Ряд плиток с зазором 8; все плитки ряда — одной высоты, по самой высокой (просьба Дмитрия 19.11.2026). */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.space2), content = content)
}

/**
 * Вкладки внутри экрана — сегменты во вдавленной дорожке: выбранный — «Сияние» на уровне «сверху» (шаг 5.13а)
 * («Тренировки / Результаты / Инфо / Расписание», «Месяц / Выплаты / Коллеги / Год», отчёты).
 */
@Composable
fun <T> SegmentTabs(
    items: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: (T) -> String? = { null },
) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .insetLevel(MaterialTheme.shapes.medium, colors)
            .padding(4.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            val t = tag(item)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(
                        if (isSelected) Modifier.raised(MaterialTheme.shapes.small, colors, elevation = 4).background(AuroraBrush)
                        else Modifier.clip(MaterialTheme.shapes.small),
                    )
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(item) }
                    .then(if (t != null) Modifier.testTag(t) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) OnAurora else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Подпись над карточкой: «Дальше сегодня · 4», «История». */
@Composable
fun BlockTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = Dimens.space1, bottom = Dimens.space2),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}

/**
 * Квадратик 36 dp цвета места слева у строки списка (шаг 5.12а), как значки в «Ещё»: внутри — короткое имя
 * места («ББ», «Зал») или значок. [place] `null` — нейтральный квадратик `surfaceAlt`.
 */
@Composable
fun PlaceBadge(place: PlaceColor?, modifier: Modifier = Modifier, text: String? = null, icon: ImageVector? = null) {
    val colors = PersonalkaTheme.colors
    val tint = place?.tone(colors.isDark)
    Box(
        modifier.size(36.dp).then(if (tint != null) Modifier.raised(RoundedCornerShape(10.dp), colors, elevation = 2).background(tint.block(2)) else Modifier.insetLevel(RoundedCornerShape(10.dp), colors)),
        contentAlignment = Alignment.Center,
    ) {
        val content = tint?.text ?: colors.text
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            text != null -> Text(text.take(4), style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
        }
    }
}

/** Точка 10 dp цвета места: в строках, где квадратик лишний (разбивка, слоты, итоги по местам). */
@Composable
fun PlaceDot(place: PlaceColor, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(place.tone(PersonalkaTheme.colors.isDark).stripe))
}

/** Метка статуса на подложке тона («Сверено», «Списание», «Не сверено») — вместо цветного текста (шаг 5.12а). */
@Composable
fun StatusLabel(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tone.fill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = tone.content,
        maxLines = 1,
    )
}

/**
 * Строка внутри карточки: необязательная точка цвета места, подпись и пояснение, справа число (`labelLarge`, 600 —
 * шаг 5.12а). Разбивки зарплаты, итоги отчётов, строки сверок.
 */
@Composable
fun ValueRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    place: PlaceColor? = null,
    valueColor: Color = PersonalkaTheme.colors.text,
    onClick: (() -> Unit)? = null,
) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (place != null) {
            PlaceDot(place)
            Spacer(Modifier.width(Dimens.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (value != null) {
            Spacer(Modifier.width(Dimens.space3))
            Text(value, style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.SemiBold), color = valueColor, maxLines = 1)
        }
    }
}

/**
 * Карточка настроек (шаг 5.12в): переключатели и галочки раздела — на `surface` со скруглением 14, как пункты «Ещё»,
 * а не строками прямо на фоне. Отступы строк внутри — 16 по бокам.
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            // Шаг 5.14: группа настроек — на уровне «сверху», как карточки-списки и пункты «Ещё».
            .raised(MaterialTheme.shapes.large, PersonalkaTheme.colors, elevation = 4)
            .background(PersonalkaTheme.colors.surface)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space1),
        content = content,
    )
}

/**
 * Вдавленная плашка (шаг 5.12д): тёмное углубление в цветной карточке — заливка, тень по верхнему краю внутри
 * и светлая черта снизу, будто окошко выбито в карточке. Ступень и пояснение ⓘ на «Деньгах».
 */
fun Modifier.insetWell(shape: androidx.compose.ui.graphics.Shape, fill: Color = Color.Black.copy(alpha = 0.24f)): Modifier =
    this.clip(shape).background(fill).drawBehind {
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent), startY = 0f, endY = 6.dp.toPx()))
        drawRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
    }

/**
 * Кнопка-значок шапки на уровне «сверху» (шаг 5.14): 44 dp, скругление 14, зона нажатия 48. [aurora] — главное
 * действие экрана «Сиянием» («Новый клиент»); остальные — светлой выпуклой плашкой.
 */
@Composable
fun RaisedIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aurora: Boolean = false,
    iconRotation: Float = 0f,
) {
    val colors = PersonalkaTheme.colors
    val shape = MaterialTheme.shapes.medium
    Box(Modifier.size(Dimens.touch), contentAlignment = Alignment.Center) {
        Box(
            modifier
                .size(44.dp)
                .raised(shape, colors, elevation = 4)
                .then(if (aurora) Modifier.background(AuroraBrush) else Modifier.background(colors.nav))
                .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (aurora) OnAurora else colors.text, modifier = Modifier.size(22.dp).rotate(iconRotation))
        }
    }
}
