package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Запрет из формы (ТЗ 5.5, макет «Запрет») — то, что выбрано сейчас. Разовый действует в [date]; повторяющийся
 * ([weekly]) — в свои дни недели с [date] по [validTo] включительно («Период: с 01.09.2026 — до бессрочно»).
 * Конец 00:00 — до конца дня (4.5).
 */
data class RestrictionDraft(
    val locationId: String? = null,
    val start: LocalTime,
    val end: LocalTime,
    val reason: String = "",
    val weekly: Boolean = false,
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** Дата разового или начало периода повторяющегося. */
    val date: LocalDate,
    /** Последний день повторения; `null` — бессрочно. */
    val validTo: LocalDate? = null,
)

/** Поля формы запрета, под которыми показывается ошибка. */
enum class RestrictionField { PLACE, TIME, WEEKDAYS, PERIOD }

/** Правила формы запрета (ТЗ 5.5): черновик по умолчанию, запрет из черновика и обратно, ошибки словами тренера. */
object RestrictionFormRules {
    /** Новый запрет — на час: «с 17:00 — до 18:00». */
    const val DEFAULT_MINUTES = 60

    /** Новый запрет с [start] на час, но не дальше полуночи (конец 00:00 — конец дня). */
    fun new(start: LocalDateTime, locationId: String?): RestrictionDraft {
        val from = ClubTime.minuteOfDay(start)
        val to = minOf(from + DEFAULT_MINUTES, ClubTime.MINUTES_PER_DAY) % ClubTime.MINUTES_PER_DAY
        return RestrictionDraft(
            locationId = locationId,
            start = LocalTime(start.hour, start.minute),
            end = LocalTime(to / 60, to % 60),
            date = start.date,
        )
    }

    /** «Каждую неделю» включили — по умолчанию день даты, как у повтора тренировки. */
    fun weekly(draft: RestrictionDraft, on: Boolean): RestrictionDraft = when {
        !on -> draft.copy(weekly = false)
        draft.weekdays.isEmpty() -> draft.copy(weekly = true, weekdays = setOf(draft.date.dayOfWeek))
        else -> draft.copy(weekly = true)
    }

    /** Запрет для правил и записи; [skipDates] — пропущенные дни прежнего запрета остаются. */
    fun toRestriction(id: String, draft: RestrictionDraft, skipDates: Set<LocalDate> = emptySet()): Restriction = Restriction(
        id = id,
        locationId = draft.locationId.orEmpty(),
        reason = draft.reason.trim(),
        startTime = draft.start,
        endTime = draft.end,
        weekdays = if (draft.weekly) draft.weekdays else emptySet(),
        date = if (draft.weekly) null else draft.date,
        validFrom = draft.date,
        validTo = if (draft.weekly) draft.validTo else null,
        skipDates = skipDates,
    )

    /** Сохранённый запрет в форме изменения. */
    fun draftOf(restriction: Restriction): RestrictionDraft = RestrictionDraft(
        locationId = restriction.locationId,
        start = restriction.startTime,
        end = restriction.endTime,
        reason = restriction.reason,
        weekly = !restriction.isOneOff,
        weekdays = restriction.weekdays,
        date = restriction.date ?: restriction.validFrom,
        validTo = restriction.validTo,
    )

    /**
     * Ошибки формы словами тренера: место, конец позже начала (00:00 — конец дня), дни недели у повторяющегося,
     * конец периода не раньше начала. Пусто — можно сохранять.
     */
    fun problems(draft: RestrictionDraft): Map<RestrictionField, String> = buildMap {
        if (draft.locationId == null) put(RestrictionField.PLACE, "Выберите место")
        if (draft.weekly && draft.weekdays.isEmpty()) put(RestrictionField.WEEKDAYS, "Выберите дни недели")
        when (RestrictionRules.problem(toRestriction("", draft.copy(weekdays = draft.weekdays.ifEmpty { setOf(draft.date.dayOfWeek) })))) {
            RestrictionProblem.TIME_REVERSED -> put(RestrictionField.TIME, "Конец должен быть позже начала")
            RestrictionProblem.PERIOD_REVERSED -> put(RestrictionField.PERIOD, "Не раньше начала периода")
            RestrictionProblem.NO_DATE, null -> Unit
        }
    }
}
