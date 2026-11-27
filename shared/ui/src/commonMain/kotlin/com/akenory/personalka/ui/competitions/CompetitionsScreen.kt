package com.akenory.personalka.ui.competitions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.competitions.CompetitionRow
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.competition.Competition
import com.akenory.personalka.domain.competition.CompetitionDraft
import com.akenory.personalka.domain.competition.CompetitionField
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.ItemInput
import com.akenory.personalka.domain.competition.RuleInput
import com.akenory.personalka.domain.format.CompetitionFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.ui.clients.FormLabel
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PlaceBadge
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.groups.ErrorLine
import com.akenory.personalka.ui.groups.FormLabelSpaced
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Экраны соревнований в стопке раздела «Ещё» (5.21): список — пункт «Соревнования», карточка, форма и подбор — поверх. */
object CompetitionRoute {
    const val LIST = "competitions"
    const val NEW = "competition/new"
    private const val PREFIX = "competition/"

    fun card(id: String) = PREFIX + id
    fun edit(id: String) = "$PREFIX$id/edit"
    fun pick(id: String) = "$PREFIX$id/pick"

    fun isCompetitions(route: String): Boolean = route == LIST || route.startsWith(PREFIX)

    fun parse(route: String): CompetitionScreen? {
        if (route == LIST) return CompetitionScreen.List
        if (route == NEW) return CompetitionScreen.New
        if (!route.startsWith(PREFIX)) return null
        val parts = route.removePrefix(PREFIX).split('/')
        return when {
            parts.size == 1 -> CompetitionScreen.Card(parts[0])
            parts.size == 2 && parts[1] == "edit" -> CompetitionScreen.Edit(parts[0])
            parts.size == 2 && parts[1] == "pick" -> CompetitionScreen.Pick(parts[0])
            else -> null
        }
    }

    /** Форма и подбор — во весь экран с «✕»: на сложенном Fold нижняя панель прячется. */
    fun isForm(route: String?): Boolean = route != null && parse(route).let {
        it == CompetitionScreen.New || it is CompetitionScreen.Edit || it is CompetitionScreen.Pick
    }
}

sealed interface CompetitionScreen {
    data object List : CompetitionScreen
    data object New : CompetitionScreen
    data class Card(val id: String) : CompetitionScreen
    data class Edit(val id: String) : CompetitionScreen
    data class Pick(val id: String) : CompetitionScreen
}

const val COMPETITION_ADD_TAG = "competition-add"
const val COMPETITION_NAME_TAG = "competition-name"
const val COMPETITION_PLACE_TAG = "competition-place"
const val COMPETITION_DATE_TAG = "competition-date"
const val COMPETITION_RULE_ADD_TAG = "competition-rule-add"
const val COMPETITION_DELETE_TAG = "competition-delete"
const val COMPETITION_DELETE_CONFIRM_TAG = "competition-delete-confirm"
fun competitionRowTag(name: String) = "competition-row-$name"
fun ruleFromTag(index: Int) = "competition-rule-from-$index"
fun ruleToTag(index: Int) = "competition-rule-to-$index"
fun ruleSexTag(index: Int, sex: Sex?) = "competition-rule-$index-${sex?.name ?: "ANY"}"
fun ruleRemoveTag(index: Int) = "competition-rule-remove-$index"
fun itemPresetTag(title: String) = "competition-item-preset-$title"
fun itemTitleTag(index: Int) = "competition-item-title-$index"
fun itemAmountTag(index: Int) = "competition-item-amount-$index"

/**
 * «Ещё → Соревнования» (5.21): список, форма, карточка соревнования и подбор участников. Записи — в [scope]
 * приложения: экран закрывается сразу, запись и «Отменить» доживают до конца.
 *
 * @param shareText системное «Поделиться» текстом: заявка, кто не сдал, итоги; `null` — кнопки нет.
 */
