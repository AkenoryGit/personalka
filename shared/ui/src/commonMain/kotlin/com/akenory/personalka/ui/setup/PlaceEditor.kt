package com.akenory.personalka.ui.setup

import androidx.compose.foundation.layout.Column
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
import com.akenory.personalka.domain.reference.DraftField
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

const val PLACE_NAME_TAG = "place-name"
const val PLACE_SHORT_TAG = "place-short"

/**
 * Место (ТЗ 3.3, 5.16): название и короткое имя до 4 знаков — его видно в календаре и в строках тренировок.
 *
 * @param others другие действующие места: короткое имя не повторяется.
 * @param archived `null` у нового места; у сохранённого — в архиве ли оно.
 */
@Composable
fun PlaceEditor(
    title: String,
    initial: PlaceDraft,
    others: List<PlaceDraft>,
    onSave: (PlaceDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean? = null,
    onArchive: ((Boolean) -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var short by rememberSaveable { mutableStateOf(initial.shortName) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val draft = PlaceDraft(name, short)
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
