package com.akenory.personalka.ui.home

import androidx.compose.foundation.background
import com.akenory.personalka.domain.money.SalaryPlan
import com.akenory.personalka.domain.money.PlanPace
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupState
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.NoticeRow
import com.akenory.personalka.data.clients.Birthday
import com.akenory.personalka.data.clients.Followup
import com.akenory.personalka.data.clients.WaitRow
import com.akenory.personalka.data.db.entity.ReconciliationItemEntity
import com.akenory.personalka.data.money.SptWaiting
import com.akenory.personalka.data.training.MissingDiary
import com.akenory.personalka.data.training.TrainingCard
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.cabinet.ConsentDue
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.FollowupFormat
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.format.SptFormat
import com.akenory.personalka.domain.format.SyncFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.HomeSlot
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.money.AdvanceState
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.report1c.Reconcile1cRules
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.domain.training.StatusRules
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.calendar.COPIED
import com.akenory.personalka.ui.calendar.EntryRow
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.theme.tabular
import com.akenory.personalka.ui.training.TrainingCardHost
import com.akenory.personalka.ui.training.TrainingCardKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

internal const val NEAREST_TAG = "home-nearest"
internal const val NEAREST_OPEN_TAG = "home-nearest-open"
internal const val NEAREST_DONE_TAG = "home-nearest-done"
internal const val NEAREST_DIARY_TAG = "home-nearest-diary"
internal const val LATER_TAG = "home-later"
internal const val NEXT_TAG = "home-next"
internal const val TODO_TAG = "home-todo"
internal const val PAST_TAG = "home-past"
internal const val TOMORROW_TAG = "home-tomorrow"
internal const val MONEY_TAG = "home-money"
internal const val BIRTHDAYS_TAG = "home-birthdays"
internal fun birthdayTag(id: String) = "home-birthday-$id"
internal const val MONEY_SETUP_TAG = "home-money-setup"
internal const val MONEY_TODAY_TAG = "home-money-today"
internal const val MONEY_MONTH_TAG = "home-money-month"
internal const val MONEY_TIER_TAG = "home-money-tier"
internal fun todoTag(kind: TodoKind) = "home-todo-${kind.name.lowercase()}"
internal fun dayRowTag(id: String) = "home-day-$id"

/** Место под «+» внизу ленты: последний пункт не уходит под кнопку. */
private val FAB_SPACE = 88.dp

/**
 * Пункт «Нужно сделать» (5.2): значок, текст, подпись, число справа ([count]; `null` — без числа). [trainingId] — что
 * открыть: группу без отметки — её карточку, «Без дневника» — дневник самой ранней; у пункта про копию — `null`,
 * он ведёт в «Настройки → Данные». [clientId] — карточка клиента: «Удалить данные клиента» — с ближайшим сроком.
 */
data class HomeTodo(
    val kind: TodoKind,
    val title: String,
    val note: String,
    val count: Int?,
    val trainingId: String? = null,
    val clientId: String? = null,
)

internal fun CalendarTraining.slot() = HomeSlot(id, start, durationMin, group, cancelled, marked)

/**
 * Карточка «Деньги» (5.2, шаг 3.9): [NotSet] — зарплата не настроена, «Настроить зарплату» (5.18); [Month] — текущий
 * месяц тем же расчётом, что вкладка «Месяц» «Зарплаты».
 */
sealed interface HomeMoney {
    data object NotSet : HomeMoney

    data class Month(val view: SalaryMonth) : HomeMoney
}

/** Синхронизация для «Нужно сделать»: сервер подключён; [late] — изменения ждут отправки больше суток (5.2). */
data class SyncTodo(val late: Boolean, val lastSync: LocalDateTime?)

/**
 * Пункты «Нужно сделать» в порядке ТЗ 5.2. С шага 1.16 — группы без отметки и копия (в режиме «Только телефон»),
 * с шага 2.1 — «Без дневника» ([noDiary] — `DiaryRepository.missing`), с шага 2.4 — клиенты без записи ([followup]),
 * с шага 2.4б — освободившееся время листа ожидания ([waiting]), с шага 2.10а — [sync]: сервер подключён — вместо
 * копии «Изменения не отправлены больше суток»; с шага 2.13а — отозванные согласия ([consents] —
 * `CabinetRepository.due`); с шага 3.7а — СПТ, ждущие покупки ([spt] — `SptRepository.waiting`); с шага 3.9 — аванс
 * ([advance] — `SalaryRepository.advance`): с дня аванса, пока сумма не введена; с шага 3.12 — кого предупредить
 * об отпуске ([notices] — `CalendarSettingsRepository.notices`). [backup] `null` — раздела копии нет
 * (тесты). Их число —
 * счётчик дел на значке «Главной».
 */
