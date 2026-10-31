package com.akenory.personalka.data.clients

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ClientRelationEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.groups.member
import com.akenory.personalka.data.groups.syncGroupTrainings
import com.akenory.personalka.data.groups.toSeriesTraining
import com.akenory.personalka.data.money.SptRepository
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.results.ResultRepository
import com.akenory.personalka.data.series.Recorder
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.data.series.restore
import com.akenory.personalka.data.series.toSeries
import com.akenory.personalka.data.series.write
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.AvatarLook
import com.akenory.personalka.domain.client.AvatarRules
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.RelationRole
import com.akenory.personalka.domain.client.Relations
import com.akenory.personalka.domain.client.RelativeLink
import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.client.StoredRelation
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.group.MemberChange
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.training.ClientOutcome
import com.akenory.personalka.domain.training.DiaryRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus

/**
 * Что вернуть по «Отменить» после «В архив»: места в группах, какими они были до архива, и его серии
 * с тренировками ([series], шаг 1.13д).
 */
class ArchiveUndo internal constructor(val clientId: String, val memberships: List<GroupMemberEntity>, internal val series: Undo?)

/** Строка списка клиентов (5.8): клиент, постоянное расписание и ближайшая тренировка. */
data class ClientRow(val client: ClientEntity, val schedule: List<ScheduleLine>, val next: LocalDateTime?)

/**
 * Родственник в карточке (4.17): связь с этой стороны, сам родственник и его расписание. [clientLabel] — у «Другое»
 * подпись второй стороны: кем клиент приходится родственнику.
 */
data class RelativeRow(val link: RelativeLink, val relative: ClientEntity, val schedule: List<ScheduleLine>, val clientLabel: String? = null)

/**
 * Тренировка в карточке клиента (5.9): тренировка, участие клиента, тип и место. [noDiary] — клиент был, а дневника
 * нет ([DiaryRules.missing]); дневник выключен в настройках — `false`.
 */
data class ClientTrainingRow(
    val training: TrainingEntity,
    val participation: ParticipationEntity,
    val type: TrainingTypeEntity?,
    val place: LocationEntity?,
    val noDiary: Boolean = false,
)

/**
 * Клиенты (ТЗ 5.8, 5.9) и родственные связи (4.17) в базе телефона.
 *
 * Связи между записями держит этот код (в базе внешних ключей нет, 1.1): объединение переносит на оставшуюся
 * карточку всё, что ссылается на клиента, «Удалить полностью» стирает его данные сразу (3.1).
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
/** Именинник на «Главной» (шаг 3.9р): [text] — «Ковалёв Артём · 9 лет». */
data class Birthday(val clientId: String, internal val sortKey: String, val text: String)

class ClientRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val clients = db.clientDao()
    private val schedule = db.scheduleDao()
    private val trainings = db.trainingDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /** Результаты клиентов (шаг 2.3, ТЗ 5.11). */
    val results: ResultRepository = ResultRepository(db, clock)

    /** «Без записи» (4.15): список, метка «Нет записи», «Написал» и «Пауза до…». */
    val followup = ClientFollowup(db, clock)

    /** Лист ожидания (4.16): «Ждут время», «+ В ожидание», «Записать», «Больше не ждёт». */
    val waitlist = ClientWaitList(db, clock)

    /** СПТ клиента в «Инфо» (5.9) и «Купил блок со скидкой» (4.9, шаг 3.7а). */
    val spt = SptRepository(db, clock)

    /** Надёжность клиентов (шаг 3.14): карточка клиента и «Уточнить, придёт ли» на «Главной». */
    val reliability = ClientReliability(db)

    /** id всех клиентов, и в архиве: фото удалённых и объединённых стираются (шаг 3.13). */
    val ids: Flow<Set<String>> = clients.observeAllClients().map { list -> list.mapTo(HashSet()) { it.id } }

    /** Все клиенты, и в архиве, со строкой списка. [now] — «сейчас» клуба: от него ближайшая тренировка. */
    fun rows(now: LocalDateTime): Flow<List<ClientRow>> = combine(
        clients.observeAllClients(),
        schedules(now.date),
        trainings.observeNextTrainings(now, TrainingStatus.PLANNED, ParticipationStatus.EXPECTED),
    ) { all, lines, next ->
        val nextOf = next.associate { it.clientId to it.start }
        all.map { ClientRow(it, lines[it.id].orEmpty(), nextOf[it.id]) }
    }

    /**
     * Постоянное расписание каждого клиента на [today] и дальше: серии ПТ, сплита и СПТ — по составу серии,
     * групповые — по составу группы.
     */
    fun schedules(today: LocalDate): Flow<Map<String, List<ScheduleLine>>> = combine(
        schedule.observeSeriesFrom(today),
        schedule.observeSeriesClients(),
        clients.observeMembersFrom(today),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { series, seriesClients, members, types, places ->
        val typeShort = types.associate { it.id to it.shortName }
        val placeShort = places.associate { it.id to it.shortName }
        val byGroup = members.groupBy({ it.groupId }, { it.clientId })
        val byClient = mutableMapOf<String, MutableList<ScheduleLine>>()
        series.forEach { s ->
            val line = ScheduleLine(s.weekdays, s.startTime, typeShort[s.typeId].orEmpty(), placeShort[s.locationId].orEmpty())
            val ids = if (s.groupId != null) byGroup[s.groupId].orEmpty() else seriesClients.filter { it.seriesId == s.id }.map { it.clientId }
            ids.distinct().forEach { byClient.getOrPut(it) { mutableListOf() } += line }
        }
        byClient
    }

    fun client(id: String): Flow<ClientEntity?> = clients.observeClient(id)

    /**
     * Именинники [day] среди занимающихся (шаг 3.9р): блок «День рождения сегодня» на «Главной», по фамилии.
     * Архивных нет — они больше не ходят.
     */
    fun birthdays(day: LocalDate): Flow<List<Birthday>> = clients.observeClients(ClientStatus.ACTIVE).map { list ->
        list.mapNotNull { c ->
            val birth = c.birthDate?.takeIf { ClientRules.isBirthday(it, day) } ?: return@mapNotNull null
            val name = ClientName(c.lastName, c.firstName, c.middleName)
            Birthday(c.id, ClientRules.sortKey(name), ClientFormat.birthday(name.short, birth, day))
        }.sortedBy { it.sortKey }
    }

    /** Родственники клиента (4.17), и те, что в архиве, — в порядке, в котором их добавляли. */
    fun relatives(clientId: String, today: LocalDate): Flow<List<RelativeRow>> = combine(
        clients.observeRelationsOf(clientId),
        clients.observeAllClients(),
        schedules(today),
    ) { relations, all, lines ->
        val byId = all.associateBy { it.id }
        relations.sortedWith(compareBy<ClientRelationEntity>({ it.sync.createdAt }, { it.id })).mapNotNull { r ->
            val link = Relations.linkFor(clientId, r.id, r.stored()) ?: return@mapNotNull null
            val relative = byId[link.relativeId] ?: return@mapNotNull null
            RelativeRow(link, relative, lines[relative.id].orEmpty(), Relations.linkFor(relative.id, r.id, r.stored())?.label)
        }
    }

    /** Тренировки клиента по времени, и отменённые (5.9). */
    fun trainings(clientId: String): Flow<List<ClientTrainingRow>> = combine(
        trainings.observeTrainingsOfClient(clientId),
        trainings.observeParticipationsOfClient(clientId),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
        coaches.observeSettings(),
    ) { list, participations, types, places, settings ->
        val byTraining = participations.associateBy { it.trainingId }
        val typeOf = types.associateBy { it.id }
        val placeOf = places.associateBy { it.id }
        val diaryEnabled = settings?.diaryEnabled ?: false
        // История в группе — с первого «пришёл» (4.4, шаг 5.3н): раньше прошедшие тренировки группы не видны.
        val clubNow = ClubTime.now(clock())
        val starts = GroupRules.historyStarts(
            list.mapNotNull { t -> t.groupId?.let { g -> byTraining[t.id]?.let { Triple(g, t.start.date, it.status == ParticipationStatus.ATTENDED) } } },
        )
        list.mapNotNull { t ->
            val p = byTraining[t.id] ?: return@mapNotNull null
            if (GroupRules.hiddenFromHistory(t.groupId, t.start.date, t.start <= clubNow, p.status, starts)) return@mapNotNull null
            val type = typeOf[t.typeId]
            val noDiary = ClientOutcome.of(t.status, p.status) == ClientOutcome.DONE &&
                DiaryRules.missing(t.status, diaryEnabled, type?.diaryFact == true, t.fact)
            ClientTrainingRow(t, p, type, placeOf[t.locationId], noDiary)
        }
    }

    /** Сколько ближайших тренировок показывать в карточке (настройка, 4). */
    suspend fun upcomingInCard(): Int = coaches.currentCoach()?.let { coaches.settings(it.id) }?.upcomingInCard ?: DEFAULT_UPCOMING

    // --- Клиент ---

    /** Новый клиент из формы (5.8). Возвращает id. */
    suspend fun create(draft: ClientDraft): String {
        val now = clock()
        val client = newClient(coachId(), now, draft)
        clients.upsertClient(client)
        return client.id
    }

    /**
     * Похожий клиент перед созданием нового (5.8, шаг 3.9о): ближайший среди всех, и в архиве, кроме [except].
     * Все места, где заводится клиент, спрашивают его — дубль не появится незаметно.
     */
    suspend fun similar(name: ClientName, except: String? = null): ClientEntity? = db.similarClient(name, except)

    /** Правка из «Изменить». Поля, которых нет в форме (1С, кабинет), не трогаются. */
    suspend fun update(id: String, draft: ClientDraft) {
        val client = clients.client(id) ?: return
        val now = clock()
        clients.upsertClient(draft.applyTo(client, ClubTime.today(now)).copy(sync = client.sync.touched(now)))
    }

    /**
     * «Придёт» в «Уточнить, придёт ли» (шаг 3.16): клиент подтвердил приход на это начало тренировки — до него он
     * в списке не появится. Возвращает участие до правки для «Отменить» ([undoConfirm]); участия нет — `null`.
     */
    suspend fun confirmVisit(participationId: String): ParticipationEntity? {
        val p = trainings.participation(participationId) ?: return null
        val t = trainings.training(p.trainingId) ?: return null
        trainings.upsertParticipation(p.copy(confirmedStart = t.start, sync = p.sync.touched(clock())))
        return p
    }

    /** «Отменить» после «Придёт»: участие как было, клиент снова в списке. */
    suspend fun undoConfirm(before: ParticipationEntity) {
        val current = trainings.participation(before.id) ?: return
        trainings.upsertParticipation(current.copy(confirmedStart = before.confirmedStart, sync = current.sync.touched(clock())))
    }

    /**
     * «В архив» (5.9): клиент остаётся в связях и истории, из групп уходит через состав (4.4) — последний день
     * в группе вчерашний, в будущих тренировках групп его больше не ждут; прошлое не меняется. Его серии ПТ, сплита
     * и СПТ заканчиваются сегодня, будущие тренировки удаляются (и сегодняшние, если ещё не начались), их планы —
     * в отложенный план клиента; в сплите с двумя клиентами серия идёт дальше со вторым (4.4, шаг 1.13д).
     * Возвращает то, что вернёт «Отменить» ([undoArchive]); клиента нет или он уже в архиве — `null`.
     */
    suspend fun archive(id: String): ArchiveUndo? {
        val now = clock()
        val lastDay = ClubTime.today(now).minus(1, DateTimeUnit.DAY)
        var undo: ArchiveUndo? = null
        db.withWriteTransaction {
            val client = clients.client(id)
            if (client == null || client.status == ClientStatus.ARCHIVED) return@withWriteTransaction
            clients.upsertClient(client.copy(sync = client.sync.touched(now), status = ClientStatus.ARCHIVED))
            val memberships = clients.membershipsOf(id)
            val changed = mutableListOf<GroupMemberEntity>()
            memberships.groupBy { it.groupId }.forEach { (groupId, mine) ->
                val before = clients.membersOf(groupId).map { it.member() }
                mine.forEach { m ->
                    val change = GroupRules.leave(listOf(m.member()), lastDay).firstOrNull() ?: return@forEach
                    when (change) {
                        is MemberChange.End -> clients.upsertGroupMember(m.copy(sync = m.sync.touched(now), toDate = change.lastDay))
                        is MemberChange.Remove -> clients.upsertGroupMember(m.copy(sync = m.sync.deleted(now)))
                    }
                    changed += m
                }
                db.syncGroupTrainings(groupId, before, now)
            }
            val series = endSeriesOf(id, now)
            undo = ArchiveUndo(id, changed, series)
            // Серии ушедшего клиента освободили время — кто его ждёт (4.16).
            series?.let { db.recordFreed(it, now) }
        }
        return undo
    }

    /** «Отменить» после «В архив»: клиент снова действующий, места в группах — как были. */
    suspend fun undoArchive(undo: ArchiveUndo) {
        val now = clock()
        db.withWriteTransaction {
            val client = clients.client(undo.clientId) ?: return@withWriteTransaction
            clients.upsertClient(client.copy(sync = client.sync.touched(now), status = ClientStatus.ACTIVE))
            undo.series?.let { db.restore(it, now) }
            undo.memberships.groupBy { it.groupId }.forEach { (groupId, old) ->
                val before = clients.membersOf(groupId).map { it.member() }
                old.forEach { m ->
                    val current = clients.groupMember(m.id) ?: return@forEach
                    clients.upsertGroupMember(m.copy(sync = current.sync.touched(now).copy(deletedAt = m.sync.deletedAt)))
                }
                db.syncGroupTrainings(groupId, before, now)
            }
        }
    }

    /** «Вернуть из архива» из меню: клиент снова в списке; в группы его возвращают через состав. */
    suspend fun unarchive(id: String) = db.unarchiveClient(id, clock())

    /**
     * «Объединить с…» (5.8, 4.17): тренировки, результаты, серии, группы, стартовые, лист ожидания и связи
     * клиента [goneId] переходят к [keepId], пустые поля оставшейся карточки заполняются из второй,
     * вторая карточка удаляется. Если оба были на одной тренировке или в одной серии, остаётся запись [keepId];
     * связь дублей между собой и повтор связи с тем же человеком схлопываются.
     */
    suspend fun merge(keepId: String, goneId: String) {
        require(keepId != goneId) { "Клиент не объединяется сам с собой" }
        val now = clock()
        db.withWriteTransaction {
            val keep = clients.client(keepId) ?: return@withWriteTransaction
            val gone = clients.client(goneId) ?: return@withWriteTransaction
            clients.upsertClient(keep.mergedWith(gone, ClubTime.today(now)).copy(sync = keep.sync.touched(now)))

            trainings.participationRowsOf(goneId).filter { it.sync.deletedAt == null }.forEach { p ->
                val mine = trainings.participationRow(p.trainingId, keepId)
                when {
                    mine == null -> trainings.upsertParticipation(p.copy(clientId = keepId, sync = p.sync.touched(now)))
                    mine.sync.deletedAt != null -> {
                        trainings.upsertParticipation(p.copy(id = mine.id, clientId = keepId, sync = mine.sync.restored(now)))
                        trainings.upsertParticipation(p.copy(sync = p.sync.deleted(now)))
                    }
                    else -> trainings.upsertParticipation(p.copy(sync = p.sync.deleted(now)))
                }
            }
            schedule.seriesClientRowsOf(goneId).filter { it.sync.deletedAt == null }.forEach { sc ->
                val mine = schedule.seriesClientRow(sc.seriesId, keepId)
                when {
                    mine == null -> schedule.upsertSeriesClient(sc.copy(clientId = keepId, sync = sc.sync.touched(now)))
                    mine.sync.deletedAt != null -> {
                        schedule.upsertSeriesClient(mine.copy(sync = mine.sync.restored(now)))
                        schedule.upsertSeriesClient(sc.copy(sync = sc.sync.deleted(now)))
                    }
                    else -> schedule.upsertSeriesClient(sc.copy(sync = sc.sync.deleted(now)))
                }
            }
            trainings.resultsOf(goneId).forEach { trainings.upsertResult(it.copy(clientId = keepId, sync = it.sync.touched(now))) }
            trainings.sptCasesOf(goneId).forEach { trainings.upsertSptCase(it.copy(clientId = keepId, sync = it.sync.touched(now))) }
            clients.membershipsOf(goneId).forEach { clients.upsertGroupMember(it.copy(clientId = keepId, sync = it.sync.touched(now))) }
            clients.waitEntriesOf(goneId).forEach { clients.upsertWaitEntry(it.copy(clientId = keepId, sync = it.sync.touched(now))) }

            val keepRelations = clients.relationsOf(keepId)
            val goneRelations = clients.relationsOf(goneId)
            val drops = Relations.mergeDrops(keepId, goneId, keepRelations.map { it.id to it.stored() }, goneRelations.map { it.id to it.stored() })
            goneRelations.forEach { r ->
                if (r.id in drops) {
                    clients.upsertRelation(r.copy(sync = r.sync.deleted(now)))
                    return@forEach
                }
                val moved = r.copy(
                    clientAId = if (r.clientAId == goneId) keepId else r.clientAId,
                    clientBId = if (r.clientBId == goneId) keepId else r.clientBId,
                )
                // Пара уникальна и с удалёнными записями: убранная раньше связь с тем же человеком оживает.
                val old = clients.relationRowsBetween(moved.clientAId, moved.clientBId).firstOrNull()
                if (old == null) {
                    clients.upsertRelation(moved.copy(sync = r.sync.touched(now)))
                } else {
                    clients.upsertRelation(moved.copy(id = old.id, sync = old.sync.restored(now)))
                    clients.upsertRelation(r.copy(sync = r.sync.deleted(now)))
                }
            }

            clients.upsertClient(gone.copy(sync = gone.sync.deleted(now)))
        }
    }

    /**
     * «Удалить полностью» (5.9, 3.1, 10.3): данные клиента стираются сразу — карточка, результаты, связи,
     * лист ожидания, стартовые, место в группах и сериях. Тренировки, которые ещё не начались, — без него:
     * осталось пусто (не группа) — удаляется и тренировка. Прошедшие остаются для зарплаты без имени и без
     * записей о клиенте: стёрты поведение и причина отмены, а у тренировки только с ним — план и факт.
     * Серия, где больше никого нет, заканчивается вчера.
     */
    suspend fun erase(id: String) {
        val now = clock()
        val local = ClubTime.now(now)
        db.withWriteTransaction {
            trainings.participationRowsOf(id).forEach { p ->
                val training = trainings.training(p.trainingId)
                if (training == null || training.sync.deletedAt != null) {
                    trainings.eraseParticipation(p.id)
                    return@forEach
                }
                val others = trainings.participationsOf(training.id).filter { it.clientId != id }
                if (training.start > local) {
                    trainings.eraseParticipation(p.id)
                    if (others.isEmpty() && training.groupId == null) {
                        trainings.upsertTraining(training.copy(sync = training.sync.deleted(now)))
                    }
                } else {
                    trainings.upsertParticipation(p.copy(behavior = null, cancelReason = null, sync = p.sync.touched(now)))
                    if (others.isEmpty() && training.groupId == null) {
                        trainings.upsertTraining(training.copy(plan = null, fact = null, cancelReason = null, sync = training.sync.touched(now)))
                    }
                }
            }
            val yesterday = local.date.minus(1, DateTimeUnit.DAY)
            schedule.seriesClientRowsOf(id).filter { it.sync.deletedAt == null }.forEach { sc ->
                val series = schedule.series(sc.seriesId) ?: return@forEach
                val others = schedule.seriesClients(series.id).filter { it.clientId != id }
                if (others.isEmpty() && series.groupId == null && series.sync.deletedAt == null) {
                    val ended = when {
                        series.validFrom > yesterday -> series.copy(sync = series.sync.deleted(now))
                        series.validTo == null || series.validTo > yesterday -> series.copy(validTo = yesterday, sync = series.sync.touched(now))
                        else -> null
                    }
                    if (ended != null) schedule.upsertSeries(ended)
                }
            }
            schedule.eraseSeriesClientsOf(id)
            trainings.eraseResultsOf(id)
            trainings.eraseSptCasesOf(id)
            clients.eraseWaitEntriesOf(id)
            clients.eraseMembershipsOf(id)
            clients.eraseRelationsOf(id)
            clients.eraseClient(id)
        }
    }

    // --- Родственники (4.17) ---

    /**
     * Родственник [relativeId] приходится клиенту [clientId] — [role]. Пол, которого не было, задаёт выбор:
     * «Мать» — [relativeSex] женский; [clientSex] — ответ на «Артём для Натальи: сын / дочь».
     * Связь с тем же человеком одна: повторное добавление меняет её. Возвращает id связи.
     */
    suspend fun addRelation(
        clientId: String,
        relativeId: String,
        role: RelationRole,
        relativeSex: Sex? = null,
        clientSex: Sex? = null,
        relativeLabel: String? = null,
        clientLabel: String? = null,
    ): String {
        require(clientId != relativeId) { "Клиент не родственник сам себе" }
        val now = clock()
        val coach = coachId()
        val stored = Relations.orient(clientId, relativeId, role, relativeLabel, clientLabel)
        val old = clients.relationRowsBetween(clientId, relativeId).firstOrNull()
        val id = old?.id ?: Ids.newId(now)
        db.withWriteTransaction {
            clients.upsertRelation(stored.toEntity(id, old?.sync?.restored(now) ?: SyncFields.new(coach, now)))
            setSexIfUnknown(relativeId, relativeSex, now)
            setSexIfUnknown(clientId, clientSex, now)
        }
        return id
    }

    /**
     * «Новый клиент» прямо из «+ Родственник»: фамилия и имя, пол — из выбранного слова или по фамилии;
     * родители, бабушки, дедушки, дяди, тёти и супруги — взрослые. Клиент и связь — одной транзакцией.
     * Возвращает id нового клиента.
     */
    suspend fun createRelative(
        clientId: String,
        lastName: String,
        firstName: String,
        role: RelationRole,
        relativeSex: Sex? = null,
        clientSex: Sex? = null,
        relativeLabel: String? = null,
        clientLabel: String? = null,
    ): String {
        val now = clock()
        val coach = coachId()
        val sex = relativeSex ?: ClientRules.guessSex(null, lastName)?.sex
        val adult = role.kind == RelationKind.SPOUSE ||
            (role.senior && role.kind in setOf(RelationKind.PARENT, RelationKind.GRANDPARENT, RelationKind.AUNT_UNCLE))
        val relative = newClient(coach, now, ClientDraft(lastName, firstName, sex = sex, isAdult = adult))
        val stored = Relations.orient(clientId, relative.id, role, relativeLabel, clientLabel)
        db.withWriteTransaction {
            clients.upsertClient(relative)
            clients.upsertRelation(stored.toEntity(Ids.newId(now + 1), SyncFields.new(coach, now)))
            setSexIfUnknown(clientId, clientSex, now)
        }
        return relative.id
    }

    /** «Изменить связь» из карточки [clientId]: та же запись, новое слово — сразу в обеих карточках. */
    suspend fun updateRelation(
        relationId: String,
        clientId: String,
        role: RelationRole,
        relativeSex: Sex? = null,
        clientSex: Sex? = null,
        relativeLabel: String? = null,
        clientLabel: String? = null,
    ) {
        val old = clients.relation(relationId) ?: return
        val relativeId = Relations.linkFor(clientId, old.id, old.stored())?.relativeId ?: return
        val now = clock()
        val stored = Relations.orient(clientId, relativeId, role, relativeLabel, clientLabel)
        db.withWriteTransaction {
            clients.upsertRelation(stored.toEntity(old.id, old.sync.touched(now)))
            setSexIfUnknown(relativeId, relativeSex, now)
            setSexIfUnknown(clientId, clientSex, now)
        }
    }

    /** «Убрать связь» — сразу из обеих карточек; [restoreRelation] — «Отменить». */
    suspend fun removeRelation(relationId: String) {
        val r = clients.relation(relationId) ?: return
        if (r.sync.deletedAt == null) clients.upsertRelation(r.copy(sync = r.sync.deleted(clock())))
    }

    suspend fun restoreRelation(relationId: String) {
        val r = clients.relation(relationId) ?: return
        if (r.sync.deletedAt != null) clients.upsertRelation(r.copy(sync = r.sync.restored(clock())))
    }

    private suspend fun setSexIfUnknown(clientId: String, sex: Sex?, now: Long) {
        if (sex == null) return
        val client = clients.client(clientId) ?: return
        if (client.sex == null) clients.upsertClient(client.copy(sex = sex, sync = client.sync.touched(now)))
    }

    /** Серии клиента в архиве (4.4): по серии за раз — отложенные планы идут ему. `null` — серий не было. */
    private suspend fun endSeriesOf(clientId: String, now: Long): Undo? {
        val clubNow = ClubTime.now(now)
        val coach = coaches.currentCoach() ?: return null
        val mine = schedule.seriesOfClient(clientId).filter { it.groupId == null && (it.validTo == null || it.validTo >= clubNow.date) }
        if (mine.isEmpty()) return null
        val duties = reference.trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
        val restrictions = schedule.blocks().map { it.toRestriction() }
        val recorder = Recorder(db, now)
        mine.forEach { entity ->
            val people = schedule.seriesClients(entity.id).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }
            val series = entity.toSeries(people)
            val own = trainings.trainingsOfSeries(entity.id).map { it.toSeriesTraining(trainings.participationsOf(it.id)) }
            val changes = SeriesRules.archiveClient(clientId, listOf(series), own, clubNow) { Ids.newId(now) }
            if (changes.isEmpty) return@forEach
            recorder.write(db, changes, coach.id, now, restrictions, series.pattern.copy(clientIds = listOf(clientId))) { it in duties }
        }
        return recorder.undo()
    }

    private suspend fun coachId(): String = coaches.currentCoach()?.id ?: error("Клиенты появляются после первого запуска")

    private fun newClient(coachId: String, now: Long, draft: ClientDraft): ClientEntity = newClientEntity(coachId, now, draft)

    private companion object {
        const val DEFAULT_UPCOMING = 4
    }
}

