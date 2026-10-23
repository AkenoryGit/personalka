package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.CalendarDrag
import com.akenory.personalka.domain.calendar.DaySpan
import com.akenory.personalka.domain.calendar.MoveProblem
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.TypePresets
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** «Каждую неделю» в форме (5.5): дни недели и последний день; `null` в [until] — бессрочно. */
data class Repeat(val weekdays: Set<DayOfWeek>, val until: LocalDate? = null)

/**
 * Тренировка из формы (5.5) — то, что выбрано сейчас. Клиенты — в порядке выбора: «Петров / Сидоров» в блоке
 * не меняется местами. У группового типа — группа, а без неё [clientIds] — разовый список детей.
 *
 * @param repeat `null` — «Не повторять».
 */
data class TrainingDraft(
    val start: LocalDateTime,
    val typeId: String? = null,
    val clientIds: List<String> = emptyList(),
    val groupId: String? = null,
    val locationId: String? = null,
    val durationMin: Int = TypePresets.DEFAULT_DURATION,
    val repeat: Repeat? = null,
    val plan: String = "",
)

/** Тип в форме: чип, сколько клиентов и длительность по умолчанию. Дежурств среди них нет — у них своя форма. */
data class FormType(
    val id: String,
    val name: String,
    val kind: TrainingKind,
    val color: String,
    val defaultDurationMin: Int,
    /** Как в типе: 1 — ПТ и СПТ, 2 — сплит, `null` — сколько угодно. */
    val maxClients: Int?,
)

/** Поля формы, под которыми показывается ошибка. */
enum class FormField { TYPE, CLIENTS, PLACE, TIME, DURATION, WEEKDAYS, UNTIL }

/**
 * Правила формы тренировки (ТЗ 5.5): порядок типов, выбор клиентов, время по умолчанию и проверки при сохранении.
 * Запреты (4.5) и вопрос «Только эту / Эту и следующие» (4.4) проверяет слой данных при записи.
 */
object TrainingFormRules {
    /** Сколько чипов типов видно сразу; остальные — под «Ещё N» (макет «Новая тренировка»). */
    const val TYPE_CHIPS = 5

    /** Недавние клиенты над полем поиска. */
    const val RECENT_CLIENTS = 3

    /** Найденные клиенты под полем поиска. */
    const val FOUND_CLIENTS = 5

    /** Последний использованный тип первым, остальные — как в настройках (5.5). */
    fun typeOrder(types: List<FormType>, lastUsedId: String?): List<FormType> {
        val last = types.firstOrNull { it.id == lastUsedId } ?: return types
        return listOf(last) + (types - last)
    }

    /**
     * Сколько клиентов можно выбрать: ПТ и СПТ — ровно один, сплит — один или два (4.7), группа — сколько угодно
     * (`null`), свой тип — как в нём.
     */
    fun clientLimit(type: FormType): Int? = when (type.kind) {
        TrainingKind.PERSONAL, TrainingKind.SPT -> 1
        TrainingKind.SPLIT -> 2
        TrainingKind.GROUP -> null
        TrainingKind.DUTY -> 0
        TrainingKind.OTHER -> type.maxClients
    }

    /**
     * Нажатие на клиента: выбранный снимается; у ПТ новый заменяет прежнего; у сплита с двумя выбранными
     * новый встаёт вторым — первый, как правило, тот, с кем сплит и задумывался.
     */
    fun toggleClient(selected: List<String>, id: String, limit: Int?): List<String> = when {
        id in selected -> selected - id
        limit == null || selected.size < limit -> selected + id
        limit <= 1 -> listOf(id)
        else -> selected.take(limit - 1) + id
    }

    /**
     * Тип сменили: длительность — из нового типа, если тренер не менял её сам; клиентов не больше, чем в типе;
     * группа — только у группового.
     */
    fun withType(draft: TrainingDraft, type: FormType, keepDuration: Boolean): TrainingDraft {
        val limit = clientLimit(type)
        return draft.copy(
            typeId = type.id,
            durationMin = if (keepDuration) draft.durationMin else type.defaultDurationMin,
            clientIds = if (limit == null) draft.clientIds else draft.clientIds.take(limit),
            groupId = draft.groupId.takeIf { type.kind == TrainingKind.GROUP },
        )
    }