fun homeTodos(
    trainings: List<CalendarTraining>,
    backup: BackupState?,
    now: LocalDateTime,
    noDiary: List<MissingDiary> = emptyList(),
    followup: Followup = Followup.EMPTY,
    waiting: List<WaitRow> = emptyList(),
    sync: SyncTodo? = null,
    consents: List<ConsentDue> = emptyList(),
    spt: List<SptWaiting> = emptyList(),
    advance: AdvanceState? = null,
    notices: List<NoticeRow> = emptyList(),
    report1c: List<ReconciliationItemEntity> = emptyList(),
): List<HomeTodo> = buildList {
    val groups = HomeRules.unmarkedGroups(trainings.map { it.slot() }, now)
    if (groups.isNotEmpty()) {
        val byId = trainings.associateBy { it.id }
        add(
            HomeTodo(
                kind = TodoKind.UNMARKED_GROUPS,
                title = HomeFormat.unmarkedGroups(groups.size),
                note = HomeFormat.unmarkedGroupsNote(groups.map { it.start to byId.getValue(it.id).title }, now.date),
                count = groups.size,
                trainingId = groups.first().id,
            ),
        )
    }
    if (notices.isNotEmpty()) {
        // Кого предупредить об отпуске (5.16, шаг 3.12): нажатие — список с «Предупредил» у каждого.
        add(HomeTodo(TodoKind.VACATION_NOTICE, CalendarSettingsFormat.NOTICE_TODO, HomeFormat.names(notices.map { it.title }), count = notices.size))
    }
    if (noDiary.isNotEmpty()) {
        add(
            HomeTodo(
                kind = TodoKind.NO_DIARY,
                title = HomeFormat.NO_DIARY,
                note = HomeFormat.noDiaryNote(noDiary.map { it.start to it.title }, now.date),
                count = noDiary.size,
                trainingId = noDiary.first().id,
            ),
        )
    }
    val freed = waiting.filter { it.match != null }
    if (freed.isNotEmpty()) {
        val names = freed.map { CalendarFormat.client(ClientName(it.client.lastName, it.client.firstName)) }
        add(HomeTodo(TodoKind.FREED_TIME, WaitFormat.todo(freed.first().match!!, freed.size), WaitFormat.todoNote(names), count = freed.size))
    }
    if (followup.rows.isNotEmpty()) {
        val names = followup.rows.map { ClientName(it.client.lastName, it.client.firstName) }
        add(HomeTodo(TodoKind.NOT_BOOKED, FollowupFormat.todo(names.size), FollowupFormat.todoNote(names), count = names.size))
    }
    spt.firstOrNull()?.let { nearest ->
        // СПТ ждут покупки (4.9): ближайший срок и его клиент; нажатие — «Инфо» клиента с «Купил блок со скидкой».
        add(HomeTodo(TodoKind.SPT_PURCHASE, SptFormat.TODO, SptFormat.todoNote(nearest.case.windowUntil, nearest.name), count = spt.size, clientId = nearest.case.clientId))
    }
    // Расхождения с 1С (8.4, шаг 4.2в): открытые и «Написал администратору» старше 3 дней; нажатие — «Сверки».
    val open1c = report1c.count { it.resolution == ReconcileResolution.OPEN }
    val late1c = report1c.count { it.resolution == ReconcileResolution.ASKED_ADMIN && Reconcile1cRules.remind(it.resolvedAt, ClubTime.toMillis(now)) }
    if (open1c + late1c > 0) {
        add(HomeTodo(TodoKind.RECONCILE, ReconcileFormat.TODO_TITLE, ReconcileFormat.todoNote(open1c, late1c), count = open1c + late1c))
    }
    if (advance != null && advance.ask(now.date)) {
        // Аванс (4.11): нажатие — «Зарплата» → «Выплаты» с открытым окном суммы.
        add(HomeTodo(TodoKind.ADVANCE, HomeFormat.ADVANCE, HomeFormat.advanceNote(advance), count = null))
    }
    if (sync != null) {
        // «С сервером» (5.2): вместо напоминаний о копии — изменения, не отправленные больше суток.
        if (sync.late) add(HomeTodo(TodoKind.BACKUP, SyncFormat.LATE, SyncFormat.lateNote(sync.lastSync), count = null))
    } else if (backup != null) {
        HomeRules.backup(backup.folder != null, backup.last, now)?.let { todo ->
            add(HomeTodo(TodoKind.BACKUP, HomeFormat.backup(todo), HomeFormat.backupNote(todo, backup.last), count = null))
        }
    }
    if (consents.isNotEmpty()) {
        // Отозванные согласия (6.1): данные клиента удаляются в течение 30 дней, ближайший срок — в подписи.
        add(
            HomeTodo(
                kind = TodoKind.CONSENT_REVOKED,
                title = CabinetLinkFormat.deleteData(consents.size),
                note = CabinetLinkFormat.deleteNote(consents.map { it.name }, consents.first().deleteBy, now.date),
                count = consents.size,
                clientId = consents.first().clientId,
            ),
        )
    }
}.sortedBy { it.kind }