@Composable
fun CompetitionsScreen(
    route: String,
    shell: ShellState,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onBack: (() -> Unit)?,
    shareText: ((String) -> Unit)? = null,
) {
    val repo = clients.competitions
    val rows by remember(repo) { repo.rows }.collectAsState(initial = null)
    val loaded = rows ?: return
    val today = now.date
    val close: () -> Unit = { shell.back() }

    when (val screen = CompetitionRoute.parse(route)) {
        null -> Unit
        CompetitionScreen.List -> CompetitionList(loaded, today, onOpen = { shell.open(CompetitionRoute.card(it)) }, onNew = { shell.open(CompetitionRoute.NEW) }, onBack = onBack)
        CompetitionScreen.New -> CompetitionEditor(
            title = CompetitionFormat.NEW_TITLE,
            initial = null,
            today = today,
            now = now,
            onSave = { draft ->
                scope.launch {
                    val id = repo.create(draft)
                    shell.replaceTop(CompetitionRoute.card(id))
                }
            },
            onDelete = null,
            onClose = close,
        )
        is CompetitionScreen.Edit -> {
            val row = loaded.firstOrNull { it.competition.id == screen.id } ?: return
            CompetitionEditor(
                title = CompetitionFormat.EDIT_TITLE,
                initial = row.competition,
                today = today,
                now = now,
                onSave = { draft ->
                    scope.launch { repo.update(screen.id, draft) }
                    close()
                },
                onDelete = {
                    shell.reset(CompetitionRoute.LIST)
                    scope.launch {
                        val undo = repo.delete(screen.id) ?: return@launch
                        messages.showUndo(CompetitionFormat.DELETED) { scope.launch { repo.undoDelete(undo) } }
                    }
                },
                onClose = close,
            )
        }
        is CompetitionScreen.Card -> CompetitionCard(screen.id, loaded, repo, shell, messages, scope, today, onBack, shareText)
        is CompetitionScreen.Pick -> CompetitionPick(screen.id, loaded, clients, messages, scope, now, close)
    }
}

/** Список (5.21): «Впереди» — от ближайших, «Прошли» — от новых; строка — «сб 12.12 · 12 участников · не всё сдали 4». */
@Composable
private fun CompetitionList(rows: List<CompetitionRow>, today: LocalDate, onOpen: (String) -> Unit, onNew: () -> Unit, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        TopBar(CompetitionFormat.TITLE, onBack = onBack)
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = PIcons.Trophy,
                    title = CompetitionFormat.EMPTY_TITLE,
                    text = CompetitionFormat.EMPTY_TEXT,
                    actionLabel = CompetitionFormat.EMPTY_ACTION,
                    onAction = onNew,
                )
            }
            return@Column
        }
        val byId = rows.associateBy { it.competition.id }
        val all = rows.map { it.competition }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                SecondaryButton(CompetitionFormat.ADD, onNew, icon = PIcons.Add, modifier = Modifier.testTag(COMPETITION_ADD_TAG))
            }
            listOf(
                CompetitionFormat.UPCOMING to CompetitionRules.upcoming(all, today),
                CompetitionFormat.PAST to CompetitionRules.past(all, today),
            ).forEach { (title, list) ->
                if (list.isEmpty()) return@forEach
                FormLabelSpaced(title)
                ListCard {
                    list.forEachIndexed { index, c ->
                        if (index > 0) RowDivider()
                        PersonRow(
                            title = c.name,
                            subtitle = CompetitionFormat.listLine(c, byId.getValue(c.id).participants, today),
                            onClick = { onOpen(c.id) },
                            modifier = Modifier.testTag(competitionRowTag(c.name)),
                            avatar = { PlaceBadge(null, icon = PIcons.Trophy) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Dimens.space6))
        }
    }
}

// Строки формы хранятся одной строкой текста — так их помнит rememberSaveable.
private const val FIELD = '\u001F'
private const val ROW = '\u001E'

private fun rulesText(list: List<RuleInput>) = list.joinToString(ROW.toString()) { listOf(it.sex?.name.orEmpty(), it.from, it.to).joinToString(FIELD.toString()) }

private fun rulesOf(text: String): List<RuleInput> = text.split(ROW).filter { it.isNotEmpty() }.map { line ->
    val bits = line.split(FIELD)
    RuleInput(bits[0].takeIf { it.isNotEmpty() }?.let(Sex::valueOf), bits.getOrElse(1) { "" }, bits.getOrElse(2) { "" })
}

private fun itemsText(list: List<ItemInput>) = list.joinToString(ROW.toString()) { listOf(it.id, it.title, it.amount).joinToString(FIELD.toString()) }

