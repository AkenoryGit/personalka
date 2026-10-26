package com.akenory.personalka.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.data.clients.adultOn
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.RelationRole
import com.akenory.personalka.domain.client.Relations
import com.akenory.personalka.domain.client.symmetric
import com.akenory.personalka.domain.format.RuName
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val RELATIVE_SEARCH_TAG = "relative-search"
const val NEW_RELATIVE_TAG = "relative-new"
const val NEW_RELATIVE_LAST_TAG = "relative-new-last"
const val NEW_RELATIVE_FIRST_TAG = "relative-new-first"
const val OTHER_LABEL_TAG = "relative-other-label"
fun relativePickTag(name: String) = "relative-pick-$name"

/** Сколько клиентов видно до поиска: сначала однофамильцы, потом остальные по алфавиту. */
private const val SUGGESTED = 6

/**
 * «+ Родственник» и «Изменить связь» (4.17, макет ClRelAdd): кто — клиент из списка, поиском или новый с той же
 * фамилией; кем приходится — чипами в роде родственника, старшему — родители и бабушки, младшему — дети и внуки;
 * «Муж / Жена» — только у взрослых. Пол клиента неизвестен — тут же вопрос «Артём для Натальи: сын / дочь».
 * Перед «Сохранить» видно, что появится во второй карточке. Не больше 4 нажатий: «+ Родственник» → кто →
 * кем приходится → «Сохранить».
 *
 * Форма во весь экран, как остальные формы (1.6), а не панель снизу, как на макете: так она переживает
 * сложение Fold и не прячется под клавиатурой.
 *
 * @param relationId `null` — новая связь; иначе «Изменить связь»: родственник тот же, меняется слово.
 */
