package com.akenory.personalka.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.NoticeRow
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.WaitRow
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.ui.calendar.CalendarScreen
import com.akenory.personalka.ui.clients.CabinetTools
import com.akenory.personalka.ui.clients.ClientFilter
import com.akenory.personalka.ui.clients.ClientListState
import com.akenory.personalka.ui.clients.ClientRoute
import com.akenory.personalka.ui.clients.ClientsScreen
import com.akenory.personalka.ui.clients.rememberClientListState
import com.akenory.personalka.ui.components.EmptyState
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.groups.GroupRoute
import com.akenory.personalka.ui.groups.GroupsScreen
import com.akenory.personalka.ui.home.HomeMoney
import com.akenory.personalka.ui.home.HomeScreen
import com.akenory.personalka.ui.home.HomeTodo
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.reconcile.ReconcileScreen
import com.akenory.personalka.ui.reconcile.Report1cResultScreen
import com.akenory.personalka.ui.reconcile.Report1cRoute
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.salary.SalaryScreen
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.SettingsRoute
import com.akenory.personalka.ui.settings.SettingsScreen
import com.akenory.personalka.ui.settings.SyncTools
import com.akenory.personalka.ui.slots.SlotsRoute
import com.akenory.personalka.ui.slots.SlotsScreen
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.TrainingFormKey
import com.akenory.personalka.ui.training.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.yearMonth

/**
 * Экраны разделов. С шага 1.5 — заготовки: заголовок и пустой экран с тем, в каком шаге раздел появится.
 * Каждый следующий шаг заменяет свою заготовку настоящим экраном: «Ещё → Настройки» — с шага 1.6,
 * «Клиенты» — с шага 1.7, «Ещё → Группы» — с шага 1.8, «Календарь» — с шага 1.9, «Главная» — с шага 1.16.
 *
 * @param now «сейчас» по клубу: от него сегодняшняя дата, возраст и ближайшие тренировки.
 * @param onForm открыть форму тренировки ([TrainingFormKey]): из календаря — новая на это время, из карточки
 *   тренировки — изменение (шаг 1.14); «Изменить» в окне зоны — форму
 *   дежурства или своего запрета (шаг 1.13а); из карточки клиента — новая с этим клиентом и «Изменить с даты…»
 *   его серии (шаг 1.13б); из «Настройки → Календарь» — свой запрет и дежурство (шаг 1.13г).
 * @param card открытая карточка тренировки в календаре (шаг 1.14); [onCard] — открыть или закрыть её.
 * @param backup резервная копия для «Настройки → Данные» (шаг 1.15); `null` — раздела нет.
 * @param account аккаунт на сервере для «Настройки → Данные → Аккаунт» (шаг 2.8а); `null` — строки нет.
 * @param sync синхронизация для «Настройки → Данные → Сервер» (шаг 2.10а); `null` — раздела нет.
 * @param home тренировки «Главной» и [todos] — пункты «Нужно сделать» (шаг 1.16); [homeCard] — карточка, открытая
 *   с «Главной», [onHomeCard] — открыть или закрыть её.
 * @param calendarDay день, который «Главная» попросила открыть в «Календаре»; [onCalendarDay] — открыть его
 *   (раздел «Календарь» переключается) или `null` — день открыт.
 * @param onDiary открыть дневник тренировки (шаг 2.1) — экран поверх приложения.
 * @param followup «Без записи» (шаг 2.4) для «Клиентов»; [clientList] — поиск и фильтр их списка: пункт «Главной»
 *   открывает «Без записи»; [voice] — голосовой ввод в заметке листа ожидания.
 * @param cabinets ссылки кабинетов (шаг 2.13) — когда сервер подключён и «Кабинеты» открыты у аккаунта; `null` —
 *   ни раздела «Кабинеты» в настройках, ни кнопки «Кабинет» в карточке.
 * @param shareText системное «Поделиться» текстом: «Поделиться расчётом» с коллегой (шаг 3.8).
 */
