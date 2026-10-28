package com.akenory.personalka.ui.settings

import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.account.Feature
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.money.ColleagueRow
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.CoCoachingRules
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.groups.FormLabelSpaced
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.Hint
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

internal const val COLLEAGUES_ROW_TAG = "settings-colleagues"
internal const val COLLEAGUE_ADD_TAG = "colleague-add"
internal const val COLLEAGUE_NAME_TAG = "colleague-name"
internal const val COLLEAGUE_FROM_TAG = "colleague-from"
internal const val COLLEAGUE_ARCHIVE_TAG = "colleague-archive"
internal fun colleagueRowTag(name: String) = "colleague-$name"
internal fun colleagueSchemeTag(k: PaySchemeKind) = "colleague-scheme-${k.name}"

/**
 * «Коллеги» (5.16, шаг 3.7г) — только с совместным ведением групп: коллеги по имени со схемой выплат на сегодня,
 * архивные — ниже, в «В архиве». Нажатие — форма коллеги.
 */
@Composable
internal fun ColleaguesScreen(settings: SettingsRepository, open: Set<Feature>, today: LocalDate, shell: ShellState, onBack: (() -> Unit)?) {
    val rows by remember(settings, today) { settings.colleagues.rows(today) }.collectAsState(initial = null)
    val loaded = rows ?: return
    val colors = PersonalkaTheme.colors
    val add = { shell.open(SettingsRoute.NEW_COLLEAGUE) }
    Column(Modifier.fillMaxSize()) {
        TopBar(CoCoachingFormat.COLLEAGUES, onBack = onBack)
        if (loaded.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = PIcons.Groups,
                    title = CoCoachingFormat.EMPTY,
                    text = CoCoachingFormat.EMPTY_TEXT,
                    actionLabel = CoCoachingFormat.NEW,
                    onAction = add,
                )
            }
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    CoCoachingFormat.count(loaded.count { !it.colleague.archived }),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                SecondaryButton(CoCoachingFormat.ADD, add, icon = PIcons.Add, modifier = Modifier.testTag(COLLEAGUE_ADD_TAG))
            }
            loaded.partition { !it.colleague.archived }.toList().forEachIndexed { part, list ->
                if (list.isEmpty()) return@forEachIndexed
                if (part == 1) FormLabelSpaced(CoCoachingFormat.ARCHIVED)
                ColleagueList(list, SalarySetupRules.payTypes(open)) { shell.open(SettingsRoute.colleague(it)) }
            }
            Spacer(Modifier.height(Dimens.space6))
        }
    }
}

@Composable
private fun ColleagueList(list: List<ColleagueRow>, payTypes: Boolean, onOpen: (String) -> Unit) {
    ListCard {
        list.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            PersonRow(
                title = row.colleague.name,
                // Без видов оплаты схемы коллеги нет и в строке (27.10.2026).
                subtitle = if (!payTypes) null else row.kind?.let { SalarySetupFormat.scheme(it) } ?: SalarySetupFormat.NOT_SET,
                onClick = { onOpen(row.colleague.id) },
                modifier = Modifier.testTag(colleagueRowTag(row.colleague.name)),
            )
        }
    }
}

/**
 * Коллега (5.16, 4.8): имя и схема выплат. Новый — схема с 1 января; у прежнего смена схемы — «С какого месяца»
 * (по умолчанию текущий), прошлые месяцы считаются по прежней. «Убрать в архив» — коллега пропадает из выбора,
 * прошлые группы и взаиморасчёт его помнят.
 */
