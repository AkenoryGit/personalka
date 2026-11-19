package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaColors
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor

/**
 * Общие элементы нового оформления (шаг 5.11а, `docs/mockups/redesign`): цвет — у карточек, а не только у меток.
 * Зелёный — деньги и «проведено», янтарный — списание и «без записи», цвет места — всё про одну тренировку,
 * остальное — нейтральные плитки `surface` на фоне `bg`. Оранжевый здесь не используется (ТЗ 10.5).
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

/** Цветная карточка: скругление 20, отступы 16. Сумма месяца, «Сейчас», шапка карточки тренировки. */
@Composable
fun ToneCard(tone: Tone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
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
 * в ряд — [TileRow]. С [onClick] плитка нажимается; с [selected] она ещё и фильтр (клиенты): выбранная обведена рамкой `primary`.
 */
@Composable
fun RowScope.Tile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: Tone = PersonalkaTheme.colors.neutralTone(),
    suffix: String? = null,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
) {
    val colors = PersonalkaTheme.colors
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .weight(1f)
            .clip(shape)
            .background(tone.fill)
            .then(if (selected == true) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .then(
                when {
                    onClick == null -> Modifier
                    selected != null -> Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                    else -> Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .heightIn(min = Dimens.touch)
            .padding(horizontal = Dimens.space3, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = tone.strong, maxLines = 1)
            if (suffix != null) {
                Text(suffix, style = MaterialTheme.typography.bodySmall, color = tone.muted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Ряд плиток с зазором 8. */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space2), content = content)
}

/**
 * Вкладки внутри экрана — сегменты на `surface`: выбранный залит `primary` («Тренировки / Результаты / Инфо /
 * Расписание», «Месяц / Выплаты / Коллеги / Год», отчёты). Заменяет подчёркнутые вкладки трёх экранов.
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
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .padding(4.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            val t = tag(item)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) colors.primary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(item) }
                    .then(if (t != null) Modifier.testTag(t) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.onPrimary else colors.text,
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
