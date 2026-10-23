package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Освободившееся время (4.16): [series] — постоянное (серия закончилась, «Эту и следующие» ушла на другое время,
 * клиент ушёл в архив) — день недели берётся из [date], ближайшего такого дня; иначе разовое — эта дата.
 */
data class FreedSlot(val date: LocalDate, val start: LocalTime, val locationId: String, val series: Boolean) {
    val at: LocalDateTime get() = LocalDateTime(date, start)
}

/** Занятое время дня (4.13, шаг 1 алгоритма): неотменённые тренировки тренера и запреты места. */
data class Busy(val start: LocalDateTime, val end: LocalDateTime)

/** Совпадение освободившегося времени с записями листа ожидания (ТЗ 4.16). */
object WaitMatch {
    /** Разовое — только если до начала больше часа. */
    const val MIN_LEAD_MINUTES = 60

    /**
     * Запись [want] ждёт освободившееся время [slot]: тот же вид (постоянное или разовое), день входит в её дни,
     * начало — в её промежуток, место совпадает (или любое); у разового — дата не позже срока и до начала больше часа;
     * в этот день окно с [slot] вмещает [durationMin] — длительность её типа — и не задевает [busy] (4.13).
     */
    fun matches(want: WaitWant, slot: FreedSlot, durationMin: Int, busy: List<Busy>, now: LocalDateTime): Boolean {
        if (if (slot.series) !want.series else !want.oneOff) return false
        if (want.days.isNotEmpty() && slot.date.dayOfWeek !in want.days) return false
        if (want.ranges.isNotEmpty() && want.ranges.none { slot.start in it }) return false
        if (want.locationId != null && want.locationId != slot.locationId) return false
        if (!slot.series) {
            if (want.until != null && slot.date > want.until) return false
            if (ClubTime.minutesBetween(now, slot.at) <= MIN_LEAD_MINUTES) return false
        } else if (slot.at <= now) {
            return false
        }
        return fits(slot.at, durationMin, busy)
    }

    /** Окно [start, start + [durationMin]) свободно: ничего занятого с ним не пересекается. */
    fun fits(start: LocalDateTime, durationMin: Int, busy: List<Busy>): Boolean {
        val end = ClubTime.plusMinutes(start, durationMin)
        return busy.none { it.start < end && start < it.end }
    }

    /** Ближайший день недели [day] не раньше [from]: серия, закончившаяся во вторник, освобождает ближайший вторник. */
    fun nearest(day: DayOfWeek, from: LocalDate): LocalDate {
        var date = from
        while (date.dayOfWeek != day) date = date.plus(1, DateTimeUnit.DAY)
        return date
    }

    /**
     * Совпадение держится, пока время не прошло и его не заняли (4.16). У постоянного — ближайший такой день после
     * [now]: прошёл вторник — следующий; занятость проверяет [fitsOn].
     */
    fun current(slot: FreedSlot, now: LocalDateTime): FreedSlot? {
        if (!slot.series) return slot.takeIf { it.at > now }
        var date = nearest(slot.date.dayOfWeek, maxOf(slot.date, now.date))
        if (LocalDateTime(date, slot.start) <= now) date = date.plus(7, DateTimeUnit.DAY)
        return slot.copy(date = date)
    }

    /**
     * Порядок «Ждут время» (4.16): сначала записи, для которых освободилось время, ближайшее — выше; дальше — кто ждёт
     * дольше. [matchOf] — текущее совпадение записи, [since] — момент записи.
     */
    fun <T> order(items: List<T>, matchOf: (T) -> FreedSlot?, since: (T) -> Long): List<T> =
        items.sortedWith(compareBy<T>({ matchOf(it) == null }, { matchOf(it)?.at }, { since(it) }))
}
