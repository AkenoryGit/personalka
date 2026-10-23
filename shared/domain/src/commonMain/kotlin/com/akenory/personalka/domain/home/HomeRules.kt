package com.akenory.personalka.domain.home

import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.StatusRules
import com.akenory.personalka.domain.training.StatusSettings
import com.akenory.personalka.domain.training.TrainingState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/**
 * Тренировка для «Главной» (ТЗ 5.2): состояние для правил статусов и то, что видно в блоке «Ближайшая».
 *
 * @param title «Орлов М.», «Ильин / Лаптев / Носова», «Вт, Чт 18:00 ДБ (10)».
 * @param color ключ цвета места — полоса слева от имени.
 * @param weekdays дни серии — «каждый вт и чт»; разовая — `null`.
 */
data class HomeTraining(
    val id: String,
    val state: TrainingState,
    val title: String,
    val typeShort: String,
    val placeShort: String,
    val color: String = "",
    val plan: String? = null,
    val weekdays: Set<DayOfWeek>? = null,
) {
    val start: LocalDateTime get() = state.start
    val end: LocalDateTime get() = state.end
}

/**
 * Блок «Ближайшая тренировка» (5.2).
 *
 * @param next ближайшая сегодня; `null` — сегодня всё.
 * @param moreToday сколько ещё сегодня после неё; [lastToday] — начало последней.
 * @param later первая тренировка следующего дня, где они есть: строка под «Сегодня всё» или под ближайшей,
 *   если она сегодня последняя. `null` — впереди ничего нет.
 */
data class Upcoming(
    val next: HomeTraining?,
    val moreToday: Int,
    val lastToday: LocalDateTime?,
    val later: HomeTraining?,
)

/** Пункт «Нужно сделать» (5.2) — куда ведёт нажатие и какой значок. */
enum class TodoKind {
    /** Прошла, а отметки нет: группа, где никто не отмечен, или ПТ без «Проведено» при выключенном автозакрытии. */
    UNMARKED,

    /** Папка резервной копии не выбрана. */
    BACKUP_FOLDER,

    /** Копии нет больше 7 дней. */
    BACKUP_OLD,
}

/**
 * Строка «Нужно сделать»: значок по [kind], [title], подпись [note] (имена или время), число справа — [count]
 * (`null` — числа нет). [trainingId] — карточка, которую открывает нажатие.
 */
data class TodoItem(
    val kind: TodoKind,
    val title: String,
    val note: String,
    val count: Int?,
    val trainingId: String? = null,
)

/** Что известно о резервной копии для «Нужно сделать»: выбрана ли папка и когда была последняя копия (7.6). */
data class BackupFacts(val folderChosen: Boolean, val last: LocalDateTime?)

/**
 * «Главная» без денег (ТЗ 5.2): ближайшая тренировка и «Нужно сделать». Дежурства здесь не тренировки: отмечать
 * в них нечего, они закрываются сами (4.1).
 */
object HomeRules {
    /** На сколько дней вперёд искать ближайшую, если сегодня и завтра пусто: отпуск дольше бывает редко. */
    const val AHEAD_DAYS = 62

    /** Сколько тренировок называть в подписи пункта, остальные — «и ещё N». */
    const val NOTE_NAMES = 2

    /**
     * Ближайшая — следующая по началу сегодня: не отменена, не проведена. Идущая ПТ не показывается — она отметится
     * сама; идущая группа — показывается: её отмечают по детям, пока она идёт.
     */
    fun upcoming(trainings: List<HomeTraining>, now: LocalDateTime): Upcoming {
        val today = now.date
        val open = trainings
            .filter { it.state.kind != TrainingKind.DUTY && it.state.status == TrainingStatus.PLANNED }
            .sortedWith(compareBy({ it.start }, { it.id }))
        val next = open.firstOrNull { t ->
            t.start.date == today && (t.start > now || t.state.kind == TrainingKind.GROUP && now < t.end)
        }
        val rest = if (next == null) emptyList() else open.filter { it.start.date == today && it.start > next.start && it.id != next.id }
        val later = if (rest.isEmpty()) open.firstOrNull { it.start.date > today } else null
        return Upcoming(next, rest.size, rest.lastOrNull()?.start, later)
    }

    /** Кнопки «Проведено» / «Все пришли» видны с начала минус 15 минут, как в карточке (5.4). */
    fun canMark(training: HomeTraining, now: LocalDateTime): Boolean = StatusRules.canMarkDone(training.state, now)

    /**
     * Прошли, а отметки нет (4.1): то, что не закроется в конце само, — группа без единого отмеченного, ПТ при
     * выключенном «закрывать само». Сначала старые.
     */
    fun unmarked(trainings: List<HomeTraining>, now: LocalDateTime, settings: StatusSettings): List<HomeTraining> =
        trainings
            .filter { t ->
                t.state.kind != TrainingKind.DUTY && StatusRules.needsMarking(t.state, now) &&
                    StatusRules.closeAtEnd(t.state, now, settings) == t.state
            }
            .sortedWith(compareBy({ it.start }, { it.id }))

    /**
     * «Нужно сделать» по порядку 5.2; пустой список — блока нет. Дневник (2.1), лист ожидания и клиенты без записи
     * (2.4), деньги (этап 3), сверка (4.2) и согласия (2.12) добавят свои пункты. [backup] — `null`, если копии
     * на этом устройстве нет вовсе (тесты экранов).
     */
    fun todo(trainings: List<HomeTraining>, now: LocalDateTime, settings: StatusSettings, backup: BackupFacts?): List<TodoItem> = buildList {
        val unmarked = unmarked(trainings, now, settings)
        if (unmarked.isNotEmpty()) {
            add(
                TodoItem(
                    kind = TodoKind.UNMARKED,
                    title = HomeFormat.unmarkedTitle(unmarked.size, groups = unmarked.all { it.state.kind == TrainingKind.GROUP }),
                    note = HomeFormat.names(unmarked.map { HomeFormat.unmarkedEntry(it.title, it.start, now.date) }, NOTE_NAMES),
                    count = unmarked.size,
                    trainingId = unmarked.first().id,
                ),
            )
        }
        when {
            backup == null -> Unit
            !backup.folderChosen -> add(TodoItem(TodoKind.BACKUP_FOLDER, HomeFormat.BACKUP_FOLDER, HomeFormat.BACKUP_FOLDER_NOTE, count = null))
            BackupRules.remind(backup.last, now) -> add(TodoItem(TodoKind.BACKUP_OLD, HomeFormat.BACKUP_OLD, HomeFormat.backupLast(backup.last), count = null))
        }
    }

    /** С какого дня по какой (не включая) читать тренировки для «Главной»: сегодня и [AHEAD_DAYS] дней вперёд. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = today to today.plus(AHEAD_DAYS, DateTimeUnit.DAY)
}
