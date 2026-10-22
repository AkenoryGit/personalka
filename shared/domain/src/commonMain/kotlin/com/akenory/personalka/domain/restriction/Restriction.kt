package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.series.NewTraining
import com.akenory.personalka.domain.series.SeriesTraining
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Запрет (ТЗ 3.4, `ScheduleBlock`): время, когда в этом месте свои тренировки ставить не стоит,
 * например «ДБ 17:00–18:00 — групповая тренировка». Слой данных собирает его из `ScheduleBlockEntity`:
 * свои запреты и запреты клуба вместе, без удалённых. Личный это запрет или клуба, правилам всё равно.
 */
data class Restriction(
    val id: String,
    val locationId: String,
    /** Текст предупреждения: «Групповая тренировка». */
    val reason: String,
    val startTime: LocalTime,
    /** Конец; 00:00 — до конца дня (24:00), поэтому 00:00–00:00 — весь день. */
    val endTime: LocalTime,
    /** Дни недели повторяющегося; пусто — разовый. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** Дата разового. */
    val date: LocalDate? = null,
    /** Период повторения. У разового не используется: он действует в свою [date]. */
    val validFrom: LocalDate,
    /** Последний день повторения включительно; пусто — бессрочно. */
    val validTo: LocalDate? = null,
    /** Исключённые даты: «Пропустить этот день». */
    val skipDates: Set<LocalDate> = emptySet(),
) {
    val isOneOff: Boolean get() = weekdays.isEmpty()

    /** Начало в минутах от начала суток; секунды не считаются. */
    val startMinute: Int get() = startTime.hour * 60 + startTime.minute

    /** Конец в минутах от начала суток; 00:00 — это 24:00, то есть 1440. */
    val endMinute: Int
        get() = (endTime.hour * 60 + endTime.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
}

/** Что не так с запретом: форма показывает это под полем и не сохраняет. */
enum class RestrictionProblem {
    /** Разовый без даты. */
    NO_DATE,

    /** Конец не позже начала (кроме конца 00:00 — это конец дня). */
    TIME_REVERSED,

    /** Повторение кончается раньше, чем начинается. */
    PERIOD_REVERSED,
}

/**
 * Где и когда тренировка — то, что сверяется с запретами (ТЗ 4.5). Интервал — \[начало, конец).
 * Тип важен только одним: дежурство с запретами не сверяется.
 */
data class Placement(
    val start: LocalDateTime,
    val durationMin: Int,
    val locationId: String,
    /** Дежурство (`TrainingKind.DUTY`). */
    val isDuty: Boolean = false,
) {
    val date: LocalDate get() = start.date

    val end: LocalDateTime get() = TrainingTime.end(start, durationMin)
}

/** Сохранённая тренировка или дежурство — для пересчёта флага и накладок с дежурствами. */
data class PlacedTraining(
    val id: String,
    val placement: Placement,
    /** Отменена целиком (`TrainingStatus.CANCELLED`). */
    val cancelled: Boolean = false,
    /** Текущее `over_restriction`. */
    val overRestriction: Boolean = false,
)

/** Запрет в конкретный день: «ДБ 17:00–18:00 — групповая тренировка» во вт 20.10. Конец в полночь — 00:00 следующего дня. */
data class RestrictionHit(
    val restriction: Restriction,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val date: LocalDate get() = start.date

    val locationId: String get() = restriction.locationId

    val reason: String get() = restriction.reason
}

/** Что делать при сохранении одной тренировки (ТЗ 4.5, 5.5). */
sealed interface RestrictionCheck {
    /** Окна не нужно: сохранить с `over_restriction = [overRestriction]`. */
    data class Save(val overRestriction: Boolean) : RestrictionCheck

    /**
     * Окно «Малый бассейн 17:00–18:30: групповая тренировка. Поставить всё равно?» — строка на каждый
     * запрет из [hits]. «Поставить» — сохранить с `over_restriction = true`, «Отмена» — не сохранять.
     */
    data class Confirm(val hits: List<RestrictionHit>) : RestrictionCheck
}

/**
 * Запреты для нескольких тренировок разом — новой серии или «Эту и следующие». Окно одно на все:
 * «ДБ 17:00–18:00: групповая тренировка — 9 из 17 тренировок, первая вт 20.10. Поставить всё равно?»
 */
data class BatchConflicts(
    /** Сколько тренировок проверено. */
    val checked: Int,
    /** Сколько из них попало хотя бы под один запрет. */
    val conflicting: Int,
    /** Запреты с датами тренировок под ними — по первой дате, затем по началу. */
    val groups: List<ConflictGroup>,
)

/** Один запрет и даты тренировок, которые под него попали, по порядку. */
data class ConflictGroup(val restriction: Restriction, val dates: List<LocalDate>)

/** Новое значение `over_restriction` тренировки после изменения запретов. */
data class FlagChange(val trainingId: String, val overRestriction: Boolean)

/** Место и время тренировки серии; дежурство ли это — по типу, его знает слой данных. */
fun SeriesTraining.placement(isDuty: Boolean = false): Placement = Placement(start, durationMin, locationId, isDuty)

/** Место и время новой тренировки серии. */
fun NewTraining.placement(isDuty: Boolean = false): Placement = Placement(start, durationMin, locationId, isDuty)
