package com.akenory.personalka.data.clients

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.FollowupRules
import com.akenory.personalka.domain.client.FreedSlot
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitMatch
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Строка «Ждут время» (5.8): запись, клиент, что ждёт, короткие тип и место (`null` — любое), день, с которого
 * ждёт, и освободившееся время [match], которое ещё держится (4.16).
 */
data class WaitRow(
    val entry: WaitEntryEntity,
    val client: ClientEntity,
    val want: WaitWant,
    val typeShort: String,
    val placeShort: String?,
    val since: LocalDate,
    val match: FreedSlot? = null,
)

/** Чипы формы «+ В ожидание»: действующие индивидуальные типы и места — группы в лист не входят (4.16). */
data class WaitChoices(val types: List<TrainingTypeEntity>, val places: List<LocationEntity>)

/**
 * Лист ожидания (ТЗ 4.16) в базе телефона: одна активная запись на клиента; закрытые хранятся 90 дней. Запись
 * «постоянно» закрывается сама, когда для клиента создана серия ([closeForSeries]).
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class ClientWaitList internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val clients = db.clientDao()
    private val trainings = db.trainingDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /**
     * Кто ждёт (4.16): сначала записи, для которых освободилось время (ближайшее — выше), дальше — кто ждёт дольше.
     * Совпадение исчезает само, когда время прошло или его заняли другой тренировкой. Клиенты в архиве не показываются.
     */
    fun observe(now: LocalDateTime): Flow<List<WaitRow>> {
        val week = LocalDateTime(now.date.plus(8, DateTimeUnit.DAY), LocalTime(0, 0))
        val rows = combine(
            clients.observeWaitEntries(WaitStatus.WAITING),
            clients.observeAllClients(),
            reference.observeTrainingTypes(),
            reference.observeLocations(),
        ) { entries, all, types, places -> Lists(entries, all, types, places) }
        return combine(rows, trainings.observeTrainingsBetween(LocalDateTime(now.date, LocalTime(0, 0)), week)) { l, _ ->
            val byId = l.clients.associateBy { it.id }
            val typeOf = l.types.associateBy { it.id }
            val placeShort = l.places.associate { it.id to it.shortName }
            val list = l.entries.mapNotNull { e ->
                val client = byId[e.clientId]?.takeIf { it.status == ClientStatus.ACTIVE } ?: return@mapNotNull null
                val match = e.match()?.let { WaitMatch.current(it, now) }?.takeIf { slot ->
                    // Заняли другой тренировкой — совпадения больше нет.
                    db.waitingFor(listOf(slot), now).any { it.first.id == e.id }
                }
                WaitRow(e, client, e.want(), typeOf[e.typeId]?.shortName.orEmpty(), e.locationId?.let { placeShort[it] }, ClubTime.today(e.sync.createdAt), match)
            }
            WaitMatch.order(list, { it.match }, { it.entry.sync.createdAt })
        }
    }

    private data class Lists(
        val entries: List<WaitEntryEntity>,
        val clients: List<ClientEntity>,
        val types: List<TrainingTypeEntity>,
        val places: List<LocationEntity>,
    )

    /** «Не подходит» у строки с «Освободилось» (4.16): совпадение снято, запись ждёт дальше. Возвращает её до правки. */
    suspend fun notSuitable(id: String): WaitEntryEntity? {
        val entry = entry(id) ?: return null
        val now = clock()
        clients.upsertWaitEntry(entry.copy(matchDate = null, matchSlot = null, matchOneOff = false, sync = entry.sync.touched(now)))
        return entry
    }

    /**
     * Строка «Это время ждут» для подтверждения «Завершить» серии (4.16): кто ждёт её время после [lastDay] —
     * постоянно и ближайшую дату разово. Никто — `null`.
     */
    suspend fun waitingForSeriesEnd(seriesId: String, lastDay: LocalDate): String? {
        val series = db.scheduleDao().series(seriesId)?.takeIf { it.sync.deletedAt == null } ?: return null
        val now = ClubTime.now(clock())
        return db.waitingLine(series.freedSlots(maxOf(lastDay, now.date.plus(-1, DateTimeUnit.DAY))), now) {
            it.seriesId == seriesId && it.start.date > lastDay
        }
    }

    fun choices(): Flow<WaitChoices> = combine(reference.observeTrainingTypes(), reference.observeLocations()) { types, places ->
        WaitChoices(types.filter { !it.archived && it.kind in FollowupRules.INDIVIDUAL }, places.filter { !it.archived })
    }

    /** Активная запись клиента — «Изменить» и форма из карточки клиента. */
    suspend fun activeOf(clientId: String): WaitEntryEntity? =
        clients.waitEntriesOf(clientId).firstOrNull { it.status == WaitStatus.WAITING }

    suspend fun entry(id: String): WaitEntryEntity? = clients.waitEntry(id)?.takeIf { it.sync.deletedAt == null }

    /**
     * «+ В ожидание» (5.8): новая запись; у клиента уже есть активная — она и меняется (одна на клиента). Возвращает
     * id записи; клиента нет — `null`.
     */
    suspend fun save(draft: WaitDraft): String? {
        val clientId = draft.clientId ?: return null
        val want = WaitRules.clean(draft.want)
        val typeId = want.typeId ?: return null
        val now = clock()
        var id: String? = null
        db.withWriteTransaction {
            val coachId = coaches.currentCoach()?.id ?: return@withWriteTransaction
            val old = activeOf(clientId)
            val entry = (old ?: WaitEntryEntity(Ids.newId(now), SyncFields.new(coachId, now), clientId, want.series, want.oneOff, typeId = typeId))
                .copy(
                    wantsSeries = want.series,
                    wantsOneOff = want.oneOff,
                    days = want.days,
                    timeRanges = want.ranges,
                    locationId = want.locationId,
                    typeId = typeId,
                    until = want.until,
                    note = want.note.ifEmpty { null },
                    sync = old?.sync?.touched(now) ?: SyncFields.new(coachId, now),
                )
            clients.upsertWaitEntry(entry)
            id = entry.id
        }
        return id
    }

    /** «Изменить» записи [id]: что ждёт. */
    suspend fun update(id: String, want: WaitWant): Boolean {
        val entry = entry(id) ?: return false
        return save(WaitDraft(entry.clientId, want)) != null
    }

    /** «Больше не ждёт»: запись закрыта. Возвращает её до правки — для «Отменить». */
    suspend fun close(id: String): WaitEntryEntity? = setStatus(id, WaitStatus.CLOSED)

    /**
     * Записали разово через «Записать» (4.16): запись «только разово» — «Записан» с датой; запись «постоянно» разовая
     * тренировка не закрывает (снимается только совпадение) — `null`. Возвращает запись до правки — для «Вернуть».
     */
    suspend fun bookedOnce(id: String): WaitEntryEntity? {
        val entry = entry(id) ?: return null
        if (entry.wantsSeries) {
            // Записать нажали — совпадение снято, а постоянное время клиент ждёт дальше.
            if (entry.matchDate != null) notSuitable(id)
            return null
        }
        return setStatus(id, WaitStatus.BOOKED)
    }

    /** «Написал»: дата связи — «писали 18.10». Возвращает запись до правки — для «Отменить». */
    suspend fun contacted(id: String): WaitEntryEntity? {
        val entry = entry(id) ?: return null
        val now = clock()
        // «Написал» снимает и совпадение (4.16): клиенту уже предложили.
        clients.upsertWaitEntry(entry.copy(contactedAt = now, matchDate = null, matchSlot = null, matchOneOff = false, sync = entry.sync.touched(now)))
        return entry
    }

    /** «Отменить» и «Вернуть»: запись — как была в [before]. */
    suspend fun restore(before: WaitEntryEntity) {
        val current = clients.waitEntry(before.id) ?: return
        clients.upsertWaitEntry(before.copy(sync = current.sync.restored(clock())))
    }

    /** Есть ли у клиента хоть одна тренировка — без них «Больше не ждёт» предлагает удалить и карточку (4.16). */
    suspend fun hasTrainings(clientId: String): Boolean = trainings.participationRowsOf(clientId).any { it.sync.deletedAt == null }

    /**
     * Уборка раз в день: «только разово» закрывается на следующий день после срока, закрытые старше 90 дней
     * удаляются.
     */
    suspend fun tidy() {
        val now = clock()
        val today = ClubTime.today(now)
        db.withWriteTransaction {
            clients.waitEntries(WaitStatus.WAITING).filter { WaitRules.expired(it.want(), today) }.forEach {
                clients.upsertWaitEntry(it.copy(status = WaitStatus.CLOSED, closedAt = now, sync = it.sync.touched(now)))
            }
            (clients.waitEntries(WaitStatus.CLOSED) + clients.waitEntries(WaitStatus.BOOKED))
                .filter { e -> e.closedAt?.let { WaitRules.purge(ClubTime.today(it), today) } == true }
                .forEach { clients.upsertWaitEntry(it.copy(sync = it.sync.deleted(now))) }
        }
    }

    private suspend fun setStatus(id: String, status: WaitStatus): WaitEntryEntity? {
        val entry = entry(id)?.takeIf { it.status == WaitStatus.WAITING } ?: return null
        val now = clock()
        clients.upsertWaitEntry(entry.copy(status = status, closedAt = now, sync = entry.sync.touched(now)))
        return entry
    }
}

/** Что ждёт запись — для правил и строк. */
fun WaitEntryEntity.want(): WaitWant = WaitWant(
    series = wantsSeries,
    oneOff = wantsOneOff,
    days = days,
    ranges = timeRanges,
    locationId = locationId,
    typeId = typeId,
    until = until,
    note = note.orEmpty(),
)

/**
 * Для клиентов [clientIds] создана серия (4.16): их записи «постоянно» закрываются со статусом «Записан». Вызывается
 * внутри транзакции записи серии.
 */
internal suspend fun PersonalkaDatabase.closeWaitForSeries(clientIds: List<String>, now: Long) {
    val dao = clientDao()
    clientIds.forEach { clientId ->
        dao.waitEntriesOf(clientId).filter { it.status == WaitStatus.WAITING && it.wantsSeries }.forEach {
            dao.upsertWaitEntry(it.copy(status = WaitStatus.BOOKED, closedAt = now, sync = it.sync.touched(now)))
        }
    }
}
