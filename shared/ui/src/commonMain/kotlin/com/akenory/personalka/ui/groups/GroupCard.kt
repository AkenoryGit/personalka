package com.akenory.personalka.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.data.clients.adultOn
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.groups.GroupRow
import com.akenory.personalka.data.groups.GroupTrainingRow
import com.akenory.personalka.data.groups.sharing
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.CoCoachingFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.GroupFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.group.MemberField
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.clients.FormLabel
import com.akenory.personalka.ui.clients.MenuAction
import com.akenory.personalka.ui.clients.PersonRow
import com.akenory.personalka.ui.clients.SearchField
import com.akenory.personalka.ui.clients.SimilarClientPanel
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

const val GROUP_MENU_TAG = "group-menu"
const val GROUP_SHARING_TAG = "group-sharing"
const val MEMBER_ADD_TAG = "member-add"
const val MEMBER_SEARCH_TAG = "member-search"
const val MEMBER_DATE_TAG = "member-date"
const val MEMBER_FROM_TAG = "member-from"
const val MEMBER_NOT_FROM_TAG = "member-not-from"
const val NEW_MEMBER_TAG = "member-new"
const val NEW_MEMBER_LAST_TAG = "member-new-last"
const val NEW_MEMBER_FIRST_TAG = "member-new-first"
fun memberRowTag(name: String) = "member-$name"
fun memberPickTag(name: String) = "member-pick-$name"

/** Сколько ближайших тренировок группы видно в карточке. */
private const val UPCOMING = 4

/** Сколько прошедших тренировок видно в истории — дальше история уходит в календарь. */
private const val HISTORY = 30

private fun GroupMemberEntity.member() = GroupMember(clientId, fromDate, toDate)

private fun ClientRow?.nameOrGone(): String = this?.let { "${it.client.lastName} ${it.client.firstName}" } ?: "Клиент удалён"

/**
 * Карточка группы (5.10): тип, место и расписание; состав сейчас и с будущей даты — нажатие правит даты места,
 * «+ В состав» добавляет ребёнка с даты; ушедшие — отдельно; ближайшие и прошедшие тренировки с числом пришедших
 * и детьми «на раз», средняя посещаемость. «Числится на» и «Кто обычно ведёт» — с совместным ведением ([colleagues] —
 * имена коллег, `null` — выключено; шаг 3.7г).
 */
