package com.akenory.personalka.data.training

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.calendar.TrainingCopy
import com.akenory.personalka.data.clients.closeWaitForSeries
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.groups.member
import com.akenory.personalka.data.groups.toSeriesTraining
import com.akenory.personalka.data.restrictions.placement
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.series.Recorder
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.data.series.restore
import com.akenory.personalka.data.series.toSeries
import com.akenory.personalka.data.series.write
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.calendar.DaySpan
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.restriction.BatchConflicts
import com.akenory.personalka.domain.restriction.Placement
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionCheck
import com.akenory.personalka.domain.restriction.RestrictionHit
import com.akenory.personalka.domain.restriction.RestrictionRules
import com.akenory.personalka.domain.restriction.placement
import com.akenory.personalka.domain.series.ClientScheduleRules
import com.akenory.personalka.domain.series.ScheduleSeries
import com.akenory.personalka.domain.series.SeriesPattern
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.series.TrainingEdit
import com.akenory.personalka.domain.training.FormField
import com.akenory.personalka.domain.training.FormType
import com.akenory.personalka.domain.training.PlanCarryRules
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.domain.training.TrainingFormRules
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Клиент в форме: «Орлова К.» на чипе; полное имя — для поиска и проверки похожих (5.8). */
data class FormClient(val id: String, val name: ClientName) {
    val label: String get() = CalendarFormat.client(name)
}

/** Группа в форме: её тип и место подставляются, когда группу выбрали. */
data class FormGroup(val id: String, val name: String, val typeId: String, val locationId: String)

/** Место в форме: «ББ» на чипе и цвет места — точки типов показывают его оттенками (шаг 1.13в). */
data class FormPlace(val id: String, val shortName: String, val name: String, val color: String = ReferenceRules.FALLBACK_COLOR)

/**
 * Всё, что показывает форма тренировки (5.5): типы без дежурств (последний использованный первым), места,
 * клиенты по алфавиту, группы, недавние клиенты и группы, последнее место каждого типа и родственники —
 * их первыми предлагают вторым в сплит (4.17). [duty] — тип дежурства для формы дежурства (шаг 1.13а);
 * `null` — дежурств у тренера нет, и переключатель их не показывает.
 */
data class TrainingFormData(
    val types: List<FormType> = emptyList(),
    val places: List<FormPlace> = emptyList(),
    val clients: List<FormClient> = emptyList(),
    val groups: List<FormGroup> = emptyList(),
    val recentClientIds: List<String> = emptyList(),
    val recentGroupIds: List<String> = emptyList(),
    val lastPlaceByType: Map<String, String> = emptyMap(),
    val relatives: Map<String, List<String>> = emptyMap(),
    val duty: FormType? = null,
)

/**
 * Сохранённая тренировка для формы изменения: черновик, как она есть, статус и повтор её серии.
 *
 * @param marked проведена или с отметками «пришёл» — в будущее её не перенести.
 * @param seriesRepeat повтор серии; в форме он виден, а меняется вопросом «Эту и следующие» (4.4).
 * @param isDuty дежурство: открывается форма дежурства.
 */
data class StoredTraining(
    val id: String,
    val draft: TrainingDraft,
    val status: TrainingStatus,
    val marked: Boolean,
    val seriesRepeat: Repeat?,
    val isDuty: Boolean = false,
)

/**
 * Серия для формы «Изменить с даты…» (5.9): черновик с даты по умолчанию — время, длительность, тип, место,
 * клиенты, дни в повторе. [validTo] — последний день серии: позже него менять нечего.
 */
data class StoredSeries(val id: String, val draft: TrainingDraft, val validTo: LocalDate?)

/** Ответ на вопрос «Только эту / Эту и следующие» (4.4). */
enum class EditScope { ONLY_THIS, FOLLOWING }

/** Что вышло из сохранения формы. Ничего не записано, кроме [Saved]. */
sealed interface SaveResult {
    /** Записано; [message] — для сообщения с «Отменить» (5.20). */
    data class Saved(val message: String, val undo: Undo) : SaveResult

    /** Изменение тренировки серии: нужен ответ «Только эту — [date]» или «Эту и следующие». */
    data class AskScope(val question: String, val date: LocalDate) : SaveResult

    /** Под запретом того же места (4.5): окно «Поставить всё равно?» со строкой на каждый запрет. */
    data class Confirm(val conflicts: List<String>) : SaveResult