/**
 * «Главная» (ТЗ 5.2, шаг 1.16): «Ближайшая», «Нужно сделать» и «Деньги» (шаг 3.9). На разложенном Fold слева —
 * расписание дня, справа — блоки. Нажатие на тренировку открывает её карточку здесь же ([TrainingCardHost]), как в
 * календаре.
 *
 * Прокрутка и «Показать прошедшие» объявлены над веткой по ширине — сложение их не сбрасывает.
 *
 * @param trainings `CalendarRepository.homeTrainings` — от самой старой неотмеченной группы и вперёд.
 * @param todos [homeTodos]: их считает приложение — счётчик дел нужен и на значке «Главной».
 * @param card открытая карточка ([TrainingCardKey]); [onCard] — открыть или закрыть.
 * @param onDay «Ещё сегодня», «Сегодня всё» и «Завтра» — этот день в «Календаре».
 * @param onBackup пункт про копию — «Настройки → Данные».
 * @param onDiary «Дневник» у «Ближайшей» и в карточке, пункт «Без дневника» — экран дневника (5.7).
 * @param onNoBooking пункт «Клиенты без записи» — «Клиенты» с фильтром «Без записи».
 * @param onClientInfo пункт «СПТ ждут покупки» — карточка клиента на «Инфо».
 * @param money карточка «Деньги»; `null` — её нет (ещё не прочитана или тесты без зарплаты).
 * @param onSalary нажатие на «Деньги» и «Настроить зарплату» — раздел «Зарплата».
 * @param onAdvance пункт «Введите сумму аванса» — «Зарплата» → «Выплаты» с окном аванса.
 * @param notices пункт «Предупредить об отпуске» — окно со списком ([VacationNoticePanel]) здесь же.
 * @param birthdays блок «День рождения сегодня» (шаг 3.9р); нажатие на имя — карточка клиента ([onClient]).
 */
