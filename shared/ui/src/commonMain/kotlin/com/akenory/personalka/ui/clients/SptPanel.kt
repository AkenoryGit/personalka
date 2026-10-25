package com.akenory.personalka.ui.clients

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SptCaseView
import com.akenory.personalka.domain.format.SptFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.money.SptCase
import com.akenory.personalka.domain.money.SptRules
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.ErrorText
import com.akenory.personalka.ui.training.PickBox
import kotlinx.datetime.LocalDate

const val SPT_BLOCK_TAG = "spt-block"
const val SPT_BUTTON_TAG = "spt-button"
const val SPT_DATE_TAG = "spt-date"
const val SPT_SAVE_TAG = "spt-save"
const val SPT_UNMARK_TAG = "spt-unmark"

/**
 * Блок «Стартовая тренировка» в «Инфо» клиента (5.9, макет ClCardInfo): «пн 12.10 · СПТ · ББ», статус с рамкой и
 * одна кнопка — «Купил блок со скидкой», у закрытой — «Изменить покупку».
 */
@Composable
internal fun SptBlock(view: SptCaseView, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    ListCard(Modifier.testTag(SPT_BLOCK_TAG)) {
        Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            Text(SptFormat.TITLE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Text(
                SptFormat.line(view.case.sptDate, view.typeShort, view.placeShort),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = colors.text,
            )
            Row(
                Modifier
                    .border(1.dp, colors.outline, MaterialTheme.shapes.small)
                    .padding(horizontal = Dimens.space2, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (view.status) {
                    SptCaseStatus.OPEN -> PIcons.Schedule
                    SptCaseStatus.CLOSED -> PIcons.CheckCircle
                    SptCaseStatus.NOT_CLOSED -> PIcons.EventBusy
                }
                Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    SptFormat.status(view.status, view.case.windowUntil, view.case.purchaseDate),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = colors.text,
                )
            }
            SecondaryButton(
                if (view.status == SptCaseStatus.CLOSED) SptFormat.CHANGE else SptFormat.BOUGHT,
                onClick,
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.space1).testTag(SPT_BUTTON_TAG),
            )
        }
    }
}

/**
 * Окно «Купил блок со скидкой» (4.9, п. 3–5): дата покупки от дня СПТ до конца окна, иначе «Сохранить» не нажать и
 * под датой — почему. [title] — заголовок: из карточки ПТ — вопрос 4.9. У закрытой — «Снять отметку».
 *
 * @param initial дата по умолчанию: сегодня в пределах окна или день ПТ.
 * @param bonus доплата за покупку по ставке СПТ; 0 — строки о доплате нет.
 */
@Composable
internal fun SptPurchasePanel(
    case: SptCase,
    name: String,
    initial: LocalDate,
    bonus: Long,
    onSave: (LocalDate) -> Unit,
    onUnmark: (() -> Unit)?,
    onDismiss: () -> Unit,
    title: String = SptFormat.BOUGHT,
) {
    val colors = PersonalkaTheme.colors
    var date by rememberSaveable(case.id) { mutableStateOf(initial.toString()) }
    var picking by rememberSaveable(case.id) { mutableStateOf(false) }
    val day = LocalDate.parse(date)
    val problem = SptRules.purchaseProblem(case, day)?.let { SptFormat.problem(it, case.sptDate, case.windowUntil) }
    Panel(title, onDismiss = onDismiss) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = colors.text)
        Text(SptFormat.window(case.sptDate, case.windowUntil), style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.textMuted)
        Text(SptFormat.PURCHASE_DATE, Modifier.padding(top = Dimens.space2), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        PickBox(
            text = TrainingFormat.date(day),
            icon = PIcons.CalendarMonth,
            onClick = { picking = true },
            modifier = Modifier.fillMaxWidth().testTag(SPT_DATE_TAG),
            error = problem,
        )
        problem?.let { ErrorText(it) }
        if (problem == null && bonus > 0) {
            Text(SptFormat.bonusNote(bonus, day), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
        }
        Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
            PrimaryButton(SptFormat.SAVE, { onSave(day) }, Modifier.fillMaxWidth().testTag(SPT_SAVE_TAG), enabled = problem == null)
            if (onUnmark != null) {
                TextButton(onClick = onUnmark, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(SPT_UNMARK_TAG)) {
                    Text(SptFormat.UNMARK, style = MaterialTheme.typography.labelLarge, color = colors.text)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                Text("Отмена", style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
    }
    if (picking) {
        DatePickerPanel(
            initial = day,
            onPick = {
                picking = false
                date = it.toString()
            },
            onDismiss = { picking = false },
        )
    }
}
