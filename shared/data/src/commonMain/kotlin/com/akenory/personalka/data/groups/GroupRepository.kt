package com.akenory.personalka.data.groups

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.group.MemberChange
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.CoCoachingRules
import com.akenory.personalka.domain.money.Sharing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Группа в списке и карточке (5.10): тип, место, весь состав с датами (и ушедшие) и постоянное расписание.
 */
data class GroupRow(
    val group: GroupEntity,
    val type: TrainingTypeEntity?,
    val place: LocationEntity?,
    val members: List<GroupMemberEntity>,
    val schedule: List<ScheduleLine>,
) {
    /** Кто в группе на [date] — по одному разу, даже если записей несколько. */
    fun membersOn(date: LocalDate): List<GroupMemberEntity> =
        members.filter { date >= it.fromDate && (it.toDate == null || date <= it.toDate) }.distinctBy { it.clientId }
}

/** Тренировка группы с участием детей: посещаемость и история в карточке группы. */
data class GroupTrainingRow(val training: TrainingEntity, val participations: List<ParticipationEntity>)

/**
 * Постоянные группы и их состав с датами (ТЗ 5.10, 4.4) в базе телефона.
 *
 * Любая правка состава сразу меняет ожидаемых детей в будущих неотмеченных тренировках группы; прошлое,
 * отмеченное, отменённое и дети «на раз» не меняются — состав прошлых занятий остаётся, каким был.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class GroupRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val clients = db.clientDao()
    private val trainings = db.trainingDao()
    private val schedule = db.scheduleDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /** Все группы с составом и расписанием на [today] и дальше. */
    fun rows(today: LocalDate): Flow<List<GroupRow>> = combine(
        clients.observeGroups(),
        clients.observeAllMembers(),
        schedule.observeSeriesFrom(today),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { groups, members, series, types, places ->
        val typeOf = types.associateBy { it.id }
        val placeOf = places.associateBy { it.id }
        val membersOf = members.groupBy { it.groupId }
        groups.map { g ->
            val lines = series.filter { it.groupId == g.id }.map { s ->
                ScheduleLine(s.weekdays, s.startTime, typeOf[s.typeId]?.shortName.orEmpty(), placeOf[s.locationId]?.shortName.orEmpty())
            }
            GroupRow(g, typeOf[g.typeId], placeOf[g.locationId], membersOf[g.id].orEmpty(), lines)
        }
    }

    /** Тренировки группы по времени с участием детей. */
    fun trainings(groupId: String): Flow<List<GroupTrainingRow>> = combine(
        trainings.observeTrainingsOfGroup(groupId),
        trainings.observeParticipationsOfGroup(groupId),
    ) { list, participations ->
        val byTraining = participations.groupBy { it.trainingId }
        list.map { GroupTrainingRow(it, byTraining[it.id].orEmpty()) }
    }

    // --- Группа ---

    /**
     * Новая группа (5.10). Числится на тренере и ведёт он сам, пока совместное ведение выключено; с ним — как
     * в форме ([GroupDraft.sharing], шаг 3.7г). Возвращает id.
     */
    suspend fun create(draft: GroupDraft): String {
        val coach = coaches.currentCoach() ?: error("Группы появляются после первого запуска")
        val now = clock()
        val group = GroupEntity(
            id = Ids.newId(now),
            sync = SyncFields.new(coach.id, now),
            name = draft.name.trim(),
            typeId = requireNotNull(draft.typeId) { "Тип группы не выбран" },
            locationId = requireNotNull(draft.locationId) { "Место группы не выбрано" },
        ).with(draft.sharing)
        clients.upsertGroup(group)
        return group.id
    }

    /**
     * Название, тип, место и кто ведёт. Прошлые тренировки хранят свои тип и место. Новые «На ком числится» и «Кто
     * обычно ведёт» переходят на будущие неотмеченные тренировки группы, которые ещё по прежним значениям
     * ([CoCoachingRules.follows], шаг 3.7г); изменённые в карточке отдельно остаются.
     */
    suspend fun update(id: String, draft: GroupDraft) {
        val now = clock()
        db.withWriteTransaction {
            val group = clients.group(id) ?: return@withWriteTransaction
            val old = group.sharing()
            val next = CoCoachingRules.normalize(draft.sharing)
            clients.upsertGroup(
                group.copy(
                    sync = group.sync.touched(now),
                    name = draft.name.trim(),
                    typeId = draft.typeId ?: group.typeId,
                    locationId = draft.locationId ?: group.locationId,
                ).with(next),
            )
            if (next == CoCoachingRules.normalize(old)) return@withWriteTransaction
            val clubNow = ClubTime.now(now)
            trainings.trainingsOfGroup(id)
                .filter { it.status == TrainingStatus.PLANNED && it.start >= clubNow && CoCoachingRules.follows(it.sharing(), old) }
                .forEach { trainings.upsertTraining(it.with(next).copy(sync = it.sync.touched(now))) }
        }
    }

    /** Можно ли удалить группу: у неё нет ни тренировок, ни серий — история её не помнит. */
    suspend fun canDelete(id: String): Boolean =
        trainings.trainingsOfGroup(id).isEmpty() && schedule.seriesCountOfGroup(id) == 0

    /** Удалить группу, заведённую по ошибке, вместе с составом. Группу с историей не удаляет — возвращает `false`. */
    suspend fun delete(id: String): Boolean {
        val now = clock()
        var deleted = false
        db.withWriteTransaction {
            val group = clients.group(id)
            if (group != null && group.sync.deletedAt == null && canDelete(id)) {
                clients.membersOf(id).forEach { clients.upsertGroupMember(it.copy(sync = it.sync.deleted(now))) }
                clients.upsertGroup(group.copy(sync = group.sync.deleted(now)))
                deleted = true
            }
        }
        return deleted
    }

    // --- Состав (5.10) ---

    /** Ребёнок в составе с [from]: с этого дня он ожидается в будущих тренировках группы. Возвращает id записи. */
    suspend fun addMember(groupId: String, clientId: String, from: LocalDate): String {
        val group = clients.group(groupId) ?: error("Группы $groupId нет")
        val now = clock()
        val member = GroupMemberEntity(
            id = Ids.newId(now),
            sync = SyncFields.new(group.sync.coachId, now),
            groupId = groupId,
            clientId = clientId,
            fromDate = from,
        )
        changeMembers(groupId, now) { clients.upsertGroupMember(member) }
        return member.id
    }

    /**
     * Даты места в составе: [from] — первый день, [lastDay] — последний (`null` — занимается). «Исключить с даты»
     * — это последний день накануне неё (`GroupRules.lastDay`).
     */
    suspend fun updateMember(memberId: String, from: LocalDate, lastDay: LocalDate?) {
        val member = clients.groupMember(memberId) ?: return
        require(lastDay == null || lastDay >= from) { "Последний день раньше первого" }
        val now = clock()
        changeMembers(member.groupId, now) {
            clients.upsertGroupMember(member.copy(sync = member.sync.touched(now), fromDate = from, toDate = lastDay))
        }
    }

    /**
     * «Убрать из группы» (5.10, шаг 3.9т): ребёнок больше не в списке группы. Его места в составе заканчиваются
     * вчера — прошедшие тренировки его помнят, будущие неотмеченные его больше не ждут (`GroupRules.leave`); места,
     * которые ещё не начались (добавили сегодня), убираются совсем. Возвращает прежние записи для «Отменить»
     * ([undoLeave]); ребёнка нет в списке — пусто.
     */
    suspend fun leave(groupId: String, clientId: String): List<GroupMemberEntity> {
        val now = clock()
        val lastDay = ClubTime.today(now).minus(1, DateTimeUnit.DAY)
        var before: List<GroupMemberEntity> = emptyList()
        changeMembers(groupId, now) {
            val mine = clients.membersOf(groupId).filter { it.clientId == clientId }
            before = mine.filter { it.toDate == null || it.toDate > lastDay }
            for (change in GroupRules.leave(before.map { it.member() }, lastDay)) {
                val row = before.first { it.member() == change.member }
                when (change) {
                    is MemberChange.End -> clients.upsertGroupMember(row.copy(sync = row.sync.touched(now), toDate = change.lastDay))
                    is MemberChange.Remove -> clients.upsertGroupMember(row.copy(sync = row.sync.deleted(now)))
                }
            }
        }
        return before
    }

    /** «Отменить» у [leave]: записи состава — как были. */
    suspend fun undoLeave(groupId: String, before: List<GroupMemberEntity>) {
        if (before.isEmpty()) return
        val now = clock()
        changeMembers(groupId, now) {
            for (old in before) {
                val current = clients.groupMember(old.id) ?: continue
                clients.upsertGroupMember(old.copy(sync = current.sync.restored(now)))
            }
        }
    }

    /** Убрать запись о составе совсем — ребёнка добавили по ошибке; [restoreMember] — «Отменить». */
    suspend fun removeMember(memberId: String) {
        val member = clients.groupMember(memberId) ?: return
        if (member.sync.deletedAt != null) return
        val now = clock()
        changeMembers(member.groupId, now) { clients.upsertGroupMember(member.copy(sync = member.sync.deleted(now))) }
    }

    suspend fun restoreMember(memberId: String) {
        val member = clients.groupMember(memberId) ?: return
        if (member.sync.deletedAt == null) return
        val now = clock()
        changeMembers(member.groupId, now) { clients.upsertGroupMember(member.copy(sync = member.sync.restored(now))) }
    }

    private suspend fun changeMembers(groupId: String, now: Long, change: suspend () -> Unit) {
        db.withWriteTransaction {
            val before = clients.membersOf(groupId).map { it.member() }
            change()
            db.syncGroupTrainings(groupId, before, now)
        }
    }

    // --- Ребёнок «на раз» (5.4, 5.10) ---

    /**
     * Ребёнок «на раз» на одной тренировке группы — без места в составе: другие тренировки и правка состава его
     * не трогают. Кнопка «Добавить ребёнка на раз» — в карточке тренировки (1.14).
     */
    suspend fun addGuest(trainingId: String, clientId: String) {
        val training = trainings.training(trainingId) ?: return
        require(training.groupId != null) { "«На раз» — только в тренировку группы" }
        db.withWriteTransaction { db.addParticipant(training, clientId, clock()) }
    }

    /** Убрать ребёнка «на раз», пока его не отметили. */
    suspend fun removeGuest(trainingId: String, clientId: String) {
        val row = trainings.participationRow(trainingId, clientId) ?: return
        if (row.sync.deletedAt == null) trainings.upsertParticipation(row.copy(sync = row.sync.deleted(clock())))
    }
}

/** На ком числится группа и кто обычно её ведёт (3.5). */
fun GroupEntity.sharing(): Sharing = Sharing(owner, ownerColleagueId, defaultConductedBy, defaultColleagueId)

/** На ком числится тренировка и кто её провёл (3.5). */
fun TrainingEntity.sharing(): Sharing = Sharing(owner, ownerColleagueId, conductedBy, colleagueId)

private fun GroupEntity.with(s: Sharing): GroupEntity = CoCoachingRules.normalize(s).let {
    copy(owner = it.owner, ownerColleagueId = it.ownerColleagueId, defaultConductedBy = it.conductedBy, defaultColleagueId = it.colleagueId)
}

internal fun TrainingEntity.with(s: Sharing): TrainingEntity = CoCoachingRules.normalize(s).let {
    copy(owner = it.owner, ownerColleagueId = it.ownerColleagueId, conductedBy = it.conductedBy, colleagueId = it.colleagueId)
}
