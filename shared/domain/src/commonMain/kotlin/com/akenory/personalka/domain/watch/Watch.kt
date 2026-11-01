package com.akenory.personalka.domain.watch

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.HomeSlot
import com.akenory.personalka.domain.training.StatusRules
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/**
 * Тренировка для часов (шаг 5.3т): только то, что видно на запястье. Отменённые на часы не попадают.
 *
 * @param typeShort короткое название типа — «ПТ», «Сплит», «Группа».
 * @param name «Никитин А.» у ПТ и сплита, название группы у группы; `null` — тренер выключил имена на часах.
 * @param marked проведена или в группе отмечен хоть один ребёнок — для «Ближайшей» её уже нет.
 */
data class WatchSlot(
    val id: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val typeShort: String,
    val placeShort: String,
    val name: String?,
    val group: Boolean,
    val marked: Boolean,
) {
    val end: LocalDateTime get() = TrainingTime.end(start, durationMin)
}

/**
 * Что телефон отправляет на часы (шаг 5.3т). Расписание — на несколько дней вперёд, поэтому часы сами переключают
 * «Ближайшую» по времени, даже если телефон давно не открывали. Деньги — на день [date]: заработано [earned] и
 * ожидается ещё [ahead] сегодня, выполнение плана месяца [percent] и прогноз [forecastPercent]; зарплата не
 * настроена — `null`. [todo] — счётчик дел «Главной».
 */
data class WatchDay(
    val date: LocalDate,
    val slots: List<WatchSlot>,
    val earned: Long? = null,
    val ahead: Long? = null,
    val percent: Int? = null,
    val forecastPercent: Int? = null,
    val todo: Int = 0,
)

/** Деньги дня на часах: [earned] уже заработано, [evening] — к концу дня, если всё пройдёт по расписанию. */
data class WatchMoney(val earned: Long, val evening: Long)

/** Правила часов (шаг 5.3т): что отправить и что показать. Расчёты — те же, что у «Главной» (ТЗ 5.2). */
object WatchRules {
    /** Сколько дней расписания уходит на часы: сегодня и ещё два. */
    const val DAYS: Int = 3

    /** Больше тренировок часы не хранят: ячейкам и плитке нужны ближайшие. */
    const val MAX_SLOTS: Int = 40

    /**
     * Расписание для часов: неотменённые тренировки на [DAYS] дней, кроме закончившихся; [names] выключены — имён
     * нет.
     */
    fun slots(all: List<WatchSlot>, now: LocalDateTime, names: Boolean): List<WatchSlot> {
        val until = now.date.plus(DAYS, DateTimeUnit.DAY)
        return all.asSequence()
            .filter { it.start.date < until && it.end > now }
            .sortedWith(compareBy({ it.start }, { it.id }))
            .take(MAX_SLOTS)
            .map { if (names) it else it.copy(name = null) }
            .toList()
    }

    /** «Ближайшая» на часах — тем же правилом, что на «Главной»: идущая сейчас или следующая по началу. */
    fun nearest(day: WatchDay, now: LocalDateTime): WatchSlot? {
        val id = HomeRules.nearest(day.slots.map { it.home() }, now)?.id ?: return null
        return day.slots.first { it.id == id }
    }

    /** Сегодняшние после «Ближайшей» — для строки «Ещё сегодня: 3, последняя в 19:00». */
    fun laterToday(day: WatchDay, nearest: WatchSlot, now: LocalDateTime): List<WatchSlot> {
        val ids = HomeRules.laterToday(day.slots.map { it.home() }, nearest.home(), now.date).map { it.id }.toSet()
        return day.slots.filter { it.id in ids && !it.marked }
    }

    /** Кнопка «Проведено» / «Все пришли» на часах — как в карточке: с начала минус 15 минут (4.1). */
    fun canMark(slot: WatchSlot, now: LocalDateTime): Boolean =
        !slot.marked && now >= ClubTime.plusMinutes(slot.start, -StatusRules.MARK_BEFORE_START_MINUTES)

    /** Нажали «Проведено» на часах: тренировка отмечена сразу, не дожидаясь ответа телефона. */
    fun marked(day: WatchDay, id: String): WatchDay =
        day.copy(slots = day.slots.map { if (it.id == id) it.copy(marked = true) else it })

    /** Деньги дня; данные за другой день (телефон давно не открывали) — `null`, чтобы не показать вчерашнее. */
    fun money(day: WatchDay, today: LocalDate): WatchMoney? {
        if (day.date != today) return null
        val earned = day.earned ?: return null
        return WatchMoney(earned, earned + (day.ahead ?: 0))
    }

    /** План месяца в процентах: только за текущий день, как и деньги. */
    fun percent(day: WatchDay, today: LocalDate): Int? = day.percent.takeIf { day.date == today }

    /** Дела «Главной»: счётчик со вчерашнего дня уже не верен. */
    fun todo(day: WatchDay, today: LocalDate): Int? = day.todo.takeIf { day.date == today }

    private fun WatchSlot.home() = HomeSlot(id, start, durationMin, group, cancelled = false, marked = marked)
}

/**
 * Передача [WatchDay] с телефона на часы (шаг 5.3т): простой текст — строка дня и строка на тренировку, поля через
 * табуляцию. Табуляции и переводы строк в названиях заменяются пробелом. Чужая версия — `null`.
 */
object WatchCodec {
    private const val VERSION = "w1"
    private const val TAB = '\t'

    fun encode(day: WatchDay): String = buildString {
        append(listOf(VERSION, day.date, day.earned, day.ahead, day.percent, day.forecastPercent, day.todo).joinToString(TAB.toString()) { it?.toString().orEmpty() })
        day.slots.forEach { s ->
            append('\n')
            append(
                listOf(s.id, s.start, s.durationMin, clean(s.typeShort), clean(s.placeShort), s.name?.let(::clean).orEmpty(), flag(s.group), flag(s.marked))
                    .joinToString(TAB.toString()),
            )
        }
    }

    fun decode(text: String): WatchDay? = runCatching {
        val lines = text.split('\n')
        val head = lines.first().split(TAB)
        if (head[0] != VERSION) return null
        WatchDay(
            date = LocalDate.parse(head[1]),
            earned = head[2].toLongOrNull(),
            ahead = head[3].toLongOrNull(),
            percent = head[4].toIntOrNull(),
            forecastPercent = head[5].toIntOrNull(),
            todo = head[6].toInt(),
            slots = lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val f = line.split(TAB)
                WatchSlot(
                    id = f[0],
                    start = LocalDateTime.parse(f[1]),
                    durationMin = f[2].toInt(),
                    typeShort = f[3],
                    placeShort = f[4],
                    name = f[5].ifEmpty { null },
                    group = f[6] == "1",
                    marked = f[7] == "1",
                )
            },
        )
    }.getOrNull()

    private fun clean(s: String) = s.replace(TAB, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
