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
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/**
 * Строка «Ждут время» (5.8): запись, клиент, что ждёт, короткие тип и место (`null` — любое) и день, с которого
 * ждёт.
 */
data class WaitRow(
    val entry: WaitEntryEntity,
    val client: ClientEntity,
    val want: WaitWant,
    val typeShort: String,
    val placeShort: String?,
    val since: LocalDate,
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

    /** Кто ждёт — по порядку записи: дольше ждёт — выше. Клиенты в архиве не показываются. */
    fun observe(): Flow<List<WaitRow>> = combine(
        clients.observeWaitEntries(WaitStatus.WAITING),
        clients.observeAllClients(),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
    ) { entries, all, types, places ->
        val byId = all.associateBy { it.id }
        val typeShort = types.associate { it.id to it.shortName }
        val placeShort = places.associate { it.id to it.shortName }
        entries.mapNotNull { e ->
            val client = byId[e.clientId]?.takeIf { it.status == ClientStatus.ACTIVE } ?: return@mapNotNull null
            WaitRow(e, client, e.want(), typeShort[e.typeId].orEmpty(), e.locationId?.let { placeShort[it] }, ClubTime.today(e.sync.createdAt))
        }.sortedWith(compareBy<WaitRow>({ it.entry.sync.createdAt }, { it.entry.id }))
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
     * тренировка не закрывает — `null`. Возвращает запись до правки — для «Вернуть».
     */
    suspend fun bookedOnce(id: String): WaitEntryEntity? {
        if (entry(id)?.wantsSeries != false) return null
        return setStatus(id, WaitStatus.BOOKED)
    }

    /** «Написал»: дата связи — «писали 18.10». Возвращает запись до правки — для «Отменить». */
    suspend fun contacted(id: String): WaitEntryEntity? {
        val entry = entry(id) ?: return null
        val now = clock()
        clients.upsertWaitEntry(entry.copy(contactedAt = now, sync = entry.sync.touched(now)))
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
