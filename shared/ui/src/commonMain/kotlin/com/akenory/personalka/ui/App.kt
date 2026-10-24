package com.akenory.personalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.ui.clients.CabinetTools
import com.akenory.personalka.ui.clients.ClientRoute
import com.akenory.personalka.ui.clients.rememberClientListState
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.groups.GroupRoute
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.screens.SectionScreen
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.SettingsRoute
import com.akenory.personalka.ui.settings.SyncTools
import com.akenory.personalka.ui.home.SyncTodo
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.sync.SyncRules
import com.akenory.personalka.ui.setup.FirstRun
import com.akenory.personalka.ui.shell.AppShell
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.rememberClubNow
import com.akenory.personalka.ui.shell.rememberShellState
import com.akenory.personalka.ui.shell.systemMillis
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DiaryScreen
import com.akenory.personalka.ui.training.TrainingFormKey
import com.akenory.personalka.ui.training.TrainingFormSheet
import com.akenory.personalka.ui.training.VoiceInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * Приложение целиком: тема 0.2; пока тренера нет — первый запуск (шаг 1.6), потом оболочка с пятью
 * разделами (шаг 1.5). Пока база отвечает, виден только фон — это доли секунды при открытии.
 *
 * @param settings тренер, места и типы в базе телефона.
 * @param clients клиенты и родственники в базе телефона (шаг 1.7).
 * @param groups группы и их состав (шаг 1.8).
 * @param calendar тренировки дней для календаря (шаг 1.9), их создание и изменение — `calendar.forms` (шаг 1.13).
 * @param prefs настройки этого телефона: масштаб календаря.
 * @param backup резервная копия и системные окна для неё (шаг 1.15); `null` — раздела «Данные» нет (тесты).
 * @param account аккаунт на сервере (шаг 2.8а): строка «Аккаунт» в «Данных»; `null` — строки нет (тесты).
 * @param sync синхронизация (шаг 2.10а): «Сервер» в «Данных», «Войти» на первом запуске, пункт «Нужно сделать»;
 *   `null` — их нет (тесты). Сам обмен идёт в приложении платформы.
 * @param cabinets ссылки кабинетов (шаг 2.13): «Кабинет» в карточке клиента и «Кабинеты» в настройках, когда сервер
 *   подключён и «Кабинеты» открыты у аккаунта; `null` — их нет (тесты).
 * @param voice голосовой ввод в полях дневника (шаг 2.1); `null` — микрофонов нет.
 * @param reminders напоминания (шаг 2.5): «План на день» в настройках; `null` — строки нет (тесты).
 * @param notifications уведомления телефона: строка «Включить» в настройках, когда они выключены.
 * @param link нажатое уведомление ([AppLink]): приложение открывает нужный экран и зовёт [onLink].
 * @param backHandler «Назад» системы. Платформа передаёт свой обработчик (на Android — `BackHandler`
 *   из activity-compose): общий код не зависит от Android. Пока есть куда вернуться внутри приложения,
 *   «Назад» закрывает открытый экран, иначе ведёт на «Главную»; с «Главной» приложение сворачивается.
 * @param clock часы телефона, миллисекунды UTC; тесты подставляют свои.
 */
