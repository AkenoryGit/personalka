package com.akenory.personalka.data.clients

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.data.series.Undo
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.Busy
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.FreedSlot
import com.akenory.personalka.domain.client.WaitMatch
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.WaitFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.WaitStatus
import com.akenory.personalka.domain.restriction.Placement
import com.akenory.personalka.domain.restriction.RestrictionRules
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

// Освободившееся время для листа ожидания (ТЗ 4.16): что освободилось, кто его ждёт, совпадение в записи.

/** Время разовой тренировки — то, что освобождает её отмена, удаление или перенос. */
internal fun TrainingEntity.freedSlot(): FreedSlot = FreedSlot(start.date, start.time, locationId, series = false)

/**
 * Серия [s], последний день которой — [lastDay], освобождает свои дни недели с [lastDay] + 1: постоянное время
 * и ближайшую дату каждого дня разово (4.16).
 */
internal fun SeriesEntity.freedSlots(lastDay: LocalDate): List<FreedSlot> {
    val from = lastDay.plus(1, DateTimeUnit.DAY)
    return weekdays.flatMap { day ->
        val date = WaitMatch.nearest(day, from)
        listOf(FreedSlot(date, startTime, locationId, series = true), FreedSlot(date, startTime, locationId, series = false))
    }
}

/**
 * Что освободило изменение с «Отменить» [undo] (4.16): будущая тренировка, которую отменили, удалили или перенесли
 * на другое время или место, — разово; серия, которая закончилась раньше или ушла на другие дни, время или место, —
 * постоянно. Дежурства ничего не освобождают.
 */
internal suspend fun PersonalkaDatabase.freedBy(undo: Undo, now: LocalDateTime): List<FreedSlot> {
    val trainingDao = trainingDao()
    val scheduleDao = scheduleDao()
    val duties = referenceDao().trainingTypes().filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
    val slots = mutableListOf<FreedSlot>()
    undo.trainings.forEach { (id, before) ->
        if (before == null || before.status != TrainingStatus.PLANNED || before.start <= now || before.typeId in duties) return@forEach
        val after = trainingDao.training(id)
        val freed = after == null || after.sync.deletedAt != null || after.status == TrainingStatus.CANCELLED ||
            after.start != before.start || after.locationId != before.locationId
        if (freed) slots += before.freedSlot()
    }
    undo.series.forEach { (id, before) ->
        if (before == null || before.typeId in duties) return@forEach
        val after = scheduleDao.series(id)?.takeIf { it.sync.deletedAt == null }
        val today = now.date
        val beforeEnd = before.validTo
        if (beforeEnd != null && beforeEnd < today) return@forEach
        val lastDay = when {
            after == null -> today.plus(-1, DateTimeUnit.DAY)
            after.startTime != before.startTime || after.locationId != before.locationId || after.weekdays != before.weekdays ->
                maxOf(after.validFrom.plus(-1, DateTimeUnit.DAY), today.plus(-1, DateTimeUnit.DAY))
            after.validTo != null && (beforeEnd == null || after.validTo < beforeEnd) -> after.validTo
            else -> null
        } ?: return@forEach
        slots += before.freedSlots(maxOf(lastDay, today.plus(-1, DateTimeUnit.DAY))).filter { it.series }
    }
    return slots.distinct()
}

/**
 * Кто ждёт [slots] (4.16): записи «Ждёт» действующих клиентов, каждой — ближайшее подходящее время. Окно проверяется
 * по тренировкам тренера и запретам места в этот день (4.13); длительность — типа записи. [ignore] — тренировки,
 * которые действие ещё только освободит: подтверждение показывается до него.
 */
internal suspend fun PersonalkaDatabase.waitingFor(
    slots: List<FreedSlot>,
    now: LocalDateTime,
    ignore: (TrainingEntity) -> Boolean = { false },
): List<Pair<WaitEntryEntity, FreedSlot>> {
    if (slots.isEmpty()) return emptyList()
    val clientDao = clientDao()
    val types = referenceDao().trainingTypes().associateBy { it.id }
    val restrictions = scheduleDao().blocks().map { it.toRestriction() }
    val busyOn = mutableMapOf<LocalDate, List<Busy>>()
    suspend fun busy(date: LocalDate) = busyOn.getOrPut(date) {
        trainingDao().trainingsBetween(LocalDateTime(date, LocalTime(0, 0)), LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0)))
            .filter { it.status != TrainingStatus.CANCELLED && !ignore(it) }
            .map { Busy(it.start, ClubTime.plusMinutes(it.start, it.durationMin)) }
    }
    return clientDao.waitEntries(WaitStatus.WAITING).mapNotNull { entry ->
        if (clientDao.client(entry.clientId)?.status != ClientStatus.ACTIVE) return@mapNotNull null
        val duration = types[entry.typeId]?.defaultDurationMin ?: return@mapNotNull null
        val want = entry.want()
        // До дня «Напомнить с» клиента нет (шаг 3.9х): время ему не ищется.
        if (WaitRules.deferred(want, now.date)) return@mapNotNull null
        slots.filter { slot ->
            WaitMatch.matches(want, slot, duration, busy(slot.date), now) &&
                !RestrictionRules.flag(Placement(slot.at, duration, slot.locationId), restrictions)
        }.minByOrNull { it.at }?.let { entry to it }
    }
}

/**
 * Записать совпадения для [slots] (4.16): у записи уже есть совпадение, которое держится и ближе, — оно и остаётся.
 * Вызывается после действия, когда «освобождённое» уже свободно.
 */
internal suspend fun PersonalkaDatabase.recordFreed(slots: List<FreedSlot>, nowMillis: Long) {
    val now = ClubTime.now(nowMillis)
    val dao = clientDao()
    waitingFor(slots, now).forEach { (entry, slot) ->
        val old = entry.match()?.let { WaitMatch.current(it, now) }
        if (old != null && old.at <= slot.at) return@forEach
        dao.upsertWaitEntry(
            entry.copy(matchDate = slot.date, matchSlot = slot.start, matchOneOff = !slot.series, sync = entry.sync.touched(nowMillis)),
        )
    }
}

/** То же по «Отменить» изменения: что освободилось — из [undo]. */
internal suspend fun PersonalkaDatabase.recordFreed(undo: Undo, nowMillis: Long) =
    recordFreed(freedBy(undo, ClubTime.now(nowMillis)), nowMillis)

/** Совпадение записи, как его записали; место — записи, а у «любого» — пустое (его выбирает форма). */
fun WaitEntryEntity.match(): FreedSlot? {
    val date = matchDate ?: return null
    val start = matchSlot ?: return null
    return FreedSlot(date, start, locationId.orEmpty(), series = !matchOneOff)
}

/** Строка «Это время ждут: Петров К. (разово)» для подтверждения; никто не ждёт — `null`. */
internal suspend fun PersonalkaDatabase.waitingLine(
    slots: List<FreedSlot>,
    now: LocalDateTime,
    ignore: (TrainingEntity) -> Boolean = { false },
): String? {
    val found = waitingFor(slots, now, ignore)
    if (found.isEmpty()) return null
    val dao = clientDao()
    return WaitFormat.waitingFor(
        found.mapNotNull { (entry, slot) ->
            val c = dao.client(entry.clientId) ?: return@mapNotNull null
            CalendarFormat.client(ClientName(c.lastName, c.firstName)) to !slot.series
        },
    )
}
