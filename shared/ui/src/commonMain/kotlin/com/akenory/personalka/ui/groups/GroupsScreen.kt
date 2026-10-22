package com.akenory.personalka.ui.groups

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
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.groups.GroupRow
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.GroupFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.group.GroupField
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.ui.clients.FormLabel
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Экраны групп в стопке раздела «Ещё» (5.10): список — пункт «Группы», карточка и формы — поверх. */
object GroupRoute {
    const val LIST = "groups"
    const val NEW = "group/new"
    private const val PREFIX = "group/"

    fun card(id: String) = PREFIX + id
    fun edit(id: String) = "$PREFIX$id/edit"
    fun addMember(id: String) = "$PREFIX$id/add"
    fun member(id: String, memberId: String) = "$PREFIX$id/member/$memberId"

    fun isGroups(route: String): Boolean = route == LIST || route.startsWith(PREFIX)

    fun parse(route: String): GroupScreen? {
        if (route == LIST) return GroupScreen.List
        if (route == NEW) return GroupScreen.New
        if (!route.startsWith(PREFIX)) return null
        val parts = route.removePrefix(PREFIX).split('/')
        return when {
            parts.size == 1 -> GroupScreen.Card(parts[0])
            parts.size == 2 && parts[1] == "edit" -> GroupScreen.Edit(parts[0])
            parts.size == 2 && parts[1] == "add" -> GroupScreen.AddMember(parts[0])
            parts.size == 3 && parts[1] == "member" -> GroupScreen.Member(parts[0], parts[2])
            else -> null
        }
    }

    /** Форма с «✕» во весь экран: на сложенном Fold нижняя панель прячется. */
    fun isForm(route: String?): Boolean = route != null && parse(route).let {
        it == GroupScreen.New || it is GroupScreen.Edit || it is GroupScreen.AddMember || it is GroupScreen.Member
    }
}

sealed interface GroupScreen {
    data object List : GroupScreen
    data object New : GroupScreen
    data class Card(val groupId: String) : GroupScreen
    data class Edit(val groupId: String) : GroupScreen
    data class AddMember(val groupId: String) : GroupScreen
    data class Member(val groupId: String, val memberId: String) : GroupScreen
}

const val GROUP_ADD_TAG = "group-add"
const val GROUP_NAME_TAG = "group-name"
fun groupRowTag(name: String) = "group-row-$name"

/**
 * «Ещё → Группы» (5.10): список групп, карточка группы, форма группы и правка состава. Записи — в [scope]
 * приложения: экран закрывается сразу, запись и «Отменить» доживают до конца.
 */
@Composable
fun GroupsScreen(
    route: String,
    shell: ShellState,
    groups: GroupRepository,
    clients: ClientRepository,
    settings: SettingsRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onBack: (() -> Unit)?,
) {
    val today = now.date
    val rows by remember(groups, today) { groups.rows(today) }.collectAsState(initial = null)
    val people by remember(clients, now) { clients.rows(now) }.collectAsState(initial = null)
    val types by remember(settings) { settings.types }.collectAsState(initial = null)
    val places by remember(settings) { settings.places }.collectAsState(initial = null)
    val loaded = rows ?: return
    val kids = people ?: return
    val allTypes = types ?: return
    val allPlaces = places ?: return
    val close: () -> Unit = { shell.back() }

    when (val screen = GroupRoute.parse(route)) {
        null -> Unit
        GroupScreen.List -> GroupList(loaded, today, onOpen = { shell.open(GroupRoute.card(it)) }, onNew = { shell.open(GroupRoute.NEW) }, onBack = onBack)
        GroupScreen.New -> GroupEditor(
            title = "Новая группа",
            initial = null,
            others = loaded,
            types = allTypes.filter { !it.archived && it.kind == TrainingKind.GROUP },
            places = allPlaces.filter { !it.archived },
            onSave = { draft ->
                scope.launch {
                    val id = groups.create(draft)
                    shell.replaceTop(GroupRoute.card(id))
                }
            },
            onClose = close,
        )
        is GroupScreen.Edit -> {
            val row = loaded.firstOrNull { it.group.id == screen.groupId } ?: return
            GroupEditor(
                title = "Изменить",
                initial = GroupDraft(row.group.name, row.group.typeId, row.group.locationId),
                others = loaded.filter { it.group.id != row.group.id },
                // Архивный тип или место у группы остаются выбранными: история их помнит.
                types = allTypes.filter { (!it.archived && it.kind == TrainingKind.GROUP) || it.id == row.group.typeId },
                places = allPlaces.filter { !it.archived || it.id == row.group.locationId },
                onSave = { draft ->
                    scope.launch { groups.update(row.group.id, draft) }
                    close()
                },
                onClose = close,
            )
        }
        is GroupScreen.Card -> GroupCard(screen.groupId, loaded, kids, groups, shell, messages, scope, now, onBack)
        is GroupScreen.AddMember -> AddMemberForm(screen.groupId, loaded, kids, groups, clients, scope, today, close)
        is GroupScreen.Member -> MemberForm(screen.groupId, screen.memberId, loaded, kids, groups, messages, scope, today, close)
    }
}

