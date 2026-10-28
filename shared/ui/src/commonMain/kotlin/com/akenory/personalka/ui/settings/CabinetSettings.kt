package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.cabinet.CabinetLinkRules
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val CABINETS_ROW_TAG = "settings-cabinets"
internal const val CABINETS_SWITCH_TAG = "cabinets-switch"
internal const val CABINETS_CONTACT_TAG = "cabinets-contact"

/**
 * «Настройки → Кабинеты» (5.16, шаг 2.13): общий выключатель и контакт для родителей — без контакта кабинеты
 * не включаются (3.2). Код доступа по умолчанию и приветствие — потом, если понадобятся: новая ссылка выходит с кодом,
 * кабинет приветствия не показывает. Сколько ближайших тренировок видно — то же число, что в карточке.
 */
@Composable
internal fun CabinetSettingsScreen(settings: SettingsRepository, messages: MessageState, scope: CoroutineScope, onBack: (() -> Unit)?, onClose: () -> Unit) {
    val coach by remember(settings) { settings.coach }.collectAsState(initial = null)
    val on by remember(settings) { settings.cabinetsEnabled }.collectAsState(initial = null)
    val loadedCoach = coach ?: return
    val loadedOn = on ?: return
    var enabled by rememberSaveable(loadedCoach.id) { mutableStateOf(loadedOn) }
    var contact by rememberSaveable(loadedCoach.id) { mutableStateOf(loadedCoach.contactForParents.orEmpty()) }
    val noContact = CabinetLinkRules.contact(contact) == null
    val colors = PersonalkaTheme.colors

    Column(Modifier.fillMaxSize()) {
        TopBar(CabinetLinkFormat.SETTINGS_TITLE, onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            SwitchRow(
                title = CabinetLinkFormat.SETTINGS_SWITCH,
                checked = enabled,
                onToggle = { enabled = it },
                subtitle = if (enabled) CabinetLinkFormat.SETTINGS_SWITCH_NOTE else CabinetLinkFormat.OFF_NOTE,
                modifier = Modifier.testTag(CABINETS_SWITCH_TAG),
            )
            Field(
                CabinetLinkFormat.CONTACT,
                contact,
                { contact = it.take(CabinetLinkRules.CONTACT_MAX) },
                tag = CABINETS_CONTACT_TAG,
                placeholder = CabinetLinkFormat.CONTACT_HINT,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Text(CabinetLinkFormat.CONTACT_NOTE, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        BottomAction(
            text = "Сохранить",
            enabled = !(enabled && noContact),
            hint = if (enabled && noContact) CabinetLinkFormat.CONTACT_NEEDED else null,
            onClick = {
                scope.launch { settings.saveCabinets(enabled, contact) }
                onClose()
                messages.show("Сохранено")
            },
        )
    }
}
