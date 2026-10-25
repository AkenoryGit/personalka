package com.akenory.personalka.data.training

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.PlanRules
import com.akenory.personalka.domain.training.PlanSlot
import kotlinx.datetime.LocalDateTime

/**
 * Следующая тренировка (4.3) после [t] по [PlanRules.next]: кандидаты — тренировки группы, серии сплита или
 * клиентов [people]. Одна для «Плана на следующую» в дневнике (5.7) и переноса плана при отмене (шаг 2.2).
 * [after] — позже чего искать; ближайшая для перенесённого плана — позже и отменённой, и «сейчас».
 */
internal suspend fun PersonalkaDatabase.nextTraining(
    t: TrainingEntity,
    kind: TrainingKind,
    people: Set<String>,
    after: LocalDateTime = t.start,
): TrainingEntity? {
    val trainings = trainingDao()
    val groupId = t.groupId
    val seriesId = t.seriesId
    val pool = when {
        groupId != null -> trainings.trainingsOfGroup(groupId)
        kind == TrainingKind.SPLIT && seriesId != null -> trainings.trainingsOfSeries(seriesId)
        else -> people.flatMap { trainings.trainingsOfClient(it) }.distinctBy { it.id }
    }.filter { it.status == TrainingStatus.PLANNED && it.start > after && it.sync.deletedAt == null }
    if (pool.isEmpty()) return null
    val kinds = referenceDao().trainingTypes().associate { it.id to it.kind }
    val slots = pool.map { c ->
        PlanSlot(c.id, c.start, c.status, kinds[c.typeId] ?: TrainingKind.OTHER, c.seriesId, c.groupId, trainings.participationsOf(c.id).filter { !it.cancelled }.map { it.clientId }.toSet())
    }
    val of = PlanSlot(t.id, t.start, t.status, kind, t.seriesId, t.groupId, people)
    val found = PlanRules.next(of, slots) ?: return null
    return pool.first { it.id == found.id }
}

internal val ParticipationEntity.cancelled: Boolean
    get() = status == ParticipationStatus.CANCELLED || status == ParticipationStatus.CANCELLED_CHARGED
