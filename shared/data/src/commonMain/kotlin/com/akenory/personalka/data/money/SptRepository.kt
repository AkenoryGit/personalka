package com.akenory.personalka.data.money

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.PurchaseProblem
import com.akenory.personalka.domain.money.SptCase
import com.akenory.personalka.domain.money.SptDone
import com.akenory.personalka.domain.money.SptPrompt
import com.akenory.personalka.domain.money.SptRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.LocalDate

/**
 * СПТ клиента для «Инфо» (5.9): [case] — дело с типом и местом СПТ, [status] — на сегодня ([SptRules.statusOn]),
 * [bonus] — доплата за покупку блока по ставке дня СПТ (0 — суммы в ставке нет).
 */
data class SptCaseView(
    val case: SptCase,
    val status: SptCaseStatus,
    val typeShort: String,
    val placeShort: String,
    val bonus: Long,
)

/** СПТ ждёт покупки — пункт «Главной» (5.2): [name] — «Яковлева Т.»; [remind] — до конца окна 3 дня или меньше. */
data class SptWaiting(val case: SptCase, val name: ClientName, val remind: Boolean)

/** Вопрос на первой оплаченной ПТ клиента (4.9, п. 4–5): [name] — «Орлов М.», [bonus] — доплата за покупку. */
data class SptAsk(val prompt: SptPrompt, val name: String, val bonus: Long) {
    val case: SptCase get() = prompt.case()

    /** Дата покупки в окне по умолчанию: в окне — день ПТ, после окна — последний день окна. */
    val purchaseDate: LocalDate
        get() = when (prompt) {
            is SptPrompt.CloseNow -> prompt.purchaseDate
            is SptPrompt.AskRetro -> prompt.windowUntil
        }
}

/** Что вышло из «Купил блок со скидкой» или «Снять отметку»: [before] — дело до правки, для «Отменить». */
data class SptSaved(val before: SptCaseEntity, val name: String)

