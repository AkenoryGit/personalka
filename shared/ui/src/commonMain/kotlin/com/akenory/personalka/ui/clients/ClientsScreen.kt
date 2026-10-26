package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.ClientRow
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.WaitRow
import com.akenory.personalka.data.clients.adultOn
import com.akenory.personalka.data.clients.draft
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Экраны раздела «Клиенты» в его стопке: карточка и формы поверх списка (5.8, 5.9, 4.17). */
object ClientRoute {
    const val NEW = "client/new"
    private const val PREFIX = "client/"

    /** Новый клиент; [prefill] — то, что набрано в поиске: «Новый клиент: Ковалёва». */
    fun new(prefill: String = ""): String = if (prefill.isBlank()) NEW else "$NEW/${prefill.trim()}"
    fun card(id: String) = PREFIX + id

    /** Карточка сразу на «Инфо»: пункт «СПТ ждут покупки» ведёт к «Купил блок со скидкой» (шаг 3.7а). */
    fun info(id: String) = "$PREFIX$id/info"
    fun edit(id: String) = "$PREFIX$id/edit"
    fun relative(id: String) = "$PREFIX$id/relative"
    fun relation(id: String, relationId: String) = "$PREFIX$id/relation/$relationId"
    fun merge(id: String) = "$PREFIX$id/merge"

    private const val WAIT = "client/wait/"

    /** «+ В ожидание» (4.16); [clientId] — клиент уже выбран: «В лист ожидания…» из его карточки. */
    fun waitNew(clientId: String? = null): String = WAIT + "new" + (clientId?.let { "/$it" } ?: "")

    /** «Изменить» записи листа ожидания. */
    fun waitEdit(entryId: String): String = WAIT + entryId

    fun parse(route: String): ClientScreen? {
        if (route.startsWith(WAIT)) {
            val parts = route.removePrefix(WAIT).split('/')
            return if (parts[0] == "new") ClientScreen.Wait(null, parts.getOrNull(1)) else ClientScreen.Wait(parts[0], null)
        }
        if (route == NEW) return ClientScreen.New("")
        if (route.startsWith("$NEW/")) return ClientScreen.New(route.removePrefix("$NEW/"))
        if (!route.startsWith(PREFIX)) return null
        val parts = route.removePrefix(PREFIX).split('/')
        return when {
            parts.size == 1 -> ClientScreen.Card(parts[0])
            parts.size == 2 && parts[1] == "info" -> ClientScreen.Card(parts[0], info = true)
            parts.size == 2 && parts[1] == "edit" -> ClientScreen.Edit(parts[0])
            parts.size == 2 && parts[1] == "relative" -> ClientScreen.AddRelative(parts[0])
            parts.size == 2 && parts[1] == "merge" -> ClientScreen.Merge(parts[0])
            parts.size == 3 && parts[1] == "relation" -> ClientScreen.EditRelation(parts[0], parts[2])
            else -> null
        }
    }

    /** Форма с «✕» во весь экран: на сложенном Fold нижняя панель прячется. Карточка — не форма. */
    fun isForm(route: String?): Boolean = route != null && parse(route).let { it != null && it !is ClientScreen.Card }
}

/** Что открыто поверх списка. [clientId] — чей экран: его строка выделена в списке на разложенном Fold. */
sealed interface ClientScreen {
    val clientId: String?

    data class Card(override val clientId: String, val info: Boolean = false) : ClientScreen
    data class New(val prefill: String) : ClientScreen {
        override val clientId: String? get() = null
    }
    data class Edit(override val clientId: String) : ClientScreen
    data class AddRelative(override val clientId: String) : ClientScreen
    data class EditRelation(override val clientId: String, val relationId: String) : ClientScreen
    data class Merge(override val clientId: String) : ClientScreen

    /** Запись листа ожидания: новая ([entryId] `null`) или «Изменить». */
    data class Wait(val entryId: String?, override val clientId: String?) : ClientScreen
}

/** Чипы списка (5.8): «Активные», «Без записи» (4.15, 4.16), «Архив». */
enum class ClientFilter { ACTIVE, NO_BOOKING, ARCHIVE }

enum class ClientSort(val label: String) { NAME("по фамилии"), NEXT("по ближайшей тренировке") }

/** Редкие фильтры под значком «Сортировка и фильтры» (5.8). «С кабинетом» — с кабинетами, этап 2. */
enum class AgeFilter(val label: String) { ALL("Все"), CHILDREN("Дети"), ADULTS("Взрослые") }

