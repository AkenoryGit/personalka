package com.akenory.personalka.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.training.CardSharing
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.money.CoCoachingRules
import com.akenory.personalka.domain.money.Sharing
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.groups.SharingFields
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

const val CARD_SHARING_TAG = "card-sharing"
const val SHARING_PANEL_TAG = "sharing-panel"

/**
 * Строка групповой тренировки с совместным ведением (5.4, шаг 3.7г): «Числится на: я» и «Проводим: с коллегой · Лена».
 * Нажатие — окно «Кто ведёт». Отдельной кнопки нет: лимит трёх кнопок карточки (10.7).
 */
@Composable
internal fun SharingRow(sharing: CardSharing, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space3)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Dimens.row)
            .padding(horizontal = Dimens.space3, vertical = Dimens.space2)
            .testTag(CARD_SHARING_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PIcons.Groups, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Dimens.space3))
        Column(Modifier.weight(1f)) {
            Text(CoCoachingFormat.ownerLine(sharing.sharing, sharing.names), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(CoCoachingFormat.conductedLine(sharing.sharing, sharing.names), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}

/** Окно «Кто ведёт» (5.4): «На ком числится», «Проводим» и коллега; «Сохранить» пишет в эту тренировку. */
@Composable
internal fun SharingPanel(sharing: CardSharing, onSave: (Sharing) -> Unit, onDismiss: () -> Unit) {
    val start = sharing.sharing
    var owner by rememberSaveable { mutableStateOf(start.owner.name) }
    var ownerColleague by rememberSaveable { mutableStateOf(start.ownerColleagueId) }
    var conducted by rememberSaveable { mutableStateOf(start.conductedBy.name) }
    var colleague by rememberSaveable { mutableStateOf(start.colleagueId) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val current = Sharing(Owner.valueOf(owner), ownerColleague, ConductedBy.valueOf(conducted), colleague)
    val problem = CoCoachingRules.problem(current)
    Panel(CoCoachingFormat.WHO, onDismiss = onDismiss, modifier = Modifier.testTag(SHARING_PANEL_TAG)) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            SharingFields(current, sharing.choices, CoCoachingFormat.CONDUCTED, problem.takeIf { showError }) { s ->
                owner = s.owner.name
                ownerColleague = s.ownerColleagueId
                conducted = s.conductedBy.name
                colleague = s.colleagueId
            }
        }
        PanelActions(
            primary = "Сохранить",
            onPrimary = { if (problem == null) onSave(current) else showError = true },
            secondary = "Отмена",
            onSecondary = onDismiss,
        )
    }
}
