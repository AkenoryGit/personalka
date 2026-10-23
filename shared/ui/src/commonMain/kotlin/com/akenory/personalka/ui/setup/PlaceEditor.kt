package com.akenory.personalka.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.reference.DraftField
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor

const val PLACE_NAME_TAG = "place-name"
const val PLACE_SHORT_TAG = "place-short"

/**
 * Место (ТЗ 3.3, 5.16): название, короткое имя до 4 знаков — его видно в календаре и в строках тренировок — и цвет:
 * им красятся тренировки места, а оттенок выбирает тип (шаг 1.13в). Под палитрой — четыре оттенка с типами.
 *
 * @param others другие действующие места: короткое имя не повторяется, о том же цвете — предупреждение.
 * @param samples подписи образцов оттенков 1–4: короткое имя типа с этим оттенком или название оттенка.
 * @param archived `null` у нового места; у сохранённого — в архиве ли оно.
 */
@Composable
fun PlaceEditor(
    title: String,
    initial: PlaceDraft,
    others: List<PlaceDraft>,
    samples: List<String>,
    onSave: (PlaceDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean? = null,
    onArchive: ((Boolean) -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var short by rememberSaveable { mutableStateOf(initial.shortName) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val draft = PlaceDraft(name, short, color)
    val problems = ReferenceRules.placeProblems(draft, others)
    val shown = if (showErrors) problems else emptyMap()

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
        TopBar(title, onBack = onClose, close = true)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.space4),
        ) {
            Spacer(Modifier.height(Dimens.space2))
            Field(
                label = "Название",
                value = name,
                onValueChange = { name = it },
                error = shown[DraftField.NAME],
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                tag = PLACE_NAME_TAG,
            )
            Spacer(Modifier.height(Dimens.space4))
            Field(
                label = "Коротко",
                value = short,
                onValueChange = { short = it.take(ReferenceRules.PLACE_SHORT_MAX + 2) },
                error = shown[DraftField.SHORT_NAME],
                hint = "До ${ReferenceRules.PLACE_SHORT_MAX} знаков — так место видно в календаре: ББ, ДБ, Зал",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                tag = PLACE_SHORT_TAG,
            )

            SectionLabel("Цвет")
            ColorPalette(selected = color, onSelect = { color = it })
            ReferenceRules.sameColor(color, others)?.let { twin ->
                Text(
                    text = "Этот цвет уже у места «${twin.name}»",
                    modifier = Modifier.padding(top = Dimens.space2),
                    style = MaterialTheme.typography.bodySmall,
                    color = PersonalkaTheme.colors.textMuted,
                )
            }

            SectionLabel("Так будет в календаре")
            SampleCard {
                val place = PlaceColor.of(color)
                val where = short.trim().ifEmpty { null }
                ReferenceRules.SHADES.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space1)) {
                        pair.forEach { shade ->
                            val label = samples.getOrNull(shade - 1) ?: TypeFormat.shade(shade)
                            BlockSample(place, shade, label, listOfNotNull("19:00", where).joinToString(" · "), Modifier.weight(1f))
                        }
                    }
                }
            }
            if (archived != null && onArchive != null) {
                Spacer(Modifier.height(Dimens.space4))
                TextButton(onClick = { onArchive(!archived) }) {
                    Text(
                        text = if (archived) "Вернуть из архива" else "Убрать в архив",
                        style = MaterialTheme.typography.labelLarge,
                        color = PersonalkaTheme.colors.text,
                    )
                }
            }
        }
        BottomAction(
            text = "Сохранить",
            onClick = { if (problems.isEmpty()) onSave(draft) else showErrors = true },
        )
    }
}
