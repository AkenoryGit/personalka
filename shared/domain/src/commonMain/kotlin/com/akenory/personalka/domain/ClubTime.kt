package com.akenory.personalka.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Время клуба (ТЗ 3.1, 4.14). Время тренировок хранится локальным — в часовом поясе клуба,
 * а не телефона; служебные моменты — в UTC.
 *
 * Часов здесь нет: «сейчас» приходит снаружи миллисекундами UTC, как в `Ids.newId(nowMillis)`,
 * и переводится в часы клуба функцией [now]. Дальше правила работают только с часами клуба.
 */
object ClubTime {
    /** Пояс по умолчанию для настройки тренера `timezone` (ТЗ 3.2). */
    const val DEFAULT_ZONE_ID = "Asia/Yekaterinburg"

    val defaultZone: TimeZone get() = TimeZone.of(DEFAULT_ZONE_ID)

    const val MINUTES_PER_DAY: Int = 24 * 60

    /**
     * «Сейчас» по часам клуба с точностью до минуты: секунды отбрасываются. Тренер видит «Сейчас, 15:40»,
     * и правила считают от 15:40, а не от 15:40:37 (4.2).
     */
    @OptIn(ExperimentalTime::class)
    fun now(nowMillis: Long, zone: TimeZone = defaultZone): LocalDateTime =
        toMinute(Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone))

    /** Сегодняшний день по клубу, а не по телефону (4.14). */
    fun today(nowMillis: Long, zone: TimeZone = defaultZone): LocalDate = now(nowMillis, zone).date

    /** Время клуба → момент UTC в миллисекундах, например для `doneAt` в базе. */
    @OptIn(ExperimentalTime::class)
    fun toMillis(time: LocalDateTime, zone: TimeZone = defaultZone): Long =
        time.toInstant(zone).toEpochMilliseconds()

    /** То же время без секунд и долей секунды. */
    fun toMinute(time: LocalDateTime): LocalDateTime =
        if (time.second == 0 && time.nanosecond == 0) time
        else LocalDateTime(time.date, LocalTime(time.hour, time.minute))

    /**
     * Сколько целых минут от [from] до [to] по часам клуба; отрицательное, если [to] раньше.
     * Секунды не учитываются: 09:00:59 и 09:00 — одна и та же минута.
     */
    fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long = epochMinute(to) - epochMinute(from)

    /** Время через [minutes] минут (или раньше, если число отрицательное), с переходом через полночь. */
    fun plusMinutes(time: LocalDateTime, minutes: Int): LocalDateTime {
        val total = minuteOfDay(time).toLong() + minutes
        val days = total.floorDiv(MINUTES_PER_DAY.toLong())
        val inDay = total.mod(MINUTES_PER_DAY.toLong()).toInt()
        val date = if (days == 0L) time.date else time.date.plus(days, DateTimeUnit.DAY)
        return LocalDateTime(date, LocalTime(inDay / 60, inDay % 60, time.second, time.nanosecond))
    }

    /** Минута от начала суток: 00:00 → 0, 23:59 → 1439. */
    fun minuteOfDay(time: LocalDateTime): Int = time.hour * 60 + time.minute

    private fun epochMinute(time: LocalDateTime): Long =
        time.date.toEpochDays() * MINUTES_PER_DAY + minuteOfDay(time)
}
