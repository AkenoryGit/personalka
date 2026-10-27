package com.akenory.personalka.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// Окна формы тренировки (5.5) и переноса в календаре (5.3): вопрос для серии, запрет, выбор даты и времени.

const val PICKER_TAG = "picker"

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Вопрос «Только эту / Эту и следующие» (4.4, макет «Изменение тренировки серии»): серия и что меняется,
 * главная кнопка — «Только эту — вт 20.10», вторая — «Эту и следующие», третья — вернуться без изменений.
 *
 * @param back «Вернуться к правке» в форме, «Отмена» при переносе.
 */
@Composable
internal fun ScopePanel(
    title: String,
    question: String,
    date: LocalDate,
    back: String,
    onOnlyThis: () -> Unit,
    onFollowing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    Panel(title = title, onDismiss = onDismiss) {
        Text(question, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text)
        Column(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            PrimaryButton(TrainingFormat.onlyThis(date), onOnlyThis, Modifier.fillMaxWidth())
            OutlineButton(TrainingFormat.FOLLOWING, onFollowing, Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                Text(back, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
    }
}

/**
 * Окно, когда тренировку ставят под запрет (4.5): «Детский бассейн 17:00–18:00: групповая тренировка» — строка
 * на каждый запрет, у серии — «9 из 17 тренировок, первая вт 20.10»; «Поставить» и «Отмена». «Отмена» ничего
 * не записывает.
 */
@Composable
internal fun ConflictPanel(conflicts: List<String>, onDismiss: () -> Unit, onPlace: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(title = ZoneFormat.CONFLICT_QUESTION, onDismiss = onDismiss) {
        conflicts.forEach { Text(text = it, style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text) }
        PanelActions(primary = ZoneFormat.CONFLICT_PLACE, onPrimary = onPlace, secondary = ZoneFormat.CONFLICT_CANCEL, onSecondary = onDismiss)
    }
}

/**
 * Выбор даты — календарь Material по центру экрана. [onClear] — третья кнопка «Бессрочно» у конца повтора;
 * [clearText] — её слово («Сразу» у «Напомнить с»).
 * Как и [Panel], это часть экрана: при сложении Fold окно остаётся на месте.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerPanel(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
    clearText: String = BESSROCHNO,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDays() * DAY_MS)
    PickerCard(title = "Дата", onDismiss = onDismiss) {
        DatePicker(state = state, title = null, headline = null, showModeToggle = false)
        PickerButtons(
            onDismiss = onDismiss,
            onDone = { state.selectedDateMillis?.let { onPick(LocalDate.fromEpochDays(it / DAY_MS)) } ?: onDismiss() },
            onClear = onClear,
            clearText = clearText,
        )
    }
}

/** Выбор времени — циферблат Material, 24 часа; [title] — «Начало» и «Конец» у дежурства и запрета. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerPanel(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit, title: String = "Время") {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    PickerCard(title = title, onDismiss = onDismiss) {
        TimePicker(state = state, modifier = Modifier.padding(top = Dimens.space4))
        PickerButtons(onDismiss = onDismiss, onDone = { onPick(LocalTime(state.hour, state.minute)) }, onClear = null)
    }
}

/** Карточка выбора: уже, чем [Panel], по краям — 16 dp, чтобы календарь в 360 dp поместился на сложенном Fold. */
@Composable
private fun PickerCard(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = PersonalkaTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(Dimens.space4)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(colors.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = title }
                .testTag(PICKER_TAG)
                .padding(vertical = Dimens.space2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun PickerButtons(onDismiss: () -> Unit, onDone: () -> Unit, onClear: (() -> Unit)?, clearText: String = BESSROCHNO) {
    val colors = PersonalkaTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.space3), horizontalArrangement = Arrangement.End) {
        if (onClear != null) {
            TextButton(onClick = onClear) { Text(clearText, style = MaterialTheme.typography.labelLarge, color = colors.text) }
            Box(Modifier.weight(1f))
        }
        TextButton(onClick = onDismiss) { Text(ZoneFormat.CONFLICT_CANCEL, style = MaterialTheme.typography.labelLarge, color = colors.text) }
        TextButton(onClick = onDone) { Text(DONE, style = MaterialTheme.typography.labelLarge, color = colors.text) }
    }
}

internal const val DONE = "Готово"
internal const val BESSROCHNO = "Бессрочно"