private fun itemsOf(text: String): List<ItemInput> = text.split(ROW).filter { it.isNotEmpty() }.map { line ->
    val bits = line.split(FIELD)
    ItemInput(bits[0], bits.getOrElse(1) { "" }, bits.getOrElse(2) { "" })
}

/** Пол новой строки условия: первая — мальчики, после мальчиков — девочки, дальше — все. */
private fun nextSex(rules: List<RuleInput>): Sex? = when {
    rules.isEmpty() -> Sex.MALE
    rules.last().sex == Sex.MALE -> Sex.FEMALE
    else -> null
}

/**
 * Форма соревнования (5.21): название, дата, бассейн, место, «Кто допускается» (пол и годы рождения) и «Что собрать»
 * (пункты с суммами). [onDelete] — «Удалить соревнование» с окном подтверждения; у нового — `null`.
 */
@Composable
private fun CompetitionEditor(
    title: String,
    initial: Competition?,
    today: LocalDate,
    now: LocalDateTime,
    onSave: (CompetitionDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var day by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var pool by rememberSaveable { mutableStateOf(initial?.poolM ?: CompetitionRules.DEFAULT_POOL) }
    var place by rememberSaveable { mutableStateOf(initial?.place.orEmpty()) }
    var rulesSaved by rememberSaveable {
        mutableStateOf(rulesText(initial?.rules.orEmpty().map { RuleInput(it.sex, it.yearFrom.toString(), it.yearTo.toString()) }))
    }
    var itemsSaved by rememberSaveable {
        mutableStateOf(itemsText(initial?.items.orEmpty().map { ItemInput(it.id, it.title, RateRules.moneyText(it.amount)) }))
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val rules = rulesOf(rulesSaved)
    val items = itemsOf(itemsSaved)
    val date = LocalDate.parse(day)
    val problems = CompetitionRules.problems(name, rules, items)
    val shown = if (showErrors) problems else emptyMap()
    val setRule: (Int, RuleInput) -> Unit = { i, r -> rulesSaved = rulesText(rules.toMutableList().also { it[i] = r }) }
    val setItem: (Int, ItemInput) -> Unit = { i, item -> itemsSaved = itemsText(items.toMutableList().also { it[i] = item }) }
    val newItemId = { Ids.newId(ClubTime.toMillis(now)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(title, onBack = onClose, close = true)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Spacer(Modifier.height(Dimens.space1))
                Field(
                    label = CompetitionFormat.NAME,
                    value = name,
                    onValueChange = { name = it },
                    error = shown[CompetitionField.NAME],
                    hint = CompetitionFormat.NAME_HINT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    tag = COMPETITION_NAME_TAG,
                )
                Column {
                    FormLabel(CompetitionFormat.DATE)
                    PickBox(
                        text = CompetitionFormat.dateLong(date),
                        icon = PIcons.CalendarMonth,
                        onClick = { picking = true },
                        modifier = Modifier.fillMaxWidth().testTag(COMPETITION_DATE_TAG),
                    )
                }
                Column {
                    FormLabel(CompetitionFormat.POOL)
                    Segmented(ResultRules.POOLS, pool, { ResultFormat.meters(it) }, { pool = it })
                }
                Field(
                    label = CompetitionFormat.PLACE,
                    value = place,
                    onValueChange = { place = it },
                    placeholder = CompetitionFormat.PLACE_HINT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    tag = COMPETITION_PLACE_TAG,
                )
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    FormLabel(CompetitionFormat.RULES_TITLE)
                    rules.forEachIndexed { i, rule ->
                        RuleEditor(i, rule, { setRule(i, it) }, { rulesSaved = rulesText(rules.filterIndexed { j, _ -> j != i }) })
                    }
                    shown[CompetitionField.RULES]?.let { ErrorLine(it) }
                    if (rules.isEmpty()) {
                        Text(CompetitionFormat.RULES_NOTE, style = MaterialTheme.typography.bodySmall, color = PersonalkaTheme.colors.textMuted)
                    }
                    Row {
                        SecondaryButton(
                            CompetitionFormat.RULE_ADD,
                            { rulesSaved = rulesText(rules + RuleInput(nextSex(rules), "", "")) },
                            icon = PIcons.Add,
                            modifier = Modifier.testTag(COMPETITION_RULE_ADD_TAG),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    FormLabel(CompetitionFormat.ITEMS_TITLE)
                    items.forEachIndexed { i, item ->
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                            Field(
                                CompetitionFormat.ITEM_TITLE,
                                item.title,
                                { setItem(i, item.copy(title = it)) },
                                Modifier.weight(1.6f),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                                tag = itemTitleTag(i),
                            )
                            Field(
                                CompetitionFormat.ITEM_AMOUNT,
                                item.amount,
                                { setItem(i, item.copy(amount = it.take(12))) },
                                Modifier.weight(1f),
                                placeholder = "—",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                tag = itemAmountTag(i),
                            )
                            IconButton(onClick = { itemsSaved = itemsText(items.filterIndexed { j, _ -> j != i }) }) {
                                Icon(PIcons.Close, contentDescription = "Убрать пункт", tint = PersonalkaTheme.colors.textMuted)
                            }
                        }
                    }
                    shown[CompetitionField.ITEMS]?.let { ErrorLine(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        CompetitionFormat.ITEM_PRESETS.filter { preset -> items.none { it.title.trim().equals(preset, ignoreCase = true) } }.forEach { preset ->
                            ChoiceChip(
                                preset,
                                false,
                                onClick = { itemsSaved = itemsText(items + ItemInput(newItemId(), preset, "")) },
                                icon = PIcons.Add,
                                modifier = Modifier.testTag(itemPresetTag(preset)),
                            )
                        }
                        ChoiceChip(
                            CompetitionFormat.ITEM_OWN,
                            false,
                            onClick = { itemsSaved = itemsText(items + ItemInput(newItemId(), "", "")) },
                            icon = PIcons.Add,
                            modifier = Modifier.testTag(itemPresetTag(CompetitionFormat.ITEM_OWN)),
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(COMPETITION_DELETE_TAG),
                    ) {
                        Text(CompetitionFormat.DELETE, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
                    }
                }
                Spacer(Modifier.height(Dimens.space2))
            }
            BottomAction(CompetitionFormat.SAVE, onClick = {
                val draft = CompetitionRules.draft(name, date, pool, place, rules, items)
                if (draft != null) onSave(draft) else showErrors = true
            })
        }
        if (picking) {
            DatePickerPanel(
                initial = date,
                onPick = {
                    picking = false
                    day = it.toString()
                },
                onDismiss = { picking = false },
            )
        }
        if (confirmDelete && onDelete != null) {
            Panel(CompetitionFormat.DELETE_TITLE, onDismiss = { confirmDelete = false }) {
                Text(CompetitionFormat.DELETE_TEXT, style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
                Column(Modifier.fillMaxWidth().padding(top = Dimens.space2)) {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(COMPETITION_DELETE_CONFIRM_TAG),
                    ) {
                        Text(CompetitionFormat.DELETE_YES, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.error)
                    }
                    TextButton(onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                        Text(CompetitionFormat.CANCEL, style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
                    }
                }
            }
        }
    }
}

/** Строка условия: пол чипами и годы «с» и «по»; «✕» убирает строку. */
@Composable
private fun RuleEditor(index: Int, rule: RuleInput, onChange: (RuleInput) -> Unit, onRemove: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                listOf(Sex.MALE, Sex.FEMALE, null).forEach { sex ->
                    ChoiceChip(
                        CompetitionFormat.ruleSex(sex),
                        rule.sex == sex,
                        onClick = { onChange(rule.copy(sex = sex)) },
                        modifier = Modifier.testTag(ruleSexTag(index, sex)),
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag(ruleRemoveTag(index))) {
                Icon(PIcons.Close, contentDescription = "Убрать условие", tint = PersonalkaTheme.colors.textMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            val digits = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            Field(
                CompetitionFormat.YEAR_FROM,
                rule.from,
                { text -> onChange(rule.copy(from = text.filter(Char::isDigit).take(4))) },
                Modifier.weight(1f),
                placeholder = "2010",
                keyboardOptions = digits,
                tag = ruleFromTag(index),
            )
            Field(
                CompetitionFormat.YEAR_TO,
                rule.to,
                { text -> onChange(rule.copy(to = text.filter(Char::isDigit).take(4))) },
                Modifier.weight(1f),
                placeholder = "2015",
                keyboardOptions = digits,
                tag = ruleToTag(index),
            )
        }
    }
}
