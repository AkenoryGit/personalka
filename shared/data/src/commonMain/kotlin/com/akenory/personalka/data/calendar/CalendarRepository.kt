package com.akenory.personalka.data.calendar

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.restrictions.placed
import com.akenory.personalka.data.restrictions.placement
import com.akenory.personalka.data.restrictions.recalcRestrictionFlags
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.data.training.EditScope
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.data.training.TrainingRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.BadgeFacts
import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.calendar.CalendarBadges
import com.akenory.personalka.domain.calendar.CalendarDrag
import com.akenory.personalka.domain.calendar.CalendarViews
import com.akenory.personalka.domain.calendar.MoveProblem
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.calendar.ZoneSpan
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.restriction.RestrictionHit
import com.akenory.personalka.domain.restriction.RestrictionRules
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Тренировка в календаре (5.3) — всё, что нужно блоку. [start] — время клуба.
 *
 * @param title «Иванов И.», «Петров / Сидоров», «Сб 9:00 ББ (12)».
 * @param color ключ цвета типа (`TrainingType.color`): у каждой темы свои тона.
 * @param badges значки по важности (`CalendarBadges`); сколько из них видно, решает высота блока.
 * @param marked проведена или в ней отмечены пришедшие: в будущее её не перенести (`CalendarDrag.problem`).
 */
data class CalendarTraining(
    val id: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val title: String,
    val typeShort: String,
    val placeId: String,
    val placeShort: String,
    val color: String,
    val cancelled: Boolean,
    val badges: List<CalendarBadge> = emptyList(),
    val marked: Boolean = false,
)

/** Что вышло из переноса тренировки (5.3). */
sealed interface MoveResult {
    /** Перенесено; [undo] — для «Отменить». */
    data class Moved(val undo: Undo) : MoveResult

    /**
     * Тренировка серии (4.4): ничего не записано, нужен ответ «Только эту — [date]» или «Эту и следующие»;
     * [question] — «Никитин А. · каждый вт и чт, 12:00. Новое время — 12:30–13:20.»
     */
    data class AskScope(val question: String, val date: LocalDate) : MoveResult

    /**
     * Новое время под запретом того же места (4.5): ничего не записано, нужно окно «Поставить всё равно?»
     * со строкой на каждый запрет — «Малый бассейн 17:00–18:30: групповая тренировка».
     */
    data class Confirm(val conflicts: List<String>) : MoveResult

    /** Не перенесено: [problem] — почему; `null` — тренировки уже нет или время не помещается в сутки. */
    data class Refused(val problem: MoveProblem?) : MoveResult
}

/**
 * Скопированная тренировка (5.3): тип, место, длительность и клиенты в порядке записи или группа. Её предлагает
 * «Вставить скопированную» в форме новой тренировки (5.5).
 */
data class TrainingCopy(
    val typeId: String,
    val locationId: String,
    val durationMin: Int,
    val clientIds: List<String>,
    val groupId: String?,
)

/**
 * Зона дня (5.3): дежурство или запрет в день [date], минуты суток [start]–[end] по часам клуба; конец 1440 — полночь.
 *
 * @param id id запрета или тренировки-дежурства.
 * @param placeId место: фильтр мест в шапке скрывает зоны других мест (шаг 1.12).
 * @param color ключ цвета типа дежурства; у запрета пусто — штриховка нейтральная.
 * @param title заголовок окна по нажатию: «Запрет» или название типа дежурства.
 * @param text строка окна: «ДБ 17:00–18:00 — групповая тренировка».
 * @param label подпись на самой зоне: «ДБ · групповая тренировка», «Дежурство · ББ».
 * @param repeat как повторяется запрет — «Каждый вт и чт»; у дежурства пусто.
 */
