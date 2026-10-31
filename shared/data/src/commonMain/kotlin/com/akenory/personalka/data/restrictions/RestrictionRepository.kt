package com.akenory.personalka.data.restrictions

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.restriction.RestrictionField
import com.akenory.personalka.domain.restriction.RestrictionFormRules
import com.akenory.personalka.domain.restriction.RestrictionRules
import kotlinx.datetime.LocalDate

/**
 * «Отменить» после записи запрета (5.20): каким был каждый запрет до неё (id → запрет); `null` — его не было.
 * Новый запрет на несколько мест — несколько записей (шаг 1.13д).
 */
class RestrictionUndo internal constructor(internal val before: Map<String, ScheduleBlockEntity?>)

/** Что вышло из сохранения формы запрета. */
sealed interface RestrictionSaveResult {
    /** Записано; [message] — для сообщения с «Отменить». */
    data class Saved(val message: String, val undo: RestrictionUndo) : RestrictionSaveResult

    /** Ошибки под полями; пусто — запрета уже нет или он клуба. */
    data class Invalid(val problems: Map<RestrictionField, String>) : RestrictionSaveResult
}

/**
 * Свои запреты из формы (ТЗ 5.5, 4.5): создание, изменение, удаление и «Отменить». После любой записи флаг
 * «поверх запрета» у тренировок, которые ещё не начались, пересчитывается без окон (4.5). Запреты клуба
 * меняет только администратор (этап 2) — здесь они не меняются.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class RestrictionRepository internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val schedule = db.scheduleDao()
    private val coaches = db.coachDao()

    /** Свой запрет для формы изменения; `null` — его нет или он клуба. */
    suspend fun stored(id: String): RestrictionDraft? = own(id)?.let { RestrictionFormRules.draftOf(it.toRestriction()) }

    /**
     * Новый запрет ([id] пусто) или изменение своего; пропущенные дни прежнего остаются. Новый на несколько мест
     * ([RestrictionDraft.placeIds]) — по запрету на каждое место, одной записью и одним «Отменить».
     */
    suspend fun save(id: String?, draft: RestrictionDraft): RestrictionSaveResult {
        val problems = RestrictionFormRules.problems(draft)
        if (problems.isNotEmpty()) return RestrictionSaveResult.Invalid(problems)
        val now = clock()
        var result: RestrictionSaveResult = RestrictionSaveResult.Invalid(emptyMap())
        db.withWriteTransaction {
            val coach = coaches.currentCoach() ?: return@withWriteTransaction
            val before = id?.let { own(it) ?: return@withWriteTransaction }
            // Изменение — одно место; новый — каждое выбранное.
            val places = if (before != null) listOfNotNull(draft.locationId) else draft.placeIds
            val undo = mutableMapOf<String, ScheduleBlockEntity?>()
            places.forEachIndexed { index, placeId ->
                val newId = before?.id ?: Ids.newId(now + index)
                val r = RestrictionFormRules.toRestriction(newId, draft.copy(locationId = placeId), before?.skipDates?.toSet().orEmpty())
                val entity = ScheduleBlockEntity(
                    id = newId,
                    sync = before?.sync?.touched(now) ?: SyncFields.new(coach.id, now),
                    scope = Scope.COACH,
                    locationId = r.locationId,
                    reason = r.reason,
                    weekdays = r.weekdays,
                    date = r.date,
                    startTime = r.startTime,
                    endTime = r.endTime,
                    validFrom = r.validFrom,
                    validTo = r.validTo,
                    skipDates = r.skipDates.sorted(),
                )
                schedule.upsertBlock(entity)
                undo[newId] = before
            }
            db.recalcRestrictionFlags(now)
            val message = if (before == null) TrainingFormat.restrictionsCreated(places.size) else TrainingFormat.RESTRICTION_SAVED
            result = RestrictionSaveResult.Saved(message, RestrictionUndo(undo))
        }
        return result
    }

    /** «Удалить запрет»: мягкое удаление своего; `null` — его уже нет. */
    suspend fun delete(id: String): RestrictionUndo? {
        val now = clock()
        var undo: RestrictionUndo? = null
        db.withWriteTransaction {
            val before = own(id) ?: return@withWriteTransaction
            schedule.upsertBlock(before.copy(sync = before.sync.deleted(now)))
            db.recalcRestrictionFlags(now)
            undo = RestrictionUndo(mapOf(id to before))
        }
        return undo
    }

    /**
     * «Удалить → Этот и все следующие» в окне запрета (5.3, шаг 5.3р): свой запрет действует до дня перед [date].
     * До [date] он не действовал ни разу или он разовый — удаляется целиком. Возвращает сообщение и «Отменить»;
     * `null` — запрета уже нет или он клуба.
     */
    suspend fun endBefore(id: String, date: LocalDate): Pair<String, RestrictionUndo>? {
        val now = clock()
        var result: Pair<String, RestrictionUndo>? = null
        db.withWriteTransaction {
            val before = own(id) ?: return@withWriteTransaction
            val cut = RestrictionRules.endBefore(before.toRestriction(), date)
            val after = if (cut == null) {
                before.copy(sync = before.sync.deleted(now))
            } else {
                before.copy(validTo = cut.validTo, skipDates = cut.skipDates.sorted(), sync = before.sync.touched(now))
            }
            schedule.upsertBlock(after)
            db.recalcRestrictionFlags(now)
            val message = if (cut == null) ZoneFormat.RESTRICTION_DELETED else ZoneFormat.restrictionEnded(date)
            result = message to RestrictionUndo(mapOf(id to before))
        }
        return result
    }

    /** «Отменить»: запреты как были до записи — новые удаляются, изменённый и удалённый возвращаются. */
    suspend fun undo(undo: RestrictionUndo) {
        val now = clock()
        db.withWriteTransaction {
            undo.before.forEach { (id, before) ->
                val current = schedule.block(id) ?: return@forEach
                val restored = if (before == null) {
                    current.copy(sync = current.sync.deleted(now))
                } else {
                    // Запись — новой версией: для синхронизации это ещё одно изменение.
                    before.copy(sync = current.sync.touched(now).copy(deletedAt = before.sync.deletedAt))
                }
                schedule.upsertBlock(restored)
            }
            db.recalcRestrictionFlags(now)
        }
    }

    private suspend fun own(id: String): ScheduleBlockEntity? =
        schedule.block(id)?.takeIf { it.sync.deletedAt == null && it.scope == Scope.COACH }
}
