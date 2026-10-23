package com.akenory.personalka.data.clients

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.dao.ClientVisit
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.FollowupInput
import com.akenory.personalka.domain.client.FollowupRules
import com.akenory.personalka.domain.client.NotBooked
import com.akenory.personalka.domain.client.Visit
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Строка «Не записаны дальше»: клиент, правило 4.15 и короткие тип и место последнего посещения. */
data class FollowupRow(val client: ClientEntity, val item: NotBooked, val typeShort: String, val placeShort: String)

/**
 * «Без записи» (4.15): [rows] — раздел «Не записаны дальше» в порядке ТЗ; [noBooking] — у кого в карточке метка
 * «Нет записи» (и у скрытых «Написал» и «Пауза»); [days] — N из настроек.
 */
data class Followup(val days: Int, val rows: List<FollowupRow>, val noBooking: Set<String>) {
    companion object {
        val EMPTY = Followup(FollowupRules.DEFAULT_DAYS, emptyList(), emptySet())
    }
}

/**
 * Клиенты без записи (ТЗ 4.15, 5.8): список не хранится, а считается из тренировок при каждом их изменении. В базе —
 * только «Написал» и «Пауза до…» у клиента.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class ClientFollowup internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val clients = db.clientDao()
    private val trainings = db.trainingDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /** «Без записи» на [now]; выключено в настройках — пусто. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(now: LocalDateTime): Flow<Followup> = coaches.observeSettings()
        .map { (it?.followupEnabled ?: true) to (it?.followupDays ?: FollowupRules.DEFAULT_DAYS) }
        .distinctUntilChanged()
        .flatMapLatest { (enabled, days) -> if (enabled) observe(now, days) else flowOf(Followup.EMPTY.copy(days = days)) }

    private fun observe(now: LocalDateTime, days: Int): Flow<Followup> {
        val today = now.date
        val from = LocalDateTime(FollowupRules.firstDay(today, days), LocalTime(0, 0))
        val inputs = combine(
            clients.observeAllClients(),
            trainings.observeClientVisits(from, TrainingStatus.DONE, ParticipationStatus.ATTENDED),
            trainings.observeClientVisits(now, TrainingStatus.PLANNED, ParticipationStatus.EXPECTED),
            clients.observeWaitEntries(WaitStatus.WAITING),
        ) { all, visits, planned, waiting ->
            val visitsOf = visits.groupBy({ it.clientId }, { it.visit() })
            val booked = planned.filter { it.kind in FollowupRules.INDIVIDUAL }.map { it.clientId }.toSet()
            val waitingIds = waiting.map { it.clientId }.toSet()
            all to all.map { c ->
                FollowupInput(
                    clientId = c.id,
                    active = c.status == ClientStatus.ACTIVE,
                    visits = visitsOf[c.id].orEmpty(),
                    booked = c.id in booked,
                    contactedOn = c.followupContactedAt?.let { ClubTime.today(it) },
                    pausedUntil = c.followupPausedUntil,
                    waiting = c.id in waitingIds,
                )
            }
        }
        return combine(inputs, reference.observeTrainingTypes(), reference.observeLocations()) { (all, list), types, places ->
            val byId = all.associateBy { it.id }
            val typeShort = types.associate { it.id to it.shortName }
            val placeShort = places.associate { it.id to it.shortName }
            Followup(
                days = days,
                rows = FollowupRules.list(list, today, days).map { item ->
                    FollowupRow(byId.getValue(item.clientId), item, typeShort[item.last.typeId].orEmpty(), placeShort[item.last.locationId].orEmpty())
                },
                noBooking = list.filter { FollowupRules.notBooked(it, today, days) != null }.map { it.clientId }.toSet(),
            )
        }
    }

    /** «Написал»: дата связи — сегодня, клиент скрыт на 3 дня. Возвращает карточку до правки — для «Отменить». */
    suspend fun contacted(clientId: String): ClientEntity? {
        val client = clients.client(clientId) ?: return null
        val now = clock()
        clients.upsertClient(client.copy(followupContactedAt = now, sync = client.sync.touched(now)))
        return client
    }

    /** «Пауза до…» с причиной, видной только тренеру. Возвращает карточку до правки — для «Отменить». */
    suspend fun pause(clientId: String, until: LocalDate, note: String?): ClientEntity? {
        val client = clients.client(clientId) ?: return null
        val now = clock()
        val reason = note?.trim()?.takeIf { it.isNotEmpty() }
        clients.upsertClient(client.copy(followupPausedUntil = until, followupPauseNote = reason, sync = client.sync.touched(now)))
        return client
    }

    /** «Отменить» после «Написал» и «Пауза до…»: поля «Без записи» — как были в [before]. */
    suspend fun undo(before: ClientEntity) {
        val client = clients.client(before.id) ?: return
        val now = clock()
        clients.upsertClient(
            client.copy(
                followupContactedAt = before.followupContactedAt,
                followupPausedUntil = before.followupPausedUntil,
                followupPauseNote = before.followupPauseNote,
                sync = client.sync.touched(now),
            ),
        )
    }
}

private fun ClientVisit.visit() = Visit(start, kind, typeId, locationId)
