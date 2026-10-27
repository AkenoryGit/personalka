package com.akenory.personalka.data.clients

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.dao.ReliabilityRow
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.client.ReliabilityRules
import com.akenory.personalka.domain.client.ReliabilityVisit
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime

/** Кого спросить, придёт ли (шаг 3.14): клиент, «Иванов И.» и начало сегодняшней тренировки. */
data class ConfirmVisit(val clientId: String, val name: String, val start: LocalDateTime)

/**
 * Надёжность клиентов (шаг 3.14): не хранится, а считается из тренировок при каждом их изменении. Только для тренера —
 * ни в кабинет, ни в выгрузку она не попадает.
 */
class ClientReliability internal constructor(db: PersonalkaDatabase) {
    private val clients = db.clientDao()
    private val trainings = db.trainingDao()
    private val coaches = db.coachDao()

    /** Выключатель «Надёжность клиентов» в настройках (шаг 3.14а); настроек ещё нет — включена. */
    val enabled: Flow<Boolean> = coaches.observeSettings().map { it?.reliabilityEnabled ?: true }.distinctUntilChanged()

    /**
     * Надёжность всех клиентов, у кого есть ПТ, сплиты или мини-группы; у кого их нет — в карте его нет. Выключена
     * в настройках — карта пустая: надёжности не видно нигде.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Map<String, Reliability>> = enabled.flatMapLatest { on ->
        if (!on) {
            flowOf(emptyMap())
        } else {
            trainings.observeReliabilityRows().map { rows ->
                rows.groupBy({ it.clientId }, { it.visit() }).mapValues { (_, visits) -> ReliabilityRules.of(visits) }
            }
        }
    }

    /**
     * Посещаемость детей в группах и мини-группах (шаг 3.14б): по группе и ребёнку — его последние 50 тренировок
     * этой группы. Выключена в настройках — пусто.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeGroups(): Flow<Map<Pair<String, String>, Reliability>> = enabled.flatMapLatest { on ->
        if (!on) {
            flowOf(emptyMap())
        } else {
            trainings.observeReliabilityRows().map { rows ->
                rows.filter { it.groupId != null }
                    .groupBy({ it.groupId!! to it.clientId }, { it.visit() })
                    .mapValues { (_, visits) -> ReliabilityRules.ofGroup(visits) }
            }
        }
    }

    /**
     * «Уточнить, придёт ли» на «Главной»: сегодняшние ещё не начавшиеся ПТ, сплиты и мини-группы, где клиента ждут,
     * а его надёжность красная. По началу.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun confirm(now: LocalDateTime): Flow<List<ConfirmVisit>> = enabled.flatMapLatest { on ->
        if (on) confirmOn(now) else flowOf(emptyList())
    }

    private fun confirmOn(now: LocalDateTime): Flow<List<ConfirmVisit>> = combine(trainings.observeReliabilityRows(), clients.observeAllClients()) { rows, all ->
        val byClient = rows.groupBy({ it.clientId }, { it.visit() })
        val names = all.filter { it.status == ClientStatus.ACTIVE }.associateBy { it.id }
        rows.asSequence()
            .filter { it.start.date == now.date && it.start > now }
            .filter { it.trainingStatus == TrainingStatus.PLANNED && it.participationStatus == ParticipationStatus.EXPECTED }
            .filter { ReliabilityRules.counts(it.kind, it.typeName, it.typeShort) }
            .filter { ReliabilityRules.ask(ReliabilityRules.of(byClient[it.clientId].orEmpty())) }
            .mapNotNull { row ->
                val c = names[row.clientId] ?: return@mapNotNull null
                ConfirmVisit(row.clientId, CalendarFormat.client(ClientName(c.lastName, c.firstName)), row.start)
            }
            .sortedBy { it.start }
            .toList()
    }
}

private fun ReliabilityRow.visit() = ReliabilityVisit(
    start = start,
    kind = kind,
    typeName = typeName,
    typeShort = typeShort,
    training = trainingStatus,
    participation = participationStatus,
    participationReason = participationReason,
    trainingReason = trainingReason,
)
