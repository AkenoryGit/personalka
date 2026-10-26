package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SalaryMonthData
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.money.DutyCheck
import com.akenory.personalka.domain.money.DutyCheckState
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.CardHeader
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

internal const val RECONCILE_TITLE_TAG = "reconcile-title"
internal const val RECONCILE_PREV_TAG = "reconcile-prev"
internal const val RECONCILE_NEXT_TAG = "reconcile-next"
internal const val RECONCILE_SALARY_TAG = "reconcile-salary"
internal const val RECONCILE_CLOSE_TAG = "reconcile-close"
internal const val RECONCILE_CLOSED_TAG = "reconcile-closed"
internal const val RECONCILE_DUTIES_TAG = "reconcile-duties"
internal const val RECONCILE_DUTY_TOTAL_TAG = "reconcile-duty-total"
internal const val RECONCILE_DUTY_CHECK_TAG = "reconcile-duty-check"
internal const val RECONCILE_DUTY_CHECKED_TAG = "reconcile-duty-checked"
internal const val RECONCILE_DUTY_SHARE_TAG = "reconcile-duty-share"

/**
 * «Сверки» (ТЗ 5.14, шаг 3.9а) — пункт «Ещё»: месяц со стрелками и сверки этого месяца по типам. «Зарплата за месяц» —
 * «Месяц сверен» (перенесён из «Зарплаты»), «Дежурства» — список с часами, «Поделиться» и «Дежурства сверены».
 * Отчёт 1С появится здесь же на шаге 4.2. Месяц и прокрутка переживают сложение Fold: объявлены над разметкой.
 * [shareText] — системное «Поделиться»; нет его — текст копируется.
 */