@Composable
fun App(
    versionName: String,
    settings: SettingsRepository,
    clients: ClientRepository,
    groups: GroupRepository,
    calendar: CalendarRepository,
    prefs: DevicePrefs = remember { MemoryPrefs() },
    backup: BackupTools? = null,
    account: AccountRepository? = null,
    sync: SyncTools? = null,
    cabinets: CabinetTools? = null,
    voice: VoiceInput? = null,
    reminders: Reminders? = null,
    notifications: NotificationAccess? = null,
    link: AppLink? = null,
    onLink: () -> Unit = {},
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    clock: () -> Long = ::systemMillis,
) {
    PersonalkaTheme {
        val shell = rememberShellState()
        val messages = remember { MessageState() }
        val now = rememberClubNow(clock)
        // Записи из экранов идут здесь: экран закрывается сразу, запись и «Отменить» доживают до конца.
        val scope = rememberCoroutineScope()
        val hasCoach by remember(settings) { settings.coach.map { it != null } }.collectAsState(initial = null)
        // Открытая форма тренировки (5.5) — поверх всего приложения: «+» есть и на «Главной», и в «Календаре».
        var form by rememberSaveable { mutableStateOf<String?>(null) }
        // Открытая карточка тренировки в календаре (5.4): «Назад» закрывает сначала её.
        var card by rememberSaveable { mutableStateOf<String?>(null) }
        // Карточка, открытая с «Главной» (5.2), — своя: календарь её не показывает.
        var homeCard by rememberSaveable { mutableStateOf<String?>(null) }
        // Открытый дневник (5.7) — поверх всего: его открывают карточка, «Ближайшая» и «Нужно сделать».
        var diary by rememberSaveable { mutableStateOf<String?>(null) }
        // День, который «Главная» попросила открыть в «Календаре».
        var calendarDay by rememberSaveable { mutableStateOf<String?>(null) }
        // «Главная» (5.2): тренировки и «Нужно сделать» считаются здесь — счётчик дел виден на значке из любого раздела.
        val home by remember(calendar, hasCoach, now.date) {
            if (hasCoach == true) calendar.homeTrainings(now.date) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        val backupState by remember(backup) { backup?.repository?.state ?: flowOf(null) }.collectAsState(initial = null)
        val noDiary by remember(calendar, hasCoach) {
            if (hasCoach == true) calendar.diaries.missing else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // «Без записи» (4.15): раздел «Клиентов», метка в карточке и пункт «Нужно сделать».
        val followup by remember(clients, hasCoach, now) {
            if (hasCoach == true) clients.followup.observe(now) else flowOf(Followup.EMPTY)
        }.collectAsState(initial = Followup.EMPTY)
        // Лист ожидания (4.16) с освободившимся временем: раздел «Клиентов», метка «Ждёт», пункт «Нужно сделать».
        val waiting by remember(clients, hasCoach, now) {
            if (hasCoach == true) clients.waitlist.observe(now) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // «С сервером» (5.2): вместо копии — изменения, не отправленные больше суток.
        val syncState by remember(sync) { sync?.repository?.state ?: flowOf(null) }.collectAsState(initial = null)
        val pending by remember(sync) { sync?.repository?.pending ?: flowOf(0) }.collectAsState(initial = 0)
        val oldest by remember(sync) { sync?.repository?.oldestPending ?: flowOf(null) }.collectAsState(initial = null)
        // Кабинеты (5.9, 5.16): только «С сервером» и с открытыми «Кабинетами» у аккаунта (10.7: закрытое не видно).
        val signedIn by remember(account) { account?.account ?: flowOf(null) }.collectAsState(initial = null)
        val cabinetsOpen = syncState?.connected == true &&
            signedIn?.let { Feature.CABINETS in AccountRules.open(it.features, now.date) } == true
        val syncTodo = syncState?.takeIf { it.connected }?.let { s ->
            SyncTodo(SyncRules.late(pending, oldest, s.lastSync, clock()), s.lastSync?.let { ClubTime.now(it) })
        }
        // Отозванные согласия (6.1): «Удалить данные клиента» — из последнего ответа сервера, который помнит телефон,
        // поэтому пункт виден и без связи. Обновляется после каждой синхронизации.
        val consentDue by remember(cabinets) { cabinets?.repository?.due ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
        LaunchedEffect(cabinets, cabinetsOpen, syncState?.lastSync) {
            if (cabinetsOpen) cabinets?.repository?.refresh()
        }
        val todos = remember(home, backupState, now, noDiary, followup, waiting, syncTodo, consentDue) {
            homeTodos(home, backupState, now, noDiary, followup, waiting, syncTodo, consentDue)
        }
        // Поиск и фильтр «Клиентов» — здесь: «Главная» открывает их на «Без записи».
        val clientList = rememberClientListState()

        // Серии продлеваются до горизонта при открытии приложения и при смене дня (4.4).
        LaunchedEffect(calendar, hasCoach, now.date) {
            if (hasCoach == true) calendar.forms.extendSeries()
        }
        // Прошедшие закрываются в конце (4.1): при открытии приложения и каждую минуту.
        LaunchedEffect(calendar, hasCoach, now) {
            if (hasCoach == true) calendar.cards.closeEnded()
        }
        // Нажатое уведомление (5.19): сводка — «Главная», «Дневник» — первая без дневника, «Группа сразу» — её карточка.
        LaunchedEffect(link, hasCoach) {
            if (link == null || hasCoach != true) return@LaunchedEffect
            form = null
            card = null
            when (link) {
                AppLink.Home -> {
                    diary = null
                    homeCard = null
                    shell.selectRoot(Section.HOME)
                }
                AppLink.FirstDiary -> {
                    // Прошедшие закрываются раньше, чем ищется первая без дневника: приложение могло быть закрыто.
                    calendar.cards.closeEnded()
                    val first = calendar.diaries.missing.first().firstOrNull()?.id
                    if (first == null) shell.selectRoot(Section.HOME)
                    diary = first
                }
                is AppLink.Diary -> diary = link.id
                is AppLink.Card -> {
                    diary = null
                    shell.selectRoot(Section.HOME)
                    homeCard = link.id
                }
            }
            onLink()
        }
        // Лист ожидания (4.16): разовые с прошедшим сроком закрываются, закрытые старше 90 дней удаляются — раз в день.
        LaunchedEffect(clients, hasCoach, now.date) {
            if (hasCoach == true) clients.waitlist.tidy()
        }
        // Автокопия в папку (7.6): при открытии приложения и раз в час — если пора.
        LaunchedEffect(backup, hasCoach, now.date, now.hour) {
            if (hasCoach == true) backup?.repository?.autoBackup()
        }

        Box(Modifier.fillMaxSize().background(PersonalkaTheme.colors.bg)) {
            when (hasCoach) {
                null -> Unit
                false -> FirstRun(settings, backHandler, onDone = { shell.select(Section.CALENDAR) }, account, sync?.repository, clock)
                true -> {
                    backHandler(shell.canGoBack) { shell.back() }
                    backHandler(card != null && shell.section == Section.CALENDAR) { card = null }
                    backHandler(homeCard != null && shell.section == Section.HOME) { homeCard = null }
                    // «Назад» при открытой форме закрывает форму, а не экран под ней.
                    backHandler(form != null) { form = null }
                    backHandler(diary != null) { diary = null }
                    AppShell(
                        shell = shell,
                        messages = messages,
                        todoCount = todos.size,
                        onNewTraining = { form = TrainingFormKey.new() },
                        hideFab = card != null && shell.section == Section.CALENDAR || homeCard != null && shell.section == Section.HOME,
                        immersive = when (shell.section) {
                            Section.MORE -> shell.top(Section.MORE).let { SettingsRoute.isForm(it) || GroupRoute.isForm(it) }
                            Section.CLIENTS -> ClientRoute.isForm(shell.top(Section.CLIENTS))
                            else -> false
                        },
                    ) { section ->
                        SectionScreen(
                            section = section,
                            shell = shell,
                            now = now,
                            versionName = versionName,
                            settings = settings,
                            clients = clients,
                            groups = groups,
                            calendar = calendar,
                            prefs = prefs,
                            messages = messages,
                            scope = scope,
                            onForm = { form = it },
                            card = card,
                            onCard = { card = it },
                            backup = backup,
                            account = account,
                            sync = sync,
                            cabinets = cabinets?.takeIf { cabinetsOpen },
                            home = home,
                            todos = todos,
                            homeCard = homeCard,
                            onHomeCard = { homeCard = it },
                            calendarDay = calendarDay?.let(LocalDate::parse),
                            onCalendarDay = { date ->
                                calendarDay = date?.toString()
                                if (date != null) {
                                    card = null
                                    shell.select(Section.CALENDAR)
                                }
                            },
                            onDiary = { diary = it },
                            followup = followup,
                            clientList = clientList,
                            reminders = reminders,
                            notifications = notifications,
                            voice = voice,
                            waiting = waiting,
                        )
                    }
                    form?.let { key ->
                        TrainingFormSheet(
                            key = key,
                            forms = calendar.forms,
                            restrictions = calendar.restrictions,
                            clients = clients,
                            now = now,
                            messages = messages,
                            scope = scope,
                            onClose = { form = null },
                        )
                    }
                    diary?.let { id ->
                        // Имя в дневнике ведёт в карточку клиента или группы: дневник и карточка тренировки закрываются.
                        val leave = {
                            diary = null
                            card = null
                            homeCard = null
                        }
                        DiaryScreen(
                            id = id,
                            diaries = calendar.diaries,
                            voice = voice,
                            scope = scope,
                            onOpen = { diary = it },
                            onClient = { clientId ->
                                leave()
                                shell.select(Section.CLIENTS)
                                shell.open(ClientRoute.card(clientId))
                            },
                            onGroup = { groupId ->
                                leave()
                                shell.select(Section.MORE)
                                shell.open(GroupRoute.card(groupId))
                            },
                            onClose = { diary = null },
                        )
                    }
                }
            }
        }
    }
}
