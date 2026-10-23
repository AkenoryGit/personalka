package com.akenory.personalka.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.DraftField
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.reference.TypeDraft
import com.akenory.personalka.domain.reference.TypePresets
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor

const val TYPE_NAME_TAG = "type-name"
const val TYPE_SHORT_TAG = "type-short"
const val TYPE_DURATION_TAG = "type-duration"
const val TYPE_LIMIT_TAG = "type-limit"
internal fun kindTag(kind: TrainingKind) = "kind-${kind.name}"

/**
 * Конструктор типа тренировки (ТЗ 5.16, макеты «Новый тип»): один экран сверху вниз — название и короткое
 * имя → как работает → оттенок и как блок будет выглядеть в календаре → длительность → клиенты → оплата →
 * «Считать как ПТ» и ступени → дневник. Выбор вида заполняет остальное его заготовкой. Ставки — в «Ставках».
 * Цвета у типа нет: блок красится цветом места, тип выбирает оттенок (шаг 1.13в). У дежурства оттенка нет —
 * в календаре это заливка цвета места.
 *
 * @param others другие действующие типы: название не повторяется, о том же оттенке — подсказка.
 * @param places действующие места по порядку тренера — образцы блока в каждом из них.
 * @param archived `null` у нового типа; у сохранённого — в архиве ли он (кнопка «Убрать в архив» / «Вернуть»).
 */
@Composable
fun TypeEditor(
    title: String,
    initial: TypeDraft,
    others: List<TypeDraft>,
    places: List<PlaceDraft>,
    onSave: (TypeDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean? = null,
    onArchive: ((Boolean) -> Unit)? = null,
) {
    var draft by rememberSaveable(stateSaver = TypeDraftSaver) { mutableStateOf(initial) }
    // «Своя» длительность и «Не больше…» клиентов — текстом, пока тренер его набирает.
    var customDuration by rememberSaveable {
        mutableStateOf(if (initial.durationMin in ReferenceRules.DURATION_CHIPS) null else initial.durationMin.toString())
    }
    var limit by rememberSaveable { mutableStateOf(initial.maxClients?.toString()) }
    var kindChanged by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val problems = ReferenceRules.typeProblems(draft, others)
    val shown = if (showErrors) problems else emptyMap()

    Column(
        modifier
            .fillMaxSize()
            // Клавиатура поднимает «Сохранить»: от неё вычтена системная панель, она уже в отступах оболочки.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars)),
    ) {
        TopBar(title, onBack = onClose, close = true)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = Dimens.space4)
                .padding(bottom = Dimens.space6),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                Field(
                    label = "Название",
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    error = shown[DraftField.NAME],
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    tag = TYPE_NAME_TAG,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = "Коротко",
                    value = draft.shortName,
                    onValueChange = { draft = draft.copy(shortName = it.take(ReferenceRules.TYPE_SHORT_MAX + 2)) },
                    error = shown[DraftField.SHORT_NAME],
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    tag = TYPE_SHORT_TAG,
                    modifier = Modifier.width(112.dp),
                )
            }

            SectionLabel("Как работает")
            ListCard {
                TrainingKind.entries.forEachIndexed { index, kind ->
                    if (index > 0) RowDivider(start = 0.dp)
                    KindRow(kind, selected = draft.kind == kind) {
                        if (draft.kind != kind) {
                            val preset = draft.withKind(kind)
                            draft = preset
                            customDuration = null
                            limit = preset.maxClients?.toString()
                            kindChanged = true
                        }
                    }
                }
            }
            if (kindChanged) {
                Spacer(Modifier.height(Dimens.space3))
                Note(TypeFormat.presetNote(draft.kind))
            }

            if (TypePresets.hasClients(draft.kind)) {
                val first = PlaceColor.of(places.firstOrNull()?.color)
                SectionLabel("Оттенок")
                ShadePicker(selected = draft.shade, place = first, onSelect = { draft = draft.copy(shade = it) })
                val twins = others.filter { it.kind != TrainingKind.DUTY && it.shade == draft.shade }.map { it.name }
                TypeFormat.sameShade(twins)?.let { hint ->
                    Text(
                        text = hint,
                        modifier = Modifier.padding(top = Dimens.space2),
                        style = MaterialTheme.typography.bodySmall,
                        color = PersonalkaTheme.colors.textMuted,
                    )
                }

                SectionLabel("Так будет в календаре")
                BlockPreview(draft, places)
            }

            SectionLabel("Длительность, мин")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                ReferenceRules.DURATION_CHIPS.forEach { minutes ->
                    ChoiceChip(
                        text = minutes.toString(),
                        selected = customDuration == null && draft.durationMin == minutes,
                        onClick = {
                            customDuration = null
                            draft = draft.copy(durationMin = minutes)
                        },
                    )
                }
                ChoiceChip(
                    text = if (customDuration == null && draft.durationMin !in ReferenceRules.DURATION_CHIPS) {
                        draft.durationMin.toString()
                    } else {
                        "Своя"
                    },
                    selected = customDuration != null || draft.durationMin !in ReferenceRules.DURATION_CHIPS,
                    onClick = { customDuration = customDuration ?: draft.durationMin.toString() },
                )
            }
            val durationText = customDuration
            if (durationText != null) {
                Field(
                    label = "Своя длительность, мин",
                    value = durationText,
                    onValueChange = { text ->
                        val digits = text.filter { it.isDigit() }.take(4)
                        customDuration = digits
                        draft = draft.copy(durationMin = digits.toIntOrNull() ?: 0)
                    },
                    error = shown[DraftField.DURATION],
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    tag = TYPE_DURATION_TAG,
                    modifier = Modifier.padding(top = Dimens.space2).widthIn(max = 240.dp),
                )
            }

            if (TypePresets.clientsEditable(draft.kind)) {
                SectionLabel("Клиентов на тренировке")
                val limitText = limit
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    ChoiceChip("Сколько угодно", selected = limitText == null, onClick = {
                        limit = null
                        draft = draft.copy(maxClients = null)
                    })
                    ChoiceChip("Не больше…", selected = limitText != null, onClick = {
                        if (limit == null) limit = draft.maxClients?.toString() ?: ""
                    })
                }
                if (limitText != null) {
                    Field(
                        label = "Не больше, клиентов",
                        value = limitText,
                        onValueChange = { text ->
                            val digits = text.filter { it.isDigit() }.take(3)
                            limit = digits
                            draft = draft.copy(maxClients = digits.toIntOrNull() ?: 0)
                        },
                        error = shown[DraftField.MAX_CLIENTS],
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        tag = TYPE_LIMIT_TAG,
                        modifier = Modifier.padding(top = Dimens.space2).widthIn(max = 240.dp),
                    )
                }
            }

            SectionLabel("Оплата")
            Segmented(
                options = PayUnit.entries,
                selected = draft.payUnit,
                label = TypeFormat::payUnit,
                onSelect = { draft = draft.copy(payUnit = it) },
            )

            if (TypePresets.hasClients(draft.kind)) {
                Spacer(Modifier.height(Dimens.space3))
                SwitchRow(
                    title = "Считать как ПТ",
                    subtitle = "Идёт в счётчик ПТ и в ступень месяца",
                    checked = draft.countsTowardPt,
                    onToggle = { draft = draft.copy(countsTowardPt = it) },
                )
                SwitchRow(
                    title = "Ставка зависит от ступени",
                    subtitle = "Своя ставка при 1–100, 101–150 и 151+ ПТ",
                    checked = draft.tierDependent,
                    onToggle = { draft = draft.copy(tierDependent = it) },
                )

                SectionLabel("Дневник")
                CheckRow("План", checked = draft.diaryPlan, onToggle = { draft = draft.copy(diaryPlan = it) })
                CheckRow("Факт", checked = draft.diaryFact, onToggle = { draft = draft.copy(diaryFact = it) })
                CheckRow(
                    title = "Поведение",
                    subtitle = if (draft.kind == TrainingKind.GROUP) "У групп обычно не ведут" else null,
                    checked = draft.diaryBehavior,
                    onToggle = { draft = draft.copy(diaryBehavior = it) },
                )
            }

            Spacer(Modifier.height(Dimens.space4))
            Note("Ставку задайте в «Ставках». Пока её нет, тренировки этого типа будут в «Нужно сделать».")

            if (archived != null && onArchive != null) {
                Spacer(Modifier.height(Dimens.space3))
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
            onClick = {
                if (problems.isEmpty()) onSave(draft) else showErrors = true
            },
        )
    }
}

