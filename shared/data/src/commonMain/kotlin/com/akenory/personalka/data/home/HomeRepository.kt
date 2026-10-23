package com.akenory.personalka.data.home

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.training.ordered
import com.akenory.personalka.data.training.statusSettings
import com.akenory.personalka.data.training.toState
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.HomeTraining
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.StatusSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Что нужно «Главной» (5.2): тренировки с сегодня на [HomeRules.AHEAD_DAYS] дней вперёд и запланированные прошлые —
 * кандидаты в «без отметки»; настройки статусов. Что из этого ближайшая и что в «Нужно сделать», решает
 * [HomeRules] по «сейчас» — поэтому поток зависит только от дня и не перечитывает базу каждую минуту.
 */
data class HomeData(val trainings: List<HomeTraining>, val settings: StatusSettings)

/** «Главная» без денег (ТЗ 5.2): чтение для блоков «Ближайшая» и «Нужно сделать». */
class HomeRepository internal constructor(private val db: PersonalkaDatabase) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()
    private val schedule = db.scheduleDao()

    /** Тренировки «Главной» на [today]; обновляется сама после каждого изменения. Отменённые тоже — правила их отсеют. */
    fun data(today: LocalDate): Flow<HomeData> {
        val (from, to) = HomeRules.range(today)
        val start = LocalDateTime(from, LocalTime(0, 0))
        val end = LocalDateTime(to, LocalTime(0, 0))
        val rows = combine(
            trainings.observeTrainingsBetween(start, end),
            trainings.observeParticipationsBetween(start, end),
            trainings.observePlannedBefore(start, TrainingStatus.PLANNED),
            trainings.observeParticipationsOfPlannedBefore(start, TrainingStatus.PLANNED),
        ) { ahead, aheadPeople, past, pastPeople -> Rows(past + ahead, pastPeople + aheadPeople) }
        val names = combine(
            reference.observeTrainingTypes(),
            reference.observeLocations(),
            clients.observeGroups(),
            clients.observeAllClients(),
            schedule.observeSeriesFrom(today),
        ) { types, places, groups, people, series ->
            Names(
                types = types.associateBy { it.id },
                places = places.associateBy { it.id },
                groups = groups.associate { it.id to it.name },
                clients = people.associate { it.id to ClientName(it.lastName, it.firstName, it.middleName) },
                weekdays = series.associate { it.id to it.weekdays },
            )
        }
        return combine(rows, names, coaches.observeSettings()) { (list, people), n, settings ->
            val byTraining = people.groupBy { it.trainingId }
            val home = list.map { t ->
                val type = n.types[t.typeId]
                val ordered = byTraining[t.id].orEmpty().ordered()
                val cancelled = t.status == TrainingStatus.CANCELLED
                // Детей группы в скобках — тех, кого ждут, как на блоке календаря.
                val waiting = if (cancelled) ordered.size else ordered.count { it.status != ParticipationStatus.CANCELLED && it.status != ParticipationStatus.CANCELLED_CHARGED }
                val place = n.places[t.locationId]
                HomeTraining(
                    id = t.id,
                    state = t.toState(type?.kind ?: TrainingKind.OTHER, ordered),
                    title = CalendarFormat.title(
                        clients = ordered.mapNotNull { n.clients[it.clientId] },
                        groupName = t.groupId?.let { n.groups[it] },
                        groupSize = waiting,
                        typeName = type?.name.orEmpty(),
                    ),
                    typeShort = type?.shortName.orEmpty(),
                    placeShort = place?.shortName.orEmpty(),
                    color = place?.color.orEmpty(),
                    plan = t.plan?.takeIf { it.isNotBlank() },
                    weekdays = t.seriesId?.let { n.weekdays[it] },
                )
            }
            HomeData(home, settings.statusSettings())
        }
    }
}

private data class Rows(val trainings: List<TrainingEntity>, val people: List<ParticipationEntity>)

private data class Names(
    val types: Map<String, TrainingTypeEntity>,
    val places: Map<String, LocationEntity>,
    val groups: Map<String, String>,
    val clients: Map<String, ClientName>,
    val weekdays: Map<String, Set<DayOfWeek>>,
)
