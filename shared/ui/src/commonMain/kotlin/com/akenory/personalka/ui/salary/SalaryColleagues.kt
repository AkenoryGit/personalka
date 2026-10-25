package com.akenory.personalka.ui.salary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.SalaryMonthData
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.format.SettlementFormat
import com.akenory.personalka.domain.money.ColleagueSettlement
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.domain.money.SettlementCase
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

internal fun colleagueTag(id: String) = "salary-colleague-$id"
internal fun settledTag(id: String) = "salary-settled-$id"
internal fun shareTag(id: String) = "salary-share-$id"
internal fun caseTag(key: String) = "salary-case-$key"
internal const val COLLEAGUES_EMPTY_TAG = "salary-colleagues-empty"

/**
 * «Коллеги» (ТЗ 5.12, 4.8, макет PayColleagues): карточка на каждого коллегу — случаи групп с суммами, нажатие
 * раскрывает тренировки; итог «Лена отдаёт вам …», итог по расписанию, «Рассчитались» и «Поделиться». Раскрытые
 * случаи ([expanded]) хранит экран — они переживают сложение Fold.
 */
@Composable
internal fun ColleaguesTab(
    data: SalaryMonthData,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onSettle: (ColleagueSettlement, Boolean) -> Unit,
    onShare: (ColleagueSettlement) -> Unit,
) {
    val rows = data.view.colleagues.sortedBy { data.colleagues[it.colleagueId]?.lowercase() ?: "" }
    if (rows.isEmpty()) {
        SalaryCard(Modifier.testTag(COLLEAGUES_EMPTY_TAG)) { Muted(SettlementFormat.EMPTY) }
        return
    }
    rows.forEach { row -> ColleagueCard(data, row, expanded, onExpand, onSettle, onShare) }
}

@Composable
private fun ColleagueCard(
    data: SalaryMonthData,
    row: ColleagueSettlement,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onSettle: (ColleagueSettlement, Boolean) -> Unit,
    onShare: (ColleagueSettlement) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val view = data.view
    val name = data.colleagues[row.colleagueId] ?: SalaryFormat.COLLEAGUE
    SalaryCard(Modifier.testTag(colleagueTag(row.colleagueId))) {
        Column {
            Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = colors.text)
            SettlementFormat.scheme(data.colleagueSchemes[row.colleagueId], view.scheme?.kind)?.let { Muted(it) }
        }
        Column(Modifier.padding(top = Dimens.space2)) {
            Muted(SettlementFormat.totalTitle(view.month))
            Text(SettlementFormat.total(name, row.total), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        }
        row.cases.forEach { c ->
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            CaseRow(data, c, name, c.key in expanded) { onExpand(c.key) }
        }
        if (row.cases.isNotEmpty()) {
            HorizontalDivider(thickness = 2.dp, color = colors.text)
            MoneyRow(SalaryFormat.settlement(name, row.total), MoneyFormat.rub(kotlin.math.abs(row.total)), bold = true)
        }
        if (view.period != MonthPeriod.PAST && row.forecast != row.total) Muted(SettlementFormat.forecast(view.month, name, row.forecast))
        SettlementFormat.settledNote(row)?.let { Muted(it) }
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space2), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            if (row.isSettled) {
                OutlineButton(SettlementFormat.UNSETTLE, { onSettle(row, false) }, Modifier.weight(1f).testTag(settledTag(row.colleagueId)))
            } else {
                SecondaryButton(SettlementFormat.SETTLED, { onSettle(row, true) }, Modifier.weight(1f).testTag(settledTag(row.colleagueId)), icon = PIcons.Check)
            }
            OutlineButton(SettlementFormat.SHARE, { onShare(row) }, Modifier.weight(1f).testTag(shareTag(row.colleagueId)), icon = PIcons.Share)
        }
        Muted(SettlementFormat.SHARE_NOTE)
    }
}

/** Случай 4.8: заголовок, группа и сумма с «кто отдаёт»; раскрытый — тренировки и как считается доля. */
@Composable
private fun CaseRow(data: SalaryMonthData, c: SettlementCase, name: String, open: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.touch)
            .padding(vertical = Dimens.space2)
            .testTag(caseTag(c.key)),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = Dimens.space3)) {
            Text(SettlementFormat.caseTitle(c.case), style = MaterialTheme.typography.bodyLarge, color = colors.text, fontWeight = FontWeight.SemiBold)
            Muted(SettlementFormat.caseNote(c, groupName(data, c)))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(MoneyFormat.signedRub(c.amount), style = MaterialTheme.typography.bodyLarge, color = colors.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            Muted(SettlementFormat.direction(c.amount, name))
        }
        Icon(
            if (open) PIcons.ExpandLess else PIcons.ExpandMore,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.padding(start = Dimens.space1).size(20.dp),
        )
    }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = Dimens.space3, bottom = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
        c.trainings.forEach { t ->
            Row(Modifier.fillMaxWidth()) {
                Text(SettlementFormat.training(t), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                Text(MoneyFormat.signedRub(t.amount), style = MaterialTheme.typography.bodyMedium, color = colors.text, textAlign = TextAlign.End)
            }
        }
        SettlementFormat.formula(c)?.let { Muted(it) }
    }
}

/** Название группы случая; группы нет (удалена) — «тип · место». */
internal fun groupName(data: SalaryMonthData, c: SettlementCase): String =
    c.groupId?.let { data.groups[it] } ?: placeName(data, c)

/** «Группа · ББ» — для текста коллеге: название группы может быть любым. */
internal fun placeName(data: SalaryMonthData, c: SettlementCase): String {
    val type = data.types[c.typeId]?.let { it.shortName.ifBlank { it.name } }.orEmpty()
    val place = data.places[c.locationId]?.let { it.shortName.ifBlank { it.name } }.orEmpty()
    return listOf(type, place).filter { it.isNotBlank() }.joinToString(" · ")
}
