package com.akenory.personalka.data.calendar

import com.akenory.personalka.data.clients.want
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.restrictions.toRestriction
import com.akenory.personalka.domain.calendar.DayStarts
import com.akenory.personalka.domain.calendar.FreeSlots
import com.akenory.personalka.domain.calendar.PlaceStarts
import com.akenory.personalka.domain.calendar.SlotBusy
import com.akenory.personalka.domain.calendar.SlotParams
import com.akenory.personalka.domain.calendar.SlotQuery
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Найденное время (4.13): по помещениям и дням — начала занятий (шаг 4.3б); кто из листа ожидания ждёт время
 * этого дня в этом помещении — «Петров К.».
 */
data class SlotResult(val places: List<PlaceStarts>, val waiting: Map<DayStarts, List<String>> = emptyMap())

/**
 * Свободные слоты (ТЗ 4.13, 5.13) из базы телефона: занятое — неотменённые тренировки и дежурства тренера (группа,
 * которую ведёт коллега без него, не занимает), запреты — свои и клуба, по каждому помещению отдельно (шаг 4.3а);
 * пометки «ждёт» — записи листа ожидания
 * действующих клиентов с длительностью их типа.
 */
class FreeSlotRepository internal constructor(private val db: PersonalkaDatabase) {
    private val reference = db.referenceDao()
    private val clients = db.clientDao()

    fun observe(query: SlotQuery, now: LocalDateTime): Flow<SlotResult> {
        val from = LocalDateTime(query.from, LocalTime(0, 0))
        val to = LocalDateTime(query.to.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        val waits = combine(clients.observeWaitEntries(WaitStatus.WAITING), clients.observeAllClients()) { entries, people ->
            val byId = people.associateBy { it.id }
            entries.mapNotNull { e -> byId[e.clientId]?.takeIf { it.status == ClientStatus.ACTIVE }?.let { e to it } }
        }
        return combine(
            db.trainingDao().observeTrainingsBetween(from, to),
            db.scheduleDao().observeBlocks(),
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            waits,
        ) { trainings, blocks, types, places, waiting ->
            val typeById = types.associateBy { it.id }
            val busy = trainings
                .filter { it.status != TrainingStatus.CANCELLED && it.conductedBy != ConductedBy.COLLEAGUE }
                .map { SlotBusy(it.start, it.durationMin, duty = typeById[it.typeId]?.kind == TrainingKind.DUTY) }
            val restrictions = blocks.map { it.toRestriction() }
            val days = FreeSlots.find(query, busy, restrictions, places.filter { !it.archived }.map { it.id }, now)
            val byPlace = FreeSlots.byPlace(days, query.durationMin, query.stepMin)
            val marks = byPlace.flatMap { it.days }.associateWith { day ->
                waiting.mapNotNull { (entry, client) ->
                    val duration = typeById[entry.typeId]?.defaultDurationMin ?: return@mapNotNull null
                    if (day.windows.none { FreeSlots.suits(it, entry.want(), duration, query.stepMin) }) return@mapNotNull null
                    CalendarFormat.client(ClientName(client.lastName, client.firstName))
                }
            }.filterValues { it.isNotEmpty() }
            SlotResult(byPlace, marks)
        }
    }

    /**
     * «Найти время» у записи листа ожидания (5.8, 4.13): параметры [base], а дни, промежутки начала, место
     * и длительность — как в записи (длительность — её типа). Записи или клиента нет — `null`.
     */
    suspend fun forWait(waitId: String, base: SlotParams): WaitSlots? {
        val entry = clients.waitEntry(waitId)?.takeIf { it.sync.deletedAt == null } ?: return null
        val client = clients.client(entry.clientId) ?: return null
        val want = entry.want()
        val duration = reference.trainingTypes().firstOrNull { it.id == entry.typeId }?.defaultDurationMin ?: base.durationMin
        return WaitSlots(
            params = base.copy(days = want.days, ranges = want.ranges, locationId = want.locationId, durationMin = duration),
            name = CalendarFormat.client(ClientName(client.lastName, client.firstName)),
            clientId = client.id,
            typeId = entry.typeId,
            series = want.series,
        )
    }
}

/** «Найти время» для записи листа ожидания: параметры поиска и кого записывать нажатием на окно. */
data class WaitSlots(val params: SlotParams, val name: String, val clientId: String, val typeId: String, val series: Boolean)