@Composable
fun SectionScreen(
    section: Section,
    shell: ShellState,
    now: LocalDateTime,
    versionName: String,
    settings: SettingsRepository,
    clients: ClientRepository,
    groups: GroupRepository,
    calendar: CalendarRepository,
    prefs: DevicePrefs,
    messages: MessageState,
    scope: CoroutineScope,
    onForm: (String) -> Unit,
    card: String? = null,
    onCard: (String?) -> Unit = {},
    backup: BackupTools? = null,
    account: AccountRepository? = null,
    sync: SyncTools? = null,
    home: List<CalendarTraining> = emptyList(),
    todos: List<HomeTodo> = emptyList(),
    notices: List<NoticeRow> = emptyList(),
    homeCard: String? = null,
    onHomeCard: (String?) -> Unit = {},
    calendarDay: LocalDate? = null,
    onCalendarDay: (LocalDate?) -> Unit = {},
    onDiary: (String) -> Unit = {},
    followup: Followup = Followup.EMPTY,
    clientList: ClientListState = rememberClientListState(),
    reminders: Reminders? = null,
    notifications: NotificationAccess? = null,
    voice: VoiceInput? = null,
    waiting: List<WaitRow> = emptyList(),
    cabinets: CabinetTools? = null,
    shareText: ((String) -> Unit)? = null,
    advanceAsk: Boolean = false,
    onAdvanceAsk: (Boolean) -> Unit = {},
) {
    // «Кабинет» в карточке — только при включённых кабинетах (5.9); раздел «Кабинеты» в настройках — чтобы их включить.
    val cabinetsOn by remember(settings) { settings.cabinetsEnabled }.collectAsState(initial = false)
    val openClient: (String) -> Unit = { id ->
        shell.select(Section.CLIENTS)
        shell.open(ClientRoute.card(id))
    }
    val openGroup: (String) -> Unit = { id ->
        shell.select(Section.MORE)
        shell.open(GroupRoute.card(id))
    }
    val openSlots: (String) -> Unit = { route ->
        shell.select(Section.MORE)
        shell.reset(route)
    }
    when (section) {
        Section.HOME -> {
            // «Деньги» (5.2, шаг 3.9): текущий месяц тем же расчётом, что «Зарплата»; читается, только пока «Главная» открыта.
            val categories by remember(settings) { settings.salary.categories }.collectAsState(initial = null)
            val month by remember(settings, now.date) { settings.months.month(now.date.yearMonth, now.date) }.collectAsState(initial = null)
            val loadedCategories = categories
            val money = when {
                loadedCategories == null -> null
                SalarySetupRules.needed(loadedCategories) -> HomeMoney.NotSet
                else -> month?.takeIf { it.view.month == now.date.yearMonth }?.let { HomeMoney.Month(it.view) }
            }
            HomeScreen(
            calendar = calendar,
            trainings = home,
            todos = todos,
            now = now,
            messages = messages,
            scope = scope,
            card = homeCard,
            onCard = onHomeCard,
            onDay = { date -> onCalendarDay(date) },
            onBackup = {
                shell.select(Section.MORE)
                shell.reset(SettingsRoute.HOME)
                shell.open(SettingsRoute.DATA)
            },
            onNoBooking = {
                clientList.filter = ClientFilter.NO_BOOKING
                shell.selectRoot(Section.CLIENTS)
            },
            onReconcile = {
                shell.select(Section.MORE)
                shell.reset(MoreItem.RECONCILE.route)
            },
            onEdit = { id -> onForm(TrainingFormKey.edit(id)) },
            onClient = { id ->
                onHomeCard(null)
                openClient(id)
            },
            onClientInfo = { id ->
                onHomeCard(null)
                shell.select(Section.CLIENTS)
                shell.open(ClientRoute.info(id))
            },
            onGroup = { id ->
                onHomeCard(null)
                openGroup(id)
            },
            onDiary = onDiary,
            money = money,
            onSalary = { shell.selectRoot(Section.SALARY) },
            onAdvance = {
                onAdvanceAsk(true)
                shell.selectRoot(Section.SALARY)
            },
            notices = notices,
        )
        }
        Section.CALENDAR -> CalendarScreen(
            calendar = calendar,
            prefs = prefs,
            now = now,
            messages = messages,
            scope = scope,
            card = card,
            onCard = onCard,
            onEdit = { id -> onForm(TrainingFormKey.edit(id)) },
            // Карточка клиента или группы открывается в своём разделе; карточка тренировки закрывается.
            onClient = { id ->
                onCard(null)
                openClient(id)
            },
            onGroup = { id ->
                onCard(null)
                openGroup(id)
            },
            onCreate = { start -> onForm(TrainingFormKey.new(start)) },
            onEditZone = { zone ->
                onForm(if (zone.kind == ZoneKind.RESTRICTION) TrainingFormKey.restriction(zone.id) else TrainingFormKey.edit(zone.id))
            },
            goTo = calendarDay,
            onWent = { onCalendarDay(null) },
            onDiary = onDiary,
            onSlots = { openSlots(SlotsRoute.ROOT) },
        )
        Section.CLIENTS -> ClientsScreen(shell, clients, calendar.forms, messages, scope, now, onForm, followup, clientList, voice, waiting, cabinets?.takeIf { cabinetsOn }) { id ->
            openSlots(SlotsRoute.forWait(id))
        }
        Section.SALARY -> SalaryScreen(settings, account, now.date, messages, scope, shareText, advanceAsk, { onAdvanceAsk(false) }, { openSlots(SlotsRoute.ROOT) }) {
            shell.select(Section.MORE)
            shell.reset(SettingsRoute.HOME)
            shell.open(SettingsRoute.RATES)
        }
        Section.MORE -> MoreScreen(shell, versionName) { route, onBack ->
            when {
                SettingsRoute.isSettings(route) -> SettingsScreen(route, shell, settings, calendar, now, messages, scope, onForm, onBack, backup, reminders, notifications, account, sync, cabinets != null)
                GroupRoute.isGroups(route) -> GroupsScreen(route, shell, groups, clients, settings, messages, scope, now, onBack)
                route == MoreItem.RECONCILE.route -> ReconcileScreen(settings, now.date, messages, scope, onBack, shareText, backup?.files) { id ->
                    shell.open(Report1cRoute.of(id))
                }
                SlotsRoute.isSlots(route) -> SlotsScreen(calendar, prefs, now, messages, onBack, onForm, shareText, SlotsRoute.waitOf(route))
                Report1cRoute.idOf(route) != null -> Report1cResultScreen(settings.report1c, Report1cRoute.idOf(route)!!, messages, scope, onBack, shareText)
                else -> MoreItem.entries.firstOrNull { it.route == route }?.let { MoreDetail(it, onBack) }
            }
        }
    }
}

