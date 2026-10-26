package com.akenory.personalka.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

const val SIMILAR_PANEL_TAG = "similar-client"

/**
 * «Похожий клиент уже есть» (5.8, шаг 3.9о) — перед созданием клиента в любом месте: в форме клиента и тренировки,
 * в составе группы, «на раз», в листе ожидания и в родственниках. [clientId] — похожий ([ClientRepository.similar]).
 * «Да, выбрать» — [onPick] с ним (архивного место вызова возвращает в действующие), «Нет, это другой человек» —
 * [onCreate]: полные тёзки бывают, второй заводится, но только осознанно. Под именем — дата рождения, телефон
 * и «в архиве»: по ним тёзок различают.
 */
@Composable
fun SimilarClientPanel(
    clientId: String,
    clients: ClientRepository,
    today: LocalDate,
    onPick: (ClientEntity) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) = SimilarClientPanel(clientId, clients::client, today, onPick, onCreate, onDismiss)

/** То же, клиент — из [load] (карточка тренировки берёт его из своего хранилища). */
@Composable
fun SimilarClientPanel(
    clientId: String,
    load: (String) -> Flow<ClientEntity?>,
    today: LocalDate,
    onPick: (ClientEntity) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val similar by remember(clientId) { load(clientId) }.collectAsState(initial = null)
    val client = similar ?: return
    val colors = PersonalkaTheme.colors
    Panel(title = ClientFormat.SIMILAR_TITLE, onDismiss = onDismiss, modifier = Modifier.testTag(SIMILAR_PANEL_TAG)) {
        Column(Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.medium).padding(Dimens.space3)) {
            val name = listOfNotNull(client.lastName, client.firstName, client.middleName?.takeIf { it.isNotBlank() }).joinToString(" ")
            Text(name, style = MaterialTheme.typography.labelLarge, color = colors.text)
            val detail = ClientFormat.similarDetail(client.birthDate, client.contactPhone, client.status == ClientStatus.ARCHIVED, today)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Text(ClientFormat.similarQuestion(client.sex), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        PanelActions(primary = ClientFormat.SIMILAR_PICK, onPrimary = { onPick(client) }, secondary = ClientFormat.SIMILAR_OTHER, onSecondary = onCreate)
    }
}