@Composable
fun HomeScreen(
    calendar: CalendarRepository,
    trainings: List<CalendarTraining>,
    todos: List<HomeTodo>,
    now: LocalDateTime,
    messages: MessageState,
    scope: CoroutineScope,
    card: String?,
    onCard: (String?) -> Unit,
    onDay: (LocalDate) -> Unit,
    onBackup: () -> Unit,
    onNoBooking: () -> Unit = {},
    onReconcile: () -> Unit = {},
    onEdit: (String) -> Unit,
    onClient: (String) -> Unit,
    onGroup: (String) -> Unit,
    onDiary: (String) -> Unit = {},
    onClientInfo: (String) -> Unit = onClient,
    money: HomeMoney? = null,
    onSalary: () -> Unit = {},
    onAdvance: () -> Unit = onSalary,
    notices: List<NoticeRow> = emptyList(),
    birthdays: List<Birthday> = emptyList(),
) {
    val wide = LocalWide.current
    var noticesOpen by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val dayScroll = rememberScrollState()
    var showPast by rememberSaveable { mutableStateOf(false) }
    val today = now.date
    val slots = remember(trainings) { trainings.map { it.slot() } }
    val byId = remember(trainings) { trainings.associateBy { it.id } }
    val nearestSlot = HomeRules.nearest(slots, now)
    val nearest = nearestSlot?.let { byId[it.id] }
    val nearestCard by remember(nearest?.id) { nearest?.let { calendar.cards.card(it.id) } ?: flowOf(null) }
        .collectAsState(initial = null)
    val open: (String) -> Unit = { id -> onCard(TrainingCardKey.card(id)) }
    val done: (String) -> Unit = { id ->
        scope.launch {
            calendar.cards.markDone(id)?.let { result -> messages.showUndo(result.message) { scope.launch { calendar.cards.undo(result.undo) } } }
        }
    }
    val onTodo: (HomeTodo) -> Unit = { todo ->
        val id = todo.trainingId
        val client = todo.clientId
        when {
            todo.kind == TodoKind.NOT_BOOKED || todo.kind == TodoKind.FREED_TIME -> onNoBooking()
            todo.kind == TodoKind.ADVANCE -> onAdvance()
            todo.kind == TodoKind.RECONCILE -> onReconcile()
            todo.kind == TodoKind.VACATION_NOTICE -> noticesOpen = true
            client != null && todo.kind == TodoKind.SPT_PURCHASE -> onClientInfo(client)
            client != null -> onClient(client)
            id == null -> onBackup()
            todo.kind == TodoKind.NO_DIARY -> onDiary(id)
            else -> open(id)
        }
    }

    @Composable
    fun Blocks(laterRow: Boolean) {
        // «Деньги» — первыми (просьба Дмитрия 26.10.2026): сегодня и месяц видны сразу.
        money?.let { MoneyBlock(it, today, onSalary) }
        NearestBlock(
            nearest = nearest,
            card = nearestCard?.takeIf { it.id == nearest?.id },
            later = nearestSlot?.takeIf { laterRow && !HomeRules.todayDone(it, today) }?.let { HomeRules.laterToday(slots, it, today) }.orEmpty(),
            now = now,
            onOpen = open,
            onDone = done,
            onDiary = onDiary,
            onDay = onDay,
        )
        // Именинники (шаг 3.9р) — между «Ближайшей» и делами: поздравить до тренировки. Нет — блока нет (10.7).
        if (birthdays.isNotEmpty()) BirthdayBlock(birthdays, onClient)
        if (todos.isNotEmpty()) TodoBlock(todos, onTodo)
    }

    TrainingCardHost(
        key = card,
        wide = wide,
        cards = calendar.cards,
        results = calendar.results,
        now = now,
        messages = messages,
        scope = scope,
        onClose = { onCard(null) },
        onEdit = onEdit,
        onClient = onClient,
        onGroup = onGroup,
        onCopy = { id -> scope.launch { if (calendar.copy(id)) messages.show(COPIED) } },
        onDiary = onDiary,
    ) {
      Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(dayScroll)) {
                    TopBar(DateFormat.header(today))
                    DaySchedule(
                        trainings = HomeRules.day(slots, today).mapNotNull { byId[it.id] },
                        tomorrow = HomeRules.tomorrow(slots, today).mapNotNull { byId[it.id] },
                        nearestId = nearest?.id,
                        now = now,
                        showPast = showPast,
                        onTogglePast = { showPast = !showPast },
                        onOpen = open,
                        onDay = onDay,
                    )
                    Spacer(Modifier.height(FAB_SPACE))
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(Dimens.space4),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                ) {
                    // На разложенном строки «Ещё сегодня» нет: весь день — слева.
                    Blocks(laterRow = false)
                    Spacer(Modifier.height(FAB_SPACE))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                TopBar(DateFormat.header(today))
                Column(
                    Modifier.padding(horizontal = Dimens.space4),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                ) {
                    Blocks(laterRow = true)
                    Spacer(Modifier.height(FAB_SPACE))
                }
            }
        }
        if (noticesOpen && notices.isNotEmpty()) {
            VacationNoticePanel(
                notices = notices,
                onDone = { row ->
                    scope.launch {
                        calendar.settings.notified(row.id, done = true)
                        messages.showUndo(CalendarSettingsFormat.noticeDone(row.title)) { scope.launch { calendar.settings.notified(row.id, done = false) } }
                    }
                },
                onCopied = { messages.show(CalendarSettingsFormat.NOTICE_COPIED) },
                onClose = { noticesOpen = false },
            )
        }
      }
    }
}

