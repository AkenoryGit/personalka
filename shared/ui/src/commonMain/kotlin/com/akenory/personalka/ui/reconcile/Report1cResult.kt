package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.reconcile.Report1cItemView
import com.akenory.personalka.data.reconcile.Report1cRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.CardHeader
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Экран сверки с 1С в стопке «Ещё»: `reconcile-1c/<id сверки>`. */
object Report1cRoute {
    private const val PREFIX = "reconcile-1c/"

    fun of(id: String): String = PREFIX + id

    fun idOf(route: String?): String? = route?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

internal const val REPORT1C_SUMMARY_TAG = "reconcile-1c-summary"
internal const val REPORT1C_SEND_TAG = "reconcile-1c-send"
internal const val REPORT1C_ADD_TAG = "reconcile-1c-add"
internal const val REPORT1C_FIX_TAG = "reconcile-1c-fix"
internal const val REPORT1C_ASKED_TAG = "reconcile-1c-asked"
internal const val REPORT1C_IGNORE_TAG = "reconcile-1c-ignore"
internal fun report1cItemTag(id: String) = "reconcile-1c-item-$id"

/**
 * Сверка с отчётом 1С (ТЗ 8.4, шаг 4.2в): период и сводка, три списка нерешённых пунктов, нажатие на пункт — его
 * действия (не больше трёх, 10.7). «Отправить администратору» собирает строки всех пунктов, которые исправляет
 * администратор, и отмечает их «Написал администратору». Прокрутка и открытый пункт — над разметкой (сложение Fold).
 */
@Composable
internal fun Report1cResultScreen(
    repo: Report1cRepository,
    id: String,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: (() -> Unit)?,
    shareText: ((String) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val rec by remember(repo, id) { repo.reconciliation(id) }.collectAsState(initial = null)
    val views by remember(repo, id) { repo.view(id) }.collectAsState(initial = null)
    val clipboard = LocalClipboardManager.current
    val colors = PersonalkaTheme.colors
    val open = views.orEmpty().filter { it.item.resolution == ReconcileResolution.OPEN || it.item.resolution == ReconcileResolution.ASKED_ADMIN }
    val forAdmin = open.filter { it.item.resolution == ReconcileResolution.OPEN }.mapNotNull { v -> adminLine(v)?.let { v to it } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(ReconcileFormat.REPORT_TITLE, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(colors.bg)
                    .verticalScroll(scroll)
                    .padding(Dimens.space4)
                    .padding(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.space3),
            ) {
                val r = rec ?: return@Column
                if (views == null) return@Column
                SalaryCard(Modifier.testTag(REPORT1C_SUMMARY_TAG)) {
                    CardHeader(ReconcileFormat.period(r.periodFrom, r.periodTo, r.generatedAt))
                    Text(ReconcileFormat.summary(r.matched, open.size), style = MaterialTheme.typography.titleMedium, color = colors.text)
                    if (open.isEmpty()) Muted(ReconcileFormat.NOTHING) else Muted(ReconcileFormat.REPEAT_NOTE)
                    if (forAdmin.isNotEmpty()) {
                        OutlineButton(
                            ReconcileFormat.SEND_ADMIN,
                            {
                                val text = ReconcileFormat.adminText(forAdmin.map { it.second })
                                if (shareText != null) shareText(text) else clipboard.setText(AnnotatedString(text))
                                scope.launch {
                                    forAdmin.forEach { (v, _) -> repo.resolve(v.item.id, ReconcileResolution.ASKED_ADMIN) }
                                    messages.show(ReconcileFormat.ASKED_DONE)
                                }
                            },
                            Modifier.fillMaxWidth().testTag(REPORT1C_SEND_TAG),
                            icon = PIcons.Share,
                        )
                    }
                }
                Section(ReconcileFormat.ONLY_1C, open.filter { it.item.kind in ONLY_1C_KINDS }) { openId = it }
                Section(ReconcileFormat.ONLY_APP, open.filter { it.item.kind == ReconcileItemKind.ONLY_APP }) { openId = it }
                Section(ReconcileFormat.DIFFER, open.filter { it.item.kind == ReconcileItemKind.STATUS_DIFF }) { openId = it }
            }
        }
        val chosen = views?.firstOrNull { it.item.id == openId }
        if (chosen != null) {
            ItemActions(chosen, onDismiss = { openId = null }) { action ->
                openId = null
                scope.launch {
                    val message = when (action) {
                        Action.ADD -> if (repo.addFromReport(chosen.item.id)) ReconcileFormat.ADDED else null
                        Action.FIX -> if (repo.fixInApp(chosen.item.id)) ReconcileFormat.FIXED else null
                        Action.ASKED -> repo.resolve(chosen.item.id, ReconcileResolution.ASKED_ADMIN).let { ReconcileFormat.ASKED_DONE }
                        Action.IGNORE -> repo.resolve(chosen.item.id, ReconcileResolution.IGNORED).let { ReconcileFormat.IGNORED_DONE }
                    }
                    message?.let(messages::show)
                }
            }
        }
    }
}

private val ONLY_1C_KINDS = setOf(ReconcileItemKind.ONLY_1C, ReconcileItemKind.UNKNOWN_CLIENT, ReconcileItemKind.UNKNOWN_SERVICE)

private enum class Action { ADD, FIX, ASKED, IGNORE }

/** Строка администратору — только у пунктов, которые исправляет он (8.4). */
private fun adminLine(v: Report1cItemView): String? = ReconcileFormat.adminLine(v.diff, v.what ?: return null, v.name, v.sex)

@Composable
private fun Section(title: String, items: List<Report1cItemView>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) return
    SalaryCard {
        CardHeader(title, items.size.toString())
        items.forEach { v ->
            val item = v.item
            MoneyRow(
                title = ReconcileFormat.itemTitle(item.at, v.name),
                amount = "",
                modifier = Modifier.clickable { onOpen(item.id) }.heightIn(min = Dimens.touch).testTag(report1cItemTag(item.id)),
                note = ReconcileFormat.itemNote(
                    item.kind,
                    item.statusDiffers,
                    item.rowStatus,
                    item.rowServiceName,
                    v.app,
                    v.status,
                    v.sex,
                    item.resolvedAt?.takeIf { item.resolution == ReconcileResolution.ASKED_ADMIN }?.let { ClubTime.today(it) },
                ),
            )
        }
    }
}