    /** Ошибки под полями; пусто — тренировки уже нет. */
    data class Invalid(val problems: Map<FormField, String>) : SaveResult
}

/**
 * Создание и изменение тренировки (ТЗ 5.5): одна тренировка или серия (4.4), окно «Поставить всё равно?» (4.5),
 * вопрос «Только эту / Эту и следующие» и «Отменить» (5.20). Продление серий до горизонта — [extendSeries].
 *
 * Сохранение идёт в несколько шагов, как перенос в календаре: ответ, которого ещё нет (вопрос серии, окно запрета),
 * возвращается без записи, а форма вызывает то же сохранение с ответом.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class TrainingRepository internal constructor(
    private val db: PersonalkaDatabase,
    private val clock: () -> Long,
    /** Последняя скопированная тренировка (5.3): её предлагает «Вставить скопированную». */
    val copied: StateFlow<TrainingCopy?>,
) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()
    private val schedule = db.scheduleDao()

    /** Данные формы; обновляются сами — новый клиент из формы сразу виден. */
    fun form(): Flow<TrainingFormData> {
        val lists = combine(
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            clients.observeClients(ClientStatus.ACTIVE),
            clients.observeGroups(),
            clients.observeRelations(),
        ) { types, places, people, groups, relations ->
            Lists(
                types = types.filter { !it.archived && it.kind != TrainingKind.DUTY }.map { it.toForm() },
                duty = types.firstOrNull { !it.archived && it.kind == TrainingKind.DUTY }?.toForm(),
                places = places.filter { !it.archived }.map { FormPlace(it.id, it.shortName, it.name, it.color) },
                clients = people.map { FormClient(it.id, ClientName(it.lastName, it.firstName, it.middleName)) }.sortedBy { ClientRules.sortKey(it.name) },
                groups = groups.map { FormGroup(it.id, it.name, it.typeId, it.locationId) }.sortedBy { it.name },
                relatives = relations.flatMap { listOf(it.clientAId to it.clientBId, it.clientBId to it.clientAId) }
                    .groupBy({ it.first }, { it.second }),
            )
        }
        return combine(lists, trainings.observeLastCreated(RECENT_TRAININGS), trainings.observeRecentParticipations(RECENT_PARTICIPATIONS)) { l, last, parts ->
            val types = l.types.associateBy { it.id }
            val active = l.clients.map { it.id }.toSet()
            val groupIds = l.groups.map { it.id }.toSet()
            TrainingFormData(
                types = TrainingFormRules.typeOrder(l.types, last.firstOrNull { it.typeId in types }?.typeId),
                places = l.places,
                clients = l.clients,
                groups = l.groups,
                recentClientIds = parts.map { it.clientId }.distinct().filter { it in active },
                recentGroupIds = last.mapNotNull { it.groupId }.distinct().filter { it in groupIds },
                lastPlaceByType = last.reversed().associate { it.typeId to it.locationId },
                relatives = l.relatives,
                duty = l.duty,
            )
        }
    }

    /**
     * Начало новой тренировки для «+» (5.5): ближайший свободный целый час на [durationMin] минут в часы работы.
     */
    suspend fun suggestStart(durationMin: Int): LocalDateTime {
        val now = ClubTime.now(clock())
        val settings = coaches.currentCoach()?.let { coaches.settings(it.id) }
        val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
        val busy = mutableMapOf<LocalDate, List<DaySpan>>()
        for (date in listOf(now.date, now.date.plus(1, DateTimeUnit.DAY))) {
            val from = LocalDateTime(date, LocalTime(0, 0))
            busy[date] = trainings.trainingsBetween(from, LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0)))
                .filter { it.status != TrainingStatus.CANCELLED && it.typeId !in duties }
                .map { DaySpan(it.id, ClubTime.minuteOfDay(it.start), minOf(ClubTime.minuteOfDay(it.start) + it.durationMin, ClubTime.MINUTES_PER_DAY)) }
        }
        return TrainingFormRules.nearestFreeHour(
            now = now,
            durationMin = durationMin,
            dayStart = settings?.workDayStart ?: LocalTime(7, 0),
            dayEnd = settings?.workDayEnd ?: LocalTime(23, 0),
            busy = { busy[it].orEmpty() },
        )
    }

    /** Тренировка для формы изменения; `null` — её уже нет. */
    suspend fun stored(id: String): StoredTraining? {
        val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return null
        val people = trainings.participationsOf(id).ordered()
        val series = t.seriesId?.let { schedule.series(it) }?.takeIf { it.sync.deletedAt == null }
        return StoredTraining(
            id = id,
            draft = TrainingDraft(
                start = t.start,
                typeId = t.typeId,
                // У группы клиенты — её состав и гости «на раз»: форма меняет группу, а не детей.
                clientIds = if (t.groupId != null) emptyList() else people.map { it.clientId },
                groupId = t.groupId,
                locationId = t.locationId,
                durationMin = t.durationMin,
                plan = t.plan.orEmpty(),
            ),
            status = t.status,
            marked = t.marked(people),
            seriesRepeat = series?.let { Repeat(it.weekdays, it.validTo) },
            isDuty = reference.trainingType(t.typeId)?.kind == TrainingKind.DUTY,
        )
    }

    /**
     * Новая тренировка или серия (5.5, 4.4). Серия создаёт тренировки до горизонта; план — у первой.
     * Под запретом — сначала [SaveResult.Confirm] без записи, «Поставить» — тот же вызов с [confirmed].
     */
    suspend fun create(draft: TrainingDraft, confirmed: Boolean = false): SaveResult {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var result: SaveResult = SaveResult.Invalid(emptyMap())
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val type = draft.typeId?.let { reference.trainingType(it) }
            val problems = problems(draft, type?.toForm(), clubNow)
            if (problems.isNotEmpty() || type == null) {
                result = SaveResult.Invalid(problems)
                return@withWriteTransaction
            }
            val restrictions = schedule.blocks().map { it.toRestriction() }
            val recorder = Recorder(db, now)
            val repeat = draft.repeat
            val locationId = draft.locationId!!
            // Дежурство с запретами не сверяется (4.5) и создаётся так же — разово или серией.
            val duty = type.kind == TrainingKind.DUTY
            if (repeat == null) {
                val check = RestrictionRules.check(null, Placement(draft.start, draft.durationMin, locationId, duty), restrictions)
                if (check is RestrictionCheck.Confirm && !confirmed) {
                    result = SaveResult.Confirm(conflictLines(check.hits))
                    return@withWriteTransaction
                }
                val group = draft.groupId?.let { clients.group(it) }
                val entity = TrainingEntity(
                    id = Ids.newId(now),
                    sync = SyncFields.new(coach.id, now),
                    typeId = type.id,
                    locationId = locationId,
                    start = draft.start,
                    durationMin = draft.durationMin,
                    groupId = draft.groupId,
                    plan = draft.plan.cleanPlan() ?: if (draft.start > clubNow && !duty) takePending(recorder, draft.groupId, draft.clientIds) else null,
                    owner = group?.owner ?: Owner.SELF,
                    ownerColleagueId = group?.ownerColleagueId,
                    conductedBy = group?.defaultConductedBy ?: ConductedBy.SELF,
                    colleagueId = group?.defaultColleagueId,
                    overRestriction = when (check) {
                        is RestrictionCheck.Save -> check.overRestriction
                        is RestrictionCheck.Confirm -> true
                    },
                )
                recorder.saveTraining(entity)
                recorder.setParticipants(entity, clientsOn(draft.groupId, draft.clientIds, draft.start.date))
                result = SaveResult.Saved(TrainingFormat.created(draft.start), recorder.undo())
            } else {
                val pattern = draft.pattern(repeat)
                val created = SeriesRules.create(
                    id = Ids.newId(now),
                    pattern = pattern,
                    validFrom = draft.start.date,
                    validTo = repeat.until,
                    now = clubNow,
                    horizonWeeks = horizonWeeks(),
                    members = members(draft.groupId),
                )
                // План — у первой тренировки серии; без плана — отложенный (4.3) у первой будущей.
                val first = draft.plan.cleanPlan()?.let { 0 } ?: created.created.indexOfFirst { it.start > clubNow }.takeIf { it >= 0 && !duty }
                val batch = RestrictionRules.checkAll(created.created.map { it.placement(duty) }, restrictions)
                if (batch != null && !confirmed) {
                    result = SaveResult.Confirm(batchLines(batch))
                    return@withWriteTransaction
                }
                val plan = draft.plan.cleanPlan() ?: first?.let { takePending(recorder, draft.groupId, draft.clientIds) }
                val changes = if (first == null || plan == null) created else {
                    created.copy(created = created.created.mapIndexed { i, t -> if (i == first) t.copy(plan = plan) else t })
                }
                recorder.write(db, changes, coach.id, now, restrictions, pattern, isDuty = { duty })
                // Серия для клиента закрывает его запись «постоянно» в листе ожидания (4.16).
                if (draft.groupId == null && !duty) db.closeWaitForSeries(draft.clientIds, now)
                result = SaveResult.Saved(TrainingFormat.createdSeries(repeat.weekdays, draft.start.time), recorder.undo())
            }
        }
        return result
    }

    /**
     * Изменение тренировки (5.5). У тренировки серии новое время, день, тип, место, длительность или клиенты
     * спрашивают «Только эту / Эту и следующие» (4.4): без [scope] — [SaveResult.AskScope] без записи. Если
     * «Эту и следующие» нельзя (тренировка или новая дата в прошлом), вопроса нет — меняется только эта.
     * Разовая тренировка с «Каждую неделю» становится первой тренировкой новой серии.
     *
     * @param checkFields проверять поля формы; перенос в календаре проверяет только время.
     */
    suspend fun update(
        id: String,
        draft: TrainingDraft,
        scope: EditScope? = null,
        confirmed: Boolean = false,
        checkFields: Boolean = true,
    ): SaveResult {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var result: SaveResult = SaveResult.Invalid(emptyMap())
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val people = trainings.participationsOf(id)
            val type = draft.typeId?.let { reference.trainingType(it) }
            val problems = when {
                checkFields -> problems(draft, type?.toForm(), clubNow, t.status, t.marked(people))
                TrainingTime.problem(draft.start, draft.durationMin) != null -> mapOf(FormField.TIME to "")
                else -> emptyMap()
            }
            val locationId = draft.locationId
            if (problems.isNotEmpty() || type == null || locationId == null) {
                result = SaveResult.Invalid(problems)
                return@withWriteTransaction
            }
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val isDuty = { typeId: String -> typeId in duties }
            val restrictions = schedule.blocks().map { it.toRestriction() }
            val recorder = Recorder(db, now)
            val edit = TrainingEdit(
                start = draft.start,
                durationMin = draft.durationMin,
                typeId = type.id,
                locationId = locationId,
                clientIds = if (draft.groupId != null) emptyList() else draft.clientIds,
                groupId = draft.groupId,
            )
            val before = t.placement(isDuty(t.typeId))
            val after = Placement(edit.start, edit.durationMin, edit.locationId, isDuty(edit.typeId))
            val plan = draft.plan.cleanPlan()
            val series = t.seriesId?.let { schedule.series(it) }?.takeIf { it.sync.deletedAt == null }
            val moved = if (edit.start != t.start) CalendarFormat.moved(edit.start) else TrainingFormat.SAVED
            val group = edit.groupId?.let { clients.group(it) }

            suspend fun single(): Boolean {
                val check = RestrictionRules.check(before, after, restrictions)
                if (check is RestrictionCheck.Confirm && !confirmed) {
                    result = SaveResult.Confirm(conflictLines(check.hits))
                    return false
                }
                return true
            }

            when {
                series != null && SeriesRules.asksScope(t.toSeriesTraining(people), edit) -> {
                    val training = t.toSeriesTraining(people)
                    val today = clubNow.date
                    val following = training.date >= today && edit.start.date >= today &&
                        (series.validTo == null || minOf(training.date, edit.start.date) <= series.validTo)
                    val answer = scope ?: if (following) null else EditScope.ONLY_THIS
                    if (answer == null) {
                        result = SaveResult.AskScope(question(series, t, people, edit), training.date)
                        return@withWriteTransaction
                    }
                    val stored = series.toSeries(seriesClientIds(series.id))
                    if (answer == EditScope.ONLY_THIS || !following) {
                        if (!single()) return@withWriteTransaction
                        val changes = SeriesRules.changeOnlyThis(stored, training, edit, members(edit.groupId))
                        recorder.write(db, changes, coach.id, now, restrictions, stored.pattern, isDuty)
                        setPlan(recorder, id, plan, now)
                        result = SaveResult.Saved(moved, recorder.undo())
                    } else {
                        val all = trainings.trainingsOfSeries(series.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
                        val changes = SeriesRules.changeFollowing(
                            series = stored,
                            training = training,
                            edit = edit,
                            trainings = all,
                            newSeriesId = Ids.newId(now),
                            now = clubNow,
                            horizonWeeks = horizonWeeks(),
                            members = members(edit.groupId ?: stored.pattern.groupId),
                        )
                        val old = all.associateBy { it.id }
                        val placed = changes.created.map { it.placement(isDuty(it.typeId)) } +
                            changes.updated.filter { u -> old[u.id]?.let { RestrictionRules.moved(it.placement(), u.placement()) } ?: true }
                                .map { it.placement(isDuty(it.typeId)) }
                        val batch = RestrictionRules.checkAll(placed, restrictions)
                        if (batch != null && !confirmed) {
                            result = SaveResult.Confirm(batchLines(batch))
                            return@withWriteTransaction
                        }
                        recorder.write(db, changes, coach.id, now, restrictions, stored.pattern, isDuty)
                        setPlan(recorder, id, plan, now)
                        result = SaveResult.Saved(TrainingFormat.SAVED_FOLLOWING, recorder.undo())
                    }
                }
                series != null || draft.repeat == null -> {
                    // Разовая — или у тренировки серии изменился только план: вопроса нет.
                    if (!single()) return@withWriteTransaction
                    val entity = t.edited(edit, plan, RestrictionRules.flag(after, restrictions), group, now)
                    recorder.saveTraining(entity)
                    if (edit.groupId == null || edit.groupId != t.groupId) {
                        recorder.setParticipants(entity, clientsOn(edit.groupId, edit.clientIds, edit.start.date))
                    }
                    result = SaveResult.Saved(moved, recorder.undo())
                }
                else -> {
                    // Разовая стала постоянной: с её даты — серия, сама она — первая тренировка серии.
                    val repeat = draft.repeat ?: return@withWriteTransaction
                    val pattern = draft.pattern(repeat).copy(typeId = type.id)
                    val created = SeriesRules.create(
                        id = Ids.newId(now),
                        pattern = pattern,
                        validFrom = draft.start.date,
                        validTo = repeat.until,
                        now = clubNow,
                        horizonWeeks = horizonWeeks(),
                        members = members(draft.groupId),
                    )
                    val own = created.created.firstOrNull { it.date == draft.start.date && it.start == draft.start }
                    val changes = created.copy(created = created.created - listOfNotNull(own).toSet())
                    val placed = changes.created.map { it.placement(isDuty(it.typeId)) } + listOfNotNull(after.takeIf { RestrictionRules.moved(before, it) })
                    val batch = RestrictionRules.checkAll(placed, restrictions)
                    if (batch != null && !confirmed) {
                        result = SaveResult.Confirm(batchLines(batch))
                        return@withWriteTransaction
                    }
                    recorder.write(db, changes, coach.id, now, restrictions, pattern, isDuty)
                    val seriesId = changes.series.single().id
                    val entity = t.edited(edit, plan, RestrictionRules.flag(after, restrictions), group, now)
                        .copy(seriesId = if (own != null) seriesId else null, seriesDetached = false)
                    recorder.saveTraining(entity)
                    if (edit.groupId == null || edit.groupId != t.groupId) {
                        recorder.setParticipants(entity, clientsOn(edit.groupId, edit.clientIds, edit.start.date))
                    }
                    result = SaveResult.Saved(TrainingFormat.createdSeries(repeat.weekdays, draft.start.time), recorder.undo())
                }
            }
        }
        return result
    }

    /** «Отменить» после сохранения (5.20): всё как было до него. */
    suspend fun undo(undo: Undo) {
        val now = clock()
        db.withWriteTransaction { db.restore(undo, now) }
    }

    // --- Расписание в карточке клиента (5.9) ---

    /** Серии клиента для вкладки «Расписание»: ПТ, сплит и СПТ, и завершённые; групповые меняет группа (5.10). */
    fun seriesOf(clientId: String): Flow<List<ScheduleSeries>> = combine(
        schedule.observeSeriesOfClient(clientId),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { list, types, places ->
        val typeShort = types.associate { it.id to it.shortName }
        val placeShort = places.associate { it.id to it.shortName }
        list.filter { it.groupId == null }.map {
            ScheduleSeries(it.id, it.weekdays, it.startTime, it.durationMin, typeShort[it.typeId].orEmpty(), placeShort[it.locationId].orEmpty(), it.validFrom, it.validTo)
        }
    }

    /** Серия для формы «Изменить с даты…»; `null` — её уже нет или она закончилась. */
    suspend fun storedSeries(id: String): StoredSeries? {
        val s = schedule.series(id)?.takeIf { it.sync.deletedAt == null } ?: return null
        val today = ClubTime.now(clock()).date
        if (s.validTo != null && s.validTo < today) return null
        val from = maxOf(today, s.validFrom)
        return StoredSeries(
            id = id,
            draft = TrainingDraft(
                start = LocalDateTime(from, s.startTime),
                typeId = s.typeId,
                clientIds = if (s.groupId != null) emptyList() else seriesClientIds(id),
                groupId = s.groupId,
                locationId = s.locationId,
                durationMin = s.durationMin,
                repeat = Repeat(s.weekdays, s.validTo),
            ),
            validTo = s.validTo,
        )
    }

    /**
     * «Изменить с даты…» (5.9, 4.4): то же, что «Эту и следующие», с даты черновика — новые дни, время, тип, место,
     * длительность и клиенты. Прошлое и отмеченное не трогается, план выпавшего дня переходит дальше.
     * Под запретом — сначала [SaveResult.Confirm] без записи, как у новой серии.
     */
    suspend fun changeFrom(seriesId: String, draft: TrainingDraft, confirmed: Boolean = false): SaveResult {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var result: SaveResult = SaveResult.Invalid(emptyMap())
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val series = schedule.series(seriesId)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val type = draft.typeId?.let { reference.trainingType(it) }
            val from = draft.start.date
            // Конец серии здесь не меняется — его меняет «Завершить».
            val repeat = Repeat(draft.repeat?.weekdays ?: series.weekdays, null)
            val problems = problems(draft.copy(repeat = repeat), type?.toForm(), clubNow) +
                listOfNotNull(ClientScheduleRules.fromProblem(from, clubNow.date, series.validTo)?.let { FormField.DATE to it })
            if (problems.isNotEmpty() || type == null) {
                result = SaveResult.Invalid(problems)
                return@withWriteTransaction
            }
            val stored = series.toSeries(seriesClientIds(series.id))
            val pattern = draft.pattern(repeat).copy(typeId = type.id)
            val all = trainings.trainingsOfSeries(series.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
            val changes = SeriesRules.changeFrom(
                series = stored,
                from = from,
                pattern = pattern,
                trainings = all,
                newSeriesId = Ids.newId(now),
                now = clubNow,
                horizonWeeks = horizonWeeks(),
                members = members(pattern.groupId),
            )
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val isDuty = { typeId: String -> typeId in duties }
            val restrictions = schedule.blocks().map { it.toRestriction() }
            val old = all.associateBy { it.id }
            val placed = changes.created.map { it.placement(isDuty(it.typeId)) } +
                changes.updated.filter { u -> old[u.id]?.let { RestrictionRules.moved(it.placement(), u.placement()) } ?: true }
                    .map { it.placement(isDuty(it.typeId)) }
            val batch = RestrictionRules.checkAll(placed, restrictions)
            if (batch != null && !confirmed) {
                result = SaveResult.Confirm(batchLines(batch))
                return@withWriteTransaction
            }
            val recorder = Recorder(db, now)
            recorder.write(db, changes, coach.id, now, restrictions, stored.pattern, isDuty)
            result = SaveResult.Saved(ClientFormat.changedFrom(from), recorder.undo())
        }
        return result
    }

    /**
     * «Завершить» (5.9, 4.4): [lastDay] — последний день серии, самое раннее вчера. Будущие тренировки после него
     * удаляются, их планы — в отложенный план клиента (4.3).
     */
    suspend fun endSeries(seriesId: String, lastDay: LocalDate): SaveResult {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var result: SaveResult = SaveResult.Invalid(emptyMap())
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val series = schedule.series(seriesId)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            ClientScheduleRules.lastDayProblem(lastDay, clubNow.date)?.let {
                result = SaveResult.Invalid(mapOf(FormField.DATE to it))
                return@withWriteTransaction
            }
            val stored = series.toSeries(seriesClientIds(series.id))
            val all = trainings.trainingsOfSeries(series.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
            val changes = SeriesRules.end(stored, lastDay, all, clubNow)
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val recorder = Recorder(db, now)
            recorder.write(db, changes, coach.id, now, schedule.blocks().map { it.toRestriction() }, stored.pattern) { it in duties }
            result = SaveResult.Saved(ClientFormat.SERIES_ENDED, recorder.undo())
        }
        return result
    }

    /**
     * Продление серий до горизонта (4.4) — при открытии приложения: тренировки на даты, где у серии ещё нет своей.
     * Повторный запуск ничего не дублирует. Окон запретов нет — флаг ставится молча (4.5). Возвращает, сколько создано.
     */
    suspend fun extendSeries(): Int {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var count = 0
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val list = schedule.seriesFrom(clubNow.date)
            if (list.isEmpty()) return@withWriteTransaction
            val people = schedule.allSeriesClients().groupBy { it.seriesId }
            val series = list.map { s -> s.toSeries(people[s.id].orEmpty().sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }) }
            val existing = trainings.seriesTrainingsFrom(LocalDateTime(clubNow.date, LocalTime(0, 0))).map { it.toSeriesTraining(emptyList()) }
            val members = series.mapNotNull { it.pattern.groupId }.distinct().associateWith { members(it) }
            val changes = SeriesRules.extendAll(series, existing, clubNow, horizonWeeks(), members)
            if (changes.isEmpty) return@withWriteTransaction
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            Recorder(db, now).write(db, changes, coach.id, now, schedule.blocks().map { it.toRestriction() }, owner = null) { it in duties }
            count = changes.created.size
        }
        return count
    }

    /** Ошибки формы: у дежурства — свои (место, время, повтор), у тренировки — все поля (5.5). */
    private fun problems(
        draft: TrainingDraft,
        type: FormType?,
        now: LocalDateTime,
        status: TrainingStatus = TrainingStatus.PLANNED,
        marked: Boolean = false,
    ): Map<FormField, String> =
        if (type?.kind == TrainingKind.DUTY) TrainingFormRules.dutyProblems(draft) else TrainingFormRules.problems(draft, type, now, status, marked)

    private suspend fun horizonWeeks(): Int =
        coaches.currentCoach()?.let { coaches.settings(it.id) }?.seriesHorizonWeeks ?: SeriesRules.DEFAULT_HORIZON_WEEKS

    private suspend fun members(groupId: String?): List<GroupMember> =
        if (groupId == null) emptyList() else clients.membersOf(groupId).map { it.member() }

    /** Кто ждёт на тренировке: состав группы на эту дату или выбранные клиенты. */
    private suspend fun clientsOn(groupId: String?, clientIds: List<String>, date: LocalDate): List<String> =
        if (groupId == null) clientIds else clients.membersOn(groupId, date).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }

    private suspend fun seriesClientIds(seriesId: String): List<String> =
        schedule.seriesClients(seriesId).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }

    /**
     * Отложенный план (4.3) для новой тренировки с пустым планом: у группы — её, у остальных — клиентов, без
     * повторов. Забранный план у них стирается — подставляется он один раз. `null` — ждать нечего.
     */
    private suspend fun takePending(recorder: Recorder, groupId: String?, clientIds: List<String>): String? {
        if (groupId != null) {
            val text = clients.group(groupId)?.pendingPlan?.takeIf { it.isNotBlank() } ?: return null
            recorder.setGroupPendingPlan(groupId, null)
            return text
        }
        val text = PlanCarryRules.pending(clientIds.map { clients.client(it)?.pendingPlan }) ?: return null
        clientIds.forEach { recorder.setPendingPlan(it, null) }
        return text
    }

    private suspend fun setPlan(recorder: Recorder, id: String, plan: String?, now: Long) {
        val t = trainings.training(id) ?: return
        if (t.plan == plan) return
        recorder.saveTraining(t.copy(plan = plan, sync = t.sync.touched(now)))
    }

    /** Текст вопроса: «Никитин А. · каждый вт и чт, 12:00. Новое время — 12:30–13:20.» */
    private suspend fun question(series: SeriesEntity, t: TrainingEntity, people: List<ParticipationEntity>, edit: TrainingEdit): String {
        val names = people.ordered().mapNotNull { p -> clients.client(p.clientId)?.let { ClientName(it.lastName, it.firstName, it.middleName) } }
        val group = t.groupId?.let { clients.group(it) }
        val type = reference.trainingType(t.typeId)
        val title = CalendarFormat.title(names, group?.name, people.size, type?.name.orEmpty())
        val changes = TrainingFormat.changes(
            start = edit.start,
            end = TrainingTime.end(edit.start, edit.durationMin),
            dayChanged = edit.start.date != t.start.date,
            timeChanged = edit.start.time != t.start.time || edit.durationMin != t.durationMin,
            place = edit.locationId.takeIf { it != t.locationId }?.let { reference.location(it)?.shortName },
            type = edit.typeId.takeIf { it != t.typeId }?.let { reference.trainingType(it)?.name },
            clientsChanged = edit.groupId != t.groupId || (edit.groupId == null && edit.clientIds.toSet() != people.map { it.clientId }.toSet()),
        )
        return TrainingFormat.scopeQuestion(TrainingFormat.series(title, series.weekdays, series.startTime), changes)
    }

    /** Строки окна «Поставить всё равно?» — полным названием места (4.5). */
    private suspend fun conflictLines(hits: List<RestrictionHit>): List<String> {
        val places = reference.locations().associateBy { it.id }
        return hits.map { conflict(it.restriction, places[it.restriction.locationId]?.name.orEmpty()) }
    }

    /** Окно на серию: «… — 9 из 17 тренировок, первая вт 20.10» по строке на запрет. */
    private suspend fun batchLines(batch: BatchConflicts): List<String> {
        val places = reference.locations().associateBy { it.id }
        return batch.groups.map { g ->
            TrainingFormat.batchConflict(conflict(g.restriction, places[g.restriction.locationId]?.name.orEmpty()), g.dates.size, batch.checked, g.dates.first())
        }
    }

    private fun conflict(r: Restriction, placeName: String): String = ZoneFormat.conflict(placeName, r.startMinute, r.endMinute, r.reason)

    private class Lists(
        val types: List<FormType>,
        val places: List<FormPlace>,
        val clients: List<FormClient>,
        val groups: List<FormGroup>,
        val relatives: Map<String, List<String>>,
        val duty: FormType?,
    )

    private companion object {
        /** Сколько последних тренировок смотреть: тип, место по типу, недавние группы. */
        const val RECENT_TRAININGS = 60

        /** Сколько участий в них смотреть: недавние клиенты. */
        const val RECENT_PARTICIPATIONS = 120
    }
}

