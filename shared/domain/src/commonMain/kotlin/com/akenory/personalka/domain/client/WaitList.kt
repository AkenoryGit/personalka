package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.TimeRange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Что ждёт клиент (4.16): постоянное время и/или разовое посещение, дни недели (пусто — любой), промежутки начала
 * (пусто — любое время), место (`null` — любое), тип, срок разового и заметка своими словами.
 */
data class WaitWant(
    val series: Boolean = true,
    val oneOff: Boolean = false,
    val days: Set<DayOfWeek> = emptySet(),
    val ranges: List<TimeRange> = emptyList(),
    val locationId: String? = null,
    val typeId: String? = null,
    val until: LocalDate? = null,
    val note: String = "",
)

/** Поля формы «+ В ожидание» (5.8): кто и что ждёт. */
data class WaitDraft(val clientId: String? = null, val want: WaitWant = WaitWant())

/** Что не так в форме «+ В ожидание» — слова под полем после «Сохранить». */
enum class WaitProblem { NO_CLIENT, NO_KIND, NO_TYPE, UNTIL_PAST }

/** Лист ожидания (ТЗ 4.16): запись хранится, пока клиента не записали или тренер её не закрыл. */
object WaitRules {
    /** Чипы времени начала (5.8): «Утро 07–12», «День 12–17», «Вечер 17–23». */
    val MORNING = TimeRange(LocalTime(7, 0), LocalTime(12, 0))
    val DAY = TimeRange(LocalTime(12, 0), LocalTime(17, 0))
    val EVENING = TimeRange(LocalTime(17, 0), LocalTime(23, 0))
    val PRESETS = listOf(MORNING, DAY, EVENING)

    /** Закрытые записи удаляются через 90 дней. */
    const val KEEP_CLOSED_DAYS = 90

    fun problems(draft: WaitDraft, today: LocalDate): Set<WaitProblem> = buildSet {
        val want = draft.want
        if (draft.clientId == null) add(WaitProblem.NO_CLIENT)
        if (!want.series && !want.oneOff) add(WaitProblem.NO_KIND)
        if (want.typeId == null) add(WaitProblem.NO_TYPE)
        if (want.oneOff && want.until != null && want.until < today) add(WaitProblem.UNTIL_PAST)
    }

    /** Чистая запись для базы: срок — только у разового, заметка без пробелов по краям, промежутки по порядку. */
    fun clean(want: WaitWant): WaitWant = want.copy(
        until = want.until.takeIf { want.oneOff },
        ranges = want.ranges.distinct().sortedBy { it.start },
        note = want.note.trim(),
    )

    /** «ждёт 37 дней»: с дня записи до сегодня. */
    fun daysWaiting(since: LocalDate, today: LocalDate): Int = since.daysUntil(today).coerceAtLeast(0)

    /**
     * Запись «только разово» закрывается сама на следующий день после срока; постоянная — нет: её закрывает серия
     * для клиента.
     */
    fun expired(want: WaitWant, today: LocalDate): Boolean =
        !want.series && want.oneOff && want.until != null && today > want.until

    /** Закрытую запись пора удалить: с закрытия прошло 90 дней. */
    fun purge(closedOn: LocalDate, today: LocalDate): Boolean = closedOn.plus(KEEP_CLOSED_DAYS, DateTimeUnit.DAY) <= today

    /**
     * «Записать» без освободившегося окна: ближайший после [now] подходящий день недели в начале первого промежутка.
     * Промежутков нет — `null`: время предложит форма.
     */
    fun bookingStart(want: WaitWant, now: LocalDateTime): LocalDateTime? {
        val time = want.ranges.minByOrNull { it.start }?.start ?: return null
        return (0..7).map { now.date.plus(it, DateTimeUnit.DAY) }
            .map { LocalDateTime(it, time) }
            .firstOrNull { (want.days.isEmpty() || it.dayOfWeek in want.days) && it > now }
    }

    /** Чип или свой промежуток: «Точнее…» — всё, что не совпадает с «Утро», «День», «Вечер». */
    fun isPreset(range: TimeRange): Boolean = range in PRESETS
}
