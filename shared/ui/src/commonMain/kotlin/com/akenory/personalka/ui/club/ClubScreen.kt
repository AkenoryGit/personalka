package com.akenory.personalka.ui.club

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.ClubCoach
import com.akenory.personalka.data.account.CoachesResult
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ClubFormat
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

internal fun coachTag(id: String) = "club-coach-$id"

/**
 * «Ещё → Клуб» — только администратору (ТЗ 5.17, 5.1; шаг 5.2). Пока здесь «Тренеры»: каждый, кто
 * зарегистрировался на сервере, с почтой, последним обменом и числом открытых кабинетов. Нажатие на коллегу —
 * «Приостановить» или «Возобновить» (1.5). Данные коллег сюда не приходят — сервер отдаёт только аккаунты.
 * Список берётся с сервера при каждом открытии; без связи — сообщение и пустой экран.
 * Общие места, запреты и ставки клуба — шаг 5.3.
 */
@Composable
internal fun ClubScreen(
    account: AccountRepository,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
) {
    val colors = PersonalkaTheme.colors
    var coaches by remember { mutableStateOf<List<ClubCoach>?>(null) }
    var chosen by remember { mutableStateOf<ClubCoach?>(null) }
    val me = account.account.value?.email

    fun answer(result: CoachesResult, done: String? = null) {
        when (result) {
            is CoachesResult.Loaded -> {
                coaches = result.coaches
                done?.let(messages::show)
            }
            is CoachesResult.Failed -> messages.show(result.message)
        }
    }

    LaunchedEffect(account) { answer(account.coaches()) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(ClubFormat.TITLE, onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.space4)) {
                SectionLabel(ClubFormat.COACHES)
                val list = coaches
                when {
                    list == null -> Text(ClubFormat.LOADING, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                    else -> {
                        ListCard {
                            list.forEachIndexed { i, coach ->
                                if (i > 0) RowDivider()
                                CoachRow(coach, coach.email == me, today) { chosen = coach }
                            }
                        }
                        if (list.none { it.email != me }) {
                            Text(ClubFormat.EMPTY, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(top = Dimens.space3))
                        }
                        Note(ClubFormat.PRIVATE, Modifier.padding(top = Dimens.space4))
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        chosen?.let { coach ->
            val suspend = !coach.suspended
            Panel(coach.name, onDismiss = { chosen = null }) {
                Text(
                    if (suspend) ClubFormat.SUSPEND_NOTE else ClubFormat.RESUME_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
                PanelActions(
                    primary = if (suspend) ClubFormat.SUSPEND else ClubFormat.RESUME,
                    onPrimary = {
                        chosen = null
                        scope.launch {
                            answer(account.setSuspended(coach, suspend), if (suspend) ClubFormat.SUSPENDED_DONE else ClubFormat.RESUMED_DONE)
                        }
                    },
                    secondary = "Отмена",
                    onSecondary = { chosen = null },
                )
            }
        }
    }
}

/** Строка тренера: имя, почта, состояние. Свою строку и других администраторов приостановить нельзя — она не нажимается. */
@Composable
private fun CoachRow(coach: ClubCoach, self: Boolean, today: LocalDate, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val synced = coach.syncedAt?.toLocalDateTime(ClubTime.defaultZone)?.date
    val row = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(coachTag(coach.id))
    Column(
        (if (coach.admin) row else row.clickable(role = Role.Button, onClick = onClick))
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
    ) {
        Text(ClubFormat.name(coach.name, self), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(coach.email, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        Text(
            ClubFormat.status(coach.confirmed, coach.suspended, synced, coach.cabinets, today),
            style = MaterialTheme.typography.bodySmall,
            color = if (coach.suspended) colors.text else colors.textMuted,
        )
    }
}