data class CalendarZone(
    val id: String,
    val kind: ZoneKind,
    val date: LocalDate,
    val start: Int,
    val end: Int,
    val placeId: String,
    val placeShort: String,
    val color: String,
    val title: String,
    val text: String,
    val label: String,
    val repeat: String?,
) {
    val span: ZoneSpan get() = ZoneSpan(id, kind, start, end)

    companion object {
        /** Запрет в его день. */
        fun restriction(hit: RestrictionHit, placeShort: String): CalendarZone {
            val r = hit.restriction
            return CalendarZone(
                id = r.id,
                kind = ZoneKind.RESTRICTION,
                date = hit.date,
                start = r.startMinute,
                end = r.endMinute,
                placeId = hit.locationId,
                placeShort = placeShort,
                color = "",
                title = ZoneFormat.RESTRICTION,
                text = ZoneFormat.restriction(placeShort, r.startMinute, r.endMinute, r.reason),
                label = ZoneFormat.restrictionLabel(placeShort, r.reason),
                repeat = ZoneFormat.repeat(r.weekdays, r.validTo),
            )
        }

        /** Дежурство с [start] на [minutes] минут; через полночь — до конца дня. */
        fun duty(id: String, start: LocalDateTime, minutes: Int, typeName: String, placeId: String, placeShort: String, color: String): CalendarZone {
            val from = ClubTime.minuteOfDay(start)
            val to = minOf(from + minutes, ClubTime.MINUTES_PER_DAY)
            return CalendarZone(
                id = id,
                kind = ZoneKind.DUTY,
                date = start.date,
                start = from,
                end = to,
                placeId = placeId,
                placeShort = placeShort,
                color = color,
                title = typeName,
                text = ZoneFormat.duty(placeShort, from, to, typeName),
                label = ZoneFormat.dutyLabel(typeName, placeShort),
                repeat = null,
            )
        }
    }
}

/** Место в фильтре календаря (5.3): «ББ» на чипе, «Большой бассейн» в списке. */
data class CalendarPlace(val id: String, val shortName: String, val name: String)

/** Часы работы из настроек — часы сетки (3.2); по умолчанию — часы клуба 07:00–23:00. */
data class WorkHours(val start: LocalTime = LocalTime(7, 0), val end: LocalTime = LocalTime(23, 0))

