package com.akenory.personalka.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.reminders.DiaryPrefs
import com.akenory.personalka.data.reminders.ReminderSettingsRepository
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.domain.reminder.ReminderRules
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.training.TimePickerPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val REMINDERS_ROW_TAG = "settings-reminders"
internal const val DIARY_SWITCH_TAG = "reminders-diary"
internal const val AUTO_DONE_TAG = "reminders-auto-done"
internal const val DIGEST_TAG = "reminders-digest"
internal const val DIGEST_TIME_TAG = "reminders-digest-time"
internal const val FOLLOWUP_TAG = "reminders-followup"
internal const val MORE_TAG = "reminders-more"
internal const val INSTANT_DIARY_TAG = "reminders-instant-diary"
internal const val INSTANT_GROUP_TAG = "reminders-instant-group"
internal const val DAY_PLAN_TAG = "reminders-day-plan"
internal const val ADVANCE_TAG = "reminders-advance"
internal const val BLOCKED_TAG = "reminders-blocked"
internal fun followupDaysTag(days: Int) = "followup-$days"

/**
 * «Настройки → Дневник и напоминания» (ТЗ 5.16, 5.19, шаг 2.5): «Вести дневник», автоотметка «Проведено», вечерняя
 * сводка и её время, «Клиенты без записи» со сроком, «Аванс» (шаг 3.9); под свёрнутой строкой «Дополнительно» — мгновенные напоминания
 * и «План на день». Всё пишется сразу, без «Сохранить». Время сводки и срок видны, только когда они включены (10.7).
 *
 * @param reminders «План на день» — настройка телефона; `null` — строки нет (тесты без платформы).
 * @param notifications уведомления телефона: выключены — строка «Включить» сверху; `null` — строки нет.
 */
@Composable
internal fun ReminderSettingsScreen(
    settings: ReminderSettingsRepository,
    reminders: Reminders?,
    notifications: NotificationAccess?,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val prefs by remember(settings) { settings.prefs }.collectAsState(initial = null)
    val loaded = prefs ?: return
    var more by rememberSaveable { mutableStateOf(loaded.instantDiary || loaded.instantGroup || reminders?.dayPlan == true) }
    var dayPlan by remember(reminders) { mutableStateOf(reminders?.dayPlan ?: false) }
    var advance by remember(reminders) { mutableStateOf(reminders?.advance ?: false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    // Разрешение меняется в окне Android или в настройках телефона: строка «Включить» сверяется с ним, когда окно
    // приложения снова в фокусе, и сразу после нажатия.
    val focused = LocalWindowInfo.current.isWindowFocused
    var asked by remember { mutableIntStateOf(0) }
    val blocked = remember(notifications, focused, asked) { notifications?.enabled() == false }

    fun save(write: suspend ReminderSettingsRepository.() -> Unit) {
        scope.launch {
            settings.write()
            notifications?.changed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(ReminderFormat.TITLE, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                if (blocked && (loaded.digest || loaded.instantDiary || loaded.instantGroup || dayPlan || advance)) {
                    Column(Modifier.padding(top = Dimens.space2).testTag(BLOCKED_TAG)) {
                        Text(ReminderFormat.BLOCKED, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.error)
                        OutlineButton(
                            ReminderFormat.BLOCKED_ACTION,
                            {
                                notifications?.enable()
                                asked++
                            },
                            Modifier.padding(top = Dimens.space2),
                        )
                    }
                }
                SwitchRow(ReminderFormat.DIARY, loaded.diary, { on -> save { saveDiary(on) } }, Modifier.testTag(DIARY_SWITCH_TAG), ReminderFormat.DIARY_NOTE)
                SwitchRow(ReminderFormat.AUTO_DONE, loaded.autoDone, { on -> save { saveAutoDone(on) } }, Modifier.testTag(AUTO_DONE_TAG), ReminderFormat.AUTO_DONE_NOTE)
                SwitchRow(ReminderFormat.DIGEST, loaded.digest, { on -> save { saveDigest(on) } }, Modifier.testTag(DIGEST_TAG), ReminderFormat.DIGEST_NOTE)
                if (loaded.digest) {
                    PickBox(
                        text = ReminderFormat.at(loaded.digestTime),
                        icon = PIcons.Schedule,
                        onClick = { timeOpen = true },
                        modifier = Modifier.fillMaxWidth().testTag(DIGEST_TIME_TAG),
                    )
                }
                SwitchRow(ReminderFormat.FOLLOWUP, loaded.followup, { on -> save { saveFollowup(on) } }, Modifier.testTag(FOLLOWUP_TAG))
                if (loaded.followup) {
                    SectionLabel(ReminderFormat.FOLLOWUP_DAYS, Modifier.padding(top = 0.dp))
                    Chips(ReminderRules.FOLLOWUP_DAYS, loaded.followupDays, ReminderFormat::days, ::followupDaysTag) { days ->
                        save { saveFollowupDays(days) }
                    }
                }
                if (reminders != null) {
                    // «Аванс» 25-го в 19:00 (5.19) — по умолчанию включён, поэтому не под «Дополнительно».
                    SwitchRow(
                        ReminderFormat.ADVANCE,
                        advance,
                        { on ->
                            reminders.advance = on
                            advance = on
                            notifications?.changed()
                        },
                        Modifier.testTag(ADVANCE_TAG),
                        ReminderFormat.ADVANCE_NOTE,
                    )
                }
                MoreRow(more) { more = !more }
                if (more) {
                    if (loaded.diary) {
                        SwitchRow(
                            ReminderFormat.INSTANT_DIARY,
                            loaded.instantDiary,
                            { on -> save { saveInstantDiary(on) } },
                            Modifier.testTag(INSTANT_DIARY_TAG),
                            ReminderFormat.INSTANT_DIARY_NOTE,
                        )
                    }
                    SwitchRow(
                        ReminderFormat.INSTANT_GROUP,
                        loaded.instantGroup,
                        { on -> save { saveInstantGroup(on) } },
                        Modifier.testTag(INSTANT_GROUP_TAG),
                        ReminderFormat.INSTANT_GROUP_NOTE,
                    )
                    if (reminders != null) {
                        SwitchRow(
                            ReminderFormat.DAY_PLAN,
                            dayPlan,
                            { on ->
                                reminders.dayPlan = on
                                dayPlan = on
                                notifications?.changed()
                            },
                            Modifier.testTag(DAY_PLAN_TAG),
                            ReminderFormat.DAY_PLAN_NOTE,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }
        if (timeOpen) {
            TimePickerPanel(
                loaded.digestTime,
                { time ->
                    timeOpen = false
                    save { saveDigestTime(time) }
                },
                { timeOpen = false },
                title = ReminderFormat.DIGEST,
            )
        }
    }
}

/** Свёрнутая строка «Дополнительно» (5.16): редкие настройки раздела. */
@Composable
private fun MoreRow(open: Boolean, onToggle: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(top = Dimens.space2)
            .testTag(MORE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ReminderFormat.MORE, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
        Icon(if (open) PIcons.ExpandLess else PIcons.ExpandMore, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

/** Строка раздела в «Настройках»: «Дневник · сводка в 21:00». */
internal fun reminderSummary(prefs: DiaryPrefs): String = ReminderFormat.summary(prefs.diary, prefs.digest, prefs.digestTime)