@Composable
internal fun ColleagueEditor(settings: SettingsRepository, id: String?, open: Set<Feature>, today: LocalDate, messages: MessageState, scope: CoroutineScope, onBack: () -> Unit) {
    val rows by remember(settings, today) { settings.colleagues.rows(today) }.collectAsState(initial = null)
    val loaded = rows ?: return
    val row = id?.let { loaded.firstOrNull { r -> r.colleague.id == it } ?: return }
    val initialKind = row?.kind?.takeIf { it in CoCoachingRules.SCHEMES } ?: PaySchemeKind.NONE
    var name by rememberSaveable(id) { mutableStateOf(row?.colleague?.name.orEmpty()) }
    var kindName by rememberSaveable(id) { mutableStateOf(initialKind.name) }
    var monthText by rememberSaveable(id) { mutableStateOf(today.yearMonth.toString()) }
    var picking by rememberSaveable(id) { mutableStateOf(false) }
    var showErrors by rememberSaveable(id) { mutableStateOf(false) }
    val kind = PaySchemeKind.valueOf(kindName)
    val month = YearMonth.parse(monthText)
    val problem = CoCoachingRules.nameProblem(name, loaded.filter { it.colleague.id != id }.map { it.colleague.name })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(row?.colleague?.name ?: CoCoachingFormat.NEW, onBack = onBack, close = true)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Spacer(Modifier.height(Dimens.space2))
                Field(
                    label = CoCoachingFormat.NAME,
                    value = name,
                    onValueChange = { name = it },
                    error = problem.takeIf { showErrors },
                    hint = CoCoachingFormat.NAME_HINT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    tag = COLLEAGUE_NAME_TAG,
                )
                // Схема коллеги — только с открытыми видами оплаты (решение Дмитрия 27.10.2026): иначе её нет, а расчёт —
                // «100 % 10-го» у всех.
                if (SalarySetupRules.payTypes(open)) {
                    SectionLabel(CoCoachingFormat.SCHEME)
                    ListCard {
                        CoCoachingRules.SCHEMES.forEachIndexed { i, k ->
                            if (i > 0) RowDivider()
                            CheckRow(
                                SalarySetupFormat.scheme(k),
                                kind == k,
                                { kindName = k.name },
                                Modifier.padding(horizontal = Dimens.space4).testTag(colleagueSchemeTag(k)),
                                subtitle = SalarySetupFormat.schemeNote(k),
                            )
                        }
                    }
                    Hint(CoCoachingFormat.SCHEME_NOTE)
                    // Смена схемы у прежнего коллеги — с месяца: прошлые месяцы считаются по прежней.
                    if (row != null && kind != initialKind) {
                        SectionLabel(SalarySetupFormat.FROM_MONTH)
                        PickBox(RateFormat.month(month.firstDay), PIcons.CalendarMonth, { picking = true }, Modifier.fillMaxWidth().testTag(COLLEAGUE_FROM_TAG))
                        Hint(SalarySetupFormat.SCHEME_NOTE)
                    }
                }
                if (row != null) {
                    Spacer(Modifier.height(Dimens.space4))
                    val archived = row.colleague.archived
                    SecondaryButton(
                        if (archived) CoCoachingFormat.UNARCHIVE else CoCoachingFormat.ARCHIVE,
                        {
                            scope.launch { settings.colleagues.archive(row.colleague.id, !archived) }
                            onBack()
                            if (!archived) {
                                messages.showUndo("«${row.colleague.name}» в архиве") {
                                    scope.launch { settings.colleagues.archive(row.colleague.id, false) }
                                }
                            }
                        },
                        icon = PIcons.Archive,
                        modifier = Modifier.testTag(COLLEAGUE_ARCHIVE_TAG),
                    )
                }
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction("Сохранить", onClick = {
                if (problem != null) {
                    showErrors = true
                    return@BottomAction
                }
                scope.launch {
                    if (row == null) settings.colleagues.add(name, kind, today)
                    else settings.colleagues.update(row.colleague.id, name, kind, CoCoachingRules.changeFrom(month))
                }
                onBack()
                messages.show(if (row == null) "«${name.trim()}» — в коллегах" else "Сохранено")
            })
        }
        if (picking) DatePickerPanel(month.firstDay, { picking = false; monthText = it.yearMonth.toString() }, { picking = false })
    }
}