/** Карточка блока: surface, скругление 12 (макеты 0.6). Отступы внутри — у содержимого: нижние строки идут во всю ширину. */
@Composable
private fun Block(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(PersonalkaTheme.colors.surface),
    ) { content() }
}

/** Содержимое блока с отступом 16 и промежутком 8 между строками. */
@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) { content() }
}

/**
 * «Ближайшая» (5.2): время и «через N мин» (идущая — «Сейчас» и «до конца N мин», шаг 3.9ф), имя с полосой цвета места, «Тип · место · каждый вт и чт» (у группы —
 * «ждём 2 из 3»), план; с начала минус 15 минут — «Проведено» (у группы «Все пришли»); строка «Ещё сегодня».
 * Сегодня всё — «Сегодня всё. Завтра первая в 07:00» и строка следующей. «Дневник» появится с дневником (шаг 2.1).
 */
@Composable
private fun NearestBlock(
    nearest: CalendarTraining?,
    card: TrainingCard?,
    later: List<HomeSlot>,
    now: LocalDateTime,
    onOpen: (String) -> Unit,
    onDone: (String) -> Unit,
    onDiary: (String) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val today = now.date
    Block(Modifier.testTag(NEAREST_TAG)) {
        if (nearest == null || nearest.start.date != today) {
            Padded {
                Text(HomeFormat.NEAREST, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                Text(HomeFormat.todayDone(nearest?.start, today), style = MaterialTheme.typography.titleMedium.tabular(), color = colors.text)
            }
            if (nearest != null) {
                BlockRow(
                    text = HomeFormat.nextLine(nearest.start, nearest.title, nearest.typeShort, nearest.placeShort),
                    tag = NEXT_TAG,
                    onClick = { onDay(nearest.start.date) },
                )
            }
            return@Block
        }
        Padded { NearestBody(nearest, card, now, onOpen, onDone, onDiary) }
        if (later.isNotEmpty()) {
            BlockRow(text = HomeFormat.laterToday(later.size, later.last().start), tag = LATER_TAG, onClick = { onDay(today) })
        }
    }
}

@Composable
private fun NearestBody(
    nearest: CalendarTraining,
    card: TrainingCard?,
    now: LocalDateTime,
    onOpen: (String) -> Unit,
    onDone: (String) -> Unit,
    onDiary: (String) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val end = TrainingTime.end(nearest.start, nearest.durationMin)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            HomeFormat.nearest(nearest.start, end, now),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = colors.textMuted,
        )
        Text(HomeFormat.startsIn(now, nearest.start, end), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touch)
            .clickable(role = Role.Button) { onOpen(nearest.id) }
            .testTag(NEAREST_OPEN_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.width(Dimens.blockStripe).height(44.dp).clip(RoundedCornerShape(2.dp))
                .background(PlaceColor.of(nearest.color).tone(colors.isDark).stripe),
        )
        Column(Modifier.weight(1f)) {
            Text(nearest.title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(typeLine(nearest, card), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
    card?.plan?.takeIf { it.isNotBlank() }?.let { plan ->
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = MaterialTheme.typography.labelMedium.fontSize, fontWeight = MaterialTheme.typography.labelMedium.fontWeight, color = colors.textMuted)) {
                    append(TrainingCardFormat.PLAN)
                }
                append(" ")
                append(plan)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
        )
    }
    if (card != null && StatusRules.canMarkDone(card.state, now)) {
        val action = if (card.state.kind == TrainingKind.GROUP) CardAction.ALL_CAME else CardAction.DONE
        // «Дневник» рядом, как в карточке (5.2): план на следующую пишут сразу после занятия.
        Row(Modifier.fillMaxWidth().padding(top = Dimens.space1), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            PrimaryButton(
                text = TrainingCardFormat.action(action),
                onClick = { onDone(nearest.id) },
                modifier = Modifier.weight(1f).testTag(NEAREST_DONE_TAG),
            )
            if (DiaryRules.available(card.state, now, card.keepsDiary)) {
                SecondaryButton(
                    text = TrainingCardFormat.action(CardAction.DIARY),
                    onClick = { onDiary(nearest.id) },
                    modifier = Modifier.weight(1f).testTag(NEAREST_DIARY_TAG),
                    icon = PIcons.EditNote,
                )
            }
        }
    }
}

