package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.model.TimeRange
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionRules
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Параметры поиска свободных окон (ТЗ 4.13): дни с [from] по [to] включительно, дни недели [days] и промежутки начала
 * [ranges] (пусто — все), место [locationId] (`null` — любое), длительность [durationMin], рамки дня, учитывать ли
 * запреты, считать ли дежурство занятым, максимальный перерыв [maxGapMin] (`null` — без ограничения), показывать ли
 * дни без тренировок, шаг сетки.
 */
data class SlotQuery(
    val from: LocalDate,
    val to: LocalDate,
    val days: Set<DayOfWeek> = emptySet(),
    val ranges: List<TimeRange> = emptyList(),
    val locationId: String? = null,
    val durationMin: Int = FreeSlots.DURATION_DEFAULT,
    val dayStart: LocalTime = LocalTime(7, 0),
    val dayEnd: LocalTime = LocalTime(23, 0),
    val restrictions: Boolean = true,
    val dutyBusy: Boolean = false,
    val maxGapMin: Int? = null,
    val emptyDays: Boolean = false,
    val stepMin: Int = FreeSlots.STEP,
)

/** Тренировка или дежурство тренера (неотменённые): занятое время и опора для «не дальше Gmax» (4.13). */
data class SlotBusy(val start: LocalDateTime, val durationMin: Int, val duty: Boolean = false)

/**
 * Свободное окно дня в помещении [locationId]: слот длительностью D можно начать с [startMinute] и закончить не позже
 * [endMinute]. `null` — мест в справочнике нет, запреты не проверялись.
 */
data class FreeWindow(val date: LocalDate, val startMinute: Int, val endMinute: Int, val locationId: String? = null) {
    val start: LocalDateTime get() = LocalDateTime(date, LocalTime(startMinute / 60, startMinute % 60))
}

/** Окна одного дня: по помещениям в порядке справочника, в помещении — по времени. */
data class SlotDay(val date: LocalDate, val windows: List<FreeWindow>)

/**
 * Поиск свободных окон (ТЗ 4.13). Для каждого дня: занято — неотменённые тренировки тренера, дежурства (если
 * включено) и запреты места (если включено); окна — рамки дня минус занятое, границы по шагу; с Gmax — только части
 * окон, где слот начинается не позже Gmax после конца опоры или кончается не раньше Gmax до её начала (опоры —
 * тренировки и дежурства дня); остаются окна не короче D; прошедшее сегодня отбрасывается.
 *
 * Считается по минутам: начало s подходит, если слот \[s, s + D) целиком в выровненном окне, s — в промежутках
 * начала, не в прошлом и (с Gmax) рядом с опорой. Подряд идущие подходящие начала — одно окно: с первого начала,
 * поднятого до шага, до конца слота с последнего, опущенного до шага. Без Gmax и промежутков это и есть выровненное
 * окно целиком.
 */
object FreeSlots {
    /** Длительность по умолчанию — 50 минут, как у типов (макеты, шаг 0.4). */
    const val DURATION_DEFAULT = 50

    /** Шаг сетки — 15 минут. */
    const val STEP = 15