/** Клиент из базы — в форму «Изменить». */
fun ClientEntity.draft(): ClientDraft = ClientDraft(
    lastName = lastName,
    firstName = firstName,
    middleName = middleName.orEmpty(),
    sex = sex,
    birthDate = birthDate,
    isAdult = isAdult,
    contactName = contactName.orEmpty(),
    contactPhone = contactPhone.orEmpty(),
    mediaUrl = mediaUrl.orEmpty(),
    privateNote = privateNote.orEmpty(),
)

/** Взрослый ли сегодня: с датой рождения — по возрасту, иначе как отмечено (3.5). */
fun ClientEntity.adultOn(today: LocalDate): Boolean = ClientRules.isAdult(birthDate, isAdult, today)

/** Аватарка без фото (шаг 3.13): инициалы, цвет, возраст и пол клиента. */
fun ClientEntity.avatar(today: LocalDate): AvatarLook =
    AvatarRules.client(ClientName(lastName, firstName, middleName), sex, birthDate, isAdult, today)

/** Новый клиент из черновика — из формы клиента и из «Добавить на раз» (5.4). */
/** Ближайший похожий клиент среди всех, и в архиве, кроме [except] (5.8, шаг 3.9о). */
internal suspend fun PersonalkaDatabase.similarClient(name: ClientName, except: String? = null): ClientEntity? =
    ClientRules.similar(name, clientDao().observeAllClients().first().filter { it.id != except }) {
        ClientName(it.lastName, it.firstName, it.middleName)
    }.firstOrNull()