/**
 * Календарь (ТЗ 5.3): тренировки дней с именами клиентов, типом, местом и значками; дежурства и запреты — зоны
 * под тренировками; часы сетки и шаг перетаскивания; перенос, «Копировать» и «Пропустить этот день».
 * Создание и изменение тренировок (5.5) — [forms]: календарь и форма пишут через одно и то же.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class CalendarRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()
    private val schedule = db.scheduleDao()

    val workHours: Flow<WorkHours> = coaches.observeSettings().map { settings ->
        if (settings == null) WorkHours() else WorkHours(settings.workDayStart, settings.workDayEnd)
    }

    /** Шаг перетаскивания из настроек (3.2): 5, 10, 15 или 30 минут. */
    val dragStep: Flow<Int> = coaches.observeSettings().map { it?.dragStepMinutes ?: CalendarDrag.STEP_DEFAULT }

    /** Дней в разложенном виде из настроек (3.2): вид разложенного экрана, пока тренер не выбрал другой. */
    val weekViewDays: Flow<Int> = coaches.observeSettings().map { it?.weekViewDays ?: CalendarViews.DAY_COUNTS.first() }

    /** Места для фильтра в шапке (5.3) — действующие, в порядке тренера; архивные не фильтруются отдельно. */
    val places: Flow<List<CalendarPlace>> = reference.observeLocations().map { list ->
        list.filter { !it.archived }.map { CalendarPlace(it.id, it.shortName, it.name) }
    }

    private val copiedState = MutableStateFlow<TrainingCopy?>(null)

    /** Последняя скопированная тренировка; живёт, пока приложение открыто. */
    val copied: StateFlow<TrainingCopy?> = copiedState.asStateFlow()

    /** Форма тренировки (5.5): создание, изменение, продление серий. */
    val forms: TrainingRepository = TrainingRepository(db, clock, copied)

    /**
     * Тренировки, которые начинаются в дни с [from] по [to] не включая, по времени; отменённые тоже.
     * Дежурств среди них нет: в календаре они не блоки, а зоны ([zones]).
     */
    fun trainings(from: LocalDate, to: LocalDate): Flow<List<CalendarTraining>> {
        val start = LocalDateTime(from, LocalTime(0, 0))
        val end = LocalDateTime(to, LocalTime(0, 0))
        val names = combine(
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            clients.observeGroups(),
            clients.observeAllClients(),
        ) { types, places, groups, people -> Names(types.associateBy { it.id }, places.associateBy { it.id }, groups.associateBy { it.id }, people.associateBy { it.id }) }
        return combine(
            trainings.observeTrainingsBetween(start, end),
            trainings.observeParticipationsBetween(start, end),
            names,
            coaches.observeSettings(),
        ) { list, participations, n, settings ->
            val byTraining = participations.groupBy { it.trainingId }
            val diaryEnabled = settings?.diaryEnabled ?: true
            // Дежурства — не блоки, но по ним видно, какая тренировка стоит поверх дежурства.
            val duties = list.filter { n.types[it.typeId]?.kind == TrainingKind.DUTY }.map { it.placed(isDuty = true) }
            list.mapNotNull { t ->
                val type = n.types[t.typeId]
                if (type?.kind == TrainingKind.DUTY) return@mapNotNull null
                // Клиенты — в порядке, в котором их добавляли: «Петров / Сидоров» не меняется местами.
                val people = byTraining[t.id].orEmpty().sortedWith(compareBy({ it.sync.createdAt }, { it.id }))
                val cancelled = t.status == TrainingStatus.CANCELLED
                // Детей группы в скобках — тех, кого ждут; у отменённой — всех, кто был записан.
                val waiting = if (cancelled) people.size else people.count { it.status != ParticipationStatus.CANCELLED && it.status != ParticipationStatus.CANCELLED_CHARGED }
                val badges = CalendarBadges.of(
                    BadgeFacts(
                        cancelled = cancelled,
                        charged = people.any { it.status == ParticipationStatus.CANCELLED_CHARGED },
                        overRestriction = t.overRestriction,
                        noDiary = DiaryRules.missing(t.status, diaryEnabled, type?.diaryFact == true, t.fact),
                        overDuty = RestrictionRules.dutyOverlaps(t.placement(isDuty = false), duties).isNotEmpty(),
                        done = t.status == TrainingStatus.DONE,
                    ),
                )
                val marked = t.status == TrainingStatus.DONE || people.any { it.status == ParticipationStatus.ATTENDED }
                t.toCalendar(
                    title = CalendarFormat.title(
                        clients = people.mapNotNull { n.clients[it.clientId]?.name() },
                        groupName = t.groupId?.let { n.groups[it]?.name },
                        groupSize = waiting,
                        typeName = type?.name.orEmpty(),
                    ),
                    type = type,
                    place = n.places[t.locationId],
                    badges = badges,
                    marked = marked,
                )
            }
        }
    }

    /**
     * Зоны дней с [from] по [to] не включая (5.3): дежурства, кроме отменённых, и запреты — свои и клуба, во всех
     * местах. Дежурство, которое переходит за полночь, — до конца своего дня.
     */
    fun zones(from: LocalDate, to: LocalDate): Flow<List<CalendarZone>> {
        val start = LocalDateTime(from, LocalTime(0, 0))
        val end = LocalDateTime(to, LocalTime(0, 0))
        return combine(
            schedule.observeBlocks(),
            trainings.observeTrainingsBetween(start, end),
            reference.observeTrainingTypes(),
            reference.observeLocations(),
        ) { blocks, list, types, places ->
            val typeById = types.associateBy { it.id }
            val placeById = places.associateBy { it.id }
            val duties = list.mapNotNull { t ->
                val type = typeById[t.typeId]?.takeIf { it.kind == TrainingKind.DUTY } ?: return@mapNotNull null
                if (t.status == TrainingStatus.CANCELLED) return@mapNotNull null
                CalendarZone.duty(t.id, t.start, t.durationMin, type.name, t.locationId, placeById[t.locationId]?.shortName.orEmpty(), type.color)
            }
            val restrictions = blocks.map { it.toRestriction() }
            val hatched = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }
                .takeWhile { it < to }
                .flatMap { date -> RestrictionRules.on(restrictions, date) }
                .map { hit -> CalendarZone.restriction(hit, placeById[hit.locationId]?.shortName.orEmpty()) }
                .toList()
            duties + hatched
        }
    }

    /**
     * «Пропустить этот день» у запрета (5.3): в [date] запрет не действует, флаг «поверх запрета» у тренировок,
     * которые ещё не начались, пересчитывается без окон (4.5). Повторное нажатие ничего не меняет.
     */
    suspend fun skipRestrictionDay(restrictionId: String, date: LocalDate) = changeSkips(restrictionId) { it + date }

    /** «Отменить» после «Пропустить этот день»: в [date] запрет снова действует. */
    suspend fun unskipRestrictionDay(restrictionId: String, date: LocalDate) = changeSkips(restrictionId) { it - date }

    /**
     * Перенос перетаскиванием на [start] (5.3): длительность и место прежние; статус, план и отметки остаются —
     * перенос не отмена (4.2). Проведённую нельзя унести в будущее, отменённую — никуда.
     *
     * Ответы, которых ещё нет, возвращаются без записи, как в форме (5.5): у тренировки серии —
     * [MoveResult.AskScope], «Только эту» или «Эту и следующие» — тот же вызов со [scope] (4.4); под запретом
     * того же места — [MoveResult.Confirm], «Поставить» — тот же вызов с [confirmed]. Флаг «поверх запрета»
     * пишется вместе с временем (4.5).
     */
    suspend fun move(id: String, start: LocalDateTime, confirmed: Boolean = false, scope: EditScope? = null): MoveResult {
        val now = clock()
        val t = trainings.training(id)
        if (t == null || t.sync.deletedAt != null || TrainingTime.problem(start, t.durationMin) != null) return MoveResult.Refused(null)
        val participations = trainings.participationsOf(id)
        val marked = t.status == TrainingStatus.DONE || participations.any { it.status == ParticipationStatus.ATTENDED }
        CalendarDrag.problem(t.status, marked, start, ClubTime.now(now))?.let { return MoveResult.Refused(it) }
        val stored = forms.stored(id) ?: return MoveResult.Refused(null)
        return when (val result = forms.update(id, stored.draft.copy(start = start), scope, confirmed, checkFields = false)) {
            is SaveResult.Saved -> MoveResult.Moved(result.undo)
            is SaveResult.AskScope -> MoveResult.AskScope(result.question, result.date)
            is SaveResult.Confirm -> MoveResult.Confirm(result.conflicts)
            is SaveResult.Invalid -> MoveResult.Refused(null)
        }
    }

    /** «Отменить» после переноса: всё как было — время, флаг, серия и её пропуски. */
    suspend fun undoMove(undo: Undo) = forms.undo(undo)

    /**
     * «Копировать» в меню тренировки (5.3): запоминает тип, место, длительность и клиентов — у группы саму группу.
     * `false` — тренировки уже нет.
     */
    suspend fun copy(id: String): Boolean {
        val t = trainings.training(id)
        if (t == null || t.sync.deletedAt != null) return false
        val clientIds = if (t.groupId != null) {
            emptyList()
        } else {
            trainings.participationsOf(id).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }
        }
        copiedState.value = TrainingCopy(t.typeId, t.locationId, t.durationMin, clientIds, t.groupId)
        return true
    }

    private suspend fun changeSkips(restrictionId: String, change: (Set<LocalDate>) -> Set<LocalDate>) {
        db.withWriteTransaction {
            val block = schedule.block(restrictionId)
            if (block == null || block.sync.deletedAt != null) return@withWriteTransaction
            val skips = change(block.skipDates.toSet()).sorted()
            if (skips == block.skipDates.sorted()) return@withWriteTransaction
            val now = clock()
            schedule.upsertBlock(block.copy(skipDates = skips, sync = block.sync.touched(now)))
            db.recalcRestrictionFlags(now)
        }
    }

    private class Names(
        val types: Map<String, TrainingTypeEntity>,
        val places: Map<String, LocationEntity>,
        val groups: Map<String, GroupEntity>,
        val clients: Map<String, ClientEntity>,
    )
}

private fun ClientEntity.name() = ClientName(lastName, firstName, middleName)

private fun TrainingEntity.toCalendar(
    title: String,
    type: TrainingTypeEntity?,
    place: LocationEntity?,
    badges: List<CalendarBadge>,
    marked: Boolean,
) = CalendarTraining(
    id = id,
    start = start,
    durationMin = durationMin,
    title = title,
    typeShort = type?.shortName.orEmpty(),
    placeId = locationId,
    placeShort = place?.shortName.orEmpty(),
    color = type?.color.orEmpty(),
    cancelled = status == TrainingStatus.CANCELLED,
    badges = badges,
    marked = marked,
)