@Composable
fun GroupCard(
    groupId: String,
    rows: List<GroupRow>,
    kids: List<ClientRow>,
    groups: GroupRepository,
    shell: ShellState,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onBack: (() -> Unit)?,
    colleagues: Map<String, String>? = null,
) {
    val colors = PersonalkaTheme.colors
    val today = now.date
    val row = rows.firstOrNull { it.group.id == groupId }
    val history by remember(groups, groupId) { groups.trainings(groupId) }.collectAsState(initial = null)
    val deletable by produceState(initialValue = false, groups, groupId, history) { value = groups.canDelete(groupId) }
    var confirmDelete by rememberSaveable(groupId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    if (row == null) {
        TopBar("", onBack = onBack)
        return
    }
    val byId = kids.associateBy { it.client.id }
    val members = row.members.map { it.member() }
    val current = row.members.filter { (it.toDate ?: today) >= today }
        .sortedWith(compareBy<GroupMemberEntity>({ it.fromDate > today }, { ClientRules.key(byId[it.clientId].nameOrGone()) }))
    val currentIds = current.map { it.clientId }.toSet()
    val left = row.members.filter { (it.toDate ?: today) < today && it.clientId !in currentIds }
        .sortedByDescending { it.toDate }
    val trainings = history.orEmpty()
    val done = trainings.filter { it.training.status == TrainingStatus.DONE }
    val attended = done.map { t -> t.participations.count { it.status == ParticipationStatus.ATTENDED } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text) }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag(GROUP_MENU_TAG)) {
                        Icon(PIcons.MoreVert, contentDescription = "Ещё действия", tint = colors.text)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                        MenuAction("Изменить", PIcons.Edit, {
                            menu = false
                            shell.open(GroupRoute.edit(groupId))
                        })
                        // Группу с тренировками или серией не удалить: история её помнит.
                        if (deletable) {
                            MenuAction("Удалить группу", PIcons.Delete, {
                                menu = false
                                confirmDelete = true
                            }, danger = true)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Text(row.group.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Spacer(Modifier.height(Dimens.space1))
                val days = row.schedule.joinToString("; ") { ClientFormat.daysTime(it) }.ifEmpty { "без расписания" }
                Text(
                    listOfNotNull(row.type?.name, row.place?.name, days).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                if (colleagues != null) {
                    val sharing = row.group.sharing()
                    Text(
                        CoCoachingFormat.ownerLine(sharing, colleagues) + " · " +
                            CoCoachingFormat.conductedLine(sharing, colleagues, prefix = CoCoachingFormat.DEFAULT_CONDUCTED + ": "),
                        Modifier.testTag(GROUP_SHARING_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }

                if (done.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = Dimens.space3).fillMaxWidth().background(colors.surface, MaterialTheme.shapes.medium)
                            .padding(vertical = Dimens.space3),
                    ) {
                        listOf(
                            RuText.digits(done.size.toLong()) to "проведено",
                            ClientFormat.tenths(GroupRules.averageTenths(attended)) to "в среднем пришли",
                            RuText.digits(current.count { it.fromDate <= today }.toLong()) to "в составе",
                        ).forEach { (value, label) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value, style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text)
                                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = Dimens.space5, bottom = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Состав · ${current.count { it.fromDate <= today }}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted,
                    )
                    SecondaryButton("В состав", { shell.open(GroupRoute.addMember(groupId)) }, icon = PIcons.Add, modifier = Modifier.testTag(MEMBER_ADD_TAG))
                }
                if (current.isEmpty()) {
                    Text("В группе пока никого", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
                } else {
                    MemberList(current, byId, today) { shell.open(GroupRoute.member(groupId, it.id)) }
                }
                if (left.isNotEmpty()) {
                    FormLabelSpaced("Ушли · ${left.size}")
                    MemberList(left, byId, today) { shell.open(GroupRoute.member(groupId, it.id)) }
                }

                val upcoming = trainings.filter { it.training.status == TrainingStatus.PLANNED && it.training.start >= now }
                    .sortedBy { it.training.start }.take(UPCOMING)
                val past = trainings.filter { it !in upcoming && it.training.start < now }
                    .sortedByDescending { it.training.start }.take(HISTORY)
                if (upcoming.isNotEmpty()) {
                    FormLabelSpaced("Ближайшие")
                    TrainingList(upcoming, members, now)
                }
                if (past.isNotEmpty()) {
                    FormLabelSpaced("Прошедшие")
                    TrainingList(past, members, now)
                }
                Spacer(Modifier.height(Dimens.space6))
            }
        }

        if (confirmDelete) {
            Panel("Удалить группу?", onDismiss = { confirmDelete = false }) {
                Text(
                    "«${row.group.name}» и её состав удалятся. Тренировок у группы ещё не было — история ничего не потеряет.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                PanelActions(
                    primary = "Удалить",
                    onPrimary = {
                        confirmDelete = false
                        scope.launch { groups.delete(groupId) }
                        shell.back()
                        messages.show("Группа удалена")
                    },
                    secondary = "Отмена",
                    onSecondary = { confirmDelete = false },
                )
            }
        }
    }
}

@Composable
private fun MemberList(list: List<GroupMemberEntity>, byId: Map<String, ClientRow>, today: LocalDate, onOpen: (GroupMemberEntity) -> Unit) {
    ListCard {
        list.forEachIndexed { index, m ->
            if (index > 0) RowDivider()
            val kid = byId[m.clientId]
            val name = kid.nameOrGone()
            val archived = if (kid?.client?.status == ClientStatus.ARCHIVED) "в архиве" else null
            PersonRow(
                title = name,
                subtitle = listOfNotNull(
                    GroupFormat.membership(m.fromDate, m.toDate),
                    kid?.client?.birthDate?.let { ClientFormat.age(it, today) },
                    archived,
                ).joinToString(" · "),
                onClick = { onOpen(m) },
                modifier = Modifier.testTag(memberRowTag(name)),
            )
        }
    }
}

/** Тренировки группы: дата и время, сколько пришло или ждём, дети «на раз». */
@Composable
private fun TrainingList(list: List<GroupTrainingRow>, members: List<GroupMember>, now: LocalDateTime) {
    val colors = PersonalkaTheme.colors
    ListCard {
        list.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            val t = row.training
            val active = row.participations
            val guests = GroupRules.guests(active.map { it.clientId }, members, t.start.date).size
            val status = when {
                t.status == TrainingStatus.CANCELLED -> "Отменено"
                t.status == TrainingStatus.DONE -> GroupFormat.attended(active.count { it.status == ParticipationStatus.ATTENDED }, active.size)
                t.start < now -> "не отмечено"
                else -> "ждём ${GroupFormat.children(active.count { it.status == ParticipationStatus.EXPECTED })}"
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.space4, vertical = Dimens.space3)) {
                Column(Modifier.width(72.dp)) {
                    Text(DateFormat.short(t.start.date), style = MaterialTheme.typography.labelMedium.tabular(), color = colors.text)
                    Text(DateFormat.time(t.start.time), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
                }
                Text(
                    listOf(status).plus(if (guests > 0) listOf(GroupFormat.guests(guests)) else emptyList()).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
            }
        }
    }
}

// --- Состав ---

/**
 * «+ В состав» (5.10): ребёнок из клиентов — сначала дети, поиском — все, кого нет в составе; нет в списке —
 * «Новый клиент» с фамилией и именем. С какого дня — по умолчанию сегодня: с него ребёнок ожидается в будущих
 * тренировках группы, прошедшие не меняются.
 */
@Composable
internal fun AddMemberForm(
    groupId: String,
    rows: List<GroupRow>,
    kids: List<ClientRow>,
    groups: GroupRepository,
    clients: ClientRepository,
    scope: CoroutineScope,
    today: LocalDate,
    close: () -> Unit,
) {
    val row = rows.firstOrNull { it.group.id == groupId } ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newLast by rememberSaveable { mutableStateOf("") }
    var newFirst by rememberSaveable { mutableStateOf("") }
    var fromText by rememberSaveable { mutableStateOf(DateFormat.full(today)) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var similarId by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val inGroup = row.members.filter { (it.toDate ?: today) >= today }.map { it.clientId }.toSet()
    val candidates = kids.filter { it.client.status == ClientStatus.ACTIVE && it.client.id !in inGroup }
    val shown = if (query.isBlank()) {
        candidates.sortedWith(compareBy<ClientRow> { it.client.adultOn(today) }.thenBy { ClientRules.sortKey(name(it)) }).take(8)
    } else {
        candidates.filter { ClientRules.matches(query, name(it)) }.sortedBy { ClientRules.sortKey(name(it)) }
    }
    val from = DateFormat.parseFull(fromText)
    val who = pickedId != null || (creating && newLast.isNotBlank() && newFirst.isNotBlank())

    fun save() {
        val date = from
        if (!who || date == null) {
            showErrors = true
            return
        }
        val picked = pickedId
        if (picked != null) {
            scope.launch { groups.addMember(groupId, picked, date) }
            close()
        } else if (!checking) {
            val last = newLast.trim()
            val first = newFirst.trim()
            // Сначала — нет ли его уже среди клиентов, и в архиве (5.8, шаг 3.9о): иначе в базе появится дубль.
            checking = true
            scope.launch {
                val similar = clients.similar(ClientName(last, first))
                checking = false
                if (similar != null) {
                    similarId = similar.id
                } else {
                    close()
                    val id = clients.create(ClientDraft(last, first, sex = ClientRules.guessSex(null, last)?.sex))
                    groups.addMember(groupId, id, date)
                }
            }
        }
    }

    /** «Да, выбрать» у похожего: архивный возвращается в действующие; уже в составе — добавлять нечего. */
    fun pickSimilar(similar: ClientEntity) {
        val date = from ?: return
        similarId = null
        close()
        scope.launch {
            if (similar.status == ClientStatus.ARCHIVED) clients.unarchive(similar.id)
            if (similar.id !in inGroup) groups.addMember(groupId, similar.id, date)
        }
    }

    /** «Нет, это другой человек» — полный тёзка: заводится новый клиент. */
    fun createAnyway() {
        val date = from ?: return
        val last = newLast.trim()
        val first = newFirst.trim()
        similarId = null
        close()
        scope.launch {
            val id = clients.create(ClientDraft(last, first, sex = ClientRules.guessSex(null, last)?.sex))
            groups.addMember(groupId, id, date)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar("В состав", onBack = close, close = true)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Text(row.group.name, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.textMuted)
                Spacer(Modifier.height(Dimens.space3))
                FormLabel("Кто")
                SearchField(query, { query = it }, "Фамилия или имя", Modifier.testTag(MEMBER_SEARCH_TAG))
                Spacer(Modifier.height(Dimens.space2))
                Column(Modifier.clip(MaterialTheme.shapes.medium)) {
                    shown.forEach { kid ->
                        val selected = kid.client.id == pickedId
                        PersonRow(
                            title = "${kid.client.lastName} ${kid.client.firstName}",
                            subtitle = kid.client.birthDate?.let { ClientFormat.age(it, today) },
                            onClick = {
                                pickedId = kid.client.id
                                creating = false
                            },
                            selected = selected,
                            leading = if (selected) PIcons.CheckCircle else null,
                            modifier = Modifier.testTag(memberPickTag("${kid.client.lastName} ${kid.client.firstName}")),
                        )
                    }
                    val prefill = query.trim()
                    PersonRow(
                        title = if (prefill.isEmpty()) "Новый клиент…" else "Новый клиент: $prefill…",
                        subtitle = null,
                        onClick = {
                            creating = true
                            pickedId = null
                            val words = prefill.split(' ').filter { it.isNotEmpty() }.map { w -> w.replaceFirstChar { it.uppercaseChar() } }
                            newLast = words.firstOrNull().orEmpty()
                            newFirst = words.drop(1).joinToString(" ")
                        },
                        selected = creating,
                        leading = PIcons.PersonAdd,
                        modifier = Modifier.testTag(NEW_MEMBER_TAG),
                    )
                }
                if (creating) {
                    Spacer(Modifier.height(Dimens.space3))
                    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        Field("Фамилия", newLast, { newLast = it }, Modifier.weight(1f), keyboardOptions = words, tag = NEW_MEMBER_LAST_TAG)
                        Field("Имя", newFirst, { newFirst = it }, Modifier.weight(1f), keyboardOptions = words, tag = NEW_MEMBER_FIRST_TAG)
                    }
                }
                if (showErrors && !who) ErrorLine(if (creating) "Введите фамилию и имя" else "Выберите ребёнка или добавьте нового")
                Spacer(Modifier.height(Dimens.space5))
                Field(
                    label = "С какого дня в группе",
                    value = fromText,
                    onValueChange = { fromText = it.take(12) },
                    error = if (showErrors && from == null) "Дата в виде 22.10.2026" else null,
                    hint = "С этого дня — в будущих тренировках группы. Прошедшие не изменятся.",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    tag = MEMBER_DATE_TAG,
                )
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction("Сохранить", onClick = ::save)
        }
        similarId?.let { id ->
            SimilarClientPanel(
                clientId = id,
                clients = clients,
                today = today,
                onPick = ::pickSimilar,
                onCreate = ::createAnyway,
                onDismiss = { similarId = null },
            )
        }
    }
}

private fun name(row: ClientRow) = ClientName(row.client.lastName, row.client.firstName, row.client.middleName)

/**
 * Место ребёнка в составе (5.10): «В группе с» и «Не ходит с» — «исключить с даты». Будущие тренировки группы
 * меняются сами, прошедшие — нет. Добавили по ошибке — «Убрать из состава» с «Отменить».
 */
@Composable
internal fun MemberForm(
    groupId: String,
    memberId: String,
    rows: List<GroupRow>,
    kids: List<ClientRow>,
    groups: GroupRepository,
    messages: MessageState,
    scope: CoroutineScope,
    today: LocalDate,
    close: () -> Unit,
) {
    val row = rows.firstOrNull { it.group.id == groupId } ?: return
    val member = row.members.firstOrNull { it.id == memberId } ?: return
    val kid = kids.firstOrNull { it.client.id == member.clientId }
    val name = kid.nameOrGone()
    var fromText by rememberSaveable { mutableStateOf(DateFormat.full(member.fromDate)) }
    var notFromText by rememberSaveable { mutableStateOf(GroupRules.notFrom(member.toDate)?.let { DateFormat.full(it) }.orEmpty()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val from = DateFormat.parseFull(fromText)
    val notFrom = DateFormat.parseFull(notFromText)
    val problems = GroupRules.memberProblems(from, fromText, notFrom, notFromText)
    val shown = if (showErrors) problems else emptyMap()
    val dates = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
        TopBar(name, onBack = close, close = true)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(row.group.name, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.textMuted)
            Field("В группе с", fromText, { fromText = it.take(12) }, error = shown[MemberField.FROM], keyboardOptions = dates, tag = MEMBER_FROM_TAG)
            Field(
                label = "Не ходит с",
                value = notFromText,
                onValueChange = { notFromText = it.take(12) },
                error = shown[MemberField.NOT_FROM],
                hint = "Пусто — занимается. Прошедшие тренировки не изменятся.",
                keyboardOptions = dates.copy(imeAction = ImeAction.Done),
                tag = MEMBER_NOT_FROM_TAG,
            )
            if (notFromText.isBlank()) {
                SecondaryButton("Не ходит с сегодня", { notFromText = DateFormat.full(today) })
            }
            TextButton(
                onClick = {
                    scope.launch { groups.removeMember(memberId) }
                    close()
                    messages.showUndo(
                        RuText.bySex(kid?.client?.sex, "$name убран из состава", "$name убрана из состава", "$name — не в составе"),
                    ) { scope.launch { groups.restoreMember(memberId) } }
                },
            ) {
                Text("Добавлен по ошибке — убрать из состава", style = MaterialTheme.typography.labelLarge, color = PersonalkaTheme.colors.text)
            }
        }
        BottomAction("Сохранить", onClick = {
            val start = from
            if (problems.isNotEmpty() || start == null) {
                showErrors = true
            } else {
                scope.launch { groups.updateMember(memberId, start, GroupRules.lastDay(notFrom)) }
                close()
            }
        })
    }
}
