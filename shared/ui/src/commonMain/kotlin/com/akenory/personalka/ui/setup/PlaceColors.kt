package com.akenory.personalka.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor

// Цвет места и оттенок типа (шаг 1.13в, ТЗ 3.3, 5.16): палитра в форме места, оттенки и образцы блоков в конструкторе типа.

internal fun colorTag(key: String) = "color-$key"
internal fun shadeTag(shade: Int) = "shade-$shade"

/** 16 цветов в два ряда (TypeColors 0.2): выбранный — кольцо primary 2 dp с зазором 2 dp и галочка. */
@Composable
internal fun ColorPalette(selected: String, onSelect: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        PlaceColor.entries.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { color ->
                    val isSelected = color.key == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.touch)
                            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(color.key) }
                            .semantics { contentDescription = color.title }
                            .testTag(colorTag(color.key)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(if (isSelected) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(color.tone(colors.isDark).stripe),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(PIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Четыре оттенка рядом — образцами блока в цвете [place]: «Светлый», «Средний», «Яркий», «Тёмный».
 * Выбранный — в рамке primary 2 dp.
 */
@Composable
internal fun ShadePicker(selected: Int, place: PlaceColor, onSelect: (Int) -> Unit) {
    val colors = PersonalkaTheme.colors
    val tone = place.tone(colors.isDark)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ReferenceRules.SHADES.forEach { shade ->
            val isSelected = shade == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(Dimens.touch)
                    .clip(MaterialTheme.shapes.small)
                    .then(if (isSelected) Modifier.border(2.dp, colors.primary, MaterialTheme.shapes.small) else Modifier)
                    .background(tone.block(shade))
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(shade) }
                    .testTag(shadeTag(shade)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(Dimens.blockStripe).fillMaxHeight().background(tone.stripe))
                Text(
                    text = TypeFormat.shade(shade),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Образец блока календаря (CalendarBlock): фон — цвет места оттенком, полоса 3 dp, две строки текста. */
@Composable
internal fun BlockSample(place: PlaceColor, shade: Int, title: String, line: String, modifier: Modifier = Modifier) {
    val tone = place.tone(PersonalkaTheme.colors.isDark)
    Row(
        modifier
            .height(56.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(tone.block(shade)),
    ) {
        Box(Modifier.width(Dimens.blockStripe).fillMaxHeight().background(tone.stripe))
        Column(Modifier.padding(horizontal = Dimens.space2, vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Подложка образцов «Так будет в календаре»: блоки на фоне карточки, как на сетке. */
@Composable
internal fun SampleCard(content: @Composable () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(Dimens.space2),
        verticalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        content()
    }
}
