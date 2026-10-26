package com.akenory.personalka.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.akenory.personalka.data.results.ResultDraft
import com.akenory.personalka.data.results.ResultRepository
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.ResultTime
import com.akenory.personalka.domain.result.SwimEvent
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val RESULT_TIME_TAG = "result-time"
const val RESULT_DATE_TAG = "result-date"
const val RESULT_RECORD_TAG = "result-record"
const val RESULT_DELETE_TAG = "result-delete"
const val RESULT_DELETE_CONFIRM_TAG = "result-delete-confirm"
fun resultWhoTag(clientId: String) = "result-who-$clientId"

/** Кому вносится результат: id и «Орлова Ксения». */
data class ResultWho(val id: String, val name: String)

/**
 * Форма результата (ТЗ 5.11) — окно поверх карточки клиента или тренировки: дата, стиль и дистанция чипами,
 * бассейн 25 / 50, время цифрами подряд («5216» → 52,16), тренировка или соревнования с названием старта.
 * Новая запись — на дистанции последнего результата клиента. Быстрее прежнего лучшего — сразу под временем
 * «Личный рекорд, − 7,00», и то же в сообщении после «Сохранить».
 *
 * @param who кому: в карточке клиента — он один, в карточке группы или сплита — все, кто не отменён; выбор чипами.
 * @param date день по умолчанию: сегодня или день тренировки.
 * @param editing изменяемый результат — тогда есть «Удалить»: окно «Удалить результат?», после — «Отменить» в сообщении.
 * @param onSaved после «Сохранить» — карточка клиента переходит на «Результаты».
 */
