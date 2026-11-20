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
    /**
     * Новый запрет сразу на несколько мест (шаг 1.13д): места кроме [locationId]. Сохраняется по запрету на каждое
     * место — дальше каждый меняется и удаляется сам. У изменения запрета и у «Все места» всегда пусто.
     */
    val otherPlaces: Set<String> = emptySet(),
    /** Запрет, личное дело или напоминание (шаги 5.10е, 5.10ж); у личного [reason] — его название. */
    val kind: BlockKind = BlockKind.RESTRICTION,
    val travelMinutes: Int = 0,
    val blocksTrainings: Boolean = true,
    val notifyMinutes: Int? = null,
    /** Напоминание «каждый месяц» — в число [date]. */
    val monthly: Boolean = false,
) {
    val personal: Boolean get() = kind == BlockKind.PERSONAL || kind == BlockKind.REMINDER

    /** «Все места» (шаг 5.3ю): [locationId] — [Restriction.ALL_PLACES], запрет — одна запись. */
    val allPlaces: Boolean get() = locationId == Restriction.ALL_PLACES

    /** Что записать: по месту на запрет; у «Все места» — одна запись [Restriction.ALL_PLACES]. */
    val placeIds: List<String> get() = listOfNotNull(locationId) + otherPlaces.filter { it != locationId }

    /** Выбранные места для чипов: у «Все места» — все [order]. */
    fun selected(order: List<String>): Set<String> = if (allPlaces) order.toSet() else placeIds.toSet()
}

/** Поля формы запрета, под которыми показывается ошибка. */
enum class RestrictionField { PLACE, TIME, WEEKDAYS, PERIOD, TITLE }