/** Клиент из архива снова действующий; уже действующий не меняется. */
internal suspend fun PersonalkaDatabase.unarchiveClient(id: String, now: Long) {
    val client = clientDao().client(id) ?: return
    if (client.status != ClientStatus.ACTIVE) {
        clientDao().upsertClient(client.copy(sync = client.sync.touched(now), status = ClientStatus.ACTIVE))
    }
}

internal fun newClientEntity(coachId: String, now: Long, draft: ClientDraft): ClientEntity {
    val blank = ClientEntity(id = Ids.newId(now), sync = SyncFields.new(coachId, now), lastName = "", firstName = "")
    return draft.applyTo(blank, ClubTime.today(now))
}

private fun ClientDraft.applyTo(client: ClientEntity, today: LocalDate): ClientEntity = client.copy(
    lastName = lastName.trim(),
    firstName = firstName.trim(),
    middleName = middleName.trim().ifEmpty { null },
    sex = sex,
    birthDate = birthDate,
    isAdult = ClientRules.isAdult(birthDate, isAdult, today),
    contactName = contactName.trim().ifEmpty { null },
    contactPhone = ClientRules.normalizePhone(contactPhone).ifEmpty { null },
    mediaUrl = mediaUrl.trim().ifEmpty { null },
    privateNote = privateNote.trim().ifEmpty { null },
)