@Composable
fun RelativeEditor(
    clientId: String,
    relationId: String?,
    rows: List<ClientRow>,
    clients: ClientRepository,
    nav: ClientNav,
    scope: CoroutineScope,
    today: LocalDate,
) {
    val colors = PersonalkaTheme.colors
    val client = rows.firstOrNull { it.client.id == clientId }?.client ?: return
    val relatives by remember(clients, clientId, today) { clients.relatives(clientId, today) }.collectAsState(initial = null)
    val existing = relatives ?: return
    val editing = relationId?.let { id -> existing.firstOrNull { it.link.relationId == id } }
    if (relationId != null && editing == null) return

    var query by rememberSaveable { mutableStateOf("") }
    var pickedId by rememberSaveable { mutableStateOf(editing?.relative?.id) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newLast by rememberSaveable { mutableStateOf("") }
    var newFirst by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(editing?.link?.role?.kind) }
    var senior by rememberSaveable { mutableStateOf(editing?.link?.role?.senior ?: true) }
    var chipSex by rememberSaveable { mutableStateOf(editing?.relative?.sex) }
    var otherLabel by rememberSaveable { mutableStateOf(editing?.link?.label.orEmpty()) }
    var otherBack by rememberSaveable { mutableStateOf(editing?.clientLabel.orEmpty()) }
    var clientSex by rememberSaveable { mutableStateOf<Sex?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var similarId by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val picked = rows.firstOrNull { it.client.id == pickedId }
    val relativeFirst = picked?.client?.firstName ?: newFirst.trim()
    val relativeSex = when {
        picked != null -> picked.client.sex
        creating -> ClientRules.guessSex(null, newLast)?.sex
        else -> null
    }
    val clientAdult = client.adultOn(today)
    val older = if (picked == null || editing != null) {
        null
    } else {
        Relations.older(picked.client.birthDate, picked.client.adultOn(today), client.birthDate, clientAdult)
    }
    val choices = Relations.choices(relativeSex, older, bothAdults = clientAdult && (picked?.client?.adultOn(today) ?: true))
    val role = kind?.let { RelationRole(it, if (it.symmetric) true else senior) }
    val relatedIds = existing.filter { it.link.relationId != relationId }.associateBy { it.relative.id }
    val firstName = client.firstName

    /** Связь с родственником [relativeId] из клиентов; `null` — новый клиент из полей «Новый». */
    fun relate(chosen: RelationRole, relativeId: String?) {
        val other = chosen.kind == RelationKind.OTHER
        val label = otherLabel.takeIf { other }
        val back = otherBack.takeIf { other }
        val setSex = chipSex.takeIf { !other }
        scope.launch {
            if (relativeId != null) {
                clients.addRelation(clientId, relativeId, chosen, setSex, clientSex, label, back)
            } else {
                clients.createRelative(clientId, newLast.trim(), newFirst.trim(), chosen, setSex, clientSex, label, back)
            }
        }
        nav.back()
    }

    fun save() {
        val chosen = role
        val who = picked != null || (creating && newLast.isNotBlank() && newFirst.isNotBlank())
        if (chosen == null || !who) {
            showErrors = true
            return
        }
        when {
            editing != null -> {
                val other = chosen.kind == RelationKind.OTHER
                scope.launch {
                    clients.updateRelation(
                        editing.link.relationId, clientId, chosen, chipSex.takeIf { !other }, clientSex,
                        otherLabel.takeIf { other }, otherBack.takeIf { other },
                    )
                }
                nav.back()
            }
            picked != null -> relate(chosen, picked.client.id)
            !checking -> {
                // Новый — сначала нет ли его уже среди клиентов, и в архиве (5.8, шаг 3.9о): у братьев и сестёр
                // одна мама, второй раз её заводить не надо.
                checking = true
                scope.launch {
                    val similar = clients.similar(ClientName(newLast.trim(), newFirst.trim()), except = clientId)
                    checking = false
                    if (similar != null) similarId = similar.id else relate(chosen, null)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(
                title = if (editing != null) "Изменить связь" else "Родственник для ${RuName.genitive(firstName, client.sex)}",
                onBack = nav::back,
                close = true,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            ) {
                FormLabel("Кто")
                if (editing != null) {
                    val person = editing.relative
                    PersonRow(
                        title = "${person.lastName} ${person.firstName}",
                        subtitle = null,
                        onClick = null,
                        modifier = Modifier.clip(MaterialTheme.shapes.medium),
                        selected = true,
                        leading = PIcons.CheckCircle,
                    )
                } else {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Фамилия или имя",
                        modifier = Modifier.testTag(RELATIVE_SEARCH_TAG),
                    )
                    Spacer(Modifier.height(Dimens.space2))
                    val candidates = rows.filter { it.client.id != clientId }
                    val shown = if (query.isBlank()) {
                        val stem = familyStem(client.lastName)
                        candidates.sortedWith(compareBy<ClientRow> { familyStem(it.client.lastName) != stem }.thenBy { ClientRules.sortKey(it.name) })
                            .take(SUGGESTED)
                    } else {
                        candidates.filter { ClientRules.matches(query, it.name) }.sortedBy { ClientRules.sortKey(it.name) }
                    }
                    Column(Modifier.clip(MaterialTheme.shapes.medium)) {
                        shown.forEach { row ->
                            val person = row.client
                            val name = "${person.lastName} ${person.firstName}"
                            val already = relatedIds[person.id]
                            if (already != null) {
                                val word = Relations.word(already.link.role, person.sex, already.link.label)
                                PersonRow(
                                    title = name,
                                    subtitle = "Уже ${word.replaceFirstChar { it.lowercaseChar() }}",
                                    onClick = null,
                                    muted = true,
                                    leading = PIcons.Link,
                                    modifier = Modifier.testTag(relativePickTag(name)),
                                )
                            } else {
                                val selected = person.id == pickedId
                                val archived = if (person.status == ClientStatus.ARCHIVED) " · в архиве" else ""
                                PersonRow(
                                    title = name,
                                    subtitle = row.subtitle(today) + archived,
                                    onClick = {
                                        pickedId = person.id
                                        creating = false
                                        if (chipSex != null && person.sex != null && chipSex != person.sex) kind = null
                                    },
                                    selected = selected,
                                    leading = if (selected) PIcons.CheckCircle else null,
                                    modifier = Modifier.testTag(relativePickTag(name)),
                                )
                            }
                        }
                        val prefill = query.trim().ifEmpty { client.lastName }
                        PersonRow(
                            title = "Новый клиент: $prefill…",
                            subtitle = null,
                            onClick = {
                                creating = true
                                pickedId = null
                                val words = prefill.split(' ').filter { it.isNotEmpty() }
                                newLast = words.firstOrNull()?.replaceFirstChar { it.uppercaseChar() }.orEmpty()
                                newFirst = words.drop(1).joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
                            },
                            selected = creating,
                            leading = PIcons.PersonAdd,
                            modifier = Modifier.testTag(NEW_RELATIVE_TAG),
                        )
                    }
                    if (creating) {
                        Spacer(Modifier.height(Dimens.space3))
                        val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                            Field(
                                label = "Фамилия",
                                value = newLast,
                                onValueChange = { newLast = it },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = words,
                                tag = NEW_RELATIVE_LAST_TAG,
                            )
                            Field(
                                label = "Имя",
                                value = newFirst,
                                onValueChange = { newFirst = it },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = words,
                                tag = NEW_RELATIVE_FIRST_TAG,
                            )
                        }
                    }
                    if (showErrors && picked == null && !(creating && newLast.isNotBlank() && newFirst.isNotBlank())) {
                        ErrorText(if (creating) "Введите фамилию и имя" else "Выберите родственника или добавьте нового")
                    }
                }

                Spacer(Modifier.height(Dimens.space5))
                FormLabel("Кем приходится ${RuName.dative(firstName, client.sex)}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    choices.forEach { choice ->
                        val selected = role != null && role == choice.role && kind != RelationKind.OTHER &&
                            (chipSex == choice.sex || (chipSex == null && relativeSex == choice.sex))
                        ChoiceChip(choice.word, selected, onClick = {
                            kind = choice.role.kind
                            senior = choice.role.senior
                            chipSex = choice.sex
                        })
                    }
                    ChoiceChip("Другое…", kind == RelationKind.OTHER, onClick = {
                        kind = RelationKind.OTHER
                        senior = true
                        chipSex = null
                    })
                }
                if (showErrors && role == null) ErrorText("Выберите, кем приходится")

                if (kind == RelationKind.OTHER) {
                    Spacer(Modifier.height(Dimens.space3))
                    Field(
                        label = "Кем приходится ${RuName.dative(firstName, client.sex)}",
                        value = otherLabel,
                        onValueChange = { otherLabel = it },
                        hint = "Например, крёстная",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        tag = OTHER_LABEL_TAG,
                    )
                    Spacer(Modifier.height(Dimens.space3))
                    Field(
                        label = "Кем $firstName приходится в ответ",
                        value = otherBack,
                        onValueChange = { otherBack = it },
                        hint = "Если не указать — «родственник»",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }

                // Пол клиента неизвестен — слово во второй карточке зависит от него (4.17).
                if (client.sex == null && role != null && role.kind != RelationKind.OTHER && relativeFirst.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.space5))
                    FormLabel("$firstName для ${RuName.genitive(relativeFirst, chipSex ?: relativeSex)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        listOf(Sex.MALE, Sex.FEMALE).forEach { option ->
                            ChoiceChip(
                                Relations.word(role.reverse, option).lowercase(),
                                clientSex == option,
                                onClick = { clientSex = if (clientSex == option) null else option },
                            )
                        }
                    }
                }

                if (role != null && relativeFirst.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.space5))
                    val word = Relations.word(role.reverse, client.sex ?: clientSex, otherBack.takeIf { role.kind == RelationKind.OTHER })
                    Row(
                        Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.medium).padding(Dimens.space3),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PIcons.SyncAlt, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                        Text(
                            buildAnnotatedString {
                                append("У ${RuName.genitive(relativeFirst, chipSex ?: relativeSex)} появится: ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("$word · ${client.lastName} ${client.firstName}") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction("Сохранить", onClick = ::save)
        }
        similarId?.let { id ->
            SimilarClientPanel(
                clientId = id,
                clients = clients,
                today = today,
                onPick = { similar ->
                    similarId = null
                    role?.let { relate(it, similar.id) }
                },
                onCreate = {
                    similarId = null
                    role?.let { relate(it, null) }
                },
                onDismiss = { similarId = null },
            )
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = PersonalkaTheme.colors.error,
    )
}

/** Основа фамилии без рода: «Ковалёв» и «Ковалёва», «Вишневский» и «Вишневская» — одна семья в подсказках. */
private fun familyStem(lastName: String): String {
    val key = ClientRules.key(lastName)
    return when {
        key.endsWith("ая") || key.endsWith("ий") || key.endsWith("ой") -> key.dropLast(2)
        listOf("ова", "ева", "ина", "ына").any { key.endsWith(it) } -> key.dropLast(1)
        else -> key
    }
}