    /**
     * Окна с [query.from] по [query.to]. [busy] — тренировки и дежурства тренера этих дней, отменённых среди них нет;
     * [restrictions] — все запреты (свои и клуба, «все места» — запрет в каждом месте); [places] — действующие места
     * по порядку. При «любом» месте окна ищутся в каждом помещении отдельно со своими запретами (шаг 4.3а): клиенту
     * ДБ время ББ не нужно. Дни без окон не попадают в список.
     */
    fun find(
        query: SlotQuery,
        busy: List<SlotBusy>,
        restrictions: List<Restriction>,
        places: List<String>,
        now: LocalDateTime,
    ): List<SlotDay> {
        val byDay = busy.groupBy { it.start.date }
        val result = mutableListOf<SlotDay>()
        var date = maxOf(query.from, now.date)
        while (date <= query.to) {
            if (query.days.isEmpty() || date.dayOfWeek in query.days) {
                val where = query.locationId?.let { listOf(it) } ?: places
                val windows = if (where.isEmpty()) {
                    day(query, date, byDay[date].orEmpty(), restrictions, now)
                } else {
                    where.flatMap { place -> day(query.copy(locationId = place), date, byDay[date].orEmpty(), restrictions, now) }
                }
                if (windows.isNotEmpty()) result += SlotDay(date, windows)
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return result
    }

    /** Окна одного дня [date] в месте [SlotQuery.locationId]; [busy] — тренировки и дежурства этого дня. */
    fun day(
        query: SlotQuery,
        date: LocalDate,
        busy: List<SlotBusy>,
        restrictions: List<Restriction>,
        now: LocalDateTime,
    ): List<FreeWindow> {
        // Опоры — тренировки и дежурства дня; день без них — только с «показывать пустые дни».
        val anchors = busy.map { span(it) }
        if (anchors.isEmpty() && !query.emptyDays) return emptyList()
        val step = query.stepMin
        val d = query.durationMin
        val closed = BooleanArray(ClubTime.MINUTES_PER_DAY)
        busy.filter { !it.duty || query.dutyBusy }.forEach { close(closed, span(it)) }
        val place = query.locationId
        if (query.restrictions && place != null) {
            RestrictionRules.on(restrictions, date, place).forEach { close(closed, it.restriction.startMinute until it.restriction.endMinute) }
        }
        // Рамки дня.
        close(closed, 0 until minute(query.dayStart))
        close(closed, minute(query.dayEnd, end = true) until ClubTime.MINUTES_PER_DAY)

        // Свободное с границами по шагу: конец занятого — вверх, начало следующего — вниз.
        val free = BooleanArray(ClubTime.MINUTES_PER_DAY)
        runs(BooleanArray(closed.size) { !closed[it] }).forEach { run ->
            val from = ceil(run.first, step)
            val to = (run.last + 1) / step * step
            for (m in from until to) free[m] = true
        }
        val earliest = if (date == now.date) ClubTime.minuteOfDay(now) else 0
        val gap = query.maxGapMin
        val allowed = BooleanArray(ClubTime.MINUTES_PER_DAY) { s ->
            s >= earliest &&
                s + d <= ClubTime.MINUTES_PER_DAY &&
                (s until s + d).all { free[it] } &&
                (query.ranges.isEmpty() || query.ranges.any { time(s) in it }) &&
                (gap == null || anchors.isEmpty() || anchors.any { a -> s <= a.last + 1 + gap && s + d >= a.first - gap })
        }
        // Подряд идущие начала — одно окно: с первого начала до конца слота с последнего, обе границы по шагу.
        return runs(allowed).mapNotNull { run ->
            val first = ceil(run.first, step)
            if (first > run.last) null else FreeWindow(date, first, maxOf((run.last + d) / step * step, first + d), place)
        }
    }

    /**
     * Окно подходит записи листа ожидания [want] (4.16): день в её днях, место окна — её (или она ждёт любое),
     * у разового — не позже срока, и в окне есть начало по сетке [step] из её промежутков, с которого влезает
     * [durationMin] — длительность её типа. Запреты места окно уже учло.
     */
    fun suits(window: FreeWindow, want: WaitWant, durationMin: Int, step: Int = STEP): Boolean {
        if (want.days.isNotEmpty() && window.date.dayOfWeek !in want.days) return false
        if (want.locationId != null && window.locationId != null && want.locationId != window.locationId) return false
        if (!want.series && want.until != null && window.date > want.until) return false
        var s = window.startMinute
        while (s + durationMin <= window.endMinute) {
            if (want.ranges.isEmpty() || want.ranges.any { time(s) in it }) return true
            s += step
        }
        return false
    }

    private fun ceil(minute: Int, step: Int): Int = (minute + step - 1) / step * step

    private fun runs(flags: BooleanArray): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (m in 0..flags.size) {
            val on = m < flags.size && flags[m]
            if (on && start < 0) start = m
            if (!on && start >= 0) {
                out += start until m
                start = -1
            }
        }
        return out
    }

    /** Минуты тренировки в её дне; через полночь — до конца дня. */
    private fun span(b: SlotBusy): IntRange {
        val from = ClubTime.minuteOfDay(b.start)
        return from until minOf(from + b.durationMin, ClubTime.MINUTES_PER_DAY)
    }

    private fun close(closed: BooleanArray, range: IntRange) {
        for (m in range.first.coerceAtLeast(0)..range.last.coerceAtMost(closed.size - 1)) closed[m] = true
    }

    private fun minute(time: LocalTime, end: Boolean = false): Int =
        (time.hour * 60 + time.minute).let { if (end && it == 0) ClubTime.MINUTES_PER_DAY else it }

    private fun time(minute: Int): LocalTime = LocalTime(minute / 60, minute % 60)
}

/**
 * Параметры экрана «Свободные слоты» (5.13), которые запоминаются: период — дней от сегодня, дни недели, промежутки
 * начала (чипы «Утро», «День», «Вечер»), место, длительность, перерыв и три переключателя. Рамки дня — часы работы
 * из настроек календаря, их экран не меняет.
 */
data class SlotParams(
    val periodDays: Int = 14,
    val days: Set<DayOfWeek> = emptySet(),
    val ranges: List<TimeRange> = emptyList(),
    val locationId: String? = null,
    val durationMin: Int = FreeSlots.DURATION_DEFAULT,
    val maxGapMin: Int? = null,
    val restrictions: Boolean = true,
    val dutyBusy: Boolean = false,
    val emptyDays: Boolean = false,
) {
    /** Запрос с сегодняшнего дня [today] на [periodDays] дней в рамках [dayStart]–[dayEnd]. */
    fun query(today: LocalDate, dayStart: LocalTime, dayEnd: LocalTime): SlotQuery = SlotQuery(
        from = today,
        to = today.plus(periodDays - 1, DateTimeUnit.DAY),
        days = days,
        ranges = ranges,
        locationId = locationId,
        durationMin = durationMin,
        dayStart = dayStart,
        dayEnd = dayEnd,
        restrictions = restrictions,
        dutyBusy = dutyBusy,
        maxGapMin = maxGapMin,
        emptyDays = emptyDays,
    )

    /** Строка для настроек телефона: «p=14;d=1,3;r=07:00-12:00;l=bb;m=50;g=60;f=110». */
    fun encode(): String = listOf(
        "p=$periodDays",
        "d=" + days.sortedBy { it.ordinal }.joinToString(",") { (it.ordinal + 1).toString() },
        "r=" + ranges.joinToString(",") { "${it.start}-${it.end}" },
        "l=" + locationId.orEmpty(),
        "m=$durationMin",
        "g=" + (maxGapMin?.toString() ?: ""),
        "f=" + listOf(restrictions, dutyBusy, emptyDays).joinToString("") { if (it) "1" else "0" },
    ).joinToString(";")

    companion object {
        /** Обратно из [encode]; испорченное поле — значение по умолчанию. */
        fun decode(text: String?): SlotParams {
            if (text.isNullOrBlank()) return SlotParams()
            val map = text.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val base = SlotParams()
            val flags = map["f"].orEmpty()
            return SlotParams(
                periodDays = map["p"]?.toIntOrNull()?.takeIf { it in 1..62 } ?: base.periodDays,
                days = map["d"].orEmpty().split(',').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 1..7 } }
                    .map { DayOfWeek.entries[it - 1] }.toSet(),
                ranges = map["r"].orEmpty().split(',').mapNotNull { range ->
                    runCatching {
                        val (a, b) = range.split('-')
                        TimeRange(LocalTime.parse(a), LocalTime.parse(b))
                    }.getOrNull()
                },
                locationId = map["l"]?.takeIf { it.isNotEmpty() },
                durationMin = map["m"]?.toIntOrNull()?.takeIf { it in 5..600 } ?: base.durationMin,
                maxGapMin = map["g"]?.toIntOrNull(),
                restrictions = flags.getOrNull(0)?.let { it == '1' } ?: base.restrictions,
                dutyBusy = flags.getOrNull(1)?.let { it == '1' } ?: base.dutyBusy,
                emptyDays = flags.getOrNull(2)?.let { it == '1' } ?: base.emptyDays,
            )
        }
    }
}