/** Поиск, фильтры и сортировка списка. Сохраняются вместе с экраном: сложение и тема их не сбрасывают. */
@Stable
class ClientListState(
    query: String = "",
    filter: ClientFilter = ClientFilter.ACTIVE,
    sort: ClientSort = ClientSort.NAME,
    age: AgeFilter = AgeFilter.ALL,
) {
    var query by mutableStateOf(query)
    var filter by mutableStateOf(filter)
    var sort by mutableStateOf(sort)
    var age by mutableStateOf(age)

    /** Строки, которые видны: фильтр, редкий фильтр, поиск и порядок. */
    fun visible(rows: List<ClientRow>, today: LocalDate): List<ClientRow> {
        // «Без записи» — свой список ([FollowupList]); здесь — активные, если спросят.
        val status = if (filter == ClientFilter.ARCHIVE) ClientStatus.ARCHIVED else ClientStatus.ACTIVE
        val shown = rows.filter { row ->
            val client = row.client
            client.status == status &&
                when (age) {
                    AgeFilter.ALL -> true
                    AgeFilter.CHILDREN -> !client.adultOn(today)
                    AgeFilter.ADULTS -> client.adultOn(today)
                } &&
                ClientRules.matches(query, row.name)
        }
        val byName = compareBy<ClientRow> { ClientRules.sortKey(it.name) }
        return when (sort) {
            ClientSort.NAME -> shown.sortedWith(byName)
            ClientSort.NEXT -> shown.sortedWith(compareBy<ClientRow> { it.next == null }.thenBy { it.next }.then(byName))
        }
    }

    companion object {
        val Saver: Saver<ClientListState, Any> = Saver(
            save = { arrayListOf(it.query, it.filter.name, it.sort.name, it.age.name) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val list = saved as List<String>
                ClientListState(
                    query = list[0],
                    filter = ClientFilter.valueOf(list[1]),
                    sort = ClientSort.valueOf(list[2]),
                    age = AgeFilter.valueOf(list[3]),
                )
            },
        )
    }
}

/** Состояние списка, которое переживает сложение и смену темы. */
@Composable
fun rememberClientListState(): ClientListState = rememberSaveable(saver = ClientListState.Saver) { ClientListState() }

val ClientRow.name: ClientName get() = ClientName(client.lastName, client.firstName, client.middleName)

/** Строка под именем: «9 лет · Сб 09:00 · Группа ББ». */
fun ClientRow.subtitle(today: LocalDate): String =
    ClientFormat.subtitle(client.birthDate?.let { ClientFormat.age(it, today) }, ClientFormat.schedule(schedule))

private val ListWidth = 360.dp

/**
 * Раздел «Клиенты» (5.8, 5.9): на сложенном — список, карточка и формы открываются поверх; на разложенном —
 * список слева и открытое справа, строка открытого клиента в списке выделена (4.17).
 *
 * Поиск, фильтры и прокрутка списка объявлены над веткой ширины, открытое справа стоит на одном месте дерева —
 * сложение и разложение ничего не теряют. У каждого экрана стопки своё сохранённое состояние: «Назад» из
 * карточки родственника возвращает карточку на той же вкладке и прокрутке.
 *
 * @param forms серии клиента и их запись — «Расписание» карточки (шаг 1.13б).
 * @param onForm открыть форму тренировки поверх приложения: «+ Тренировка», «+ Серия», «Изменить с даты…»,
 *   «Записать» из «Без записи».
 * @param followup «Без записи» (4.15): раздел списка и метка «Нет записи» в карточке; считает приложение — пункт
 *   «Нужно сделать» на «Главной» нужен из любого раздела.
 * @param list поиск и фильтры списка: «Главная» открывает «Без записи» ([ClientListState.filter]).
 * @param waiting лист ожидания (4.16) с освободившимся временем — его тоже считает приложение (пункт «Главной»).
 */
@Composable
fun ClientsScreen(
    shell: ShellState,
    clients: ClientRepository,
    forms: TrainingRepository,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onForm: (String) -> Unit,
    followup: Followup = Followup.EMPTY,
    list: ClientListState = rememberClientListState(),
    voice: VoiceInput? = null,
    waiting: List<WaitRow> = emptyList(),
    cabinet: CabinetTools? = null,
) {
    val wide = LocalWide.current
    val scroll = rememberLazyListState()
    val followupScroll = rememberLazyListState()
    val screens = rememberSaveableStateHolder()
    val rows by remember(clients, now) { clients.rows(now) }.collectAsState(initial = null)
    val stack = shell.stack(Section.CLIENTS)
    screens.ForgetClosed(stack)
    val loaded = rows ?: return
    val route = stack.lastOrNull()
    val top = route?.let { ClientRoute.parse(it) }
    val nav = ClientNav(shell, wide)

    Row(Modifier.fillMaxSize()) {
        if (wide || top == null) {
            ClientList(
                state = list,
                scroll = scroll,
                rows = loaded,
                today = now.date,
                selectedId = top?.clientId,
                onOpen = nav::openFromList,
                onNew = nav::newFromList,
                noBookingCount = followup.rows.size + waiting.size,
                noBooking = { modifier ->
                    FollowupList(followup, waiting, list.query, followupScroll, clients, messages, scope, now, nav, onForm, modifier)
                },
                modifier = if (wide) Modifier.width(ListWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
            )
            if (wide) VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
        }
        if (route != null && top != null) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                screens.SaveableStateProvider(route) {
                    ClientDetail(
                        screen = top,
                        rows = loaded,
                        followup = followup,
                        waiting = waiting,
                        voice = voice,
                        clients = clients,
                        forms = forms,
                        nav = nav,
                        messages = messages,
                        scope = scope,
                        now = now,
                        onForm = onForm,
                        cabinet = cabinet,
                        // На разложенном у первого экрана «Назад» нет: список и так слева.
                        onBack = if (!wide || stack.size > 1) nav::back else null,
                    )
                }
            }
        } else if (wide && loaded.isNotEmpty()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
                EmptyState(icon = PIcons.Group, title = "Откройте клиента", text = "Его карточка появится здесь.")
            }
        }
    }
}

