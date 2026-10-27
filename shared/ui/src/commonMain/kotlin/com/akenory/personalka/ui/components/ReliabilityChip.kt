package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.client.ReliabilityLevel
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaColors
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular

fun reliabilityTag(clientId: String) = "reliability-$clientId"

/** Фон и текст надёжности (шаг 3.14): зелёный, жёлтый, красный — не оранжевый «+»; «новый» — нейтральный. */
@Composable
fun reliabilityColors(r: Reliability): Pair<Color, Color> = reliabilityTone(r.level, PersonalkaTheme.colors)

/** То же для рисования без Compose — плашка на блоке календаря (шаг 3.14а). */
fun reliabilityTone(level: ReliabilityLevel?, colors: PersonalkaColors): Pair<Color, Color> = when (level) {
    ReliabilityLevel.HIGH -> colors.doneContainer to colors.onDoneContainer
    ReliabilityLevel.MIDDLE -> colors.chargedContainer to colors.onChargedContainer
    ReliabilityLevel.LOW -> colors.errorContainer to colors.onErrorContainer
    null -> colors.surfaceAlt to colors.textMuted
}

/** Плашка надёжности клиента: [text] — «84 %», «новый» или «Надёжность 84 %». */
@Composable
fun ReliabilityChip(r: Reliability, text: String, clientId: String, modifier: Modifier = Modifier) {
    val (bg, fg) = reliabilityColors(r)
    Text(
        text,
        modifier = modifier
            .testTag(reliabilityTag(clientId))
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .padding(horizontal = Dimens.space2, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium.tabular(),
        color = fg,
        maxLines = 1,
    )
}