/**
 * Список групп (5.10): название, дни и время, место, сколько детей сейчас. Группы, из которых все ушли
 * и у которых нет расписания, — внизу, в «Закончились». «Числится на» — с совместным ведением (этап 3).
 */
@Composable
private fun GroupList(rows: List<GroupRow>, today: LocalDate, onOpen: (String) -> Unit, onNew: () -> Unit, onBack: (() -> Unit)?) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Группы", onBack = onBack)
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = PIcons.Groups,
                    title = "Групп пока нет",
                    text = "Добавьте группу — состав с датами ведётся в её карточке.",
                    actionLabel = "Добавить группу",
                    onAction = onNew,
                )
            }
            return@Column
        }
        val ended: (GroupRow) -> Boolean = { row ->
            row.members.isNotEmpty() && row.schedule.isEmpty() && row.members.none { (it.toDate ?: today) >= today }
        }
        val sorted = rows.sortedWith(
            compareBy<GroupRow>(
                { row -> row.schedule.minOfOrNull { line -> DateFormat.WEEK.indexOfFirst { it in line.weekdays } } ?: DateFormat.WEEK.size },
                { row -> row.schedule.minOfOrNull { it.start.hour * 60 + it.start.minute } ?: Int.MAX_VALUE },
                { row -> ClientRules.key(row.group.name) },
            ),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    GroupFormat.groups(rows.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                SecondaryButton("Группа", onNew, icon = PIcons.Add, modifier = Modifier.testTag(GROUP_ADD_TAG))
            }
            listOf(sorted.filterNot(ended), sorted.filter(ended)).forEachIndexed { part, list ->
                if (list.isEmpty()) return@forEachIndexed
                if (part == 1) FormLabelSpaced("Закончились")
                ListCard {
                    list.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        PersonRow(
                            title = row.group.name,
                            subtitle = GroupFormat.subtitle(row.schedule, row.place?.shortName, row.membersOn(today).size),
                            onClick = { onOpen(row.group.id) },
                            modifier = Modifier.testTag(groupRowTag(row.group.name)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Dimens.space6))
        }
    }
}

@Composable
internal fun FormLabelSpaced(text: String) {
    Spacer(Modifier.height(Dimens.space4))
    FormLabel(text)
}

/**
 * Группа (5.10): название, тип вида «группа» и место. По умолчанию — первый такой тип и первое место.
 * Нет ни одного типа-группы — подсказка, где его завести.
 */
@Composable
private fun GroupEditor(
    title: String,
    initial: GroupDraft?,
    others: List<GroupRow>,
    types: List<TrainingTypeEntity>,
    places: List<LocationEntity>,
    onSave: (GroupDraft) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var typeId by rememberSaveable { mutableStateOf(initial?.typeId ?: types.firstOrNull()?.id) }
    var placeId by rememberSaveable { mutableStateOf(initial?.locationId ?: places.firstOrNull()?.id) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val draft = GroupDraft(name, typeId, placeId)
    val problems = GroupRules.problems(draft, others.map { it.group.name })
    val shown = if (showErrors) problems else emptyMap()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
        TopBar(title, onBack = onClose, close = true)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Spacer(Modifier.height(Dimens.space1))
            Field(
                label = "Название",
                value = name,
                onValueChange = { name = it },
                error = shown[GroupField.NAME],
                hint = "Например, Сб 9:00 ББ",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                tag = GROUP_NAME_TAG,
            )
            Column {
                FormLabel("Тип")
                if (types.isEmpty()) {
                    Note("Нет типа, который работает как группа. Добавьте его: «Ещё → Настройки → Места и типы».")
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        types.forEach { type -> ChoiceChip(type.name, typeId == type.id, onClick = { typeId = type.id }) }
                    }
                }
                shown[GroupField.TYPE]?.let { ErrorLine(it) }
            }
            Column {
                FormLabel("Место")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    places.forEach { place -> ChoiceChip(place.name, placeId == place.id, onClick = { placeId = place.id }) }
                }
                shown[GroupField.PLACE]?.let { ErrorLine(it) }
            }
            Text(
                "Расписание группы — серия в календаре; состав с датами — в карточке группы.",
                style = MaterialTheme.typography.bodySmall,
                color = PersonalkaTheme.colors.textMuted,
            )
        }
        BottomAction("Сохранить", onClick = { if (problems.isEmpty()) onSave(draft) else showErrors = true })
    }
}

@Composable
internal fun ErrorLine(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = PersonalkaTheme.colors.error,
    )
}
