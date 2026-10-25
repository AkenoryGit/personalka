package com.akenory.personalka.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.money.Sharing
import com.akenory.personalka.ui.clients.FormLabel
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.theme.Dimens

fun sharingOwnerTag(o: Owner) = "sharing-owner-${o.name}"
fun sharingConductedTag(c: ConductedBy) = "sharing-conducted-${c.name}"
fun sharingColleagueTag(name: String) = "sharing-colleague-$name"

/**
 * «На ком числится», «Кто обычно ведёт» (форма группы, 5.10) или «Проводим» (карточка тренировки, 5.4) чипами и выбор
 * коллеги, когда он нужен (шаг 3.7г). [choices] — коллеги «id to имя»; один — выбирается сам. Смена владельца
 * переносит выбранного коллегу: обычно это тот же человек.
 */
@Composable
internal fun SharingFields(
    sharing: Sharing,
    choices: List<Pair<String, String>>,
    conductedLabel: String,
    error: String?,
    onChange: (Sharing) -> Unit,
) {
    val only = choices.singleOrNull()?.first
    Column {
        FormLabel(CoCoachingFormat.OWNER)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            listOf(Owner.SELF to CoCoachingFormat.ME, Owner.COLLEAGUE to CoCoachingFormat.COLLEAGUE).forEach { (o, label) ->
                ChoiceChip(label, sharing.owner == o, onClick = {
                    val picked = sharing.colleague ?: only
                    onChange(
                        when (o) {
                            Owner.SELF -> sharing.copy(owner = o, ownerColleagueId = null, colleagueId = picked)
                            Owner.COLLEAGUE -> sharing.copy(owner = o, ownerColleagueId = picked, colleagueId = picked)
                        },
                    )
                }, modifier = Modifier.testTag(sharingOwnerTag(o)))
            }
        }
    }
    Column {
        FormLabel(conductedLabel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            ConductedBy.entries.forEach { c ->
                ChoiceChip(CoCoachingFormat.conducted(c), sharing.conductedBy == c, onClick = {
                    onChange(sharing.copy(conductedBy = c, colleagueId = sharing.colleagueId ?: only))
                }, modifier = Modifier.testTag(sharingConductedTag(c)))
            }
        }
    }
    if (sharing.owner == Owner.COLLEAGUE || sharing.conductedBy != ConductedBy.SELF) {
        Column {
            FormLabel(CoCoachingFormat.WHICH)
            if (choices.isEmpty()) {
                Note(CoCoachingFormat.NO_COLLEAGUES)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    choices.forEach { (id, name) ->
                        ChoiceChip(name, sharing.colleague == id, onClick = {
                            onChange(
                                if (sharing.owner == Owner.COLLEAGUE) sharing.copy(ownerColleagueId = id, colleagueId = id) else sharing.copy(colleagueId = id),
                            )
                        }, modifier = Modifier.testTag(sharingColleagueTag(name)))
                    }
                }
            }
            error?.let { ErrorLine(it) }
        }
    }
}