@Composable
fun ResultFormPanel(
    who: List<ResultWho>,
    date: LocalDate,
    editing: ResultItem?,
    results: ResultRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onSaved: () -> Unit = {},
) {
    var clientId by rememberSaveable { mutableStateOf(who.singleOrNull()?.id) }
    var day by rememberSaveable { mutableStateOf((editing?.date ?: date).toString()) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    // Пока тренер не выбрал сам — дистанция последнего результата клиента (null — «по умолчанию»).
    var styleName by rememberSaveable { mutableStateOf(editing?.event?.style?.name) }
    var distance by rememberSaveable { mutableStateOf(editing?.event?.distanceM) }
    var pool by rememberSaveable { mutableStateOf(editing?.event?.poolM) }
    var digits by rememberSaveable { mutableStateOf(editing?.timeCs?.let { ResultTime.digits(ResultTime.format(it)) } ?: "") }
    var kind by rememberSaveable { mutableStateOf(editing?.kind ?: ResultKind.TRAINING) }
    var eventName by rememberSaveable { mutableStateOf(editing?.eventName ?: "") }

    val chosen = clientId
    val previous by remember(results, chosen) { chosen?.let(results::results) ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val fallback = ResultRules.defaultEvent(previous)
    val style = styleName?.let(SwimStyle::valueOf) ?: fallback.style
    val event = SwimEvent(style, ResultRules.fitDistance(style, distance ?: fallback.distanceM), pool ?: fallback.poolM)
    val timeCs = ResultTime.parse(digits)
    val gain = timeCs?.let { ResultRules.recordGain(previous, event, it, exceptId = editing?.id) }
    val picked = LocalDate.parse(day)

    Box(Modifier.fillMaxSize()) {
        Panel(if (editing == null) ResultFormat.NEW_TITLE else ResultFormat.EDIT_TITLE, onDismiss = onClose) {
            if (who.size > 1) {
                SectionLabel(ResultFormat.WHO)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    who.forEach { person ->
                        ChoiceChip(
                            person.name,
                            person.id == clientId,
                            onClick = { clientId = person.id },
                            modifier = Modifier.testTag(resultWhoTag(person.id)),
                        )
                    }
                }
            }
            SectionLabel(ResultFormat.DATE)
            PickBox(
                text = TrainingFormat.date(picked),
                icon = PIcons.CalendarMonth,
                onClick = { pickingDay = true },
                modifier = Modifier.fillMaxWidth().testTag(RESULT_DATE_TAG),
            )
            SectionLabel(ResultFormat.STYLE)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                SwimStyle.entries.forEach { option ->
                    ChoiceChip(ResultFormat.styleChip(option), option == style, onClick = { styleName = option.name })
                }
            }
            SectionLabel(ResultFormat.DISTANCE)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                ResultRules.distances(style).forEach { meters ->
                    ChoiceChip(ResultFormat.meters(meters), meters == event.distanceM, onClick = { distance = meters })
                }
            }
            SectionLabel(ResultFormat.POOL)
            Segmented(ResultRules.POOLS, event.poolM, { ResultFormat.pool(it) }, { pool = it })
            Field(
                label = ResultFormat.TIME,
                value = digits,
                onValueChange = { digits = ResultTime.digits(it) },
                modifier = Modifier.fillMaxWidth(),
                error = ResultFormat.timeProblem(digits),
                hint = if (gain == null) ResultFormat.TIME_HINT else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                tag = RESULT_TIME_TAG,
                visualTransformation = TimeTransformation,
            )
            if (gain != null) {
                Text(
                    ResultFormat.record(gain),
                    modifier = Modifier.testTag(RESULT_RECORD_TAG),
                    style = MaterialTheme.typography.labelLarge,
                    color = PersonalkaTheme.colors.text,
                )
            }
            SectionLabel(ResultFormat.WHERE)
            Segmented(
                ResultKind.entries,
                kind,
                { if (it == ResultKind.TRAINING) ResultFormat.TRAINING else ResultFormat.COMPETITION },
                { kind = it },
            )
            if (kind == ResultKind.COMPETITION) {
                Field(ResultFormat.EVENT_NAME, eventName, { eventName = it }, placeholder = ResultFormat.OPTIONAL)
            }
            PanelActions(
                primary = ResultFormat.SAVE,
                onPrimary = {
                    val id = chosen ?: return@PanelActions
                    val cs = timeCs ?: return@PanelActions
                    onSaved()
                    onClose()
                    scope.launch {
                        val saved = results.save(ResultDraft(id, picked, event, cs, kind, eventName), editing?.id)
                        messages.showUndo(saved.message) { scope.launch { results.undo(saved.undo) } }
                    }
                },
                secondary = ResultFormat.CANCEL,
                onSecondary = onClose,
                primaryEnabled = chosen != null && timeCs != null,
            )
            if (editing != null) {
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(RESULT_DELETE_TAG),
                ) {
                    Text(ResultFormat.DELETE, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
                }
            }
        }
        if (pickingDay) {
            DatePickerPanel(
                initial = picked,
                onPick = {
                    pickingDay = false
                    day = it.toString()
                },
                onDismiss = { pickingDay = false },
            )
        }
        if (confirmingDelete && editing != null) {
            Panel(ResultFormat.DELETE_TITLE, onDismiss = { confirmingDelete = false }) {
                Text(ResultFormat.deleteLine(editing), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
                Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
                    TextButton(
                        onClick = {
                            confirmingDelete = false
                            onClose()
                            scope.launch {
                                val undo = results.delete(editing.id) ?: return@launch
                                messages.showUndo(ResultFormat.DELETED) { scope.launch { results.undo(undo) } }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(RESULT_DELETE_CONFIRM_TAG),
                    ) {
                        Text(ResultFormat.DELETE, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
                    }
                    TextButton(onClick = { confirmingDelete = false }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                        Text(ResultFormat.CANCEL, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
                    }
                }
            }
        }
    }
}

/** В поле хранятся только цифры, видно время: «10317» → «1:03,17»; курсор встаёт между теми же цифрами. */
private object TimeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown = ResultTime.typed(digits)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset == 0) return 0
                var seen = 0
                shown.forEachIndexed { i, c ->
                    if (c.isDigit()) seen++
                    if (seen == offset) return i + 1
                }
                return shown.length
            }

            override fun transformedToOriginal(offset: Int): Int = shown.take(offset).count { it.isDigit() }
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}
