package com.akenory.personalka.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.calendar.NoticeRow
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular

internal const val NOTICE_PANEL_TAG = "vacation-notices"
internal const val NOTICE_COPY_TAG = "vacation-notices-copy"
internal fun noticeDoneTag(id: String) = "vacation-notice-done-$id"

/**
 * «Предупредить об отпуске» (ТЗ 5.16, 5.2; шаг 3.12): клиенты, у которых отпуск забрал тренировки, по отпускам —
 * имя, сколько тренировок и какие дни, «Предупредил» у каждого. Отмеченный уходит из списка; последний — уходит
 * и пункт «Нужно сделать». «Скопировать список» — для рассылки в мессенджере.
 */
@Composable
internal fun VacationNoticePanel(
    notices: List<NoticeRow>,
    onDone: (NoticeRow) -> Unit,
    onCopied: () -> Unit,
    onClose: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val vacations = notices.groupBy { it.from to it.to }
    Panel(CalendarSettingsFormat.NOTICE_TODO, onDismiss = onClose, modifier = Modifier.testTag(NOTICE_PANEL_TAG)) {
        vacations.forEach { (period, rows) ->
            SectionLabel(CalendarSettingsFormat.vacationTitle(period.first, period.second), Modifier.padding(top = 0.dp))
            ListCard {
                rows.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = Dimens.space4, end = Dimens.space2, top = Dimens.space2, bottom = Dimens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
                            Text(row.subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = PersonalkaTheme.colors.textMuted)
                        }
                        SecondaryButton(CalendarSettingsFormat.NOTICE_DONE, { onDone(row) }, Modifier.testTag(noticeDoneTag(row.id)))
                    }
                }
            }
        }
        OutlineButton(
            CalendarSettingsFormat.NOTICE_COPY,
            {
                val text = vacations.map { (period, rows) ->
                    CalendarSettingsFormat.noticeList(
                        CalendarSettingsFormat.vacationTitle(period.first, period.second),
                        rows.map { it.title to it.dates },
                    )
                }.joinToString("\n\n")
                clipboard.setText(AnnotatedString(text))
                onCopied()
            },
            modifier = Modifier.fillMaxWidth().testTag(NOTICE_COPY_TAG),
            icon = PIcons.ContentCopy,
        )
        SecondaryButton(CalendarSettingsFormat.NOTICE_CLOSE, onClose, Modifier.fillMaxWidth())
    }
}
