package com.akenory.personalka.data.training

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.clients.adultOn
import com.akenory.personalka.data.clients.newClientEntity
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.groups.member
import com.akenory.personalka.data.groups.toSeriesTraining
import com.akenory.personalka.data.restrictions.placed
import com.akenory.personalka.data.restrictions.placement
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.series.Recorder
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.data.series.restore
import com.akenory.personalka.data.series.toSeries
import com.akenory.personalka.data.series.write
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.TrainingCardFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.restriction.RestrictionRules
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.training.CancelRules
import com.akenory.personalka.domain.training.ClientOutcome
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.domain.training.ParticipantState
import com.akenory.personalka.domain.training.StatusRules
import com.akenory.personalka.domain.training.StatusSettings
import com.akenory.personalka.domain.training.TrainingCardRules
import com.akenory.personalka.domain.training.TrainingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Клиент в карточке тренировки: «Носова Е.» и его итог (4.1); [guest] — ребёнок «на раз» в группе (5.4, 5.10);
 * [behavior] — поведение из дневника (5.7).
 */
data class CardClient(
    val id: String,
    val name: String,
    val state: ParticipantState,
    val outcome: ClientOutcome,
    val guest: Boolean = false,
    val behavior: String? = null,
)

/**
 * Кого можно добавить «на раз» (5.4): действующий клиент, которого нет в тренировке. [name] — «Белов Артём»,
 * как в выборе «В состав» (5.10); [adult] — взрослых в списке без поиска не показывают.
 */
data class GuestChoice(val id: String, val name: ClientName, val birthDate: LocalDate?, val adult: Boolean)

/**
 * Всё, что показывает карточка тренировки (ТЗ 5.4). [state] — для правил статусов и кнопок.
 *
 * @param title «Орлов М.», «Ковалёв / Ковалёва», «Сб 9:00 ББ (12)».
 * @param seriesWeekdays дни серии — «каждый вт и чт»; разовая — `null`.
 * @param warnings «Поверх запрета · …», «Поверх дежурства · …».
 * @param skippable «Пропустить дату серии»: будущая тренировка серии без отметок и списаний (4.4).
 * @param noDiary проведена, а дневник не заполнен — строка с оранжевой точкой.
 * @param keepsDiary дневник включён (3.2) и у типа есть его поля (3.3) — кнопка «Дневник» (5.4).
 */
data class TrainingCard(
    val id: String,
    val state: TrainingState,
    val title: String,
    val typeShort: String,
    val placeShort: String,
    val groupId: String?,
    val seriesWeekdays: Set<DayOfWeek>?,
    val clients: List<CardClient>,
    val plan: String?,
    val fact: String?,
    val warnings: List<String>,
    val skippable: Boolean,
    val noDiary: Boolean,
    val settings: StatusSettings,
    val keepsDiary: Boolean = false,
) {
    /** Итог для метки статуса: у одного клиента — его итог («Отмена со списанием»), иначе — тренировки. */
    val outcome: ClientOutcome
        get() = clients.singleOrNull()?.takeIf { state.kind != TrainingKind.GROUP }?.outcome ?: when (state.status) {
            TrainingStatus.PLANNED -> ClientOutcome.PLANNED
            TrainingStatus.DONE -> ClientOutcome.DONE
            TrainingStatus.CANCELLED -> ClientOutcome.CANCELLED
        }

    fun client(id: String): CardClient? = clients.firstOrNull { it.id == id }
}

/** Что вышло из действия карточки: [message] — для сообщения с «Отменить» (5.20). `null` у действия — ничего не сделано. */
data class CardResult(val message: String, val undo: Undo)

