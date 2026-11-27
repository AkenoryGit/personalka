package com.akenory.personalka.ui.competitions

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.competitions.CompetitionRow
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.competition.Candidate
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.CompetitionFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.clients.SearchField
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.groups.FormLabelSpaced
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

const val PICK_SEARCH_TAG = "competition-pick-search"
const val PICK_ARCHIVED_TAG = "competition-pick-archived"
const val PICK_ADD_TAG = "competition-pick-add"
const val PICK_UNKNOWN_TAG = "competition-pick-unknown"
const val BIRTH_FIELD_TAG = "competition-birth"
const val BIRTH_GUESS_TAG = "competition-birth-guess"
fun pickRowTag(name: String) = "competition-pick-$name"
fun pickUnknownRowTag(name: String) = "competition-pick-unknown-$name"
fun birthSexTag(sex: Sex) = "competition-birth-${sex.name}"

/**
 * Подбор участников (5.21): клиенты, подходящие под «Кто допускается», — разделами по условиям, с флажками; уже
 * участвующие отмечены и не снимаются. «Архивные» — переключатель, поиск — без условий. Внизу — «Не указан год или
 * пол»: нажатие открывает окно «Год и пол», оно пишет в карточку клиента. «Добавить N» — отмеченные новые.
 */
@Composable
internal fun CompetitionPick(
    id: String,
    rows: List<CompetitionRow>,
    clients: ClientRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    close: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val row = rows.firstOrNull { it.competition.id == id } ?: return
    val people by remember(clients, now) { clients.rows(now) }.collectAsState(initial = null)
    var archived by rememberSaveable(id) { mutableStateOf(false) }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var checkedText by rememberSaveable(id) { mutableStateOf("") }
    var unknownOpen by rememberSaveable(id) { mutableStateOf(false) }
    var editing by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val loaded = people ?: return

    val checked = checkedText.split(' ').filter { it.isNotEmpty() }.toSet()
    val inList = row.participants.map { it.clientId }.toSet()
    val byId = loaded.associateBy { it.client.id }
    val candidates = loaded.map { r ->
        Candidate(r.client.id, r.client.name(), r.client.birthDate, r.client.sex, r.client.status == ClientStatus.ARCHIVED)
    }
    val pick = CompetitionRules.pick(row.competition.rules, candidates, archived, query)
    val fresh = checked - inList
    val toggle: (String) -> Unit = { clientId ->
        checkedText = (if (clientId in checked) checked - clientId else checked + clientId).joinToString(" ")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(CompetitionFormat.PICK_TITLE, onBack = close, close = true)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Text(row.competition.name, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                CompetitionFormat.rules(row.competition.rules)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
                Spacer(Modifier.height(Dimens.space3))
                SearchField(query, { query = it }, CompetitionFormat.SEARCH, Modifier.testTag(PICK_SEARCH_TAG))
                SwitchRow(CompetitionFormat.ARCHIVED, archived, { archived = it }, Modifier.testTag(PICK_ARCHIVED_TAG))
                pick.sections.forEach { section ->
                    FormLabelSpaced(CompetitionFormat.section(section.rule, section.people.size, search = query.isNotBlank()))
                    if (section.people.isEmpty()) {
                        Text(CompetitionFormat.NOBODY, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                    } else {
                        ListCard {
                            section.people.forEachIndexed { index, person ->
                                if (index > 0) RowDivider()
                                PickRow(person, byId[person.id]?.schedule.orEmpty(), person.id in inList, person.id in checked) { toggle(person.id) }
                            }
                        }
                    }
                }
                if (pick.unknown.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.space4)
                            .clickable(role = Role.Button) { unknownOpen = !unknownOpen }
                            .heightIn(min = Dimens.touch)
                            .testTag(PICK_UNKNOWN_TAG),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(CompetitionFormat.unknown(pick.unknown.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                        Icon(if (unknownOpen) PIcons.ExpandLess else PIcons.ExpandMore, contentDescription = null, tint = colors.textMuted)
                    }
                    if (unknownOpen) {
                        Note(CompetitionFormat.UNKNOWN_NOTE)
                        Spacer(Modifier.height(Dimens.space2))
                        ListCard {
                            pick.unknown.forEachIndexed { index, person ->
                                if (index > 0) RowDivider()
                                PersonRow(
                                    title = person.name.short,
                                    subtitle = listOfNotNull(
                                        CompetitionFormat.lacks(person.birthDate != null),
                                        CompetitionFormat.IN_ARCHIVE.takeIf { person.archived },
                                    ).joinToString(" · "),
                                    onClick = { editing = person.id },
                                    modifier = Modifier.testTag(pickUnknownRowTag(person.name.short)),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                CompetitionFormat.addButton(fresh.size),
                onClick = {
                    val ids = fresh.toList()
                    close()
                    scope.launch {
                        val added = clients.competitions.add(id, ids)
                        if (added > 0) messages.show(CompetitionFormat.added(added))
                    }
                },
                modifier = Modifier.testTag(PICK_ADD_TAG),
                enabled = fresh.isNotEmpty(),
            )
        }
        val client = editing?.let { byId[it]?.client }
        if (client != null) {
            BirthSexPanel(client, now.date, onSave = { birth, sex ->
                editing = null
                scope.launch { clients.saveBirthAndSex(client.id, birth, sex) }
            }, onDismiss = { editing = null })
        }
    }
}

/** Строка подбора: флажок, «Фамилия Имя, год», под ней — «уже в списке» / «в архиве» и расписание клиента. */
@Composable
private fun PickRow(person: Candidate, schedule: List<ScheduleLine>, inList: Boolean, checked: Boolean, onToggle: () -> Unit) {
    val colors = PersonalkaTheme.colors
    val subtitle = listOfNotNull(
        CompetitionFormat.IN_LIST.takeIf { inList },
        CompetitionFormat.IN_ARCHIVE.takeIf { person.archived },
        ClientFormat.schedule(schedule),
    ).joinToString(" · ").ifEmpty { null }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked || inList, enabled = !inList, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(pickRowTag(person.name.short))
            .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked || inList)
        Spacer(Modifier.width(Dimens.space4))
        Column(Modifier.weight(1f)) {
            Text(
                CompetitionFormat.person(person.name, person.birthDate?.year),
                style = MaterialTheme.typography.bodyLarge,
                color = if (person.archived || inList) colors.textMuted else colors.text,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

/**
 * Окно «Год и пол» (5.21): дата рождения и «Мальчик / Девочка». Пол, предложенный по отчеству или фамилии (4.17),
 * выбран заранее с подписью; сохраняется только по «Сохранить» — в карточку клиента.
 */
@Composable
private fun BirthSexPanel(client: ClientEntity, today: LocalDate, onSave: (LocalDate?, Sex?) -> Unit, onDismiss: () -> Unit) {
    val guess = remember(client.id) { ClientRules.guessSex(client.middleName, client.lastName) }
    var birth by rememberSaveable(client.id) { mutableStateOf(client.birthDate?.let(DateFormat::full).orEmpty()) }
    var sexName by rememberSaveable(client.id) { mutableStateOf((client.sex ?: guess?.sex)?.name) }
    val parsed = DateFormat.parseFull(birth)?.takeIf { it <= today }
    val sex = sexName?.let(Sex::valueOf)
    val birthOk = birth.isBlank() || parsed != null

    Panel(CompetitionFormat.BIRTH_TITLE, onDismiss = onDismiss) {
        Text(client.name().short, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
        Field(
            label = CompetitionFormat.BIRTH,
            value = birth,
            onValueChange = { birth = it.take(12) },
            modifier = Modifier.fillMaxWidth(),
            error = if (birthOk) null else CompetitionFormat.BIRTH_BAD,
            hint = parsed?.let { ClientFormat.ageHint(it, today) } ?: CompetitionFormat.BIRTH_HINT,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            tag = BIRTH_FIELD_TAG,
        )
        SectionLabel(CompetitionFormat.SEX)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            listOf(Sex.MALE to CompetitionFormat.BOY, Sex.FEMALE to CompetitionFormat.GIRL).forEach { (option, label) ->
                ChoiceChip(label, sex == option, onClick = { sexName = option.name }, modifier = Modifier.testTag(birthSexTag(option)))
            }
        }
        if (client.sex == null && guess != null && sex == guess.sex) {
            Text(
                if (guess.byMiddleName) CompetitionFormat.GUESS_MIDDLE else CompetitionFormat.GUESS_LAST,
                Modifier.testTag(BIRTH_GUESS_TAG),
                style = MaterialTheme.typography.bodySmall,
                color = PersonalkaTheme.colors.textMuted,
            )
        }
        PanelActions(
            primary = CompetitionFormat.SAVE,
            onPrimary = { onSave(parsed, sex) },
            secondary = CompetitionFormat.CANCEL,
            onSecondary = onDismiss,
            primaryEnabled = birthOk,
        )
    }
}

private fun ClientEntity.name() = ClientName(lastName, firstName, middleName)
