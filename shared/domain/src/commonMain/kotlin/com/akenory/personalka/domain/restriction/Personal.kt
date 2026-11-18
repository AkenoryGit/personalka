package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.DateFormat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

// Рабочий график (шаг 5.10д), личные дела и напоминания (шаги 5.10е, 5.10ж) — просьба Дмитрия 18.11.2026.
// Всё, что занимает время, превращается в запреты «Все места»: проверка при записи (одно окно и на серию),
// флаг «поверх запрета» и свободные слоты работают по тем же правилам 4.5, что и запреты.

/** Рабочие часы одного дня: [start]–[end], конец 00:00 — до полуночи. */
data class DayHours(val start: LocalTime, val end: LocalTime) {
    val startMinute: Int get() = start.hour * 60 + start.minute
    val endMinute: Int get() = (end.hour * 60 + end.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
}

/**
 * Рабочий график тренера по дням недели (шаг 5.10д): у дня — часы или `null` (выходной). Свободные слоты ищутся
 * только в эти часы; тренировку вне графика поставить можно — форма спрашивает, как при запрете.
 */
data class WorkSchedule(val days: Map<DayOfWeek, DayHours?>) {
    fun on(day: DayOfWeek): DayHours? = days[day]

    /** Строка для базы: семь дней с понедельника через запятую, «07:00-21:00» или «-» — выходной. */
    fun encode(): String = DateFormat.WEEK.joinToString(",") { d -> days[d]?.let { "${it.start}-${it.end}" } ?: OFF }

    /**
     * Время вне графика — запреты «Все места» вида [BlockKind.SCHEDULE]: у рабочего дня — до начала и после конца,
     * у выходного — весь день. Действуют всегда, с [FROM].
     */
    fun restrictions(reason: String): List<Restriction> = DateFormat.WEEK.flatMap { day ->
        val hours = days[day]
        fun r(part: String, start: Int, end: Int) = Restriction(
            id = "$ID_PREFIX$day-$part",
            locationId = Restriction.ALL_PLACES,
            reason = reason,
            startTime = time(start),
            endTime = time(end % ClubTime.MINUTES_PER_DAY),
            weekdays = setOf(day),
            validFrom = FROM,
            kind = BlockKind.SCHEDULE,
        )
        if (hours == null) {
            listOf(r("day", 0, ClubTime.MINUTES_PER_DAY))
        } else {
            listOfNotNull(
                r("early", 0, hours.startMinute).takeIf { hours.startMinute > 0 },
                r("late", hours.endMinute, ClubTime.MINUTES_PER_DAY).takeIf { hours.endMinute < ClubTime.MINUTES_PER_DAY },
            )
        }
    }

    companion object {
        private const val OFF = "-"
        const val ID_PREFIX = "work-"
        val FROM: LocalDate = LocalDate(2000, 1, 1)

        /** График по умолчанию при включении: пн–пт 07:00–21:00, сб 09:00–14:00, вс — выходной. */
        val DEFAULT = WorkSchedule(
            DateFormat.WEEK.associateWith { d ->
                when (d) {
                    DayOfWeek.SATURDAY -> DayHours(LocalTime(9, 0), LocalTime(14, 0))
                    DayOfWeek.SUNDAY -> null
                    else -> DayHours(LocalTime(7, 0), LocalTime(21, 0))
                }
            },
        )

        /** Из строки базы; пусто или испорчено — `null` (графика нет). */
        fun decode(text: String?): WorkSchedule? {
            val parts = text?.split(',') ?: return null
            if (parts.size != DateFormat.WEEK.size) return null
            val days = DateFormat.WEEK.zip(parts).associate { (day, part) ->
                day to if (part == OFF) null else runCatching {
                    val (a, b) = part.split('-')
                    DayHours(LocalTime.parse(a), LocalTime.parse(b))
                }.getOrNull()
            }
            return WorkSchedule(days)
        }

        private fun time(minute: Int) = LocalTime(minute / 60, minute % 60)
    }
}

/** Ошибка часов дня графика: конец позже начала (00:00 — полночь). `null` — подходит. */
fun DayHours.problem(): String? = if (endMinute <= startMinute) "Конец позже начала" else null

/** Уведомление о личном деле или напоминании: [at] — когда показать, [start] — начало занятия. */
data class PersonalAlert(
    val id: String,
    val date: LocalDate,
    val at: LocalDateTime,
    val start: LocalDateTime,
    val title: String,
    val kind: BlockKind,
    val travelMinutes: Int = 0,
) {
    /** Ключ уведомления: то же занятие в тот же день — то же уведомление. */
    val key: String get() = "personal-$id-$date"
}

object PersonalRules {
    /** Дорога до и после дела (шаг 5.10е): нет, 15 и 30 минут, час, полтора. */
    val TRAVELS: List<Int> = listOf(0, 15, 30, 60, 90)