/**
 * Экран, ушедший из стопки, забывает введённое: после «Сохранить» следующий «Новый клиент» открывается пустым,
 * а брошенная «Назад» правка не возвращается при следующем «Изменить». Пока экран в стопке, его состояние
 * хранится: «Назад» из карточки родственника возвращает прежнюю карточку на той же вкладке.
 */
@Composable
private fun SaveableStateHolder.ForgetClosed(stack: List<String>) {
    val before = remember { arrayOf(stack) }
    SideEffect {
        if (before[0] != stack) {
            (before[0] - stack.toSet()).forEach { removeState(it) }
            before[0] = stack
        }
    }
}

/** Переходы раздела: из списка на разложенном открытое справа сменяется, а не копится в стопке. */
class ClientNav(private val shell: ShellState, private val wide: Boolean) {
    fun openFromList(id: String) = if (wide) shell.reset(ClientRoute.card(id)) else shell.open(ClientRoute.card(id))

    fun newFromList(prefill: String) = if (wide) shell.reset(ClientRoute.new(prefill)) else shell.open(ClientRoute.new(prefill))

    /** Форма из списка: на разложенном сменяет открытое справа. */
    fun formFromList(route: String) = if (wide) shell.reset(route) else shell.open(route)

    /** Поверх открытого: карточка родственника, формы карточки. */
    fun open(route: String) = shell.open(route)

    /** Заменить открытое: после «Сохранить» нового клиента — его карточка вместо формы. */
    fun replace(route: String) = shell.replaceTop(route)

    fun back() {
        shell.back()
    }
}

@Composable
private fun ClientDetail(
    screen: ClientScreen,
    rows: List<ClientRow>,
    followup: Followup,
    waiting: List<WaitRow>,
    voice: VoiceInput?,
    clients: ClientRepository,
    forms: TrainingRepository,
    nav: ClientNav,
    messages: MessageState,
    scope: CoroutineScope,
    now: LocalDateTime,
    onForm: (String) -> Unit,
    cabinet: CabinetTools?,
    onBack: (() -> Unit)?,
) {
    when (screen) {
        is ClientScreen.Card -> ClientCard(
            screen.clientId, rows, clients, forms, nav, messages, scope, now, onForm, onBack, followup, waiting, cabinet,
            startTab = if (screen.info) CardTab.INFO else CardTab.TRAININGS,
        )
        is ClientScreen.Wait -> WaitEditor(screen.entryId, screen.clientId, rows, followup, clients, nav, messages, scope, now.date, voice)
        is ClientScreen.New -> ClientEditor(
            clientId = null,
            initial = prefillDraft(screen.prefill),
            clients = clients,
            nav = nav,
            scope = scope,
            today = now.date,
        )
        is ClientScreen.Edit -> {
            val row = rows.firstOrNull { it.client.id == screen.clientId } ?: return
            ClientEditor(row.client.id, row.client.draft(), clients, nav, scope, now.date)
        }
        is ClientScreen.AddRelative -> RelativeEditor(screen.clientId, relationId = null, rows, clients, nav, scope, now.date)
        is ClientScreen.EditRelation -> RelativeEditor(screen.clientId, screen.relationId, rows, clients, nav, scope, now.date)
        is ClientScreen.Merge -> MergeScreen(screen.clientId, rows, clients, nav, messages, scope, now.date)
    }
}

/** «Новый клиент: Ковалёва Наталья» из поиска: первое слово — фамилия, второе — имя. */
internal fun prefillDraft(prefill: String): ClientDraft {
    val words = prefill.trim().split(' ').filter { it.isNotEmpty() }.map { w -> w.replaceFirstChar { it.uppercaseChar() } }
    return ClientDraft(lastName = words.getOrElse(0) { "" }, firstName = words.drop(1).joinToString(" "))
}
