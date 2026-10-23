package com.akenory.personalka.domain.reminder

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.home.HomeSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Напоминания тренера (ТЗ 5.19). Вечерняя сводка включена по умолчанию, остальные выключены.
 *
 * @param digest вечерняя сводка в [digestTime].
 * @param instantDiary «Дневник сразу»: через 15 минут после конца тренировки без дневника.
 * @param instantGroup «Группа сразу»: через 15 минут после конца группы без отметки.
 * @param dayPlan «План на день» в 07:00.
 */
data class ReminderSettings(
    val digest: Boolean = true,
    val digestTime: LocalTime = ReminderRules.DIGEST_DEFAULT,
    val instantDiary: Boolean = false,
    val instantGroup: Boolean = false,
    val dayPlan: Boolean = false,
)

/** Что пора показать. Аванс 25-го — с зарплатой (этап 3). */
sealed interface Reminder {
    /** Вечерняя сводка: пункты «Нужно сделать»; дел нет — она не приходит. */
    data object Digest : Reminder

    /** «План на день»: сегодняшние тренировки; их нет — не приходит. */
    data object DayPlan : Reminder

    /** «Дневник сразу» — тренировка [id] проведена, дневника нет; у группы — когда детей уже отметили. */
    data class Diary(val id: String) : Reminder

    /** «Группа сразу» — группа [id] прошла, никого не отметили. */
    data class Group(val id: String) : Reminder
}

/**
 * Когда приходят напоминания (5.19). Телефон проверяет их будильником: в момент проверки берутся все моменты из
 * промежутка от прошлой проверки до сейчас ([due]), и ставится следующий будильник ([next]). Время — часы клуба.
 */
object ReminderRules {
    val DIGEST_DEFAULT = LocalTime(21, 0)
    val DAY_PLAN_TIME = LocalTime(7, 0)

    /** Через сколько после конца тренировки приходят «Дневник сразу» и «Группа сразу». */
    const val INSTANT_DELAY_MIN = 15

    /**
     * Насколько назад смотрит проверка: телефон был выключен ночью — утром придёт пропущенная вечерняя сводка,
     * но не напоминания прошлой недели.
     */
    const val CATCH_UP_HOURS = 12

    /** Чипы срока «Клиенты без записи» в настройках (4.15): по умолчанию 14 дней. */
    val FOLLOWUP_DAYS = listOf(7, 14, 21, 30)

    /**
     * Что пора показать за промежуток ([from]; [to]]. [slots] — тренировки «Главной», [noDiary] — id проведённых без
     * дневника на момент [to]: если тренер успел заполнить дневник или отметить группу, напоминание не приходит.
     * Сводка и план на день — не больше одного раза, даже если проверка пропустила несколько дней.
     */
    fun due(
        from: LocalDateTime,
        to: LocalDateTime,
        settings: ReminderSettings,
        slots: List<HomeSlot>,
        noDiary: Set<String>,
    ): List<Reminder> {
        val start = maxOf(from, ClubTime.plusMinutes(to, -CATCH_UP_HOURS * 60))
        if (start >= to) return emptyList()
        fun inside(moment: LocalDateTime) = moment > start && moment <= to
        return buildList {
            if (settings.dayPlan && daily(DAY_PLAN_TIME, start, to)) add(Reminder.DayPlan)
            if (settings.instantGroup) {
                slots.filter { it.group && !it.cancelled && !it.marked && inside(instantAt(it)) }
                    .sortedBy { it.start }
                    .forEach { add(Reminder.Group(it.id)) }
            }
            if (settings.instantDiary) {
                slots.filter { !it.cancelled && it.id in noDiary && inside(instantAt(it)) }
                    .sortedBy { it.start }
                    .forEach { add(Reminder.Diary(it.id)) }
            }
            if (settings.digest && daily(settings.digestTime, start, to)) add(Reminder.Digest)
        }
    }

    /**
     * Следующий момент проверки после [after]: сводка, план на день, конец тренировки плюс 15 минут. `null` — всё
     * выключено, будильник не нужен. Есть ли у тренировки дневник, заранее не известно — это решит [due].
     */
    fun next(after: LocalDateTime, settings: ReminderSettings, slots: List<HomeSlot>): LocalDateTime? {
        val moments = buildList {
            if (settings.digest) add(nextDaily(settings.digestTime, after))
            if (settings.dayPlan) add(nextDaily(DAY_PLAN_TIME, after))
            slots.filter { !it.cancelled && (settings.instantDiary || settings.instantGroup && it.group) }
                .map { instantAt(it) }
                .filter { it > after }
                .let(::addAll)
        }
        return moments.minOrNull()
    }

    /** Когда приходит мгновенное напоминание о тренировке: конец плюс 15 минут. */
    fun instantAt(slot: HomeSlot): LocalDateTime = ClubTime.plusMinutes(slot.end, INSTANT_DELAY_MIN)

    /** Было ли время [time] какого-нибудь дня в промежутке ([from]; [to]]. */
    private fun daily(time: LocalTime, from: LocalDateTime, to: LocalDateTime): Boolean = nextDaily(time, from) <= to

    /** Ближайшее [time] строго после [after]. */
    private fun nextDaily(time: LocalTime, after: LocalDateTime): LocalDateTime {
        val today = LocalDateTime(after.date, time)
        return if (today > after) today else LocalDateTime(after.date.plus(1, DateTimeUnit.DAY), time)
    }
}
