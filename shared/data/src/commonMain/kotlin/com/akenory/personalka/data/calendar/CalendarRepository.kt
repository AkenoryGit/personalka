package com.akenory.personalka.data.calendar

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Тренировка в календаре (5.3) — всё, что нужно блоку. [start] — время клуба.
 *
 * @param title «Иванов И.», «Петров / Сидоров», «Сб 9:00 ББ (12)».
 * @param color ключ цвета типа (`TrainingType.color`): у каждой темы свои тона.
 */
data class CalendarTraining(
    val id: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val title: String,
    val typeShort: String,
    val placeId: String,
    val placeShort: String,
    val color: String,
    val cancelled: Boolean,
)

/** Часы работы из настроек — часы сетки (3.2); по умолчанию — часы клуба 07:00–23:00. */
data class WorkHours(val start: LocalTime = LocalTime(7, 0), val end: LocalTime = LocalTime(23, 0))

/**
 * Календарь (ТЗ 5.3): тренировки дней с именами клиентов, типом и местом, часы сетки.
 *
 * Дежурств среди тренировок нет: в календаре они не блоки, а заливка под тренировками (шаг 1.10).
 */
class CalendarRepository(db: PersonalkaDatabase) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    val workHours: Flow<WorkHours> = coaches.observeSettings().map { settings ->
        if (settings == null) WorkHours() else WorkHours(settings.workDayStart, settings.workDayEnd)
    }

    /** Тренировки, которые начинаются в дни с [from] по [to] не включая, по времени; отменённые тоже. */
    fun trainings(from: LocalDate, to: LocalDate): Flow<List<CalendarTraining>> {
        val start = LocalDateTime(from, LocalTime(0, 0))
        val end = LocalDateTime(to, LocalTime(0, 0))
        val names = combine(
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            clients.observeGroups(),
            clients.observeAllClients(),
        ) { types, places, groups, people -> Names(types.associateBy { it.id }, places.associateBy { it.id }, groups.associateBy { it.id }, people.associateBy { it.id }) }
        return combine(
            trainings.observeTrainingsBetween(start, end),
            trainings.observeParticipationsBetween(start, end),
            names,
        ) { list, participations, n ->
            val byTraining = participations.groupBy { it.trainingId }
            list.mapNotNull { t ->
                val type = n.types[t.typeId]
                if (type?.kind == TrainingKind.DUTY) return@mapNotNull null
                // Клиенты — в порядке, в котором их добавляли: «Петров / Сидоров» не меняется местами.
                val people = byTraining[t.id].orEmpty().sortedWith(compareBy({ it.sync.createdAt }, { it.id }))
                val cancelled = t.status == TrainingStatus.CANCELLED
                // Детей группы в скобках — тех, кого ждут; у отменённой — всех, кто был записан.
                val waiting = if (cancelled) people.size else people.count { it.status != ParticipationStatus.CANCELLED && it.status != ParticipationStatus.CANCELLED_CHARGED }
                t.toCalendar(
                    title = CalendarFormat.title(
                        clients = people.mapNotNull { n.clients[it.clientId]?.name() },
                        groupName = t.groupId?.let { n.groups[it]?.name },
                        groupSize = waiting,
                        typeName = type?.name.orEmpty(),
                    ),
                    type = type,
                    place = n.places[t.locationId],
                )
            }
        }
    }

    private class Names(
        val types: Map<String, TrainingTypeEntity>,
        val places: Map<String, LocationEntity>,
        val groups: Map<String, GroupEntity>,
        val clients: Map<String, ClientEntity>,
    )
}

private fun ClientEntity.name() = ClientName(lastName, firstName, middleName)

private fun TrainingEntity.toCalendar(title: String, type: TrainingTypeEntity?, place: LocationEntity?) = CalendarTraining(
    id = id,
    start = start,
    durationMin = durationMin,
    title = title,
    typeShort = type?.shortName.orEmpty(),
    placeId = locationId,
    placeShort = place?.shortName.orEmpty(),
    color = type?.color.orEmpty(),
    cancelled = status == TrainingStatus.CANCELLED,
)
