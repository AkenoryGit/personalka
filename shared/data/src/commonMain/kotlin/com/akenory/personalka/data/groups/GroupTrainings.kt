package com.akenory.personalka.data.groups

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.group.coachOn
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.series.SeriesTraining
import com.akenory.personalka.domain.training.ParticipantState

// Общее для групп и клиентов: состав группы меняется из карточки группы и при архиве клиента (4.4).

/** Запись состава — для правил. */
internal fun GroupMemberEntity.member(): GroupMember = GroupMember(clientId, fromDate, toDate, coach)

/** Тренировка с участниками — для правил серий (1.3): то, что серия может и не может менять. */
internal fun TrainingEntity.toSeriesTraining(participations: List<ParticipationEntity>): SeriesTraining = SeriesTraining(
    id = id,
    seriesId = seriesId,
    start = start,
    durationMin = durationMin,
    typeId = typeId,
    locationId = locationId,
    groupId = groupId,
    detached = seriesDetached,
    status = status,
    plan = plan,
    diaryWritten = !fact.isNullOrBlank() || participations.any { !it.behavior.isNullOrBlank() },
    participants = participations.map {
        ParticipantState(it.clientId, it.status, it.cancelReason, it.notifiedAt, it.chargeOverridden)
    },
)

/**
 * Состав группы [groupId] изменился, [before] — каким он был (4.4, 5.10): в будущих неотмеченных тренировках
 * группы пришедшие в состав на эту дату становятся ожидаемыми, ушедшие убираются. Прошедшие, отмеченные
 * и отменённые тренировки и дети «на раз» не меняются. Вызывается внутри транзакции, которая меняла состав.
 */
internal suspend fun PersonalkaDatabase.syncGroupTrainings(groupId: String, before: List<GroupMember>, nowMillis: Long) {
    val trainings = trainingDao()
    val after = clientDao().membersOf(groupId).map { it.member() }
    val list = trainings.trainingsOfGroup(groupId)
    val participations = list.associate { it.id to trainings.participationsOf(it.id) }
    val changes = SeriesRules.updateGroupMembers(
        trainings = list.map { it.toSeriesTraining(participations.getValue(it.id)) },
        before = before,
        after = after,
        now = ClubTime.now(nowMillis),
    )
    val byId = list.associateBy { it.id }
    changes.updated.forEach { u ->
        val training = byId[u.id] ?: return@forEach
        writeParticipants(training, participations.getValue(u.id), u.participants.map { it.clientId }, nowMillis)
    }
    // Чей ребёнок поменялся в составе (4.8, «Каждый своих») — в будущих неотмеченных тренировках тоже.
    val now = ClubTime.now(nowMillis)
    list.filter { it.status == TrainingStatus.PLANNED && it.start > now }.forEach { training ->
        trainings.participationsOf(training.id)
            .filter { it.status == ParticipationStatus.EXPECTED }
            .forEach { p ->
                val coach = after.coachOn(p.clientId, training.start.date)
                if (coach != p.coach) trainings.upsertParticipation(p.copy(coach = coach, sync = p.sync.touched(nowMillis)))
            }
    }
    fillPastGroupTrainings(groupId, nowMillis)
}

/**
 * Прошедшие неотмеченные тренировки группы [groupId] получают текущий состав без учёта «в группе с» (4.4, шаг 5.3н):
 * ребёнок, добавленный сегодня, стоит и в них — для отметки. Отмеченные, отменённые и гости не меняются; кто уже есть
 * в тренировке, не трогается. [add] — как добавить участника: у серий — через Recorder, чтобы работало «Отменить».
 */
internal suspend fun PersonalkaDatabase.fillPastGroupTrainings(
    groupId: String,
    nowMillis: Long,
    add: (suspend (TrainingEntity, String) -> Unit)? = null,
) {
    val trainings = trainingDao()
    val members = clientDao().membersOf(groupId).map { it.member() }
    val now = ClubTime.now(nowMillis)
    trainings.trainingsOfGroup(groupId).filter { it.status == TrainingStatus.PLANNED && it.start <= now }.forEach { t ->
        val people = trainings.participationsOf(t.id).map { it.clientId to (it.status == ParticipationStatus.ATTENDED) }
        GroupRules.pastFill(started = true, planned = true, participants = people, members = members, today = now.date)
            .forEach { clientId -> if (add != null) add(t, clientId) else addParticipant(t, clientId, nowMillis) }
    }
}

/**
 * Участники тренировки после правки: ушедших — убрать, новых — ожидаемыми. Пара «тренировка — клиент»
 * уникальна и с удалёнными записями: вернувшийся ребёнок оживляет свою прежнюю запись.
 */
internal suspend fun PersonalkaDatabase.writeParticipants(
    training: TrainingEntity,
    old: List<ParticipationEntity>,
    clientIds: List<String>,
    nowMillis: Long,
) {
    val trainings = trainingDao()
    old.filter { it.clientId !in clientIds }.forEach { trainings.upsertParticipation(it.copy(sync = it.sync.deleted(nowMillis))) }
    clientIds.filter { id -> old.none { it.clientId == id } }.forEach { clientId ->
        addParticipant(training, clientId, nowMillis)
    }
}

/** Ребёнок ожидается на тренировке: новая запись участия или ожившая прежняя. */
internal suspend fun PersonalkaDatabase.addParticipant(training: TrainingEntity, clientId: String, nowMillis: Long) {
    val trainings = trainingDao()
    val row = trainings.participationRow(training.id, clientId)
    // Чей ребёнок — из состава группы на день тренировки; «на раз» и не у групп — мой (4.8).
    val coach = training.groupId?.let { groupId ->
        clientDao().membersOf(groupId).map { it.member() }.coachOn(clientId, training.start.date)
    } ?: Owner.SELF
    when {
        row == null -> trainings.upsertParticipation(
            ParticipationEntity(
                id = Ids.newId(nowMillis),
                sync = SyncFields.new(training.sync.coachId, nowMillis),
                trainingId = training.id,
                clientId = clientId,
                coach = coach,
            ),
        )
        row.sync.deletedAt != null -> trainings.upsertParticipation(
            row.copy(
                sync = row.sync.restored(nowMillis),
                status = ParticipationStatus.EXPECTED,
                behavior = null,
                cancelReason = null,
                notifiedAt = null,
                chargeOverridden = false,
                coach = coach,
            ),
        )
    }
}
