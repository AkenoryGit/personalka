package com.akenory.personalka.data.restrictions

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.restriction.PlacedTraining
import com.akenory.personalka.domain.restriction.Placement
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionRules

// Запреты в базе и правила 4.5 (`RestrictionRules`, шаг 1.4). Форма запрета (1.13) пишет через то же.

/** Запрет для правил: свой или клуба — правилам всё равно. */
fun ScheduleBlockEntity.toRestriction(): Restriction = Restriction(
    id = id,
    locationId = locationId,
    reason = reason,
    startTime = startTime,
    endTime = endTime,
    weekdays = weekdays,
    date = date,
    validFrom = validFrom,
    validTo = validTo,
    skipDates = skipDates.toSet(),
)

/** Место и время тренировки для правил; дежурство ли это — по типу, его знает вызывающий. */
fun TrainingEntity.placement(isDuty: Boolean): Placement = Placement(start, durationMin, locationId, isDuty)

fun TrainingEntity.placed(isDuty: Boolean): PlacedTraining = PlacedTraining(
    id = id,
    placement = placement(isDuty),
    cancelled = status == TrainingStatus.CANCELLED,
    overRestriction = overRestriction,
)

/**
 * Запреты изменились — `over_restriction` у тренировок, которые ещё не начались, пересчитывается без окон (4.5):
 * отменённые тоже, начатые и прошедшие сохраняют свой. Вызывать в той же транзакции, что и запись запрета.
 */
internal suspend fun PersonalkaDatabase.recalcRestrictionFlags(nowMillis: Long) {
    val dao = trainingDao()
    val now = ClubTime.now(nowMillis)
    val restrictions = scheduleDao().blocks().map { it.toRestriction() }
    val duties = referenceDao().trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
    val future = dao.trainingsAfter(now)
    val byId = future.associateBy { it.id }
    RestrictionRules.recalc(future.map { it.placed(it.typeId in duties) }, restrictions, now).forEach { change ->
        val training = byId.getValue(change.trainingId)
        dao.upsertTraining(training.copy(overRestriction = change.overRestriction, sync = training.sync.touched(nowMillis)))
    }
}
