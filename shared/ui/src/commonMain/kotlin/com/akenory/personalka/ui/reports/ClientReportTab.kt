package com.akenory.personalka.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.report.ClientReportView
import com.akenory.personalka.data.report.ReportClient
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.ClientReportFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ReportFormat
import com.akenory.personalka.ui.clients.SearchField
import com.akenory.personalka.ui.salary.CardHeader
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

internal const val REPORTS_CLIENT_SEARCH_TAG = "reports-client-search"
internal const val REPORTS_CLIENT_CHANGE_TAG = "reports-client-change"
internal const val REPORTS_CLIENT_NAME_TAG = "reports-client-name"
internal const val REPORTS_CLIENT_TOTAL_TAG = "reports-client-total"
internal fun reportTabTag(tab: ReportTab) = "reports-tab-${tab.name}"
internal fun reportPickTag(id: String) = "reports-pick-$id"
internal fun reportClientRowTag(text: String) = "reports-client-row-$text"
internal fun reportVisitTag(id: String) = "reports-visit-$id"

/** Вкладки «Отчётов» (5.15): «Тренировки» и «Клиент». */
internal enum class ReportTab(val title: String) {
    TRAININGS(ClientReportFormat.TAB_TRAININGS),
    CLIENT(ClientReportFormat.TAB_CLIENT),
    GROUPS(com.akenory.personalka.domain.format.GroupReportFormat.TAB),
}

@Composable
internal fun ReportTabs(selected: ReportTab, onSelect: (ReportTab) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column {
        Row(Modifier.fillMaxWidth()) {
            ReportTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .height(Dimens.touch)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                        .testTag(reportTabTag(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(tab.title, style = MaterialTheme.typography.labelLarge, color = if (isSelected) colors.text else colors.textMuted)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(64.dp).height(3.dp).background(if (isSelected) colors.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
    }
}

/**
 * Вкладка «Клиент» (шаг 5.3к): клиент не выбран — поиск по всем, и архивным; выбран — его имя и «Другой клиент»,
 * строки «тип · место», итог «В зарплату: N из M» и тренировки по датам. Нажатие на тренировку — её карточка.
 */
@Composable
internal fun ClientReportContent(
    clientId: String?,
    view: ClientReportView?,
    clients: List<ReportClient>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (String) -> Unit,
    onChange: () -> Unit,
    onTraining: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    if (clientId == null) {
        Muted(ClientReportFormat.PICK)
        SearchField(query, onQuery, ClientReportFormat.SEARCH, Modifier.testTag(REPORTS_CLIENT_SEARCH_TAG))
        val found = clients.filter { ClientRules.matches(query, it.name) }
        if (found.isEmpty()) {
            if (clients.isNotEmpty()) Muted(ClientReportFormat.NOT_FOUND)
            return
        }
        SalaryCard {
            found.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                MoneyRow(
                    c.title,
                    if (c.archived) ClientReportFormat.ARCHIVED else "",
                    modifier = Modifier.testTag(reportPickTag(c.id)),
                    onClick = { onPick(c.id) },
                )
            }
        }
        return
    }
    val client = view?.client ?: clients.firstOrNull { it.id == clientId }
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                client?.title.orEmpty(),
                modifier = Modifier.semantics { heading() }.testTag(REPORTS_CLIENT_NAME_TAG),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            if (client?.archived == true) Muted(ClientReportFormat.ARCHIVED)
        }
        TextButton(onClick = onChange, modifier = Modifier.heightIn(min = Dimens.button).testTag(REPORTS_CLIENT_CHANGE_TAG)) {
            Text(ClientReportFormat.CHANGE, style = MaterialTheme.typography.labelLarge, color = colors.text)
        }
    }
    val report = view?.report ?: return
    val names = view.names
    if (report.counted < report.to) Muted("по ${DateFormat.dayMonth(report.counted)}")
    if (report.isEmpty) {
        Muted(ReportFormat.EMPTY, Modifier.testTag(REPORTS_EMPTY_TAG))
    } else {
        SalaryCard {
            CardHeader(" ", right = ReportFormat.IN_SALARY)
            report.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                val title = ClientReportFormat.rowTitle(row, names.type(row.key.typeId), names.place(row.key.locationId))
                MoneyRow(
                    title,
                    row.counts.inSalary.toString(),
                    modifier = Modifier.testTag(reportClientRowTag(title)),
                    note = ClientReportFormat.rowNote(row).ifEmpty { null },
                )
            }
        }
        SalaryCard {
            MoneyRow(
                ClientReportFormat.salaryLine(report.total),
                "",
                modifier = Modifier.testTag(REPORTS_CLIENT_TOTAL_TAG),
                note = ReportFormat.countsLine(report.total),
                bold = true,
            )
        }
    }
    if (report.waiting > 0) Muted(ReportFormat.waiting(report.waiting))
    if (report.ahead > 0) Muted(ReportFormat.ahead(report.ahead))
    if (report.visits.isNotEmpty()) {
        SalaryCard {
            CardHeader(ClientReportFormat.TRAININGS)
            report.visits.forEachIndexed { i, v ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.divider)
                val typePlace = ClientReportFormat.rowTitle(v, names.type(v.key.typeId), names.place(v.key.locationId))
                MoneyRow(
                    ClientReportFormat.visitLine(v, typePlace),
                    ClientReportFormat.visitStatus(v),
                    modifier = Modifier.testTag(reportVisitTag(v.trainingId)),
                    note = ClientReportFormat.visitNote(v),
                    onClick = { onTraining(v.trainingId) },
                )
            }
        }
    }
}
