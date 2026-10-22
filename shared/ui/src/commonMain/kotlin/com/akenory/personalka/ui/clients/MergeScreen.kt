package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val MERGE_SEARCH_TAG = "merge-search"
fun mergePickTag(name: String) = "merge-pick-$name"
fun mergeKeepTag(name: String) = "merge-keep-$name"

/**
 * «Объединить с…» (5.8, 4.17): вторая карточка того же человека — сначала похожие по имени, остальные поиском.
 * В окне подтверждения тренер выбирает, чья карточка остаётся (по умолчанию — та, из которой пришёл): к ней
 * переходят тренировки, результаты, серии, группы и связи, вторая удаляется.
 */
@Composable
fun MergeScreen(
    clientId: String,
    rows: List<ClientRow>,
    clients: ClientRepository,
    nav: ClientNav,
    messages: MessageState,
    scope: CoroutineScope,
    today: LocalDate,
) {
    val colors = PersonalkaTheme.colors
    val me = rows.firstOrNull { it.client.id == clientId } ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var otherId by rememberSaveable { mutableStateOf<String?>(null) }
    var keepMine by rememberSaveable { mutableStateOf(true) }
    val others = rows.filter { it.client.id != clientId }
    val similar = ClientRules.similar(me.name, others) { it.name }
    val shown = if (query.isBlank()) {
        similar + others.filter { it !in similar }.sortedBy { ClientRules.sortKey(it.name) }
    } else {
        others.filter { ClientRules.matches(query, it.name) }.sortedBy { ClientRules.sortKey(it.name) }
    }
    val myName = "${me.client.lastName} ${me.client.firstName}"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar("Объединить с…", onBack = nav::back, close = true)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Text(
                    "Выберите вторую карточку того же человека: $myName.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                Spacer(Modifier.height(Dimens.space3))
                SearchField(query, { query = it }, "Фамилия или имя", Modifier.testTag(MERGE_SEARCH_TAG))
                Spacer(Modifier.height(Dimens.space3))
                if (query.isBlank() && similar.isNotEmpty()) FormLabel("Похожие")
                Column(Modifier.clip(MaterialTheme.shapes.medium)) {
                    shown.forEach { row ->
                        val name = "${row.client.lastName} ${row.client.firstName}"
                        PersonRow(
                            title = name,
                            subtitle = row.subtitle(today) + if (row.client.status == ClientStatus.ARCHIVED) " · в архиве" else "",
                            onClick = {
                                otherId = row.client.id
                                keepMine = true
                            },
                            modifier = Modifier.testTag(mergePickTag(name)),
                        )
                    }
                }
                if (shown.isEmpty()) Text("Никого не нашлось", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        val other = otherId?.let { id -> others.firstOrNull { it.client.id == id } }
        if (other != null) {
            Panel("Объединить карточки?", onDismiss = { otherId = null }) {
                Text(
                    "Тренировки, результаты, серии и родственники перейдут в одну карточку, вторая удалится.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                FormLabel("Оставить")
                Column(Modifier.clip(MaterialTheme.shapes.medium)) {
                    listOf(true to me, false to other).forEach { (mine, row) ->
                        val name = "${row.client.lastName} ${row.client.firstName}"
                        val selected = keepMine == mine
                        PersonRow(
                            title = name,
                            subtitle = row.subtitle(today),
                            onClick = { keepMine = mine },
                            selected = selected,
                            leading = if (selected) PIcons.CheckCircle else null,
                            modifier = Modifier.testTag(mergeKeepTag(name)),
                        )
                    }
                }
                PanelActions(
                    primary = "Объединить",
                    onPrimary = {
                        val keep = if (keepMine) clientId else other.client.id
                        val gone = if (keepMine) other.client.id else clientId
                        otherId = null
                        scope.launch { clients.merge(keep, gone) }
                        nav.back()
                        if (keep != clientId) nav.replace(ClientRoute.card(keep))
                        messages.show("Карточки объединены")
                    },
                    secondary = "Отмена",
                    onSecondary = { otherId = null },
                )
            }
        }
    }
}