/** Действия пункта (8.4): у «Только в 1С» — «Добавить», «Игнорировать»; у остальных — «Исправить», «Написал администратору», «Игнорировать». */
@Composable
private fun ItemActions(v: Report1cItemView, onDismiss: () -> Unit, onAction: (Action) -> Unit) {
    val item = v.item
    val colors = PersonalkaTheme.colors
    Panel(ReconcileFormat.itemTitle(item.at, v.name), onDismiss = onDismiss) {
        Text(
            ReconcileFormat.itemNote(item.kind, item.statusDiffers, item.rowStatus, item.rowServiceName, v.app, v.status, v.sex, null),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
        val only1c = item.kind in ONLY_1C_KINDS
        val note = when {
            item.kind == ReconcileItemKind.UNKNOWN_CLIENT -> ReconcileFormat.UNKNOWN_CLIENT_NOTE
            only1c -> ReconcileFormat.ADD_NOTE
            item.kind == ReconcileItemKind.ONLY_APP -> ReconcileFormat.ONLY_APP_FIX_NOTE
            else -> null
        }
        note?.let { Muted(it) }
        Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
            if (only1c) {
                if (item.kind != ReconcileItemKind.UNKNOWN_SERVICE) {
                    PrimaryButton(ReconcileFormat.ADD, { onAction(Action.ADD) }, Modifier.fillMaxWidth().testTag(REPORT1C_ADD_TAG))
                }
            } else {
                // Тренировка, которую удалили после сверки, не исправляется — только администратор или «Игнорировать».
                if (v.status != null || item.kind == ReconcileItemKind.STATUS_DIFF && v.diff.visit != null) {
                    PrimaryButton(ReconcileFormat.FIX, { onAction(Action.FIX) }, Modifier.fillMaxWidth().testTag(REPORT1C_FIX_TAG))
                }
                if (item.resolution != ReconcileResolution.ASKED_ADMIN && v.status != ParticipationStatus.EXPECTED) {
                    TextButton(onClick = { onAction(Action.ASKED) }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(REPORT1C_ASKED_TAG)) {
                        Text(ReconcileFormat.ASKED, style = MaterialTheme.typography.labelLarge, color = colors.text)
                    }
                }
            }
            TextButton(onClick = { onAction(Action.IGNORE) }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(REPORT1C_IGNORE_TAG)) {
                Text(ReconcileFormat.IGNORE, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
        }
    }
}