/** Пункты «Ещё» (ТЗ 5.1). Экран в стопке раздела называется [route]. */
enum class MoreItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val heading: String,
    val text: String,
) {
    // У «Групп» с шага 1.8 свой экран (ui/groups), заготовка не показывается.
    GROUPS("groups", "Группы", PIcons.Groups, "Группы", "Состав с датами."),
    // «Свободные слоты» (4.13, 5.13, шаг 4.3) — свой экран (ui/slots).
    SLOTS(SlotsRoute.ROOT, "Свободные слоты", PIcons.EventAvailable, "Свободные слоты", "Окна за период."),
    // «Сверки» (5.14, шаг 3.9в) — свой экран (ui/reconcile): месяц зарплаты, дежурства, с шага 4.2 — отчёт 1С.
    RECONCILE("reconcile", "Сверки", PIcons.FactCheck, "Сверки", "Месяц зарплаты, дежурства, отчёт 1С."),
    // У «Настроек» с шага 1.6 свой экран (ui/settings), заготовка не показывается.
    SETTINGS("settings", "Настройки", PIcons.Settings, "Настройки", "Профиль, места и типы, календарь."),
    // «Клуб» — только администратору (ТЗ 5.1). Администратор бывает только с сервером (этап 2),
    // а выключенное не показывается (10.7), поэтому пункта пока нет.
}

internal fun moreRowTag(item: MoreItem) = "more-${item.route}"

/**
 * «Ещё»: на узком экране — список, пункт открывается поверх с «Назад»; на широком — список слева
 * и открытый пункт справа (ТЗ 5.1). У пункта бывают вложенные экраны («Настройки» → «Места и типы» →
 * тип): справа виден верхний, «Назад» ведёт к предыдущему.
 *
 * Открытый экран при сложении и разложении стоит на одном месте дерева — в правой части ряда, а список
 * слева то появляется, то нет. Поэтому форма с недописанным текстом переживает сложение, как и прокрутка
 * списка, поднятая над веткой «широкий / узкий».
 */
@Composable
private fun MoreScreen(
    shell: ShellState,
    versionName: String,
    detail: @Composable (route: String, onBack: (() -> Unit)?) -> Unit,
) {
    val wide = LocalWide.current
    val listScroll = rememberScrollState()
    val stack = shell.stack(Section.MORE)
    val root = MoreItem.entries.firstOrNull { it.route == stack.firstOrNull() }
    val top = stack.lastOrNull()
    val back: () -> Unit = { shell.back() }

    Row(Modifier.fillMaxSize()) {
        if (wide || top == null) {
            MoreList(
                selected = root,
                scroll = listScroll,
                versionName = versionName,
                onOpen = { if (wide) shell.reset(it.route) else shell.open(it.route) },
                modifier = if (wide) Modifier.width(360.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight(),
            )
            if (wide) VerticalDivider(thickness = 1.dp, color = PersonalkaTheme.colors.divider)
        }
        if (top != null) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // На широком у первого экрана пункта «Назад» нет: список и так слева.
                detail(top, if (!wide || stack.size > 1) back else null)
            }
        }
    }
}

@Composable
private fun MoreList(
    selected: MoreItem?,
    scroll: ScrollState,
    versionName: String,
    onOpen: (MoreItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    Column(modifier.verticalScroll(scroll)) {
        TopBar("Ещё")
        MoreItem.entries.forEach { item ->
            val isSelected = item == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (isSelected) colors.surfaceAlt else Color.Transparent)
                    .clickable(role = Role.Button) { onOpen(item) }
                    .semantics { this.selected = isSelected }
                    .testTag(moreRowTag(item))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, tint = colors.textMuted)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
            }
        }
        Text(
            text = "Версия $versionName",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.textMuted,
        )
    }
}

@Composable
private fun MoreDetail(item: MoreItem, onBack: (() -> Unit)?) {
    Placeholder(title = item.title, icon = item.icon, heading = item.heading, text = item.text, onBack = onBack)
}

/** Заготовка раздела: верхняя панель и пустой экран по центру. */
@Composable
private fun Placeholder(
    title: String,
    icon: ImageVector,
    heading: String,
    text: String,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
            EmptyState(icon = icon, title = heading, text = text)
        }
    }
}