/**
 * Карточка тренировки (ТЗ 5.4) и отмена (5.6): «Проведено», отметка детей, «Отменить» со списанием или без,
 * «Вернуть», «Удалить», «Пропустить дату серии» и закрытие прошедших в конце (4.1). Статусы считают правила
 * `shared/domain` ([StatusRules], [CancelRules]); здесь — чтение и запись с «Отменить».
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class TrainingCardRepository internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()
    private val schedule = db.scheduleDao()

    /** Карточка; `null` — тренировки нет (удалили). Обновляется сама после каждого действия. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun card(id: String): Flow<TrainingCard?> = trainings.observeTraining(id).flatMapLatest { t ->
        if (t == null || t.sync.deletedAt != null) return@flatMapLatest flowOf(null)
        val dayStart = LocalDateTime(t.start.date, LocalTime(0, 0))
        val dayEnd = LocalDateTime(t.start.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        combine(
            trainings.observeParticipationsOf(id),
            clients.observeAllClients(),
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            combine(
                coaches.observeSettings(),
                schedule.observeBlocks(),
                trainings.observeTrainingsBetween(dayStart, dayEnd),
                t.groupId?.let { clients.observeMembersOf(it) } ?: flowOf(emptyList()),
                { s, b, d, m -> CardExtras(s, b, d, m) },
            ),
        ) { people, all, types, places, (settingsRow, blocks, sameDay, members) ->
            val typeById = types.associateBy { it.id }
            val type = typeById[t.typeId]
            val kind = type?.kind ?: TrainingKind.OTHER
            val placeById = places.associateBy { it.id }
            val placeShort = placeById[t.locationId]?.shortName.orEmpty()
            val ordered = people.ordered()
            val state = t.toState(kind, ordered)
            val names = all.associateBy { it.id }
            val guests = if (t.groupId == null) emptySet() else GroupRules.guests(ordered.map { it.clientId }, members.map { it.member() }, t.start.date).toSet()
            // Дети группы — по алфавиту, как в составе (5.10): новый ребёнок встаёт на своё место, а не в конец.
            val inOrder = if (t.groupId == null) ordered else ordered.sortedBy { p -> names[p.clientId]?.let { ClientRules.sortKey(ClientName(it.lastName, it.firstName, it.middleName)) } }
            val cardClients = inOrder.mapNotNull { p ->
                val c = names[p.clientId] ?: return@mapNotNull null
                CardClient(
                    id = p.clientId,
                    name = CalendarFormat.client(ClientName(c.lastName, c.firstName, c.middleName)),
                    state = p.toState(),
                    outcome = ClientOutcome.of(t.status, p.status),
                    guest = p.clientId in guests,
                    behavior = p.behavior?.takeIf { it.isNotBlank() },
                )
            }
            val group = t.groupId?.let { clients.group(it) }
            val waiting = if (t.status == TrainingStatus.CANCELLED) ordered.size else state.participants.count { !it.isCancelled }
            val series = t.seriesId?.let { schedule.series(it) }?.takeIf { it.sync.deletedAt == null }
            val clubNow = ClubTime.now(clock())
            val warnings = if (t.status == TrainingStatus.CANCELLED || kind == TrainingKind.DUTY) {
                emptyList()
            } else {
                val placement = t.placement(isDuty = false)
                val restricted = RestrictionRules.hits(placement, blocks.map { it.toRestriction() }).map { hit ->
                    TrainingCardFormat.overRestriction(ZoneFormat.restriction(placeShort, hit.restriction.startMinute, hit.restriction.endMinute, hit.restriction.reason))
                }
                val duties = sameDay.filter { typeById[it.typeId]?.kind == TrainingKind.DUTY && it.sync.deletedAt == null }
                val overDuty = RestrictionRules.dutyOverlaps(placement, duties.map { it.placed(isDuty = true) }).map { d ->
                    val from = ClubTime.minuteOfDay(d.placement.start)
                    TrainingCardFormat.overDuty(placeById[d.placement.locationId]?.shortName.orEmpty(), from, minOf(from + d.placement.durationMin, ClubTime.MINUTES_PER_DAY))
                }
                restricted + overDuty
            }
            TrainingCard(
                id = t.id,
                state = state,
                title = CalendarFormat.title(
                    clients = ordered.mapNotNull { p -> names[p.clientId]?.let { ClientName(it.lastName, it.firstName, it.middleName) } },
                    groupName = group?.name,
                    groupSize = waiting,
                    typeName = type?.name.orEmpty(),
                ),
                typeShort = type?.shortName.orEmpty(),
                placeShort = placeShort,
                groupId = t.groupId,
                seriesWeekdays = series?.weekdays,
                clients = cardClients,
                plan = t.plan?.takeIf { it.isNotBlank() },
                fact = t.fact?.takeIf { it.isNotBlank() },
                warnings = warnings,
                skippable = series != null && t.toSeriesTraining(ordered).changeableAt(clubNow),
                noDiary = DiaryRules.missing(t.status, settingsRow?.diaryEnabled ?: true, type?.diaryFact == true, t.fact),
                settings = settingsRow.statusSettings(),
                keepsDiary = (settingsRow?.diaryEnabled ?: true) && type != null &&
                    DiaryRules.fields(kind, type.diaryPlan, type.diaryFact, type.diaryBehavior).any,
            )
        }
    }

    /**
     * Кого можно добавить «на раз» в тренировку [id] (5.4): действующие клиенты, которых в ней нет (отменённые
     * в ней — тоже есть: их возвращает «Вернуть» в строке). Список без поиска и поиск — в карточке.
     */
    fun guestChoices(id: String): Flow<List<GuestChoice>> = combine(
        trainings.observeParticipationsOf(id),
        clients.observeAllClients(),
    ) { people, all ->
        val inside = people.map { it.clientId }.toSet()
        val today = ClubTime.today(clock())
        all.filter { it.status == ClientStatus.ACTIVE && it.id !in inside }
            .map { GuestChoice(it.id, ClientName(it.lastName, it.firstName, it.middleName), it.birthDate, it.adultOn(today)) }
    }

    /**
     * «Добавить на раз» (5.4, 5.10): ребёнок только в этой тренировке группы, состав не меняется. Отмечать уже
     * можно — сразу «пришёл» ([TrainingCardRules.guestStatus]). `null` — нельзя: группа отменена или он уже в ней.
     */
    suspend fun addGuest(id: String, clientId: String): CardResult? = addGuest(id) { _, _ -> clientId }

    /** То же с новым клиентом — «Новый клиент: Белов Артём»: клиент заводится по фамилии и имени (5.8). */
    suspend fun addNewGuest(id: String, lastName: String, firstName: String): CardResult? {
        val last = lastName.trim()
        val first = firstName.trim()
        if (last.isEmpty() || first.isEmpty()) return null
        return addGuest(id) { coachId, now ->
            val client = newClientEntity(coachId, now, ClientDraft(last, first, sex = ClientRules.guessSex(null, last)?.sex))
            clients.upsertClient(client)
            client.id
        }
    }

    /**
     * «Убрать» ребёнка «на раз» — добавили по ошибке (5.4). Детей из состава так не убрать: их отменяют или правят
     * состав группы (5.10).
     */
    suspend fun removeGuest(id: String, clientId: String): CardResult? {
        val now = clock()
        var result: CardResult? = null
        db.withWriteTransaction {
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val groupId = t.groupId ?: return@withWriteTransaction
            val people = trainings.participationsOf(id).ordered()
            val row = people.firstOrNull { it.clientId == clientId } ?: return@withWriteTransaction
            val state = t.toState(reference.trainingType(t.typeId)?.kind ?: TrainingKind.OTHER, people)
            val guest = clientId in GroupRules.guests(people.map { it.clientId }, clients.membersOf(groupId).map { it.member() }, t.start.date)
            if (!TrainingCardRules.canRemoveGuest(state, guest)) return@withWriteTransaction
            val recorder = Recorder(db, now)
            recorder.saveParticipations(id, listOf(row.copy(sync = row.sync.deleted(now))))
            result = CardResult(TrainingCardFormat.guestRemoved(clientName(clientId)), recorder.undo())
        }
        return result
    }

    /** Добавить «на раз» того, кого вернёт [who] (`null` — никого); [who] зовётся, только если добавить можно. */
    private suspend fun addGuest(id: String, who: suspend (coachId: String, now: Long) -> String?): CardResult? {
        val now = clock()
        var result: CardResult? = null
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val people = trainings.participationsOf(id).ordered()
            val state = t.toState(reference.trainingType(t.typeId)?.kind ?: TrainingKind.OTHER, people)
            if (t.groupId == null || !TrainingCardRules.canAddGuest(state)) return@withWriteTransaction
            val clientId = who(coach.id, now) ?: return@withWriteTransaction
            if (people.any { it.clientId == clientId }) return@withWriteTransaction
            val recorder = Recorder(db, now)
            recorder.addParticipant(t, clientId, TrainingCardRules.guestStatus(state, ClubTime.now(now)))
            result = CardResult(TrainingCardFormat.guestAdded(clientName(clientId)), recorder.undo())
        }
        return result
    }

    /** «Проведено», у группы — «Все пришли» (4.1). `null` — уже нельзя: тренировку изменили. */
    suspend fun markDone(id: String): CardResult? = change(id) { state, now, _ ->
        if (!StatusRules.canMarkDone(state, now)) null else StatusRules.markDone(state, now) to TrainingCardFormat.MARKED_DONE
    }

    /** Нажатие на ребёнка в группе (5.4): «пришёл» ⇄ «ещё не отмечен», в проведённой — ⇄ «не пришёл». */
    suspend fun toggleAttendance(id: String, clientId: String): Boolean = change(id) { state, now, _ ->
        if (!TrainingCardRules.canToggle(state, now) || state.participants.none { it.clientId == clientId }) null
        else StatusRules.toggleAttendance(state, clientId, now) to ""
    } != null

    /**
     * Отмена (5.6): [clientIds] пусто — «Всю тренировку», иначе этих клиентов; [notifiedAt] — «Когда предупредили»,
     * [charged] — переключатель «Со списанием», если тренер его трогал. Перенос плана (4.3) — шаг 2.2.
     */
    suspend fun cancel(
        id: String,
        clientIds: List<String>,
        reason: String?,
        notifiedAt: LocalDateTime,
        charged: Boolean? = null,
    ): CardResult? = change(id) { state, _, settings ->
        if (state.status == TrainingStatus.CANCELLED) return@change null
        val open = CancelRules.open(state)
        if (clientIds.any { it !in open }) return@change null
        val next = CancelRules.apply(state, clientIds, reason, notifiedAt, settings, charged)
        val message = if (clientIds.isEmpty() || next.status == TrainingStatus.CANCELLED) {
            TrainingCardFormat.cancelled(state.start, next.participants.any { it.status == ParticipationStatus.CANCELLED_CHARGED })
        } else {
            TrainingCardFormat.cancelledFor(clientIds.map { clientName(it) })
        }
        next to message
    }

    /** «Вернуть» у отменённой (4.1): до конца — «Запланировано», после — «Проведено»; группа ждёт отметки. */
    suspend fun restore(id: String): CardResult? = change(id) { state, now, _ ->
        if (!StatusRules.canReturn(state)) null else StatusRules.returnTraining(state, now) to TrainingCardFormat.RETURNED
    }

    /** «Вернуть» одного отменённого клиента сплита или группы. */
    suspend fun restoreClient(id: String, clientId: String): CardResult? = change(id) { state, now, _ ->
        if (state.participants.none { it.clientId == clientId } || !TrainingCardRules.canReturnClient(state, clientId)) null
        else StatusRules.returnClient(state, clientId, now) to TrainingCardFormat.returnClient(clientName(clientId))
    }

    /**
     * «Удалить» (5.4): проведённую нельзя. Тренировка серии оставляет свою дату пропущенной — продление серии
     * её не вернёт (4.4).
     */
    suspend fun delete(id: String): CardResult? {
        val now = clock()
        var result: CardResult? = null
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            if (!TrainingCardRules.canDelete(t.status)) return@withWriteTransaction
            val recorder = Recorder(db, now)
            recorder.deleteTraining(id)
            val series = t.seriesId?.let { schedule.series(it) }?.takeIf { it.sync.deletedAt == null }
            if (series != null && !t.seriesDetached && t.start.date !in series.skipDates) {
                val stored = series.toSeries(seriesClientIds(series.id))
                recorder.saveSeries(stored.copy(skipDates = stored.skipDates + t.start.date), coach.id)
            }
            result = CardResult(TrainingCardFormat.DELETED, recorder.undo())
        }
        return result
    }

    /** «Пропустить дату серии» (4.4): тренировка удаляется, план переходит на следующую тренировку серии. */
    suspend fun skipDate(id: String): CardResult? {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var result: CardResult? = null
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val series = t.seriesId?.let { schedule.series(it) }?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val training = t.toSeriesTraining(trainings.participationsOf(id))
            if (!training.changeableAt(clubNow)) return@withWriteTransaction
            val stored = series.toSeries(seriesClientIds(series.id))
            val all = trainings.trainingsOfSeries(series.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
            val changes = SeriesRules.skip(stored, training, all, clubNow)
            val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
            val recorder = Recorder(db, now)
            recorder.write(db, changes, coach.id, now, schedule.blocks().map { it.toRestriction() }, stored.pattern) { it in duties }
            result = CardResult(TrainingCardFormat.skipped(t.start.date), recorder.undo())
        }
        return result
    }

    /** «Отменить» в сообщении: всё как было до действия. */
    suspend fun undo(undo: Undo) {
        val now = clock()
        db.withWriteTransaction { db.restore(undo, now) }
    }

    /**
     * Закрытие в конце (4.1): ПТ, сплит и СПТ — «Проведено», если это включено в настройках; группа с отмеченными —
     * «Проведено», неотмеченные — «не пришёл»; дежурство — «Проведено». Вызывается при открытии приложения и каждую
     * минуту; повторный вызов ничего не меняет. Возвращает, сколько закрыто.
     */
    suspend fun closeEnded(): Int {
        val now = clock()
        val clubNow = ClubTime.now(now)
        var count = 0
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val settings = coaches.settings(coach.id).statusSettings()
            val kinds = reference.trainingTypes().associate { it.id to it.kind }
            for (t in trainings.plannedBefore(clubNow, TrainingStatus.PLANNED)) {
                val people = trainings.participationsOf(t.id).ordered()
                val state = t.toState(kinds[t.typeId] ?: TrainingKind.OTHER, people)
                val closed = StatusRules.closeAtEnd(state, clubNow, settings)
                if (closed == state) continue
                save(t, people, closed, now)
                count++
            }
        }
        return count
    }

    /**
     * Действие над одной тренировкой: [action] получает её состояние, «сейчас» и настройки и отвечает новым
     * состоянием и текстом сообщения; `null` — действие недоступно, ничего не пишется.
     */
    private suspend fun change(
        id: String,
        action: suspend (TrainingState, LocalDateTime, StatusSettings) -> Pair<TrainingState, String>?,
    ): CardResult? {
        val now = clock()
        var result: CardResult? = null
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val kind = reference.trainingType(t.typeId)?.kind ?: TrainingKind.OTHER
            val people = trainings.participationsOf(id).ordered()
            val (next, message) = action(t.toState(kind, people), ClubTime.now(now), coaches.settings(coach.id).statusSettings())
                ?: return@withWriteTransaction
            val recorder = Recorder(db, now)
            save(t, people, next, now, recorder)
            result = CardResult(message, recorder.undo())
        }
        return result
    }

    /** Новое состояние — в базу: статус, причина и время отметки тренировки, участия — только изменившиеся. */
    private suspend fun save(t: TrainingEntity, people: List<ParticipationEntity>, next: TrainingState, now: Long, recorder: Recorder = Recorder(db, now)) {
        val doneAt = next.doneAt?.let { ClubTime.toMillis(it) }
        if (t.status != next.status || t.cancelReason != next.cancelReason || t.doneAt != doneAt) {
            recorder.saveTraining(t.copy(status = next.status, cancelReason = next.cancelReason, doneAt = doneAt, sync = t.sync.touched(now)))
        }
        val byClient = next.participants.associateBy { it.clientId }
        val changed = people.mapNotNull { p ->
            val s = byClient[p.clientId] ?: return@mapNotNull null
            if (s == p.toState()) return@mapNotNull null
            p.copy(status = s.status, cancelReason = s.cancelReason, notifiedAt = s.notifiedAt, chargeOverridden = s.chargeOverridden, sync = p.sync.touched(now))
        }
        if (changed.isNotEmpty()) recorder.saveParticipations(t.id, changed)
    }

    private suspend fun clientName(id: String): String =
        clients.client(id)?.let { CalendarFormat.client(ClientName(it.lastName, it.firstName, it.middleName)) }.orEmpty()

    private suspend fun seriesClientIds(seriesId: String): List<String> =
        schedule.seriesClients(seriesId).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }
}

/** Что ещё нужно карточке, кроме тренировки: настройки, запреты, тренировки того же дня, состав группы. */
private data class CardExtras(
    val settings: CoachSettingsEntity?,
    val blocks: List<ScheduleBlockEntity>,
    val sameDay: List<TrainingEntity>,
    val members: List<GroupMemberEntity>,
)

private fun List<ParticipationEntity>.ordered(): List<ParticipationEntity> = sortedWith(compareBy({ it.sync.createdAt }, { it.id }))

private fun ParticipationEntity.toState(): ParticipantState = ParticipantState(clientId, status, cancelReason, notifiedAt, chargeOverridden)

private fun TrainingEntity.toState(kind: TrainingKind, people: List<ParticipationEntity>): TrainingState = TrainingState(
    kind = kind,
    start = start,
    durationMin = durationMin,
    status = status,
    cancelReason = cancelReason,
    participants = people.map { it.toState() },
    doneAt = doneAt?.let { ClubTime.now(it) },
)

private fun CoachSettingsEntity?.statusSettings(): StatusSettings =
    if (this == null) StatusSettings() else StatusSettings(cancelNoticeMinutes, autoDonePersonal)