    /** «Напомнить» у дела: нет, за 5, 15, 30 минут, за час. */
    val TASK_NOTIFY: List<Int?> = listOf(null, 5, 15, 30, 60)

    /** «Уведомление» у напоминания: в это время, за 5, 10, 30 минут, за час. */
    val REMINDER_NOTIFY: List<Int> = listOf(0, 5, 10, 30, 60)

    /** Сколько минут напоминание занимает в календаре: только чтобы на него можно было нажать. */
    const val REMINDER_SPAN = 15

    /** Цвет личных дел по умолчанию — горчичный: местами и типами он не занят. */
    const val COLOR_DEFAULT = "mustard"

    /**
     * Запреты для правил 4.5 и свободных слотов: запреты мест как есть; личные дела, которые занимают время, —
     * «Все места» вместе с дорогой; напоминания и дела «можно ставить» не занимают ничего. Личное выключено в
     * настройках ([personalOn]) — его как будто нет. [schedule] — рабочий график, `null` — выключен.
     */
    fun forTrainings(
        blocks: List<Restriction>,
        personalOn: Boolean,
        schedule: WorkSchedule? = null,
        scheduleReason: String = SCHEDULE_REASON,
    ): List<Restriction> = blocks.mapNotNull { r ->
        when (r.kind) {
            BlockKind.RESTRICTION, BlockKind.SCHEDULE -> r
            BlockKind.REMINDER -> null
            BlockKind.PERSONAL -> if (!personalOn || !r.blocksTrainings) null else withTravel(r)
        }
    } + (schedule?.restrictions(scheduleReason) ?: emptyList())

    /**
     * Дело с дорогой: начало раньше, конец позже на [Restriction.travelMinutes], но в пределах дня; место — «Все
     * места»; причина — «Стоматолог · с дорогой 1 ч».
     */
    fun withTravel(r: Restriction): Restriction {
        val travel = r.travelMinutes
        val start = (r.startMinute - travel).coerceAtLeast(0)
        val end = (r.endMinute + travel).coerceAtMost(ClubTime.MINUTES_PER_DAY)
        return r.copy(
            locationId = Restriction.ALL_PLACES,
            startTime = LocalTime(start / 60, start % 60),
            endTime = (end % ClubTime.MINUTES_PER_DAY).let { LocalTime(it / 60, it % 60) },
            reason = if (travel > 0) "${r.reason} · с дорогой ${travelText(travel)}" else r.reason,
        )
    }

    /**
     * Уведомления личных дел и напоминаний с [from] по [to] (дни включительно): у дела — за [Restriction.notifyMinutes]
     * до выхода (начало минус дорога), у напоминания — за столько же до его времени. Без уведомления — пропускаются.
     */
    fun alerts(blocks: List<Restriction>, from: LocalDate, to: LocalDate): List<PersonalAlert> {
        val out = mutableListOf<PersonalAlert>()
        var date = from
        while (date <= to) {
            blocks.filter { it.personal && it.notifyMinutes != null && RestrictionRules.occursOn(it, date) }.forEach { r ->
                val start = LocalDateTime(date, r.startTime)
                val leave = if (r.kind == BlockKind.PERSONAL) r.travelMinutes else 0
                val at = ClubTime.plusMinutes(start, -(leave + (r.notifyMinutes ?: 0)))
                out += PersonalAlert(r.id, date, at, start, r.reason, r.kind, leave)
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return out.sortedBy { it.at }
    }

    /** «1 ч», «30 мин», «1,5 ч». */
    fun travelText(minutes: Int): String = when {
        minutes % 60 == 0 -> "${minutes / 60} ч"
        minutes > 60 && minutes % 30 == 0 -> "${minutes / 60},5 ч"
        else -> "$minutes мин"
    }

    /** Причина запретов вне графика — её видно в окне «Поставить всё равно?». */
    const val SCHEDULE_REASON = "вне рабочего графика"
}
