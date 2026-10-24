package com.akenory.personalka.data.calendar

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.groups.toSeriesTraining
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.series.Recorder
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.data.series.restore
import com.akenory.personalka.data.series.toSeries
import com.akenory.personalka.data.series.write
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.CalendarDrag
import com.akenory.personalka.domain.calendar.CalendarSettingsRules
import com.akenory.personalka.domain.calendar.Vacation
import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.series.SeriesRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Настройки календаря тренера (3.2): часы сетки, шаг перетаскивания, горизонт серий, порог отмены без списания. */
data class CalendarPrefs(
    val workStart: LocalTime = LocalTime(7, 0),
    val workEnd: LocalTime = LocalTime(23, 0),
    val dragStep: Int = CalendarDrag.STEP_DEFAULT,
    val horizonWeeks: Int = SeriesRules.DEFAULT_HORIZON_WEEKS,
    val cancelNoticeMinutes: Int = 60,
)

/**
 * Строка списка запретов или дежурств: «ДБ 17:00–18:00 — групповая тренировка» / «Каждый вт и чт».
 *
 * @param id что открывает нажатие: свой запрет — его id, дежурство — ближайшая его тренировка, которая ещё
 *   не началась; `null` — открывать нечего (запрет клуба, дежурство без будущих тренировок).
 */
data class ZoneRow(val key: String, val title: String, val subtitle: String, val id: String?)

/** Списки раздела «Календарь»: свои запреты, запреты клуба и дежурства, которые ещё действуют. */
data class ZoneLists(val own: List<ZoneRow>, val club: List<ZoneRow>, val duties: List<ZoneRow>)

/** Что вышло из «Записать отпуск». */
sealed interface VacationResult {
    data class Saved(val message: String, val undo: Undo) : VacationResult

    /** Ошибка дат; `null` у поля — оно в порядке. */
    data class Invalid(val from: String?, val to: String?) : VacationResult
}