/** «ПТ · ББ · каждый вт и чт»; у группы — «Мини-группа · ДБ · ждём 2 из 3». Пока карточка не прочитана — без добавок. */
private fun typeLine(training: CalendarTraining, card: TrainingCard?): String {
    val base = TrainingCardFormat.typeLine(training.typeShort, training.placeShort, card?.seriesWeekdays)
    if (card == null || card.state.kind != TrainingKind.GROUP) return base
    val waiting = HomeFormat.waiting(card.clients.count { it.state.status == ParticipationStatus.EXPECTED }, card.clients.size)
    return if (waiting == null) base else "$base · $waiting"
}

/** Нижняя строка блока во всю ширину, над ней — линия: «Ещё сегодня: 3, последняя в 19:00 ›». */
@Composable
private fun BlockRow(text: String, tag: String, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touch)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(start = Dimens.space4, end = Dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** «День рождения сегодня» с тортом и строки «Ковалёв Артём · 9 лет»; нажатие — карточка клиента. */
@Composable
private fun BirthdayBlock(birthdays: List<Birthday>, onClient: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    Block(Modifier.testTag(BIRTHDAYS_TAG)) {
        Row(
            Modifier.padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Icon(PIcons.Cake, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Text(ClientFormat.BIRTHDAY_TODAY, style = MaterialTheme.typography.titleMedium, color = colors.text)
        }
        Column(Modifier.padding(bottom = Dimens.space1)) {
            birthdays.forEachIndexed { i, b ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = Dimens.space4), thickness = 1.dp, color = colors.divider)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.touch)
                        .clickable(role = Role.Button) { onClient(b.clientId) }
                        .semantics(mergeDescendants = true) {}
                        .testTag(birthdayTag(b.clientId))
                        .padding(start = Dimens.space4, end = Dimens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** «Нужно сделать · 3»: число у заголовка — счётчик дел, оранжевый, как на значке «Главной». */
@Composable
private fun TodoBlock(todos: List<HomeTodo>, onClick: (HomeTodo) -> Unit) {
    val colors = PersonalkaTheme.colors
    Block(Modifier.testTag(TODO_TAG)) {
        Row(
            Modifier.padding(start = Dimens.space4, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Text(HomeFormat.TODO, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Text(
                text = todos.size.toString(),
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .widthIn(min = 24.dp)
                    .background(colors.accent, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .clearAndSetSemantics { contentDescription = HomeFormat.todoCount(todos.size) },
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = colors.onAccent,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.padding(bottom = Dimens.space1)) {
            todos.forEachIndexed { i, todo ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), thickness = 1.dp, color = colors.divider)
                TodoRow(todo, icon = iconOf(todo.kind), dot = todo.kind == TodoKind.NO_DIARY, onClick = { onClick(todo) })
            }
        }
    }
}

private fun iconOf(kind: TodoKind): ImageVector = when (kind) {
    TodoKind.UNMARKED_GROUPS -> PIcons.Groups
    TodoKind.NOT_BOOKED -> PIcons.EventBusy
    TodoKind.FREED_TIME -> PIcons.EventAvailable
    TodoKind.BACKUP -> PIcons.FolderOpen
    TodoKind.NO_DIARY -> PIcons.EditNote
    TodoKind.CONSENT_REVOKED -> PIcons.Delete
    TodoKind.SPT_PURCHASE -> PIcons.ShoppingBag
    TodoKind.ADVANCE -> PIcons.Payments
    TodoKind.VACATION_NOTICE -> PIcons.Call
    TodoKind.RECONCILE -> PIcons.FactCheck
    else -> PIcons.Info
}

/** Строка дела: значок в круге (у «без дневника» — оранжевая точка), текст, подпись, число и стрелка. */
@Composable
private fun TodoRow(todo: HomeTodo, icon: ImageVector, dot: Boolean, onClick: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(todoTag(todo.kind))
            .padding(start = Dimens.space4, end = Dimens.space3, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Box(Modifier.size(40.dp).background(colors.surfaceAlt, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            if (dot) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(8.dp)
                        .background(colors.accentGraphic, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(todo.title, style = MaterialTheme.typography.labelLarge.tabular(), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(todo.note, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        todo.count?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge.tabular(), color = colors.text) }
        Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

/**
 * «Деньги» (5.2) — первый блок «Главной»: сверху «Деньги · октябрь» и плашка ступени; две колонки «Сегодня» и «За месяц» —
 * заработано, «будет ≈ …» по расписанию и ПТ «2 ПТ · будет 5», под ней отмены «2 отмены», если были. Ниже полоса
 * плана — «заряжается» ступенью (шаг 3.9с, просьба Дмитрия): на ступени 1 проведено основным цветом, со 101-й ПТ —
 * зелёным («бонус» действует на весь месяц), со 151-й — зелёным с косыми штрихами; прогноз — цветом рамок, конец
 * полосы — план. Под полосой оценка с точкой: зелёная «Хорошо идёте…», «План выполнен…», янтарная «Поднажать…».
 * Плана нет — ни полосы, ни оценки. Нажатие — «Зарплата». Не настроена — приглашение и «Настроить зарплату» (5.18).
 */
@Composable
private fun MoneyBlock(money: HomeMoney, today: LocalDate, onSalary: () -> Unit) {
    val colors = PersonalkaTheme.colors
    when (money) {
        HomeMoney.NotSet -> Block(Modifier.testTag(MONEY_TAG)) {
            Padded {
                Text(HomeFormat.MONEY, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                Text(HomeFormat.SALARY_NOT_SET, style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(HomeFormat.SALARY_NOT_SET_NOTE, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                PrimaryButton(
                    text = HomeFormat.SALARY_SETUP,
                    onClick = onSalary,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.space1).testTag(MONEY_SETUP_TAG),
                )
            }
        }
        is HomeMoney.Month -> {
            val view = money.view
            val tier = view.tiers?.tier ?: 1
            Block(Modifier.clickable(role = Role.Button, onClick = onSalary).testTag(MONEY_TAG)) {
                Padded {
                    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(HomeFormat.moneyTitle(today), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                        HomeFormat.tierChip(view.tiers)?.let { TierChip(it, tier) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        val earnedToday = view.today ?: 0
                        val donePt = view.todayPt ?: 0
                        MoneyColumn(
                            title = HomeFormat.TODAY,
                            earned = earnedToday,
                            forecast = earnedToday + (view.todayAhead ?: 0),
                            pt = HomeFormat.ptLine(donePt, donePt + (view.todayPtAhead ?: 0)),
                            cancelled = HomeFormat.cancelledLine(view.todayCancelled ?: 0),
                            modifier = Modifier.weight(1f).testTag(MONEY_TODAY_TAG),
                        )
                        MoneyColumn(
                            title = HomeFormat.MONTH,
                            earned = view.pay.earned,
                            forecast = view.pay.forecast,
                            pt = HomeFormat.ptLine(view.pay.ptCount, view.pay.forecastPtCount),
                            cancelled = HomeFormat.cancelledLine(view.monthCancelled),
                            modifier = Modifier.weight(1f).testTag(MONEY_MONTH_TAG),
                        )
                    }
                    val plan = view.plan
                    val pace = SalaryPlan.pace(view.pay.earned, view.pay.forecast, plan)
                    if (plan != null && pace != null) {
                        val done = (view.pay.earned.toFloat() / plan).coerceIn(0f, 1f)
                        val forecast = (view.pay.forecast.toFloat() / plan).coerceIn(done, 1f)
                        ChargedBar(done, forecast, tier)
                        Row(
                            Modifier.padding(top = Dimens.space1),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                        ) {
                            Box(Modifier.size(8.dp).background(if (pace == PlanPace.BEHIND) colors.charged else colors.done, CircleShape))
                            Text(
                                HomeFormat.pace(pace, view.percent ?: 0, view.forecastPercent ?: 0, view.shortfall?.pts),
                                style = MaterialTheme.typography.labelLarge.tabular(),
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Плашка ступени: на ступени 1 — серая; со ступени 2 — зелёная с молнией, на ступени 3 — с двумя. */
@Composable
private fun TierChip(text: String, tier: Int) {
    val colors = PersonalkaTheme.colors
    val bonus = tier > 1
    Row(
        Modifier
            .background(if (bonus) colors.doneContainer else colors.surfaceAlt, CircleShape)
            .padding(start = if (bonus) 8.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
            .testTag(MONEY_TIER_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(tier - 1) { Icon(PIcons.Bolt, contentDescription = null, tint = colors.onDoneContainer, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium.tabular(), color = if (bonus) colors.onDoneContainer else colors.text, maxLines = 1)
    }
}

/** Полоса плана, «заряженная» ступенью: проведено — основным цветом, со ступени 2 — зелёным, на ступени 3 — со штрихами. */
@Composable
private fun ChargedBar(done: Float, forecast: Float, tier: Int) {
    val colors = PersonalkaTheme.colors
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.surfaceAlt)) {
        if (forecast > 0f) Box(Modifier.fillMaxWidth(forecast).fillMaxHeight().background(colors.outline))
        if (done > 0f) {
            Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(if (tier > 1) colors.done else colors.primary)) {
                if (tier >= 3) Sparks()
            }
        }
    }
}

/** Косые светлые штрихи по проведённой части полосы на ступени 3 — «заряд максимальный», без анимации. */
@Composable
private fun Sparks() {
    val stroke = Color.White.copy(alpha = 0.28f)
    Canvas(Modifier.fillMaxSize()) {
        val step = 9.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(stroke, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
            x += step
        }
    }
}

/** Колонка «Денег»: заголовок, заработано, «будет ≈ …», строка ПТ и отмены, если они есть. */
@Composable
private fun MoneyColumn(title: String, earned: Long, forecast: Long, pt: String, cancelled: String?, modifier: Modifier = Modifier) {
    val colors = PersonalkaTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        Text(MoneyFormat.rub(earned), style = MaterialTheme.typography.titleLarge.tabular(), color = colors.text, maxLines = 1)
        Text(HomeFormat.willBe(earned, forecast), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1)
        Text(pt, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.text, maxLines = 1)
        cancelled?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted, maxLines = 1) }
    }
}

/**
 * Расписание дня на разложенном (5.2): прошедшие свёрнуты в «Показать прошедшие · 7», оставшиеся — время, имя,
 * «тип · место» и статус; ближайшая выделена и с «через N мин»; внизу — «Завтра: 9 тренировок, первая в 07:00».
 */
@Composable
private fun DaySchedule(
    trainings: List<CalendarTraining>,
    tomorrow: List<CalendarTraining>,
    nearestId: String?,
    now: LocalDateTime,
    showPast: Boolean,
    onTogglePast: () -> Unit,
    onOpen: (String) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val (past, rest) = trainings.partition { HomeRules.isPast(it.slot(), now) }
    if (past.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touch)
                .clickable(role = Role.Button, onClick = onTogglePast)
                .testTag(PAST_TAG)
                .padding(horizontal = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Icon(if (showPast) PIcons.ExpandLess else PIcons.ExpandMore, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            Text(
                if (showPast) HomeFormat.HIDE_PAST else HomeFormat.showPast(past.size),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.textMuted,
            )
        }
    }
    if (trainings.isEmpty()) {
        Text(
            HomeFormat.NO_TRAININGS_TODAY,
            Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space3),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textMuted,
        )
    }
    Column(Modifier.padding(horizontal = Dimens.space2)) {
        (if (showPast) past + rest else rest).forEach { t ->
            val isNearest = t.id == nearestId
            EntryRow(
                time = DateFormat.time(t.start.time),
                stripe = if (t.cancelled) colors.outline else PlaceColor.of(t.color).tone(colors.isDark).stripe,
                title = t.title,
                line = HomeFormat.dayLine(t.typeShort, t.placeShort, if (isNearest) HomeFormat.startsIn(now, t.start, t.slot().end) else null),
                crossed = t.cancelled,
                badges = t.badges,
                tag = dayRowTag(t.id),
                onClick = { onOpen(t.id) },
                status = HomeFormat.ONGOING.takeIf { HomeRules.isOngoing(t.slot(), now) && !isNearest },
                highlighted = isNearest,
            )
        }
    }
    Text(
        text = HomeFormat.tomorrow(tomorrow.size, tomorrow.firstOrNull()?.start),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onDay(now.date.plus(1, DateTimeUnit.DAY)) }
            .testTag(TOMORROW_TAG)
            .padding(horizontal = Dimens.space4, vertical = Dimens.space3),
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = colors.textMuted,
    )
}