/** Повтор напоминания (шаг 5.10ж). */
enum class ReminderRepeat { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY }

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

    /**
     * Новое личное дело (шаг 5.10е) с [start] на час — время занято, без дороги, напоминание за 15 минут; или
     * напоминание (шаг 5.10ж) на [start], уведомление за 10 минут.
     */
    fun newPersonal(start: LocalDateTime, kind: BlockKind): RestrictionDraft {
        val base = new(start, Restriction.ALL_PLACES)
        return if (kind == BlockKind.REMINDER) {
            reminderEnd(base.copy(kind = kind, notifyMinutes = REMINDER_NOTIFY_DEFAULT))
        } else {
            base.copy(kind = kind, notifyMinutes = TASK_NOTIFY_DEFAULT)
        }
    }

    /** Дело ↔ напоминание в той же форме: время, дата, название и повтор остаются. */
    fun switchKind(draft: RestrictionDraft, kind: BlockKind): RestrictionDraft = when (kind) {
        BlockKind.REMINDER -> reminderEnd(draft.copy(kind = kind, notifyMinutes = draft.notifyMinutes ?: REMINDER_NOTIFY_DEFAULT))
        else -> {
            val from = draft.start.hour * 60 + draft.start.minute
            val to = minOf(from + DEFAULT_MINUTES, ClubTime.MINUTES_PER_DAY) % ClubTime.MINUTES_PER_DAY
            draft.copy(kind = kind, monthly = false, end = LocalTime(to / 60, to % 60))
        }
    }

    /** Минута конца: 00:00 — конец дня (1440). */
    private fun endMinute(t: LocalTime): Int = (t.hour * 60 + t.minute).let { if (it == 0) ClubTime.MINUTES_PER_DAY else it }

    /** Длительность черновика в минутах; конец раньше начала — час по умолчанию. */
    private fun duration(draft: RestrictionDraft): Int {
        val d = endMinute(draft.end) - (draft.start.hour * 60 + draft.start.minute)
        return if (d > 0) d else DEFAULT_MINUTES
    }

    private fun time(minute: Int): LocalTime = (minute % ClubTime.MINUTES_PER_DAY).let { LocalTime(it / 60, it % 60) }

    /**
     * Новое начало дела или запрета (шаг 5.22): длительность сохраняется, конец сдвигается за началом — но не дальше
     * полуночи. Так конец не может оказаться раньше начала.
     */
    fun withStart(draft: RestrictionDraft, start: LocalTime): RestrictionDraft {
        val from = start.hour * 60 + start.minute
        val to = minOf(from + duration(draft), ClubTime.MINUTES_PER_DAY)
        return draft.copy(start = start, end = time(to))
    }

    /**
     * Новый конец (шаг 5.22): если он не позже начала, начало уходит назад на прежнюю длительность (не раньше 00:00),
     * как в календаре телефона; конец 00:00 — конец дня.
     */
    fun withEnd(draft: RestrictionDraft, end: LocalTime): RestrictionDraft {
        val to = endMinute(end)
        val from = draft.start.hour * 60 + draft.start.minute
        if (to > from) return draft.copy(end = end)
        val newFrom = (to - duration(draft)).coerceAtLeast(0)
        return if (newFrom < to) draft.copy(start = time(newFrom), end = end) else draft.copy(end = end)
    }

    /** Конец напоминания: только чтобы его было видно и можно было нажать — 15 минут, не дальше полуночи. */
    fun reminderEnd(draft: RestrictionDraft): RestrictionDraft {
        val from = draft.start.hour * 60 + draft.start.minute
        val to = minOf(from + PersonalRules.REMINDER_SPAN, ClubTime.MINUTES_PER_DAY) % ClubTime.MINUTES_PER_DAY
        return draft.copy(end = LocalTime(to / 60, to % 60))
    }

    /** Повтор напоминания из черновика. */
    fun repeatOf(draft: RestrictionDraft): ReminderRepeat = when {
        draft.monthly -> ReminderRepeat.MONTHLY
        !draft.weekly -> ReminderRepeat.NONE
        draft.weekdays.size == 7 -> ReminderRepeat.DAILY
        draft.weekdays == WORKDAYS -> ReminderRepeat.WEEKDAYS
        else -> ReminderRepeat.WEEKLY
    }

    /** Выбрать повтор напоминания. «Каждую неделю» — в день даты. */
    fun withRepeat(draft: RestrictionDraft, repeat: ReminderRepeat): RestrictionDraft = when (repeat) {
        ReminderRepeat.NONE -> draft.copy(weekly = false, monthly = false)
        ReminderRepeat.DAILY -> draft.copy(weekly = true, monthly = false, weekdays = DayOfWeek.entries.toSet())
        ReminderRepeat.WEEKDAYS -> draft.copy(weekly = true, monthly = false, weekdays = WORKDAYS)
        ReminderRepeat.WEEKLY -> draft.copy(weekly = true, monthly = false, weekdays = setOf(draft.date.dayOfWeek))
        ReminderRepeat.MONTHLY -> draft.copy(weekly = false, monthly = true)
    }

    private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** Напоминание у дела по умолчанию — за 15 минут; у напоминания — за 10. */
    const val TASK_NOTIFY_DEFAULT = 15
    const val REMINDER_NOTIFY_DEFAULT = 10

    /** «Каждую неделю» включили — по умолчанию день даты, как у повтора тренировки. */
    fun weekly(draft: RestrictionDraft, on: Boolean): RestrictionDraft = when {
        !on -> draft.copy(weekly = false)
        draft.weekdays.isEmpty() -> draft.copy(weekly = true, weekdays = setOf(draft.date.dayOfWeek))
        else -> draft.copy(weekly = true)
    }

    /**
     * Нажатие на чип места нового запрета: место добавляется или убирается; первым ([RestrictionDraft.locationId])
     * становится первое выбранное в порядке мест [order]. Выбраны все места (их не меньше двух) — это «Все места»:
     * одна запись, которая действует и в местах, добавленных потом (шаг 5.3ю).
     */
    fun togglePlace(draft: RestrictionDraft, id: String, order: List<String>): RestrictionDraft {
        val now = draft.selected(order)
        return withPlaces(draft, if (id in now) now - id else now + id, order)
    }

    /** «Все места»: выбрать; если уже выбраны — снять выбор. */
    fun toggleAll(draft: RestrictionDraft, order: List<String>): RestrictionDraft =
        withPlaces(draft, if (draft.selected(order).containsAll(order)) emptySet() else order.toSet(), order)

    /** Изменение запрета: одно место или «Все места» ([Restriction.ALL_PLACES]). */
    fun pickPlace(draft: RestrictionDraft, id: String): RestrictionDraft = draft.copy(locationId = id, otherPlaces = emptySet())

    private fun withPlaces(draft: RestrictionDraft, ids: Set<String>, order: List<String>): RestrictionDraft {
        val sorted = order.filter { it in ids }
        if (order.size > 1 && sorted.size == order.size) return draft.copy(locationId = Restriction.ALL_PLACES, otherPlaces = emptySet())
        return draft.copy(locationId = sorted.firstOrNull(), otherPlaces = sorted.drop(1).toSet())
    }

    /** Запрет для правил и записи; [skipDates] — пропущенные дни прежнего запрета остаются. */
    fun toRestriction(id: String, draft: RestrictionDraft, skipDates: Set<LocalDate> = emptySet()): Restriction = Restriction(
        id = id,
        locationId = draft.locationId.orEmpty(),
        reason = draft.reason.trim(),
        startTime = draft.start,
        endTime = draft.end,
        weekdays = if (draft.weekly && !draft.monthly) draft.weekdays else emptySet(),
        date = if (draft.weekly || draft.monthly) null else draft.date,
        validFrom = draft.date,
        validTo = if (draft.weekly || draft.monthly) draft.validTo else null,
        skipDates = skipDates,
        kind = draft.kind,
        travelMinutes = if (draft.kind == BlockKind.PERSONAL) draft.travelMinutes else 0,
        blocksTrainings = draft.kind != BlockKind.PERSONAL || draft.blocksTrainings,
        monthly = draft.monthly,
        notifyMinutes = if (draft.personal) draft.notifyMinutes else null,
    )

    /**
     * Изменение одного дня повторяющегося дела или запрета (шаг 5.23). [date] — день, который открыли в календаре.
     * «Только этот день» ([onlyThis]): у прежнего этот день пропущен, новый — разовый в этот день (или в дату,
     * которую выбрали в форме). «Этот и следующие»: прежний кончается накануне ([RestrictionRules.endBefore];
     * `null` — до [date] не действовал ни разу и удаляется), новый повторяется с [date].
     * Возвращает прежний после правки (`null` — удалить) и черновик нового.
     */
    fun scoped(before: Restriction, draft: RestrictionDraft, date: LocalDate, onlyThis: Boolean): Pair<Restriction?, RestrictionDraft> {
        val dateChanged = draft.date != draftOf(before).date
        return if (onlyThis) {
            before.copy(skipDates = before.skipDates + date) to
                draft.copy(weekly = false, monthly = false, weekdays = emptySet(), validTo = null, date = if (dateChanged) draft.date else date)
        } else {
            RestrictionRules.endBefore(before, date) to draft.copy(date = if (dateChanged) maxOf(draft.date, date) else date)
        }
    }

    /** Нужно ли спрашивать «только этот день или и следующие»: дело повторяется, день известен и что-то поменяли. */
    fun asksScope(stored: RestrictionDraft?, draft: RestrictionDraft, date: LocalDate?): Boolean =
        stored != null && date != null && (stored.weekly || stored.monthly) && stored != draft

    /** Сохранённый запрет в форме изменения. */
    fun draftOf(restriction: Restriction): RestrictionDraft = RestrictionDraft(
        locationId = restriction.locationId,
        start = restriction.startTime,
        end = restriction.endTime,
        reason = restriction.reason,
        weekly = !restriction.isOneOff && !restriction.monthly,
        weekdays = restriction.weekdays,
        date = restriction.date ?: restriction.validFrom,
        validTo = restriction.validTo,
        kind = restriction.kind,
        travelMinutes = restriction.travelMinutes,
        blocksTrainings = restriction.blocksTrainings,
        notifyMinutes = restriction.notifyMinutes,
        monthly = restriction.monthly,
    )

    /**
     * Ошибки формы словами тренера: место, конец позже начала (00:00 — конец дня), дни недели у повторяющегося,
     * конец периода не раньше начала. Пусто — можно сохранять.
     */
    fun problems(draft: RestrictionDraft): Map<RestrictionField, String> = buildMap {
        if (draft.personal && draft.reason.isBlank()) {
            put(RestrictionField.TITLE, if (draft.kind == BlockKind.REMINDER) "Напишите, о чём напомнить" else "Напишите, что за дело")
        }
        if (draft.locationId == null && !draft.personal) put(RestrictionField.PLACE, "Выберите место")
        if (draft.weekly && draft.weekdays.isEmpty()) put(RestrictionField.WEEKDAYS, "Выберите дни недели")
        when (RestrictionRules.problem(toRestriction("", draft.copy(weekdays = draft.weekdays.ifEmpty { setOf(draft.date.dayOfWeek) })))) {
            RestrictionProblem.TIME_REVERSED -> put(RestrictionField.TIME, "Конец должен быть позже начала")
            RestrictionProblem.PERIOD_REVERSED -> put(RestrictionField.PERIOD, "Не раньше начала периода")
            RestrictionProblem.NO_DATE, null -> Unit
        }
    }
}
