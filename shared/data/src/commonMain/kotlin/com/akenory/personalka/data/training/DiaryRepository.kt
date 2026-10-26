package com.akenory.personalka.data.training

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.DiaryFields
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.domain.training.DiarySlot
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime

/** Поведение одного клиента в дневнике: «Поведение · Ковалёв Т.». */
data class DiaryBehavior(val clientId: String, val name: String, val text: String)

/**
 * Дневник одной тренировки для экрана (ТЗ 5.7) — как он был при открытии: дальше поля ведёт экран, а каждая правка
 * сразу пишется в базу.
 *
 * @param title «Орлова К.», «Ковалёв / Ковалёва», «Вт, Чт 18:00 ДБ (10)»; [clientId] или [groupId] — куда ведёт имя.
 * @param behavior поведение каждого, кто не отменён; у группы и у типа без поведения — пусто.
 * @param next начало следующей тренировки (4.3); `null` — следующей нет, [nextPlan] — отложенный план.
 */
data class Diary(
    val id: String,
    val title: String,
    val clientId: String?,
    val groupId: String?,
    val kind: TrainingKind,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val typeShort: String,
    val placeShort: String,
    val fields: DiaryFields,
    val plan: String,
    val fact: String,
    val behavior: List<DiaryBehavior>,
    val next: LocalDateTime?,
    val nextPlan: String,
) {
    val slot: DiarySlot get() = DiarySlot(id, start)
}

/** Проведённая без дневника: для «Следующей без дневника» и пункта «Нужно сделать» (5.2). */
data class MissingDiary(val id: String, val start: LocalDateTime, val title: String) {
    val slot: DiarySlot get() = DiarySlot(id, start)
}