/**
 * Раздел «Календарь» в настройках (ТЗ 5.16, шаг 1.13г): часы работы, шаг перетаскивания и горизонт серий
 * в [CoachSettingsEntity]; «Отпуск с … по …» (4.4) с «Отменить»; списки запретов и дежурств.
 * Календарь читает настройки сам ([CalendarRepository.workHours], [CalendarRepository.dragStep]) — новые часы
 * сразу меняют сетку.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class CalendarSettingsRepository internal constructor(
    private val db: PersonalkaDatabase,
    private val clock: () -> Long,
    private val forms: TrainingRepository,
) {
    private val coaches = db.coachDao()
    private val schedule = db.scheduleDao()
    private val trainings = db.trainingDao()
    private val reference = db.referenceDao()

    val prefs: Flow<CalendarPrefs> = coaches.observeSettings().map { s ->
        if (s == null) CalendarPrefs() else CalendarPrefs(s.workDayStart, s.workDayEnd, s.dragStepMinutes, s.seriesHorizonWeeks, s.cancelNoticeMinutes)
    }

    /** Часы работы; возвращает ошибку и ничего не пишет, если конец не позже начала. */
    suspend fun saveWorkHours(start: LocalTime, end: LocalTime): String? {
        CalendarSettingsRules.workHoursProblem(start, end)?.let { return it }
        update { it.copy(workDayStart = start, workDayEnd = end) }
        return null
    }

    /** Порог отмены без списания (4.2): новые отмены считаются по нему, прошлые не пересчитываются. */
    suspend fun saveCancelNotice(minutes: Int) {
        require(minutes in CalendarSettingsRules.CANCEL_NOTICES) { "Порог отмены: $minutes" }
        update { it.copy(cancelNoticeMinutes = minutes) }
    }

    suspend fun saveDragStep(minutes: Int) {
        require(minutes in CalendarSettingsRules.DRAG_STEPS) { "Шаг перетаскивания: $minutes" }
        update { it.copy(dragStepMinutes = minutes) }
    }

    /**
     * Горизонт серий. Больший сразу продлевает серии до нового горизонта; меньший уже созданные тренировки
     * не удаляет — новые просто не создаются, пока горизонт их не догонит.
     */
    suspend fun saveHorizon(weeks: Int) {
        require(weeks in CalendarSettingsRules.HORIZONS) { "Горизонт серий: $weeks" }
        update { it.copy(seriesHorizonWeeks = weeks) }
        forms.extendSeries()
    }

    /**
     * «Отпуск с … по …» (4.4) для всех серий тренера, и дежурств тоже: будущие тренировки серий в эти дни удаляются,
     * дни уходят в пропуски серий — и те, до которых серия ещё не создана; план — на первую тренировку после отпуска,
     * нет её — в отложенный план клиента или группы. Разовые тренировки остаются.
     */
    suspend fun vacation(vacation: Vacation): VacationResult {
        val now = clock()
        val clubNow = ClubTime.now(now)
        val fromProblem = CalendarSettingsRules.vacationFromProblem(vacation, clubNow.date)
        val toProblem = CalendarSettingsRules.vacationToProblem(vacation)
        if (fromProblem != null || toProblem != null) return VacationResult.Invalid(fromProblem, toProblem)
        var result: VacationResult = VacationResult.Invalid(null, null)
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val people = schedule.allSeriesClients().groupBy { it.seriesId }
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val restrictions = schedule.blocks().map { it.toRestriction() }
            val recorder = Recorder(db, now)
            var deleted = 0
            // По серии за раз: отложенные планы идут клиентам или группе своей серии.
            schedule.seriesFrom(vacation.from).filter { it.validFrom <= vacation.to }.forEach { entity ->
                val series = entity.toSeries(
                    people[entity.id].orEmpty().sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId },
                )
                val own = trainings.trainingsOfSeries(entity.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
                val changes = SeriesRules.vacation(listOf(series), own, vacation.from, vacation.to, clubNow)
                if (changes.isEmpty) return@forEach
                recorder.write(db, changes, coach.id, now, restrictions, series.pattern) { it in duties }
                deleted += changes.deleted.size
            }
            result = VacationResult.Saved(CalendarSettingsFormat.vacationSaved(vacation.from, vacation.to, deleted), recorder.undo())
        }
        return result
    }

    /** «Отменить» после отпуска: тренировки и пропуски серий — как были. */
    suspend fun undo(undo: Undo) {
        val now = clock()
        db.withWriteTransaction { db.restore(undo, now) }
    }

    /**
     * Списки запретов и дежурств, которые ещё действуют на [now]. Свой запрет открывает свою форму, запрет клуба —
     * только для просмотра; дежурство — форму своей ближайшей тренировки, которая ещё не началась.
     */
    fun zones(now: LocalDateTime): Flow<ZoneLists> {
        val today = now.date
        val horizon = LocalDateTime(today.plus(HORIZON_DAYS, DateTimeUnit.DAY), LocalTime(0, 0))
        return combine(
            schedule.observeBlocks(),
            schedule.observeSeriesFrom(today),
            trainings.observeTrainingsBetween(LocalDateTime(today, LocalTime(0, 0)), horizon),
            reference.observeTrainingTypes(),
            reference.observeLocations(),
        ) { blocks, series, upcoming, types, places ->
            val place = places.associate { it.id to it.shortName }
            val restrictions = CalendarSettingsRules.current(blocks.map { it.toRestriction() }, today)
            val scopes = blocks.associate { it.id to it.scope }
            val rows = restrictions.map { r ->
                val own = scopes[r.id] == Scope.COACH
                ZoneRow(
                    key = r.id,
                    title = ZoneFormat.restriction(place[r.locationId].orEmpty(), r.startMinute, r.endMinute, r.reason),
                    subtitle = CalendarSettingsFormat.period(r.weekdays, r.date, r.validFrom, r.validTo, today),
                    id = r.id.takeIf { own },
                ) to own
            }
            val duties = types.filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val next = upcoming.filter { it.typeId in duties && it.sync.deletedAt == null && it.start > now }.sortedBy { it.start }
            val dutySeries = series.filter { it.typeId in duties }
                .sortedWith(compareBy({ s -> DateFormat.WEEK.indexOfFirst { it in s.weekdays } }, { it.startTime }, { it.validFrom }))
                .map { s ->
                    val start = s.startTime.hour * 60 + s.startTime.minute
                    ZoneRow(
                        key = s.id,
                        title = CalendarSettingsFormat.duty(place[s.locationId].orEmpty(), start, minOf(start + s.durationMin, ClubTime.MINUTES_PER_DAY)),
                        subtitle = CalendarSettingsFormat.period(s.weekdays, null, s.validFrom, s.validTo, today),
                        id = next.firstOrNull { it.seriesId == s.id }?.id,
                    )
                }
            val oneOff = next.filter { it.seriesId == null }.map { t ->
                val start = ClubTime.minuteOfDay(t.start)
                ZoneRow(
                    key = t.id,
                    title = CalendarSettingsFormat.duty(place[t.locationId].orEmpty(), start, minOf(start + t.durationMin, ClubTime.MINUTES_PER_DAY), t.coverFor),
                    subtitle = CalendarSettingsFormat.period(emptySet(), t.start.date, t.start.date, null, today),
                    id = t.id,
                )
            }
            ZoneLists(
                own = rows.filter { it.second }.map { it.first },
                club = rows.filter { !it.second }.map { it.first },
                duties = dutySeries + oneOff,
            )
        }
    }

    private suspend fun update(change: (CoachSettingsEntity) -> CoachSettingsEntity) {
        val coach = coaches.currentCoach() ?: return
        val settings = coaches.settings(coach.id) ?: return
        coaches.upsertSettings(change(settings).copy(sync = settings.sync.touched(clock())))
    }

    private companion object {
        /** Разовые дежурства в списке — на год вперёд. */
        const val HORIZON_DAYS = 366
    }
}
