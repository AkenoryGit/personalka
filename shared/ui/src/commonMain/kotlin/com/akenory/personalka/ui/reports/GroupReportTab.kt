package com.akenory.personalka.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.report.GroupReportView
import com.akenory.personalka.domain.format.GroupReportFormat
import com.akenory.personalka.domain.report.GroupReportBlock
import com.akenory.personalka.domain.report.GroupReportKid
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular

internal const val REPORTS_GROUPS_PICK_TAG = "reports-groups-pick"
internal const val REPORTS_GROUPS_PANEL_TAG = "reports-groups-panel"
internal const val REPORTS_GROUPS_ALL_TAG = "reports-groups-all"
internal const val REPORTS_SHARE_TEXT_TAG = "reports-share-text"
internal const val REPORTS_SHARE_EXCEL_TAG = "reports-share-excel"
internal fun reportGroupTag(id: String) = "reports-group-$id"
internal fun reportGroupPickTag(id: String) = "reports-group-pick-$id"
internal fun reportKidTag(groupId: String, clientId: String) = "reports-kid-$groupId-$clientId"

/** Ключ выбранных групп в настройках телефона: id через запятую; нет ключа — все (5.15). */
internal const val GROUPS_PREF = "reports.groups"

internal fun decodeGroups(text: String?): Set<String>? = text?.split(',')?.filter { it.isNotBlank() }?.toSet()

/**
 * «Отчёты → Группы» (ТЗ 5.15, шаг 5.3о): строка выбора групп и блок каждой группы — сводка, дети в составе с «6 из 8 ·
 * 75 %», ушедшие и «на раз». Нажатие на название — карточка группы, на ребёнка — его вкладка «Клиент».
 */
@Composable
internal fun GroupReportContent(
    view: GroupReportView?,
    chosen: Set<String>?,
    onPick: () -> Unit,
    onGroup: (String) -> Unit,
    onKid: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    view ?: return
    val total = view.groups.size
    if (total > 0) {
        val count = chosen?.count { id -> view.groups.any { it.first == id } } ?: total
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onPick).testTag(REPORTS_GROUPS_PICK_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(GroupReportFormat.picked(count, total), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
    }
    if (view.report.isEmpty) {
        Text(GroupReportFormat.EMPTY, Modifier.testTag(REPORTS_EMPTY_TAG), style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
        return
    }
    view.report.blocks.forEach { b -> GroupBlock(b, onGroup, onKid) }
}

@Composable
private fun GroupBlock(b: GroupReportBlock, onGroup: (String) -> Unit, onKid: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    SalaryCard(Modifier.testTag(reportGroupTag(b.group.id))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onGroup(b.group.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(b.group.name, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
        Muted(GroupReportFormat.summary(b))
        GroupReportFormat.extra(b)?.let { Muted(it) }
        b.kids.forEachIndexed { i, k ->
            HorizontalDivider(Modifier.padding(top = if (i == 0) Dimens.space2 else 0.dp), thickness = 1.dp, color = colors.divider)
            KidRow(b, k, onKid)
        }
        if (b.left.isNotEmpty()) {
            Text(GroupReportFormat.LEFT, Modifier.padding(top = Dimens.space3), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            b.left.forEach { k ->
                HorizontalDivider(thickness = 1.dp, color = colors.divider)
                KidRow(b, k, onKid)
            }
        }
        GroupReportFormat.guests(b)?.let { Muted(it, Modifier.padding(top = Dimens.space3)) }
    }
}

/** Ребёнок: имя (и подпись коллеги у «Каждый своих»), справа «6 из 8 · 75 %», ниже — отмены, последний раз, пропуски. */
@Composable
private fun KidRow(b: GroupReportBlock, k: GroupReportKid, onKid: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onKid(k.clientId) }.padding(vertical = Dimens.space2)
            .testTag(reportKidTag(b.group.id, k.clientId)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(GroupReportFormat.name(k.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            b.group.colleague?.takeIf { k.colleague }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                Spacer(Modifier.width(Dimens.space2))
            }
            Text(GroupReportFormat.share(k), style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text)
        }
        val under = listOfNotNull(GroupReportFormat.counts(k), k.last?.let(GroupReportFormat::last)).joinToString(" · ")
        if (under.isNotEmpty()) Muted(under)
        GroupReportFormat.missed(k)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }
}

/** Окно «Какие группы»: «Все» и группы с тренировками в периоде галочками; «Готово» сохраняет выбор. */
@Composable
internal fun GroupPickPanel(groups: List<Pair<String, String>>, chosen: Set<String>?, onSave: (Set<String>?) -> Unit, onDismiss: () -> Unit) {
    var picked by remember(groups, chosen) { mutableStateOf(chosen?.intersect(groups.map { it.first }.toSet()) ?: groups.map { it.first }.toSet()) }
    val all = picked.size == groups.size
    Panel(GroupReportFormat.PICK_TITLE, onDismiss = onDismiss, modifier = Modifier.testTag(REPORTS_GROUPS_PANEL_TAG)) {
        CheckRow(GroupReportFormat.ALL, all, { on -> picked = if (on) groups.map { it.first }.toSet() else emptySet() }, Modifier.testTag(REPORTS_GROUPS_ALL_TAG))
        groups.forEach { (id, name) ->
            CheckRow(name, id in picked, { on -> picked = if (on) picked + id else picked - id }, Modifier.testTag(reportGroupPickTag(id)))
        }
        PanelActions(
            primary = GroupReportFormat.DONE,
            onPrimary = { onSave(if (all || picked.isEmpty()) null else picked) },
            secondary = "Отмена",
            onSecondary = onDismiss,
        )
    }
}

/** «Поделиться» отчётом по группам: «Текстом» или «Файлом Excel» (5.15). */
@Composable
internal fun GroupSharePanel(excel: Boolean, onText: () -> Unit, onExcel: () -> Unit, onDismiss: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Panel(GroupReportFormat.SHARE_TITLE, onDismiss = onDismiss) {
        listOfNotNull(
            Triple(GroupReportFormat.SHARE_TEXT, REPORTS_SHARE_TEXT_TAG, onText),
            if (excel) Triple(GroupReportFormat.SHARE_EXCEL, REPORTS_SHARE_EXCEL_TAG, onExcel) else null,
        ).forEach { (title, tag, action) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.row).clickable(role = Role.Button, onClick = action).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            }
        }
    }
}