@Composable
private fun KindRow(kind: TrainingKind, selected: Boolean, onSelect: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceAlt else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(kindTag(kind))
            .heightIn(min = 56.dp)
            .padding(horizontal = Dimens.space3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline),
        )
        Spacer(Modifier.width(Dimens.space3))
        Column {
            Text(TypeFormat.kind(kind), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(TypeFormat.kindHint(kind), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

/**
 * Образцы блока в календаре: тип в каждом месте — цвет места его оттенком, «19:00 · Аква · ББ» (CalendarBlock).
 * Мест больше трёх — видны первые три; мест нет — сланцевый образец без места.
 */
@Composable
private fun BlockPreview(draft: TypeDraft, places: List<PlaceDraft>) {
    val name = draft.name.trim().ifEmpty { "Новый тип" }
    val shown = places.take(MAX_SAMPLES).ifEmpty { listOf(null) }
    SampleCard {
        shown.forEach { place ->
            val line = listOfNotNull("19:00", draft.shortName.trim().ifEmpty { null }, place?.shortName).joinToString(" · ")
            BlockSample(PlaceColor.of(place?.color), draft.shade, name, line, Modifier.fillMaxWidth())
        }
    }
}

private const val MAX_SAMPLES = 3

/** Тип в состоянии экрана — одной строкой: форма переживает пересоздание окна и выгрузку из памяти. */
internal val TypeDraftSaver: Saver<TypeDraft, String> = Saver(save = { encodeType(it) }, restore = { decodeType(it) })

private const val FIELD = "\u001F"

internal fun encodeType(t: TypeDraft): String = listOf(
    t.name, t.shortName, t.kind.name, t.shade, t.durationMin, t.maxClients ?: "", t.payUnit.name,
    t.countsTowardPt, t.tierDependent, t.diaryPlan, t.diaryFact, t.diaryBehavior,
).joinToString(FIELD)

internal fun decodeType(s: String): TypeDraft {
    val p = s.split(FIELD)
    return TypeDraft(
        name = p[0],
        shortName = p[1],
        kind = TrainingKind.valueOf(p[2]),
        shade = p[3].toIntOrNull() ?: ReferenceRules.SHADE_LIGHT,
        durationMin = p[4].toInt(),
        maxClients = p[5].toIntOrNull(),
        payUnit = PayUnit.valueOf(p[6]),
        countsTowardPt = p[7].toBoolean(),
        tierDependent = p[8].toBoolean(),
        diaryPlan = p[9].toBoolean(),
        diaryFact = p[10].toBoolean(),
        diaryBehavior = p[11].toBoolean(),
    )
}