    /**
     * Ближайший свободный час для «+» (5.5): первый целый час после «сейчас», где тренировка на [durationMin] минут
     * не задевает другие тренировки дня и кончается до конца рабочего дня. Сегодня ничего — первый свободный час
     * завтра с начала работы; и там занято — начало работы завтра.
     *
     * @param busy занятые минуты дня — неотменённые тренировки; дежурства не мешают.
     */
    fun nearestFreeHour(
        now: LocalDateTime,
        durationMin: Int,
        dayStart: LocalTime,
        dayEnd: LocalTime,
        busy: (LocalDate) -> List<DaySpan>,
    ): LocalDateTime {
        val open = dayStart.hour * 60 + dayStart.minute
        val close = (dayEnd.hour * 60 + dayEnd.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }
        val nowMinute = ClubTime.minuteOfDay(now) + if (now.second > 0 || now.nanosecond > 0) 1 else 0
        val firstToday = maxOf(open, (nowMinute + 59) / 60 * 60)
        free(now.date, firstToday, close, durationMin, busy(now.date))?.let { return it }
        val tomorrow = now.date.plus(1, DateTimeUnit.DAY)
        val firstTomorrow = (open + 59) / 60 * 60
        return free(tomorrow, firstTomorrow, close, durationMin, busy(tomorrow)) ?: ClubTime.plusMinutes(LocalDateTime(tomorrow, LocalTime(0, 0)), open)
    }

    private fun free(date: LocalDate, from: Int, close: Int, durationMin: Int, busy: List<DaySpan>): LocalDateTime? {
        var minute = from
        while (minute + durationMin <= minOf(close, ClubTime.MINUTES_PER_DAY)) {
            val end = minute + durationMin
            if (busy.none { it.start < end && minute < it.end }) return ClubTime.plusMinutes(LocalDateTime(date, LocalTime(0, 0)), minute)
            minute += 60
        }
        return null
    }

    /**
     * Ошибки формы словами тренера (5.5): обязательные поля, длительность 5–600 минут, конец не позже 24:00,
     * повтор с днями недели и концом не раньше начала. Пусто — можно сохранять.
     *
     * @param type выбранный тип; `null` — тип не выбран.
     * @param marked у изменяемой тренировки — проведена или с отметками: её нельзя унести в будущее (как при переносе).
     */
    fun problems(
        draft: TrainingDraft,
        type: FormType?,
        now: LocalDateTime,
        status: TrainingStatus = TrainingStatus.PLANNED,
        marked: Boolean = false,
    ): Map<FormField, String> = buildMap {
        if (type == null) {
            put(FormField.TYPE, "Выберите тип")
        } else {
            clientsProblem(draft, type)?.let { put(FormField.CLIENTS, it) }
        }
        if (draft.locationId == null) put(FormField.PLACE, "Выберите место")
        when (TrainingTime.problem(draft.start, draft.durationMin)) {
            TimeProblem.TOO_SHORT, TimeProblem.TOO_LONG ->
                put(FormField.DURATION, "От ${TrainingTime.MIN_DURATION} до ${TrainingTime.MAX_DURATION} минут")
            TimeProblem.CROSSES_MIDNIGHT -> put(FormField.TIME, "Тренировка должна закончиться до 24:00")
            null -> Unit
        }
        if (marked && CalendarDrag.problem(status, marked = true, draft.start, now) == MoveProblem.MARKED_IN_FUTURE) {
            put(FormField.TIME, "Проведённую тренировку нельзя перенести в будущее")
        }
        val repeat = draft.repeat
        if (repeat != null) {
            if (repeat.weekdays.isEmpty()) put(FormField.WEEKDAYS, "Выберите дни недели")
            if (repeat.until != null && repeat.until < draft.start.date) put(FormField.UNTIL, "Не раньше даты тренировки")
        }
    }

    private fun clientsProblem(draft: TrainingDraft, type: FormType): String? {
        val count = draft.clientIds.size
        return when (type.kind) {
            TrainingKind.GROUP -> if (draft.groupId == null && count == 0) "Выберите группу или детей" else null
            TrainingKind.SPLIT -> if (count !in 1..2) "Выберите одного или двух клиентов" else null
            TrainingKind.DUTY -> null
            else -> {
                val limit = clientLimit(type)
                when {
                    limit == 1 && count != 1 -> "Выберите клиента"
                    count == 0 -> "Выберите клиентов"
                    limit != null && count > limit -> "Не больше $limit"
                    else -> null
                }
            }
        }
    }
}