private fun TrainingTypeEntity.toForm(): FormType = FormType(id, name, kind, shade, defaultDurationMin, maxClients)

private fun List<ParticipationEntity>.ordered(): List<ParticipationEntity> = sortedWith(compareBy({ it.sync.createdAt }, { it.id }))

private fun TrainingEntity.marked(people: List<ParticipationEntity>): Boolean =
    status == TrainingStatus.DONE || people.any { it.status == ParticipationStatus.ATTENDED }

private fun String.cleanPlan(): String? = trim().ifEmpty { null }

private fun TrainingDraft.pattern(repeat: Repeat): SeriesPattern = SeriesPattern(
    typeId = typeId.orEmpty(),
    locationId = locationId.orEmpty(),
    weekdays = repeat.weekdays,
    startTime = start.time,
    durationMin = durationMin,
    clientIds = if (groupId != null) emptyList() else clientIds,
    groupId = groupId,
)

/** Тренировка после правки из формы: время, тип, место, группа, план и флаг; у новой группы — «числится» и «проводим» из неё. */
private fun TrainingEntity.edited(
    edit: TrainingEdit,
    plan: String?,
    overRestriction: Boolean,
    group: GroupEntity?,
    now: Long,
): TrainingEntity {
    val newGroup = group?.takeIf { edit.groupId != groupId }
    return copy(
        start = edit.start,
        durationMin = edit.durationMin,
        typeId = edit.typeId,
        locationId = edit.locationId,
        groupId = edit.groupId,
        plan = plan,
        overRestriction = overRestriction,
        owner = newGroup?.owner ?: if (edit.groupId == null) Owner.SELF else owner,
        ownerColleagueId = if (newGroup != null) newGroup.ownerColleagueId else if (edit.groupId == null) null else ownerColleagueId,
        conductedBy = newGroup?.defaultConductedBy ?: if (edit.groupId == null) ConductedBy.SELF else conductedBy,
        colleagueId = if (newGroup != null) newGroup.defaultColleagueId else if (edit.groupId == null) null else colleagueId,
        sync = sync.touched(now),
    )
}