/**
 * Дневник тренировки (ТЗ 5.7): поля по типу (3.3), черновик при каждом вводе, «План на следующую» в план следующей
 * тренировки или в отложенный план клиента или группы (4.3) и очередь проведённых без дневника.
 *
 * Правки пишутся по одной и по порядку ([writes]): последняя набранная буква всегда побеждает. «Отменить» у правок
 * дневника нет — это набор текста.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class DiaryRepository internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()
    private val writes = Mutex()

    /** Проведённые без дневника по времени ([DiaryRules.missing]); дневник выключен — пусто. */
    val missing: Flow<List<MissingDiary>> = combine(
        trainings.observeDoneWithoutFact(TrainingStatus.DONE),
        reference.observeTrainingTypes(),
        coaches.observeSettings(),
    ) { list, types, settings ->
        val typeById = types.associateBy { it.id }
        val enabled = settings?.diaryEnabled ?: false
        list.filter { t -> DiaryRules.missing(t.status, enabled, typeById[t.typeId]?.diaryFact == true, t.fact) }
            .map { t -> MissingDiary(t.id, t.start, title(t, typeById[t.typeId]?.name.orEmpty())) }
    }

    /** Дневник тренировки [id]; `null` — её нет или дневника у неё нет (дежурство, отменённая, дневник выключен). */
    suspend fun diary(id: String): Diary? {
        val t = trainings.training(id)?.takeIf { it.sync.deletedAt == null && it.status != TrainingStatus.CANCELLED } ?: return null
        val type = reference.trainingType(t.typeId) ?: return null
        if (coaches.currentCoach()?.let { coaches.settings(it.id) }?.diaryEnabled != true) return null
        val fields = DiaryRules.fields(type.kind, type.diaryPlan, type.diaryFact, type.diaryBehavior)
        if (!fields.any) return null
        val people = trainings.participationsOf(id).ordered()
        val open = people.filter { !it.cancelled }
        val names = open.associate { p -> p.clientId to clients.client(p.clientId)?.let { CalendarFormat.client(ClientName(it.lastName, it.firstName, it.middleName)) }.orEmpty() }
        val next = nextOf(t, type.kind)
        val nextPlan = when {
            next != null -> next.plan
            t.groupId != null -> clients.group(t.groupId)?.pendingPlan
            else -> open.firstOrNull()?.let { clients.client(it.clientId)?.pendingPlan }
        }
        return Diary(
            id = t.id,
            title = title(t, type.name),
            clientId = open.singleOrNull()?.clientId?.takeIf { t.groupId == null },
            groupId = t.groupId,
            kind = type.kind,
            start = t.start,
            end = TrainingTime.end(t.start, t.durationMin),
            typeShort = type.shortName,
            placeShort = reference.location(t.locationId)?.shortName.orEmpty(),
            fields = fields,
            plan = t.plan.orEmpty(),
            fact = t.fact.orEmpty(),
            behavior = if (fields.behavior) open.map { DiaryBehavior(it.clientId, names[it.clientId].orEmpty(), it.behavior.orEmpty()) } else emptyList(),
            next = next?.start,
            nextPlan = nextPlan.orEmpty(),
        )
    }

    /** «План был» — исправленный план этой тренировки. */
    suspend fun savePlan(id: String, text: String) = write {
        val t = trainings.training(id) ?: return@write
        if (t.plan.orEmpty() != text) trainings.upsertTraining(t.copy(plan = text.orNull(), sync = t.sync.touched(clock())))
    }

    /** «Факт»: непустой факт — дневник заполнен ([DiaryRules.missing]). */
    suspend fun saveFact(id: String, text: String) = write {
        val t = trainings.training(id) ?: return@write
        if (t.fact.orEmpty() != text) trainings.upsertTraining(t.copy(fact = text.orNull(), sync = t.sync.touched(clock())))
    }

    /** «Поведение» клиента [clientId]. */
    suspend fun saveBehavior(id: String, clientId: String, text: String) = write {
        val p = trainings.participationRow(id, clientId)?.takeIf { it.sync.deletedAt == null } ?: return@write
        if (p.behavior.orEmpty() != text) trainings.upsertParticipation(p.copy(behavior = text.orNull(), sync = p.sync.touched(clock())))
    }

    /**
     * «План на следующую» (5.7): план следующей тренировки (4.3); её нет — отложенный план группы или клиентов,
     * он подставится в первую новую тренировку с пустым планом.
     */
    suspend fun saveNextPlan(id: String, text: String) = write {
        val t = trainings.training(id) ?: return@write
        val kind = reference.trainingType(t.typeId)?.kind ?: TrainingKind.OTHER
        val now = clock()
        val next = nextOf(t, kind)
        when {
            next != null -> if (next.plan.orEmpty() != text) trainings.upsertTraining(next.copy(plan = text.orNull(), sync = next.sync.touched(now)))
            t.groupId != null -> clients.group(t.groupId)?.let { g ->
                if (g.pendingPlan.orEmpty() != text) clients.upsertGroup(g.copy(pendingPlan = text.orNull(), sync = g.sync.touched(now)))
            }
            else -> trainings.participationsOf(id).filter { !it.cancelled }.forEach { p ->
                val c = clients.client(p.clientId) ?: return@forEach
                if (c.pendingPlan.orEmpty() != text) clients.upsertClient(c.copy(pendingPlan = text.orNull(), sync = c.sync.touched(now)))
            }
        }
    }

    private suspend fun write(block: suspend () -> Unit) {
        writes.withLock { db.withWriteTransaction { block() } }
    }

    /** Следующая тренировка (4.3) для тех, кто в этой тренировке не отменён. */
    private suspend fun nextOf(t: TrainingEntity, kind: TrainingKind): TrainingEntity? =
        db.nextTraining(t, kind, trainings.participationsOf(t.id).filter { !it.cancelled }.map { it.clientId }.toSet())

    /** Имя тренировки, как в календаре и карточке. */
    private suspend fun title(t: TrainingEntity, typeName: String): String {
        val open = trainings.participationsOf(t.id).ordered().filter { !it.cancelled }
        return CalendarFormat.title(
            clients = open.mapNotNull { p -> clients.client(p.clientId)?.let { ClientName(it.lastName, it.firstName, it.middleName) } },
            groupName = t.groupId?.let { clients.group(it) }?.name,
            groupSize = open.size,
            typeName = typeName,
        )
    }
}

private fun List<ParticipationEntity>.ordered(): List<ParticipationEntity> = sortedWith(compareBy({ it.sync.createdAt }, { it.id }))

/** Пустой текст в базе — `null`, как у полей, которые не заполняли. */
private fun String.orNull(): String? = ifBlank { null }