/**
 * СПТ в приложении (ТЗ 4.9, шаг 3.7а): дела `spt_case` по проведённым СПТ, «Купил блок со скидкой» и снятие
 * отметки, вопрос на ПТ, СПТ клиента и «СПТ ждут покупки». Окно, статусы и даты решает [SptRules]; доплату в месяц
 * покупки считает «Зарплата» по этим делам.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SptRepository internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainings = db.trainingDao()
    private val clients = db.clientDao()
    private val reference = db.referenceDao()

    /** Дела совпадают с проведёнными СПТ ([SptRules.reconcile]); вызывается вместе с закрытием прошедших. */
    suspend fun reconcile() {
        db.withWriteTransaction { db.reconcileSpt(clock()) }
    }

    /** СПТ клиента для «Инфо», старые первыми; пересчитываются сами после каждой отметки. */
    fun ofClient(clientId: String, today: LocalDate): Flow<List<SptCaseView>> = combine(
        trainings.observeSptCases(),
        reference.observeTrainingTypes(),
        reference.observeLocations(),
        reference.observeRateVersions(),
    ) { cases, types, places, rates ->
        val typeOf = types.associateBy { it.id }
        val placeOf = places.associateBy { it.id }
        val pay = rates.map { it.pay() }
        cases.filter { it.clientId == clientId }.mapNotNull { entity ->
            val t = trainings.training(entity.sptTrainingId) ?: return@mapNotNull null
            val case = entity.domain(t)
            val closed = case.copy(status = SptCaseStatus.CLOSED, purchaseDate = case.purchaseDate ?: case.sptDate)
            SptCaseView(
                case = case,
                status = SptRules.statusOn(case, today),
                typeShort = typeOf[t.typeId]?.shortName.orEmpty(),
                placeShort = placeOf[t.locationId]?.shortName.orEmpty(),
                bonus = SptRules.bonus(closed, pay),
            )
        }
    }

    /** «СПТ ждут покупки» на день [today]: открытые дела, ближайший срок первым. */
    fun waiting(today: LocalDate): Flow<List<SptWaiting>> = combine(
        trainings.observeSptCases(),
        clients.observeAllClients(),
    ) { cases, all ->
        val byId = all.associateBy { it.id }
        cases.mapNotNull { entity ->
            val client = byId[entity.clientId]?.takeIf { it.sync.deletedAt == null } ?: return@mapNotNull null
            val t = trainings.training(entity.sptTrainingId) ?: return@mapNotNull null
            val case = entity.domain(t)
            if (SptRules.statusOn(case, today) != SptCaseStatus.OPEN) return@mapNotNull null
            SptWaiting(case, ClientName(client.lastName, client.firstName), SptRules.remind(case, today))
        }.sortedWith(compareBy({ it.case.windowUntil }, { it.case.sptDate }))
    }

    /**
     * Вопрос в карточке тренировки [trainingId] (4.9, п. 4–5): ПТ или сплит — первая оплаченная тренировка клиента
     * в день СПТ или позже, а дело не закрыто. Ответили — вопрос уходит сам: дело закрыто.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ask(trainingId: String): Flow<SptAsk?> = trainings.observeTraining(trainingId).flatMapLatest { t ->
        if (t == null || t.sync.deletedAt != null) return@flatMapLatest flowOf(null)
        combine(trainings.observeParticipationsOf(t.id), trainings.observeSptCases()) { people, cases -> people to cases }
            .mapLatest { (people, cases) ->
                val kinds = reference.trainingTypes().associate { it.id to it.kind }
                val kind = kinds[t.typeId] ?: return@mapLatest null
                people.filter { SptRules.isPaidPt(kind, it.status) }.firstNotNullOfOrNull { p ->
                    val open = cases.filter { it.clientId == p.clientId && it.status != SptCaseStatus.CLOSED }
                    if (open.isEmpty()) return@firstNotNullOfOrNull null
                    val paid = paidPtDates(p.clientId, kinds)
                    open.firstNotNullOfOrNull { entity ->
                        val spt = trainings.training(entity.sptTrainingId) ?: return@firstNotNullOfOrNull null
                        SptRules.prompt(entity.domain(spt), t.start.date, paid)
                    }?.let { prompt ->
                        val client = clients.client(p.clientId) ?: return@let null
                        val rates = reference.observeRateVersions().first().map { it.pay() }
                        val case = prompt.case()
                        val bonus = SptRules.bonus(case.copy(status = SptCaseStatus.CLOSED, purchaseDate = case.sptDate), rates)
                        SptAsk(prompt, CalendarFormat.client(ClientName(client.lastName, client.firstName)), bonus)
                    }
                }
            }
    }

    /**
     * «Купил блок со скидкой» с датой [date] (4.9, п. 3): дата от дня СПТ до конца окна — дело `CLOSED`, иначе
     * причина и ничего не пишется. Закрыть можно и незакрытую в срок задним числом (п. 5).
     */
    suspend fun close(caseId: String, date: LocalDate): Pair<SptSaved?, PurchaseProblem?> {
        val now = clock()
        var result: Pair<SptSaved?, PurchaseProblem?> = null to null
        db.withWriteTransaction {
            val entity = trainings.sptCase(caseId)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val t = trainings.training(entity.sptTrainingId) ?: return@withWriteTransaction
            val case = entity.domain(t)
            val problem = SptRules.purchaseProblem(case, date)
            if (problem != null) {
                result = null to problem
                return@withWriteTransaction
            }
            val closed = SptRules.close(case, date) ?: return@withWriteTransaction
            trainings.upsertSptCase(entity.copy(purchaseDate = closed.purchaseDate, status = closed.status, sync = entity.sync.touched(now)))
            result = SptSaved(entity, nameOf(entity.clientId)) to null
        }
        return result
    }

    /** «Снять отметку»: дело снова открыто, если окно не прошло, иначе не закрыто. */
    suspend fun reopen(caseId: String): SptSaved? {
        val now = clock()
        var result: SptSaved? = null
        db.withWriteTransaction {
            val entity = trainings.sptCase(caseId)?.takeIf { it.sync.deletedAt == null } ?: return@withWriteTransaction
            val t = trainings.training(entity.sptTrainingId) ?: return@withWriteTransaction
            val open = SptRules.reopen(entity.domain(t), ClubTime.today(now))
            trainings.upsertSptCase(entity.copy(purchaseDate = null, status = open.status, sync = entity.sync.touched(now)))
            result = SptSaved(entity, nameOf(entity.clientId))
        }
        return result
    }

    /** «Отменить» в сообщении: покупка и статус — как до правки. */
    suspend fun undo(saved: SptSaved) {
        val now = clock()
        db.withWriteTransaction {
            val current = trainings.sptCase(saved.before.id) ?: return@withWriteTransaction
            trainings.upsertSptCase(
                current.copy(purchaseDate = saved.before.purchaseDate, status = saved.before.status, sync = current.sync.touched(now)),
            )
        }
    }

    private suspend fun paidPtDates(clientId: String, kinds: Map<String, TrainingKind>): List<LocalDate> {
        val mine = trainings.observeParticipationsOfClient(clientId).first().associateBy { it.trainingId }
        return trainings.observeTrainingsOfClient(clientId).first().filter { t ->
            val kind = kinds[t.typeId] ?: return@filter false
            val p = mine[t.id] ?: return@filter false
            SptRules.isPaidPt(kind, p.status)
        }.map { it.start.date }
    }

    private suspend fun nameOf(clientId: String): String =
        clients.client(clientId)?.let { CalendarFormat.client(ClientName(it.lastName, it.firstName)) }.orEmpty()
}

