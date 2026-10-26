package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.reconcile.Alias1cView
import com.akenory.personalka.data.reconcile.Report1cRepository
import com.akenory.personalka.data.reconcile.Service1cView
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

internal fun report1cServiceTag(name: String) = "reconcile-1c-service-$name"
internal fun report1cAliasTag(key: String) = "reconcile-1c-alias-$key"

/**
 * «Услуги и имена из 1С» (шаг 4.2г, ТЗ 5.14, 8.3): что тренер ответил при загрузке отчёта — услуга → тип и место, имя →
 * клиент. Нажатие открывает то же окно выбора; у имени «Нет такого клиента» убирает пару. Сверки не пересчитываются сами:
 * в базе нет строк отчёта целиком, поэтому после правки отчёт загружается ещё раз. Держится над разметкой экрана.
 */
internal class Report1cMappings(
    private val repo: Report1cRepository,
    private val messages: MessageState,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
        private set
    var service by mutableStateOf<Service1cView?>(null)
        private set
    var alias by mutableStateOf<Alias1cView?>(null)
        private set

    fun show() {
        open = true
    }

    fun close() {
        open = false
        back()
    }

    fun edit(s: Service1cView) {
        service = s
    }

    fun edit(a: Alias1cView) {
        alias = a
    }

    /** Из окна выбора — обратно к списку. */
    fun back() {
        service = null
        alias = null
    }

    fun saveService(s: Service1cView, typeId: String, placeId: String) {
        scope.launch {
            if (typeId != s.typeId || placeId != s.locationId) {
                repo.saveService(s.name1c, typeId, placeId)
                messages.show(ReconcileFormat.MAPPING_SAVED)
            }
            back()
        }
    }

    fun chooseClient(a: Alias1cView, clientId: String?) {
        scope.launch {
            when (clientId) {
                null -> {
                    repo.deleteAlias(a.nameKey)
                    messages.show(ReconcileFormat.ALIAS_DELETED)
                }
                a.clientId -> Unit
                else -> {
                    repo.saveAlias(a.name1c, clientId)
                    messages.show(ReconcileFormat.MAPPING_SAVED)
                }
            }
            back()
        }
    }

    suspend fun choices(a: Alias1cView) = repo.aliasChoices(a)
}

/** Список и окна выбора «Услуг и имён из 1С». */
@Composable
internal fun Report1cMappingsPanel(state: Report1cMappings, repo: Report1cRepository, settings: SettingsRepository, today: LocalDate) {
    if (!state.open) return
    val service = state.service
    val alias = state.alias
    when {
        service != null -> {
            val types by remember(settings) { settings.types }.collectAsState(initial = emptyList())
            val places by remember(settings) { settings.places }.collectAsState(initial = emptyList())
            ServiceStep(
                ReconcileFormat.SERVICE_EDIT, service.name1c, types, places,
                typeHint = { ts -> service.typeId.takeIf { id -> ts.any { it.id == id } } },
                placeHint = { ps -> service.locationId.takeIf { id -> ps.any { it.id == id } } },
                primary = ReconcileFormat.SAVE,
                onSave = { typeId, placeId -> state.saveService(service, typeId, placeId) },
                onCancel = state::back,
            )
        }
        alias != null -> {
            val candidates by produceState(emptyList(), alias) { value = state.choices(alias) }
            NameStep(
                ReconcileFormat.NAME_EDIT, alias.name1c, candidates, today,
                note = ReconcileFormat.ALIAS_EDIT_NOTE,
                onChoose = { state.chooseClient(alias, it) },
                onCancel = state::back,
                current = alias.clientId,
            )
        }
        else -> MappingList(state, repo)
    }
}

@Composable
private fun MappingList(state: Report1cMappings, repo: Report1cRepository) {
    val data by remember(repo) { repo.mappings() }.collectAsState(initial = null)
    val m = data ?: return
    Panel(ReconcileFormat.MAPPINGS_TITLE, onDismiss = state::close) {
        if (m.services.isNotEmpty()) {
            SectionLabel(ReconcileFormat.SERVICES)
            m.services.forEach { s ->
                MoneyRow(s.name1c, "", Modifier.testTag(report1cServiceTag(s.name1c)), note = s.app, onClick = { state.edit(s) })
            }
        }
        if (m.names.isNotEmpty()) {
            SectionLabel(ReconcileFormat.NAMES)
            m.names.forEach { a ->
                MoneyRow(a.name1c, "", Modifier.testTag(report1cAliasTag(a.nameKey)), note = ReconcileFormat.aliasNote(a.client), onClick = { state.edit(a) })
            }
        }
        Muted(ReconcileFormat.MAPPINGS_NOTE)
        PrimaryButton(ReconcileFormat.DONE, state::close, Modifier.fillMaxWidth())
    }
}