/** Оставшаяся карточка после объединения: её поля главнее, пустые берутся из второй, заметки — обе. */
private fun ClientEntity.mergedWith(gone: ClientEntity, today: LocalDate): ClientEntity {
    val birth = birthDate ?: gone.birthDate
    val notes = listOfNotNull(privateNote, gone.privateNote).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return copy(
        middleName = middleName ?: gone.middleName,
        birthDate = birth,
        isAdult = ClientRules.isAdult(birth, isAdult, today),
        sex = sex ?: gone.sex,
        contactName = contactName ?: gone.contactName,
        contactPhone = contactPhone ?: gone.contactPhone,
        mediaUrl = mediaUrl ?: gone.mediaUrl,
        privateNote = notes.joinToString("\n").ifEmpty { null },
        name1c = name1c ?: gone.name1c,
        pendingPlan = pendingPlan ?: gone.pendingPlan,
        status = if (status == ClientStatus.ACTIVE || gone.status == ClientStatus.ACTIVE) ClientStatus.ACTIVE else status,
    )
}

private fun ClientRelationEntity.stored() = StoredRelation(clientAId, clientBId, kind, labelA, labelB)

private fun StoredRelation.toEntity(id: String, sync: SyncFields) = ClientRelationEntity(
    id = id,
    sync = sync,
    clientAId = aId,
    clientBId = bId,
    kind = kind,
    labelA = labelA,
    labelB = labelB,
)
