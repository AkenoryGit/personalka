package com.akenory.personalka.data.training

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.series.Recorder
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.PlanCarryRules
import kotlinx.datetime.LocalDateTime

/**
 * [Recorder.undo] после того, как перенесённые планы встали на ближайшие тренировки ([alignCarried]): так «Отменить»
 * возвращает и сохранение, и перенос плана.
 */
internal suspend fun Recorder.alignedUndo(db: PersonalkaDatabase, now: Long): Undo {
    alignCarried(db, now)
    return undo()
}

/**
 * План отменённой тренировки стоит на ближайшей будущей тренировке того же клиента, группы или серии сплита
 * (ТЗ 4.3, уточнение 25.10.2026). После записи через этот [Recorder] — новой тренировки, переноса, удаления — план
 * переходит туда, где он теперь должен быть: на новую ближайшую (с новой строки, если у неё свой план) или, если
 * будущих не осталось, в отложенный план. Перенесённый текст правили — он остаётся, где был: правку не двигаем.
 *
 * Ближайшая — первая `PLANNED` позже и отменённой, и «сейчас». Отменённая тренировка с перенесённым планом сама
 * передаёт его дальше при отмене, здесь её не трогаем.
 */
internal suspend fun Recorder.alignCarried(db: PersonalkaDatabase, now: Long) {
    val trainings = db.trainingDao()
    val clubNow = ClubTime.now(now)
    val touched = touchedTrainings.keys.toList()
    if (touched.isEmpty()) return
    val current = touched.associateWith { trainings.training(it) }
    val clientIds = touched.flatMap { id ->
        trainings.participationsOf(id).map { it.clientId } + touchedParticipants[id].orEmpty().map { it.clientId }
    }.toSet()
    val groupIds = touched.flatMap { listOfNotNull(current[it]?.groupId, touchedTrainings[it]?.groupId) }.toSet()
    val holders = (
        current.values.filterNotNull() +
            clientIds.flatMap { trainings.trainingsOfClient(it) } +
            groupIds.flatMap { trainings.trainingsOfGroup(it) }
        )
        .distinctBy { it.id }
        .filter { !it.planCarriedText.isNullOrBlank() && it.planCarriedFromId != null }
        .filter { it.sync.deletedAt != null || (it.status == TrainingStatus.PLANNED && it.start > clubNow) }
        .sortedBy { it.start }
    if (holders.isEmpty()) return
    val kinds = db.referenceDao().trainingTypes().associate { it.id to it.kind }
    for (h in holders) {
        val from = trainings.training(h.planCarriedFromId!!)?.takeIf { it.sync.deletedAt == null && it.status == TrainingStatus.CANCELLED } ?: continue
        val holder = trainings.training(h.id) ?: continue
        val text = holder.planCarriedText?.takeIf { it.isNotBlank() && holder.planCarriedFromId == from.id } ?: continue
        moveCarried(db, from, holder, text, kinds[from.typeId] ?: TrainingKind.OTHER, clubNow, now)
    }
}

private suspend fun Recorder.moveCarried(
    db: PersonalkaDatabase,
    from: TrainingEntity,
    holder: TrainingEntity,
    text: String,
    kind: TrainingKind,
    clubNow: LocalDateTime,
    now: Long,
) {
    val trainings = db.trainingDao()
    val people = trainings.participationsOf(from.id).map { it.clientId }
    val target = db.nextTraining(from, kind, people.toSet(), after = maxOf(from.start, clubNow))
    if (target?.id == holder.id) return
    // Перенесённое другой отменённой у ближайшей не затираем: её «Вернуть» его бы уже не нашло.
    if (target != null && target.planCarriedFromId != null && target.planCarriedFromId != from.id) return
    if (holder.sync.deletedAt == null) {
        val (left, plan) = PlanCarryRules.move(text, holder.plan, target?.plan) ?: return
        saveTraining(holder.copy(plan = left, planCarriedFromId = null, planCarriedText = null, sync = holder.sync.touched(now)))
        place(from, target, people, text, plan, now)
        return
    }
    // Удалённая: её план удаление серии (4.4) могло уже переложить на тренировку серии — дальше ведём оттуда.
    saveTraining(holder.copy(planCarriedFromId = null, planCarriedText = null, sync = holder.sync.touched(now)))
    val shifted = touchedTrainings.entries.firstNotNullOfOrNull { (id, before) ->
        trainings.training(id)?.takeIf {
            it.id != holder.id && it.sync.deletedAt == null && text in it.plan.orEmpty() && text !in before?.plan.orEmpty()
        }
    }
    when {
        shifted != null && shifted.id == target?.id -> place(from, target, people, text, target.plan.orEmpty(), now)
        shifted != null -> {
            val (left, plan) = PlanCarryRules.move(text, shifted.plan, target?.plan) ?: run {
                // Переложили внутрь чужого плана — остаётся там, но откат при «Вернуть» его найдёт.
                if (shifted.planCarriedFromId == null) saveTraining(shifted.copy(planCarriedFromId = from.id, planCarriedText = text, sync = shifted.sync.touched(now)))
                return
            }
            saveTraining(shifted.copy(plan = left, sync = shifted.sync.touched(now)))
            place(from, target?.let { trainings.training(it.id) }, people, text, plan, now)
        }
        // Удаление серии без следующей тренировки уже положило план в отложенный.
        target == null && waitsPending(db, from, people, text) -> Unit
        else -> place(from, target, people, text, PlanCarryRules.join(target?.plan, text), now)
    }
}

/** Перенесённый [text] — на [target] с планом [plan] или, если её нет, в отложенный план группы или клиентов. */
private suspend fun Recorder.place(from: TrainingEntity, target: TrainingEntity?, people: List<String>, text: String, plan: String, now: Long) {
    if (target != null) {
        saveTraining(target.copy(plan = plan, planCarriedFromId = from.id, planCarriedText = text, sync = target.sync.touched(now)))
        return
    }
    val groupId = from.groupId
    if (groupId != null) addGroupPendingPlan(groupId, text) else people.forEach { addPendingPlan(it, text) }
}

/** Текст уже лежит в отложенном плане группы или клиента. */
private suspend fun waitsPending(db: PersonalkaDatabase, from: TrainingEntity, people: List<String>, text: String): Boolean {
    val clients = db.clientDao()
    val groupId = from.groupId
    return if (groupId != null) text in clients.group(groupId)?.pendingPlan.orEmpty()
    else people.any { text in clients.client(it)?.pendingPlan.orEmpty() }
}
