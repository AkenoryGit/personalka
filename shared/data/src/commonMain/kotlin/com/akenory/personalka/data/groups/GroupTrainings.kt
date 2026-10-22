package com.akenory.personalka.data.groups

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.series.SeriesTraining
import com.akenory.personalka.domain.training.ParticipantState

// Общее для групп и клиентов: состав группы меняется из карточки группы и при архиве клиента (4.4).

/** Запись состава — для правил. */
internal fun GroupMemberEntity.member(): GroupMember = GroupMember(clientId, fromDate, toDate)

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
    when {
        row == null -> trainings.upsertParticipation(
            ParticipationEntity(
                id = Ids.newId(nowMillis),
                sync = SyncFields.new(training.sync.coachId, nowMillis),
                trainingId = training.id,
                clientId = clientId,
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
            ),
        )
    }
}
