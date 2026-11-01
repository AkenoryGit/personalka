package com.akenory.personalka.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.training.PeriodPreview
import com.akenory.personalka.data.training.TrainingCardRepository
import com.akenory.personalka.domain.format.PeriodCancelFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.training.CancelReasons
import com.akenory.personalka.domain.training.PeriodCancelRules
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val PERIOD_PANEL_TAG = "period-panel"
const val PERIOD_FROM_TAG = "period-from"
const val PERIOD_TO_TAG = "period-to"
const val PERIOD_SUMMARY_TAG = "period-summary"

/**
 * «Не будет на период» (4.2а, шаг 5.3у): с какого по какое число, одна причина на все, итог «Отменится 6 тренировок ·
 * вт 03.11 – чт 26.11» и кнопка «Отменить 6 тренировок». Открывается из меню «…» карточки клиента и ссылкой из
 * панели отмены тренировки. После записи — сообщение с «Отменить».
 */
@Composable
fun PeriodCancelPanel(
    clientId: String,
    name: String,
    from: LocalDate,
    today: LocalDate,
    cards: TrainingCardRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    var fromText by rememberSaveable(clientId) { mutableStateOf(maxOf(from, today).toString()) }
    var toText by rememberSaveable(clientId) { mutableStateOf(PeriodCancelRules.defaultTo(maxOf(from, today)).toString()) }
    var reason by rememberSaveable(clientId) { mutableStateOf<String?>(CancelReasons.TRIP) }
    var ownReason by rememberSaveable(clientId) { mutableStateOf("") }
    // Какой день выбирается: «from», «to» или ничего.
    var picking by rememberSaveable(clientId) { mutableStateOf<String?>(null) }
    val start = LocalDate.parse(fromText)
    val end = LocalDate.parse(toText)
    val problem = PeriodCancelRules.problem(start, end, today)
    val preview by produceState<PeriodPreview?>(null, clientId, start, end, problem) {
        value = null
        value = if (problem != null) PeriodPreview.EMPTY else cards.periodPreview(clientId, start, end)
    }
    val reasonText = if (reason == null) ownReason.trim().takeIf { it.isNotEmpty() } else reason

    Panel(PeriodCancelFormat.TITLE, onDismiss = onClose, modifier = Modifier.testTag(PERIOD_PANEL_TAG)) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = colors.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            Column(Modifier.weight(1f)) {
                SectionLabel(PeriodCancelFormat.FROM)
                PickBox(TrainingFormat.date(start), PIcons.CalendarMonth, { picking = "from" }, Modifier.fillMaxWidth().testTag(PERIOD_FROM_TAG))
            }
            Column(Modifier.weight(1f)) {
                SectionLabel(PeriodCancelFormat.TO)
                PickBox(
                    TrainingFormat.date(end),
                    PIcons.CalendarMonth,
                    { picking = "to" },
                    Modifier.fillMaxWidth().testTag(PERIOD_TO_TAG),
                    error = problem?.let(PeriodCancelFormat::problem),
                )
            }
        }
        problem?.let { ErrorText(PeriodCancelFormat.problem(it)) }

        SectionLabel(TrainingCardFormat.REASON)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            CancelReasons.DEFAULT_CHIPS.forEach { chip ->
                ChoiceChip(chip, selected = reason == chip, onClick = { reason = chip })
            }
            ChoiceChip(TrainingCardFormat.OWN_REASON, selected = reason == null, onClick = { reason = null }, icon = PIcons.Edit)
        }
        if (reason == null) {
            Field(TrainingCardFormat.OWN_REASON, ownReason, { ownReason = it }, placeholder = TrainingCardFormat.REASON_HINT)
        }

        val shown = preview
        if (problem == null && shown != null) {
            val first = shown.first
            val last = shown.last
            val text = if (shown.count == 0 || first == null || last == null) {
                PeriodCancelFormat.NONE
            } else {
                PeriodCancelFormat.summary(shown.count, first, last)
            }
            Text(text, Modifier.testTag(PERIOD_SUMMARY_TAG), style = MaterialTheme.typography.labelLarge.tabular(), color = colors.text)
            if (shown.charged > 0) {
                Text(PeriodCancelFormat.charged(shown.charged), style = MaterialTheme.typography.bodySmall, color = colors.charged)
            }
        }
        Note(PeriodCancelFormat.NOTE)
        val count = shown?.count ?: 0
        PanelActions(
            primary = if (count > 0) PeriodCancelFormat.button(count) else TrainingCardFormat.CANCEL_TRAINING,
            onPrimary = {
                onClose()
                scope.launch {
                    val result = cards.cancelPeriod(clientId, start, end, reasonText) ?: return@launch
                    messages.showUndo(result.message) { scope.launch { cards.undo(result.undo) } }
                }
            },
            secondary = "Отмена",
            onSecondary = onClose,
            primaryEnabled = problem == null && count > 0,
        )
    }

    when (picking) {
        "from" -> DatePickerPanel(
            initial = start,
            onPick = {
                picking = null
                fromText = it.toString()
                // «По» не остаётся раньше «С»: сдвигается на ту же длину периода.
                if (LocalDate.parse(toText) < it) toText = PeriodCancelRules.defaultTo(it).toString()
            },
            onDismiss = { picking = null },
        )
        "to" -> DatePickerPanel(
            initial = end,
            onPick = {
                picking = null
                toText = it.toString()
            },
            onDismiss = { picking = null },
        )
    }
}
