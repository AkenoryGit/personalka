package com.akenory.personalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.akenory.personalka.ui.components.hideKeyboardOnTap
import com.akenory.personalka.ui.groups.GroupRoute
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.reminders.NotificationAccess
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.screens.MoreItem
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
import com.akenory.personalka.domain.format.TourFormat
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.tour.TourRules
import com.akenory.personalka.domain.tour.TourStop
import com.akenory.personalka.ui.clients.ClientScreen
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.tour.LocalTour
import com.akenory.personalka.ui.tour.TourCard
import com.akenory.personalka.ui.tour.TourOverlay
import com.akenory.personalka.ui.tour.TourState
import com.akenory.personalka.ui.tour.TourTarget
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.yearMonth

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
 * @param shareText системное «Поделиться» текстом (шаг 3.8): «Поделиться расчётом» с коллегой; `null` — текст копируется.
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
    shareText: ((String) -> Unit)? = null,
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
        // СПТ ждут покупки (4.9, шаг 3.7а): пункт «Нужно сделать» с ближайшим сроком.
        val sptWaiting by remember(calendar, hasCoach, now.date) {
            if (hasCoach == true) calendar.cards.spt.waiting(now.date) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // Аванс (4.11, шаг 3.9): пункт «Введите сумму аванса» с дня аванса, пока сумма не введена.
        val advance by remember(settings, hasCoach, now.date.yearMonth) {
            if (hasCoach == true) settings.months.advance(now.date.yearMonth) else flowOf(null)
        }.collectAsState(initial = null)
        // Кого предупредить об отпуске (5.16, шаг 3.12): пункт «Нужно сделать» и окно со списком на «Главной».
        val notices by remember(calendar, hasCoach, now.date) {
            if (hasCoach == true) calendar.settings.notices(now.date) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // Просьба открыть окно аванса в «Зарплате»: пункт «Главной» или уведомление «Аванс».
        var advanceAsk by rememberSaveable { mutableStateOf(false) }
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
        // Расхождения с 1С (8.4, шаг 4.2в): пункт «Нужно сделать».
        val report1c by remember(settings, hasCoach) {
            if (hasCoach == true) settings.report1c.unresolved() else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // Не ходят в группу 30 дней (5.10, шаг 3.9у): пункт «Нужно сделать».
        val groupAbsent by remember(groups, hasCoach, now.date) {
            if (hasCoach == true) groups.absent(now.date) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        // Уточнить, придёт ли (шаг 3.14): сегодняшние клиенты с красной надёжностью.
        val confirm by remember(clients, hasCoach, now) {
            if (hasCoach == true) clients.reliability.confirm(now) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        val todos = remember(home, backupState, now, noDiary, followup, waiting, syncTodo, consentDue, sptWaiting, advance, notices, report1c, groupAbsent, confirm) {
            homeTodos(home, backupState, now, noDiary, followup, waiting, syncTodo, consentDue, sptWaiting, advance, notices, report1c, groupAbsent, confirm)
        }
        // Поиск и фильтр «Клиентов» — здесь: «Главная» открывает их на «Без записи».
        val clientList = rememberClientListState()

        // Знакомство с приложением (5.18, шаги 5.3б, 5.3г): после первого запуска и из «Ещё».
        val tour = remember(prefs) { TourState(prefs) }
        val tourSchemes by remember(settings, hasCoach) {
            if (hasCoach == true) settings.salary.schemes else flowOf(null)
        }.collectAsState(initial = null)
        val tourCalendar by remember(calendar, hasCoach) {
            if (hasCoach == true) calendar.settings.prefs else flowOf(null)
        }.collectAsState(initial = null)
        val tourDiary by remember(calendar, hasCoach) {
            if (hasCoach == true) calendar.reminders.prefs else flowOf(null)
        }.collectAsState(initial = null)
        var askNoBackup by rememberSaveable { mutableStateOf(false) }
        var salaryStarted by rememberSaveable { mutableStateOf(false) }
        // Папка была выбрана ещё до остановки «Копия»: тогда знакомство только говорит, куда ложится копия.
        var folderBefore by rememberSaveable { mutableStateOf(false) }
        val salaryNeeded = tourSchemes?.let(SalarySetupRules::needed) ?: true
        val folderChosen = backupState?.folder != null
        // Пустой час первой остановки: сегодня, а если сегодня места нет (вечер, всё занято) — завтра с начала дня.
        val tourHours = tourCalendar
        val slotToday = tourHours?.let { h ->
            TourRules.slotMinute(
                nowMinute = now.hour * 60 + now.minute,
                startMinute = h.workStart.hour * 60 + h.workStart.minute,
                endMinute = h.workEnd.hour * 60 + h.workEnd.minute,
                busy = home.filter { it.start.date == now.date }.map { t ->
                    val from = t.start.hour * 60 + t.start.minute
                    from..(from + t.durationMin)
                },
            )
        }
        val slotDay = if (tourHours == null || slotToday != null) now.date else now.date.plus(1, DateTimeUnit.DAY)
        SideEffect { tour.slotDay = slotDay }
        fun tourGo(next: TourStop?) {
            tour.go(next)
            when (next) {
                TourStop.CALENDAR -> {
                    card = null
                    homeCard = null
                    shell.selectRoot(Section.CALENDAR)
                    slotDay.takeIf { it != now.date }?.let { calendarDay = it.toString() }
                }
                TourStop.CARD -> Unit
                TourStop.CLIENTS -> {
                    card = null
                    shell.selectRoot(Section.CLIENTS)
                }
                TourStop.HOME -> {
                    homeCard = null
                    shell.selectRoot(Section.HOME)
                }
                TourStop.SALARY -> {
                    salaryStarted = false
                    shell.selectRoot(Section.SALARY)
                }
                TourStop.BACKUP -> {
                    folderBefore = folderChosen
                    shell.select(Section.MORE)
                    shell.reset(SettingsRoute.DATA)
                }
                null -> Unit
            }
        }
        fun tourFinish() {
            tour.go(null)
            messages.show(TourFormat.DONE)
        }
        fun tourNext(from: TourStop) {
            val next = TourRules.next(from, salaryNeeded, hasTraining = card != null)
            when {
                // Последняя остановка без папки копии — сначала «Без копии?».
                next == null && backup != null && TourRules.asksAboutBackup(folderChosen) -> askNoBackup = true
                // Без резервной копии (тесты) остановки «Копия» нет.
                next == null || next == TourStop.BACKUP && backup == null -> tourFinish()
                else -> tourGo(next)
            }
        }
        fun pickFolder() {
            backup?.files?.pickFolder { uri, name -> backup.repository.chooseFolder(uri, name) }
        }
        // Мастер зарплаты закончен — дальше копия; папку выбрали на остановке «Копия» — знакомство кончилось.
        LaunchedEffect(tour.stop, salaryNeeded, tourSchemes != null) {
            if (tour.stop == TourStop.SALARY && tourSchemes != null && !salaryNeeded) tourNext(TourStop.SALARY)
        }
        LaunchedEffect(tour.stop, folderChosen) {
            if (tour.stop == TourStop.BACKUP && folderChosen && !folderBefore) tourFinish()
        }

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
                AppLink.Advance -> {
                    diary = null
                    homeCard = null
                    advanceAsk = true
                    shell.selectRoot(Section.SALARY)
                }
                is AppLink.Diary -> diary = link.id
                is AppLink.Card -> {
                    diary = null
                    shell.selectRoot(Section.HOME)
                    homeCard = link.id
                }
                AppLink.Report1c -> {
                    diary = null
                    homeCard = null
                    shell.select(Section.MORE)
                    if (shell.top(Section.MORE) != MoreItem.RECONCILE.route) {
                        shell.reset(MoreItem.RECONCILE.route)
                    }
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

        fun tourCard(stop: TourStop): TourCard {
            val next = TourFormat.NEXT to { tourNext(stop) }
            return when (stop) {
                TourStop.CALENDAR -> TourCard(
                    stop, TourFormat.CALENDAR_TITLE, listOf(TourFormat.CALENDAR_TEXT),
                    main = next, gesture = TourFormat.CALENDAR_GESTURE, aboveFab = true,
                )
                TourStop.CARD -> TourCard(
                    stop, TourFormat.CARD_TITLE,
                    listOf(
                        TourFormat.cardDone(tourDiary?.autoDone ?: true),
                        TourFormat.cardCancel(tourCalendar?.cancelNoticeMinutes ?: DEFAULT_NOTICE),
                    ),
                    main = next,
                )
                TourStop.CLIENTS -> {
                    val opened = shell.section == Section.CLIENTS &&
                        shell.top(Section.CLIENTS)?.let { ClientRoute.parse(it) is ClientScreen.Card } == true
                    when {
                        opened -> TourCard(stop, TourFormat.CLIENTS_TITLE, listOf(TourFormat.CLIENTS_OPEN_TEXT), main = next, holes = false)
                        TourTarget.ADD_CLIENT in tour.targets ->
                            TourCard(stop, TourFormat.CLIENTS_EMPTY_TITLE, listOf(TourFormat.CLIENTS_EMPTY_TEXT), main = next, gesture = TourFormat.CLIENTS_EMPTY_GESTURE)
                        else -> TourCard(stop, TourFormat.CLIENTS_TITLE, listOf(TourFormat.CLIENTS_TEXT), main = next, gesture = TourFormat.CLIENTS_GESTURE)
                    }
                }
                TourStop.HOME -> TourCard(stop, TourFormat.HOME_TITLE, listOf(TourFormat.homeText(tourDiary?.diary == true)), main = next)
                TourStop.SALARY -> TourCard(
                    stop, TourFormat.SALARY_TITLE, listOf(TourFormat.SALARY_TEXT),
                    main = TourFormat.SETUP to {
                        salaryStarted = true
                        shell.selectRoot(Section.SALARY)
                    },
                    second = next,
                )
                TourStop.BACKUP -> {
                    val folder = backupState?.folderName
                    if (folderBefore && folder != null) {
                        TourCard(stop, TourFormat.BACKUP_TITLE, listOf(TourFormat.backupDone(folder)), main = TourFormat.FINISH to { tourFinish() })
                    } else {
                        TourCard(
                            stop, TourFormat.BACKUP_TITLE, listOf(TourFormat.BACKUP_TEXT),
                            main = TourFormat.PICK_FOLDER to {
                                if (shell.section != Section.MORE || shell.top(Section.MORE) != SettingsRoute.DATA) tourGo(TourStop.BACKUP)
                                pickFolder()
                            },
                            second = next,
                        )
                    }
                }
            }
        }

        // Нажатие мимо поля ввода убирает клавиатуру — на всех экранах (5.20, шаг 5.3д).
        Box(Modifier.fillMaxSize().background(PersonalkaTheme.colors.bg).hideKeyboardOnTap()) {
            when (hasCoach) {
                null -> Unit
                false -> FirstRun(settings, backHandler, onDone = { tourGo(TourStop.CALENDAR) }, account, sync?.repository, clock)
                true -> CompositionLocalProvider(LocalTour provides tour) {
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
                            shareText = shareText,
                            home = home,
                            todos = todos,
                            notices = notices,
                            consents = consentDue,
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
                            advanceAsk = advanceAsk,
                            onAdvanceAsk = { advanceAsk = it },
                            onTour = { tourGo(TourStop.CALENDAR) },
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
                            onCreated = { id ->
                                // Знакомство: новая тренировка с первой остановки — сразу её карточка.
                                if (tour.stop == TourStop.CALENDAR) {
                                    tour.go(TourStop.CARD)
                                    shell.selectRoot(Section.CALENDAR)
                                    card = id
                                }
                            },
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
                    val stop = tour.stop
                    val tourHidden = form != null || diary != null || askNoBackup ||
                        stop == TourStop.SALARY && salaryStarted && shell.section == Section.SALARY
                    if (stop != null && !tourHidden) {
                        TourOverlay(tour, tourCard(stop))
                    }
                    if (askNoBackup) {
                        Panel(TourFormat.NO_BACKUP_TITLE, onDismiss = { askNoBackup = false }) {
                            Text(TourFormat.NO_BACKUP_TEXT, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.text)
                            Text(TourFormat.NO_BACKUP_NOTE, style = MaterialTheme.typography.bodyLarge, color = PersonalkaTheme.colors.textMuted)
                            PanelActions(
                                primary = TourFormat.PICK_FOLDER,
                                onPrimary = {
                                    askNoBackup = false
                                    tourGo(TourStop.BACKUP)
                                    pickFolder()
                                },
                                secondary = TourFormat.NO_BACKUP,
                                onSecondary = {
                                    askNoBackup = false
                                    tourFinish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Правило списания по умолчанию, пока настройка не прочитана (4.2): 1 час. */
private const val DEFAULT_NOTICE = 60