private fun SptPrompt.case(): SptCase = when (this) {
    is SptPrompt.CloseNow -> case
    is SptPrompt.AskRetro -> case
}

/** Дело из базы во вход [SptRules]: тип и место — самой СПТ [training]. */
internal fun SptCaseEntity.domain(training: TrainingEntity) =
    SptCase(id, clientId, sptTrainingId, training.typeId, training.locationId, sptDate, windowUntil, purchaseDate, status)

/**
 * Сверка дел СПТ с проведёнными СПТ в момент [now] (4.9, п. 1, шаг 3.7а) — внутри транзакции того, кто зовёт:
 * после «Проведено», «Вернуть», «Удалить», «Отменить» в карточке и закрытия прошедших.
 */
internal suspend fun PersonalkaDatabase.reconcileSpt(now: Long) {
    val dao = trainingDao()
    val coach = coachDao().currentCoach() ?: return
    val done = dao.trainingsOfKind(TrainingKind.SPT, TrainingStatus.DONE).associateBy { it.id }
    val came = dao.participationsOfKind(TrainingKind.SPT, TrainingStatus.DONE)
        .filter { it.status == ParticipationStatus.ATTENDED }
        .groupBy { it.trainingId }
    val spt = done.values.mapNotNull { t ->
        val client = came[t.id]?.minByOrNull { it.sync.createdAt }?.clientId ?: return@mapNotNull null
        SptDone(t.id, client, t.start.date)
    }
    val entities = dao.sptCases()
    val byId = entities.associateBy { it.id }
    // Тип и место сверке не нужны: у дела без своей СПТ их и не взять.
    val cases = entities.map { SptCase(it.id, it.clientId, it.sptTrainingId, "", "", it.sptDate, it.windowUntil, it.purchaseDate, it.status) }
    val changes = SptRules.reconcile(spt, cases, ClubTime.today(now))
    if (changes.isEmpty) return
    changes.removed.forEach { c -> byId[c.id]?.let { dao.upsertSptCase(it.copy(sync = it.sync.deleted(now))) } }
    changes.changed.forEach { c ->
        val e = byId[c.id] ?: return@forEach
        dao.upsertSptCase(
            e.copy(
                clientId = c.clientId,
                sptDate = c.sptDate,
                windowUntil = c.windowUntil,
                purchaseDate = c.purchaseDate,
                status = c.status,
                sync = e.sync.touched(now),
            ),
        )
    }
    changes.opened.forEachIndexed { i, d ->
        dao.upsertSptCase(SptCaseEntity(Ids.newId(now), SyncFields.new(coach.id, now + i), d.clientId, d.trainingId, d.date, SptRules.windowUntil(d.date)))
    }
}
