package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.data.training.CardResult
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

internal const val ZONE_DELETE_FOLLOWING_TAG = "zone-delete-following"

/**
 * «Удалить» из окна дежурства или своего запрета (ТЗ 5.3, шаг 5.3р) — как «Удалить» в карточке тренировки (5.4).
 *
 * Дежурство: будущее серии — «Только это — сб 31.10» (дата остаётся пропущенной) или «Это и все следующие»
 * с окном «Удалить 9 дежурств» (серия заканчивается накануне, 4.4); разовое — одно подтверждение; проведённое —
 * «Удалить прошедшее дежурство?», только одно: оно уйдёт из зарплаты. В сверенном месяце сначала спрашивается
 * подтверждение правки, как у тренировки.
 *
 * Запрет: повторяющийся — «Только этот день — сб 31.10» (то же, что «Пропустить этот день», у сегодняшнего
 * и будущего дня) или «Этот и все следующие» (запрет действует до накануне); разовый — одно подтверждение.
 * После любого удаления — сообщение с «Отменить».
 */
@Composable
internal fun ZoneDelete(
    zone: CalendarZone,
    today: LocalDate,
    calendar: CalendarRepository,
    messages: MessageState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val duty = zone.kind == ZoneKind.DUTY
    val key = zone.id + zone.date
    var closedOk by rememberSaveable(key) { mutableStateOf(false) }
    var following by rememberSaveable(key) { mutableStateOf(false) }
    val closedMonths by remember(calendar, duty) { if (duty) calendar.cards.closedMonths else flowOf<Set<YearMonth>>(emptySet()) }
        .collectAsState(initial = emptySet())
    val count by produceState<Int?>(null, key) {
        if (duty && !zone.done) value = calendar.cards.followingCount(zone.id)
    }
    val head = DateFormat.short(zone.date) + " · " + zone.text

    fun dutyDone(result: CardResult?) {
        onDismiss()
        if (result != null) messages.showUndo(result.message) { scope.launch { calendar.cards.undo(result.undo) } }
    }

    val month = zone.date.yearMonth
    when {
        duty && month in closedMonths && !closedOk -> Panel(title = TrainingCardFormat.CLOSED_TITLE, onDismiss = onDismiss) {
            Text(TrainingCardFormat.closedText(month), style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
            PanelActions(
                primary = TrainingCardFormat.CLOSED_CHANGE,
                onPrimary = { closedOk = true },
                secondary = TrainingCardFormat.CLOSED_KEEP,
                onSecondary = onDismiss,
            )
        }

        duty && following && count != null -> Panel(title = ZoneFormat.DUTY_FOLLOWING_TITLE, onDismiss = onDismiss) {
            val n = count ?: 0
            Text(ZoneFormat.dutyFollowingText(n, zone.date), style = MaterialTheme.typography.bodyLarge.tabular(), color = PersonalkaTheme.colors.text)
            PanelActions(
                primary = ZoneFormat.deleteDuties(n),
                onPrimary = { scope.launch { dutyDone(calendar.cards.deleteFollowing(zone.id)) } },
                secondary = ZoneFormat.KEEP,
                onSecondary = onDismiss,
            )
        }

        duty -> Panel(title = if (zone.done) ZoneFormat.DUTY_DELETE_DONE_TITLE else ZoneFormat.DUTY_DELETE_TITLE, onDismiss = onDismiss) {
            Text(head, style = MaterialTheme.typography.bodyLarge.tabular(), color = PersonalkaTheme.colors.text)
            Text(
                if (zone.done) ZoneFormat.DUTY_DELETE_DONE_TEXT else ZoneFormat.DUTY_DELETE_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = PersonalkaTheme.colors.textMuted,
            )
            val deleteOne: () -> Unit = { scope.launch { dutyDone(calendar.cards.delete(zone.id)) } }
            // «Это и все следующие» — только у будущего дежурства серии; проведённое удаляется одно.
            if (!zone.done && (count ?: 0) > 1) {
                Choices(
                    first = ZoneFormat.dutyOnly(zone.date),
                    onFirst = deleteOne,
                    second = ZoneFormat.DUTY_FOLLOWING,
                    onSecond = { following = true },
                    onKeep = onDismiss,
                )
            } else {
                PanelActions(primary = ZoneFormat.DELETE, onPrimary = deleteOne, secondary = ZoneFormat.KEEP, onSecondary = onDismiss)
            }
        }

        else -> Panel(title = ZoneFormat.RESTRICTION_DELETE_TITLE, onDismiss = onDismiss) {
            Text(head, style = MaterialTheme.typography.bodyLarge.tabular(), color = PersonalkaTheme.colors.text)
            zone.repeat?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.textMuted) }
            val restrictions = calendar.restrictions
            val ending: () -> Unit = {
                onDismiss()
                scope.launch {
                    val result = calendar.restrictions.endBefore(zone.id, zone.date) ?: return@launch
                    messages.showUndo(result.first) { scope.launch { restrictions.undo(result.second) } }
                }
            }
            if (zone.repeating) {
                Choices(
                    first = ZoneFormat.onlyThisDay(zone.date).takeIf { zone.date >= today },
                    onFirst = {
                        onDismiss()
                        scope.launch { calendar.skipRestrictionDay(zone.id, zone.date) }
                        messages.showUndo(ZoneFormat.skipped(zone.date)) { scope.launch { calendar.unskipRestrictionDay(zone.id, zone.date) } }
                    },
                    second = ZoneFormat.THIS_AND_FOLLOWING,
                    onSecond = ending,
                    onKeep = onDismiss,
                )
            } else {
                PanelActions(primary = ZoneFormat.DELETE, onPrimary = ending, secondary = ZoneFormat.KEEP, onSecondary = onDismiss)
            }
        }
    }
}

/** «Только это — …» главной кнопкой, «… и все следующие» контурной, «Не удалять» текстом — как у тренировки серии. */
@Composable
private fun Choices(first: String?, onFirst: () -> Unit, second: String, onSecond: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        if (first != null) PrimaryButton(first, onFirst, Modifier.fillMaxWidth())
        OutlineButton(second, onSecond, Modifier.fillMaxWidth().testTag(ZONE_DELETE_FOLLOWING_TAG))
        TextButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
            Text(ZoneFormat.KEEP, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
        }
    }
}
