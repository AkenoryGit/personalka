package com.akenory.personalka.ui.reminders

import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.money.SalaryRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.reminders.DiaryPrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.reminder.Reminder
import com.akenory.personalka.domain.reminder.ReminderRules
import com.akenory.personalka.domain.reminder.ReminderSettings
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.home.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

/** Канал уведомления: его видно в настройках телефона, каждый можно выключить отдельно. */
enum class NoticeChannel { DIGEST, INSTANT }

/**
 * Уведомление, которое пора показать. [key] — одно и то же у повторного уведомления о том же: новое заменяет старое.
 * [open] — куда ведёт нажатие, [diary] — кнопка «Дневник» (только у сводки, когда есть тренировки без дневника).
 */
data class Notice(
    val key: String,
    val channel: NoticeChannel,
    val title: String,
    val text: String,
    val open: AppLink,
    val diary: AppLink? = null,
)

/** Что показать сейчас и когда проверить в следующий раз; `null` — напоминания выключены, будильник не нужен. */
data class ReminderCheck(val notices: List<Notice>, val next: LocalDateTime?)

/**
 * Напоминания тренера (ТЗ 5.19) — то, что телефон делает по будильнику и без открытого приложения: закрывает
 * прошедшие, продлевает серии (5.5), считает «Нужно сделать» тем же [homeTodos], что «Главная», и решает по
 * [ReminderRules], что показать. Все уведомления локальные.
 *
 * Время прошлой проверки хранится в [prefs]: проверка берёт всё, что наступило с неё, — будильник, который телефон
 * задержал, ничего не теряет, а повторный не повторяет.
 *
 * @param backup копия для пункта «нет резервной копии»; `null` — пункта нет.
 * @param salary аванс для уведомления 25-го и пункта «Введите сумму аванса»; `null` — их нет (тесты без зарплаты).
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class Reminders(
    private val calendar: CalendarRepository,
    private val clients: ClientRepository,
    private val backup: BackupRepository?,
    private val prefs: DevicePrefs,
    private val clock: () -> Long,
    private val salary: SalaryRepository? = null,
) {
    /** «План на день» — настройка этого телефона (в базе тренера для неё поля нет). */
    var dayPlan: Boolean
        get() = prefs.boolean(DAY_PLAN) ?: false
        set(value) = prefs.putBoolean(DAY_PLAN, value)

    /** «Аванс» 25-го в 19:00 — настройка этого телефона, по умолчанию включена (5.19). */
    var advance: Boolean
        get() = prefs.boolean(ADVANCE) ?: true
        set(value) = prefs.putBoolean(ADVANCE, value)

    private fun settings(diary: DiaryPrefs) =
        ReminderSettings(diary.digest, diary.digestTime, diary.instantDiary && diary.diary, diary.instantGroup, dayPlan, advance)

    /**
     * Дни аванса, сумма которого ещё не введена: этого месяца и следующего — после 25-го будильник ставится сразу
     * на следующий месяц. Схема без аванса — пусто.
     */
    private fun advanceDays(today: LocalDate): Flow<List<LocalDate>> {
        val repo = salary ?: return flowOf(emptyList())
        val month = today.yearMonth
        val next = month.firstDay.plus(1, DateTimeUnit.MONTH).yearMonth
        return combine(repo.advance(month), repo.advance(next)) { a, b -> listOfNotNull(a, b).filter { it.actual == null }.map { it.date } }
    }

    /** Проверка по будильнику: что показать с прошлой проверки и когда следующая. До первого запуска — ничего. */
    suspend fun check(): ReminderCheck {
        val now = ClubTime.now(clock())
        val last = prefs.string(CHECKED)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: now
        prefs.putString(CHECKED, now.toString())
        if (!calendar.reminders.ready()) return ReminderCheck(emptyList(), null)
        calendar.cards.closeEnded()
        calendar.forms.extendSeries()
        val settings = settings(calendar.reminders.prefs.first())
        val home = calendar.homeTrainings(now.date).first()
        val slots = home.map { it.slot() }
        val noDiary = calendar.diaries.missing.first()
        val advance = salary?.advance(now.date.yearMonth)?.first()
        val advanceDays = advanceDays(now.date).first()
        val due = ReminderRules.due(last, now, settings, slots, noDiary.map { it.id }.toSet(), advanceDays)
        val byId = home.associateBy { it.id }
        val notices = due.mapNotNull { reminder ->
            when (reminder) {
                Reminder.Digest -> {
                    val todos = homeTodos(
                        home,
                        backup?.state?.value,
                        now,
                        noDiary,
                        clients.followup.observe(now).first(),
                        clients.waitlist.observe(now).first(),
                        // В сводке — только СПТ, у которых до конца окна 3 дня или меньше (5.19, 4.9, п. 6).
                        spt = calendar.cards.spt.waiting(now.date).first().filter { it.remind },
                        advance = advance,
                    )
                    if (todos.isEmpty()) {
                        null
                    } else {
                        Notice(
                            key = DIGEST_KEY,
                            channel = NoticeChannel.DIGEST,
                            title = ReminderFormat.digestTitle(todos.size),
                            text = ReminderFormat.digest(todos.map { it.kind to it.count }),
                            open = AppLink.Home,
                            diary = AppLink.FirstDiary.takeIf { noDiary.isNotEmpty() },
                        )
                    }
                }
                Reminder.DayPlan -> {
                    val today = HomeRules.day(slots, now.date).filter { !it.cancelled }
                    today.firstOrNull()?.let { first ->
                        Notice(DAY_PLAN_KEY, NoticeChannel.INSTANT, ReminderFormat.DAY_PLAN_TITLE, ReminderFormat.dayPlan(today.size, first.start), AppLink.Home)
                    }
                }
                Reminder.Advance -> Notice(ADVANCE_KEY, NoticeChannel.INSTANT, ReminderFormat.ADVANCE_TITLE, ReminderFormat.ADVANCE_TEXT, AppLink.Advance)
                is Reminder.Diary -> byId[reminder.id]?.let { t ->
                    Notice(t.id, NoticeChannel.INSTANT, t.title, ReminderFormat.instantDiary(t.start, t.slot().end), AppLink.Diary(t.id))
                }
                is Reminder.Group -> byId[reminder.id]?.let { t ->
                    Notice(t.id, NoticeChannel.INSTANT, t.title, ReminderFormat.instantGroup(t.start, t.slot().end), AppLink.Card(t.id))
                }
            }
        }
        return ReminderCheck(notices, ReminderRules.next(now, settings, slots, advanceDays))
    }

    /**
     * Следующая проверка — заново при каждом изменении настроек и тренировок, пока приложение открыто: новая
     * тренировка на сегодня сразу получает своё «Дневник сразу».
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun nextCheck(): Flow<LocalDateTime?> = calendar.reminders.prefs.flatMapLatest { diary ->
        val today = ClubTime.now(clock()).date
        combine(calendar.homeTrainings(today), advanceDays(today)) { home, advance ->
            ReminderRules.next(ClubTime.now(clock()), settings(diary), home.map { it.slot() }, advance)
        }
    }

    companion object {
        /** Время прошлой проверки по часам клуба. */
        const val CHECKED = "reminders.checkedAt"
        const val DAY_PLAN = "reminders.dayPlan"
        const val ADVANCE = "reminders.advance"
        const val ADVANCE_KEY = "advance"
        const val DIGEST_KEY = "digest"
        const val DAY_PLAN_KEY = "dayPlan"
    }
}
