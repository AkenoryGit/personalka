package com.akenory.personalka.ui.clients

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientField
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.Segmented
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

const val CLIENT_LAST_TAG = "client-last"
const val CLIENT_FIRST_TAG = "client-first"
const val CLIENT_MIDDLE_TAG = "client-middle"
const val CLIENT_BIRTH_TAG = "client-birth"
const val CLIENT_CONTACT_TAG = "client-contact"
const val CLIENT_PHONE_TAG = "client-phone"
const val CLIENT_MEDIA_TAG = "client-media"
const val CLIENT_NOTE_TAG = "client-note"

/**
 * Новый клиент и «Изменить» (5.8, макет ClNew): фамилия и имя обязательны, остальное — по желанию. Пол
 * приложение предлагает само по отчеству или фамилии (4.17), пока тренер не выбрал его сам. С датой рождения
 * «ребёнок / взрослый» считается сам, без неё — переключателем.
 *
 * Перед созданием — проверка похожих среди всех клиентов, и в архиве: та же фамилия и имя или фамилия, отличающаяся
 * на 1–2 буквы, при том же имени — «Похожий клиент уже есть. Это она?» ([SimilarClientPanel]).
 *
 * @param clientId `null` — новый клиент.
 */
@Composable
fun ClientEditor(
    clientId: String?,
    initial: ClientDraft,
    clients: ClientRepository,
    nav: ClientNav,
    scope: CoroutineScope,
    today: LocalDate,
) {
    var last by rememberSaveable { mutableStateOf(initial.lastName) }
    var first by rememberSaveable { mutableStateOf(initial.firstName) }
    var middle by rememberSaveable { mutableStateOf(initial.middleName) }
    // Пол: пока тренер не выбрал сам, он предлагается по отчеству или фамилии; у сохранённого — как записан.
    var sexChosen by rememberSaveable { mutableStateOf(clientId != null || initial.sex != null) }
    var chosenSex by rememberSaveable { mutableStateOf(initial.sex) }
    var birth by rememberSaveable { mutableStateOf(initial.birthDate?.let { DateFormat.full(it) }.orEmpty()) }
    var adult by rememberSaveable { mutableStateOf(initial.isAdult) }
    var contact by rememberSaveable { mutableStateOf(initial.contactName) }
    var phone by rememberSaveable { mutableStateOf(initial.contactPhone.let { if (it.isEmpty()) it else ClientFormat.phone(it) }) }
    var media by rememberSaveable { mutableStateOf(initial.mediaUrl) }
    var note by rememberSaveable { mutableStateOf(initial.privateNote) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var similarId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val guess = ClientRules.guessSex(middle, last)
    val sex = if (sexChosen) chosenSex else guess?.sex
    val birthDate = DateFormat.parseFull(birth)
    val draft = ClientDraft(last, first, middle, sex, birthDate, adult, contact, phone, media, note)
    val problems = ClientRules.problems(draft, birth, today)
    val shown = if (showErrors) problems else emptyMap()

    fun create() {
        if (saving) return
        saving = true
        scope.launch {
            val id = clients.create(draft)
            nav.replace(ClientRoute.card(id))
        }
    }

    fun save() {
        when {
            problems.isNotEmpty() -> showErrors = true
            clientId != null -> {
                scope.launch { clients.update(clientId, draft) }
                nav.back()
            }
            !saving -> {
                // Пока идёт проверка, второе нажатие не заводит второго клиента.
                saving = true
                scope.launch {
                    val similar = clients.similar(draft.name)
                    saving = false
                    if (similar != null) similarId = similar.id else create()
                }
            }
        }
    }

    fun chooseSex(option: Sex) {
        sexChosen = true
        chosenSex = if (sex == option) null else option
    }

    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
            TopBar(if (clientId == null) "Новый клиент" else "Изменить", onBack = nav::back, close = true)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Spacer(Modifier.height(Dimens.space1))
                Field("Фамилия", last, { last = it }, error = shown[ClientField.LAST_NAME], keyboardOptions = words, tag = CLIENT_LAST_TAG)
                Field("Имя", first, { first = it }, error = shown[ClientField.FIRST_NAME], keyboardOptions = words, tag = CLIENT_FIRST_TAG)
                Field("Отчество · если есть", middle, { middle = it }, keyboardOptions = words, tag = CLIENT_MIDDLE_TAG)

                Column {
                    FormLabel("Пол")
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        ChoiceChip("Мужской", sex == Sex.MALE, onClick = { chooseSex(Sex.MALE) })
                        ChoiceChip("Женский", sex == Sex.FEMALE, onClick = { chooseSex(Sex.FEMALE) })
                    }
                    if (!sexChosen && guess != null) {
                        Text(
                            if (guess.byMiddleName) "Предложен по отчеству" else "Предложен по фамилии",
                            style = MaterialTheme.typography.bodySmall,
                            color = PersonalkaTheme.colors.textMuted,
                        )
                    }
                }

                Field(
                    label = "Дата рождения",
                    value = birth,
                    onValueChange = { birth = it.take(12) },
                    error = shown[ClientField.BIRTH_DATE],
                    hint = birthDate?.takeIf { it <= today }?.let { ClientFormat.ageHint(it, today) } ?: "Например, 12.05.2017",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    tag = CLIENT_BIRTH_TAG,
                )
                if (birthDate == null) {
                    Segmented(
                        options = listOf(false, true),
                        selected = adult,
                        label = { ClientFormat.adult(it) },
                        onSelect = { adult = it },
                    )
                }

                Column {
                    FormLabel("Для связи")
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        Field(
                            label = "Кто: мама, папа…",
                            value = contact,
                            onValueChange = { contact = it },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            tag = CLIENT_CONTACT_TAG,
                        )
                        Field(
                            label = "Телефон",
                            value = phone,
                            onValueChange = { phone = it },
                            modifier = Modifier.weight(1f),
                            error = shown[ClientField.PHONE],
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                            tag = CLIENT_PHONE_TAG,
                        )
                    }
                }
                Field(
                    label = "Фото и видео · ссылка",
                    value = media,
                    onValueChange = { media = it },
                    hint = "Папка на Яндекс Диске или в Облаке Mail.ru",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    tag = CLIENT_MEDIA_TAG,
                )
                Field(
                    label = ClientFormat.NOTE,
                    value = note,
                    onValueChange = { note = it },
                    hint = ClientFormat.NOTE_HINT,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    tag = CLIENT_NOTE_TAG,
                    singleLine = false,
                )
                Spacer(Modifier.height(Dimens.space4))
            }
            BottomAction("Сохранить", onClick = ::save)
        }

        similarId?.let { id ->
            SimilarClientPanel(
                clientId = id,
                clients = clients,
                today = today,
                onPick = { nav.replace(ClientRoute.card(it.id)) },
                onCreate = {
                    similarId = null
                    create()
                },
                onDismiss = { similarId = null },
            )
        }
    }
}

/** Подпись группы полей — как подпись над полем ввода. */
@Composable
internal fun FormLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = PersonalkaTheme.colors.textMuted,
    )
}