@Composable
internal fun ReconcileScreen(
    settings: SettingsRepository,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
    shareText: ((String) -> Unit)? = null,
) {
    var monthText by rememberSaveable { mutableStateOf(today.yearMonth.toString()) }
    val month = YearMonth.parse(monthText)
    val scroll = rememberScrollState()
    val repo = settings.months
    val data by remember(repo, month, today) { repo.month(month, today) }.collectAsState(initial = null)
    val clipboard = LocalClipboardManager.current
    val colors = PersonalkaTheme.colors

    Column(Modifier.fillMaxSize()) {
        TopBar(ReconcileFormat.TITLE, onBack = onBack)
        MonthRow(
            month,
            onPrev = { monthText = month.firstDay.minus(1, DateTimeUnit.MONTH).yearMonth.toString() },
            onNext = { monthText = month.firstDay.plus(1, DateTimeUnit.MONTH).yearMonth.toString() },
        )
        val loaded = data?.takeIf { it.view.month == month }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.bg)
                .verticalScroll(scroll)
                .padding(Dimens.space4)
                .padding(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            if (loaded == null) return@Column
            if (!loaded.view.canClose && !loaded.duties.visible) {
                Muted(ReconcileFormat.future(month))
                return@Column
            }
            if (loaded.view.canClose) {
                SalaryCheck(loaded) { closed ->
                    scope.launch {
                        repo.setClosed(month, closed)
                        messages.show(if (closed) SalaryFormat.closedMessage(month) else SalaryFormat.REOPENED)
                    }
                }
            }
            if (loaded.duties.visible) {
                DutyCard(
                    loaded,
                    onCheck = { minutes ->
                        scope.launch {
                            repo.setDutiesChecked(month, minutes)
                            messages.show(if (minutes != null) ReconcileFormat.checkedMessage(month) else ReconcileFormat.DUTIES_UNCHECKED)
                        }
                    },
                    onShare = {
                        val text = ReconcileFormat.shareText(month, loaded.duties) { loaded.places[it]?.shortName }
                        if (shareText != null) {
                            shareText(text)
                        } else {
                            clipboard.setText(AnnotatedString(text))
                            messages.show(ReconcileFormat.COPIED)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthRow(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.space4, end = Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            SalaryFormat.month(month),
            modifier = Modifier.weight(1f).semantics { heading() }.testTag(RECONCILE_TITLE_TAG),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        IconButton(onClick = onPrev, modifier = Modifier.testTag(RECONCILE_PREV_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = "Прошлый месяц", tint = colors.text, modifier = Modifier.rotate(180f))
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(RECONCILE_NEXT_TAG)) {
            Icon(PIcons.ChevronRight, contentDescription = "Следующий месяц", tint = colors.text)
        }
    }
}

/** «Зарплата за месяц»: заработано для сравнения с расчётом клуба и «Месяц сверен» / «Сверен» + «Снять сверку» (5.12, 4.1). */
@Composable
private fun SalaryCheck(data: SalaryMonthData, onClosed: (Boolean) -> Unit) {
    val colors = PersonalkaTheme.colors
    val view = data.view
    SalaryCard(Modifier.testTag(RECONCILE_SALARY_TAG)) {
        CardHeader(ReconcileFormat.SALARY_TITLE)
        Text(ReconcileFormat.earned(view.pay.earned), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        if (view.closed) {
            Row(Modifier.testTag(RECONCILE_CLOSED_TAG), verticalAlignment = Alignment.CenterVertically) {
                Icon(PIcons.CheckCircle, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
                Text(SalaryFormat.MONTH_CLOSED, Modifier.padding(start = Dimens.space2), style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
            Muted(SalaryFormat.closedNote(view.month))
            TextButton(onClick = { onClosed(false) }, modifier = Modifier.heightIn(min = Dimens.button).testTag(RECONCILE_CLOSE_TAG)) {
                Text(SalaryFormat.REOPEN_MONTH, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        } else {
            Muted(ReconcileFormat.SALARY_NOTE)
            OutlineButton(SalaryFormat.CLOSE_MONTH, { onClosed(true) }, Modifier.fillMaxWidth().testTag(RECONCILE_CLOSE_TAG), icon = PIcons.CheckCircle)
            Muted(SalaryFormat.closeNote(view.month))
        }
    }
}

/**
 * «Дежурства»: строки месяца (у непроведённых — «по расписанию»), итог проведённых с деньгами, «Поделиться» и отметка
 * «Дежурства сверены» с часами, на которых сверили. Кнопок в карточке две — бюджет 10.7.
 */
@Composable
private fun DutyCard(data: SalaryMonthData, onCheck: (Int?) -> Unit, onShare: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val check: DutyCheck = data.duties
    SalaryCard(Modifier.testTag(RECONCILE_DUTIES_TAG)) {
        CardHeader(ReconcileFormat.DUTY_TITLE)
        check.rows.forEach { row ->
            MoneyRow(
                title = ReconcileFormat.dutyTitle(row, data.places[row.locationId]?.shortName),
                amount = SalaryFormat.hours(row.durationMin),
                note = listOfNotNull(row.coverFor?.let { ReconcileFormat.coverFor(it) }, ReconcileFormat.DUTY_PLANNED.takeIf { !row.done })
                    .joinToString(" · ").ifEmpty { null },
            )
        }
        Text(
            ReconcileFormat.doneTotal(check),
            modifier = Modifier.testTag(RECONCILE_DUTY_TOTAL_TAG),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        if (check.plannedMinutes > 0) Muted(ReconcileFormat.plannedTotal(check.plannedMinutes))
        if (check.doneMinutes > 0) {
            TextButton(onClick = onShare, modifier = Modifier.heightIn(min = Dimens.button).testTag(RECONCILE_DUTY_SHARE_TAG)) {
                Icon(PIcons.Share, contentDescription = null, tint = colors.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Dimens.space2))
                Text(ReconcileFormat.SHARE, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
        if (!check.canCheck || (check.doneMinutes == 0 && check.checkedMinutes == null)) return@SalaryCard
        when (check.state) {
            DutyCheckState.CHECKED -> {
                Row(Modifier.testTag(RECONCILE_DUTY_CHECKED_TAG), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PIcons.CheckCircle, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
                    Text(ReconcileFormat.DUTIES_CHECKED, Modifier.padding(start = Dimens.space2), style = MaterialTheme.typography.titleMedium, color = colors.text)
                }
                TextButton(onClick = { onCheck(null) }, modifier = Modifier.heightIn(min = Dimens.button).testTag(RECONCILE_DUTY_CHECK_TAG)) {
                    Text(ReconcileFormat.UNCHECK, style = MaterialTheme.typography.labelLarge, color = colors.text)
                }
            }
            DutyCheckState.CHANGED, DutyCheckState.NONE -> {
                check.checkedMinutes?.let { Muted(ReconcileFormat.changed(it, check.doneMinutes)) }
                OutlineButton(
                    ReconcileFormat.CHECK_DUTIES,
                    { onCheck(check.doneMinutes) },
                    Modifier.fillMaxWidth().testTag(RECONCILE_DUTY_CHECK_TAG),
                    icon = PIcons.CheckCircle,
                )
                Muted(ReconcileFormat.CHECK_NOTE)
            }
        }
    }
}
