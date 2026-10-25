package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.MonthAdjustmentEntity
import com.akenory.personalka.data.db.entity.MonthRecordEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.OwnerScheme
import com.akenory.personalka.domain.money.OwnerSchemes
import com.akenory.personalka.domain.money.PayAdjustment
import com.akenory.personalka.domain.money.PayRate
import com.akenory.personalka.domain.money.PayRules
import com.akenory.personalka.domain.money.PayScheme
import com.akenory.personalka.domain.money.PayTraining
import com.akenory.personalka.domain.money.PayType
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.money.SalaryMonthRules
import com.akenory.personalka.domain.money.SptCase
import com.akenory.personalka.domain.money.TierScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Месяц экрана «Зарплата» (5.12) и всё, что к нему нужно: [view] — расчёт, [adjustments] — доплаты и удержания
 * в порядке внесения, имена типов, мест и коллег — по id.
 */
data class SalaryMonthData(
    val view: SalaryMonth,
    val adjustments: List<MonthAdjustmentEntity>,
    val types: Map<String, TrainingTypeEntity>,
    val places: Map<String, LocationEntity>,
    val colleagues: Map<String, String>,
)

/**
 * Деньги месяца из базы (ТЗ 5.12, 4.6–4.12): тренировки с участием, ставки, ступени, схемы, СПТ, доплаты и план
 * переводятся во вход расчёта `shared/domain/money`, а он решает всё остальное. Здесь же — план, аванс и доплаты.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SalaryRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val trainingDao = db.trainingDao()
    private val reference = db.referenceDao()
    private val money = db.moneyDao()
    private val coaches = db.coachDao()

    private data class Sources(
        val trainings: List<TrainingEntity>,
        val participations: List<ParticipationEntity>,
        val types: List<TrainingTypeEntity>,
        val rates: List<PayRate>,
        val tiers: List<TierScheme>,
    )

    private data class Money(
        val schemes: List<PayScheme>,
        val colleagueSchemes: Map<String, List<OwnerScheme>>,
        val spt: List<SptCaseEntity>,
        val adjustments: List<MonthAdjustmentEntity>,
        val records: List<MonthRecordEntity>,
    )

    /**
     * Месяц [month] на день [today]; пересчитывается сам при каждой правке тренировок, ставок и денег. Тренировки
     * берутся и за два месяца до него: прогноз ждёт в группе столько детей, сколько было на 4 последних (4.12),
     * а доплата СПТ месяца покупки берёт тип и место самой СПТ (4.9) — окно СПТ месяц.
     */
    fun month(month: YearMonth, today: LocalDate): Flow<SalaryMonthData> {
        val from = month.firstDay.minus(HISTORY_MONTHS, DateTimeUnit.MONTH).atTime(LocalTime(0, 0))
        val to = month.lastDay.plus(1, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        val sources = combine(
            trainingDao.observeTrainingsBetween(from, to),
            trainingDao.observeParticipationsBetween(from, to),
            reference.observeTrainingTypes(),
            reference.observeRateVersions(),
            reference.observeTierSchemeVersions(),
        ) { trainings, participations, types, rates, tiers ->
            Sources(trainings, participations, types, rates.map { it.pay() }, tiers.map { it.scheme() })
        }
        val moneySources = combine(
            coaches.observePaySchemeVersions(),
            money.observeColleagueSchemes(),
            trainingDao.observeSptCases(),
            money.observeAdjustments(month),
            money.observeMonthRecords(),
        ) { schemes, colleagueSchemes, spt, adjustments, records ->
            Money(
                schemes.map { it.payScheme() },
                colleagueSchemes.groupBy { it.colleagueId }.mapValues { (_, list) -> list.map { OwnerScheme.of(it.validFrom, it.kind) } },
                spt, adjustments, records,
            )
        }
        return combine(sources, moneySources, reference.observeLocations(), money.observeColleagues()) { s, m, places, colleagues ->
            build(month, today, s, m, places, colleagues.associate { it.id to it.name })
        }.flowOn(Dispatchers.Default)
    }

    private fun build(
        month: YearMonth,
        today: LocalDate,
        s: Sources,
        m: Money,
        places: List<LocationEntity>,
        colleagues: Map<String, String>,
    ): SalaryMonthData {
        val byTraining = s.participations.groupBy { it.trainingId }
        val trainings = s.trainings.map { it.pay(byTraining[it.id].orEmpty()) }
        val ptTypeId = RatesRepository.ptTypeId(s.types)
        val types = s.types.map { it.payType(ptTypeId) }
        val trainingById = s.trainings.associateBy { it.id }
        val spt = m.spt.mapNotNull { case ->
            val t = trainingById[case.sptTrainingId] ?: return@mapNotNull null
            SptCase(case.id, case.clientId, case.sptTrainingId, t.typeId, t.locationId, case.sptDate, case.windowUntil, case.purchaseDate, case.status)
        }
        val input = MonthInput(
            month = month,
            trainings = trainings,
            types = types,
            rates = s.rates,
            schemes = s.tiers,
            owners = OwnerSchemes(m.schemes.map { it.owner() }, m.colleagueSchemes),
            spt = spt,
            adjustments = m.adjustments.map { PayAdjustment(it.amount, it.inClubAccrual, ClubTime.today(it.sync.createdAt)) },
        )
        // Две записи одного месяца с двух телефонов — берётся последняя изменённая, как в MoneyDao.monthRecord.
        val records = m.records.groupBy { it.month }.mapValues { (_, list) -> list.maxWith(compareBy({ it.sync.updatedAt }, { it.id })) }
        val plans = records.mapNotNull { (k, v) -> v.planAmount?.let { k to it } }.toMap()
        val activePlaces = places.filter { !it.archived }.map { it.id }
        val ofMonth = PayRules.inMonth(trainings, month)
        val view = SalaryMonthRules.build(
            input, m.schemes, plans, records[month]?.advanceActual, today, ptTypeId,
            SalaryMonthRules.ptPlace(ofMonth, ptTypeId, activePlaces),
            closed = records[month]?.closed == true,
        )
        return SalaryMonthData(view, m.adjustments, s.types.associateBy { it.id }, places.associateBy { it.id }, colleagues)
    }

    /** План месяца [month] (4.12); `null` — стереть, тогда месяц возьмёт план прошлого. */
    suspend fun savePlan(month: YearMonth, amount: Long?) = updateRecord(month) { it.copy(planAmount = amount) }

    /** Фактический аванс месяца [month] (4.11); `null` — снова ожидаемый. */
    suspend fun saveAdvance(month: YearMonth, amount: Long?) = updateRecord(month) { it.copy(advanceActual = amount) }

    /** «Месяц сверен» (5.12): [closed] — закрыть месяц, правки его тренировок спросят подтверждение (4.1). */
    suspend fun setClosed(month: YearMonth, closed: Boolean) = updateRecord(month) { it.copy(closed = closed) }

    private suspend fun updateRecord(month: YearMonth, change: (MonthRecordEntity) -> MonthRecordEntity) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val old = money.monthRecord(month)
        val record = old ?: MonthRecordEntity(Ids.newId(now), SyncFields.new(coach.id, now), month)
        val changed = change(record)
        if (old != null && changed == old) return
        money.upsertMonthRecord(if (old == null) changed else changed.copy(sync = old.sync.touched(now)))
    }

    /** «+ Доплата» (5.12): доплата ([amount] больше нуля) или удержание месяца. Возвращает id записи. */
    suspend fun addAdjustment(month: YearMonth, amount: Long, comment: String, inClubAccrual: Boolean): String? {
        val coach = coaches.currentCoach() ?: return null
        val now = clock()
        val id = Ids.newId(now)
        money.upsertAdjustment(MonthAdjustmentEntity(id, SyncFields.new(coach.id, now), month, amount, comment.trim(), inClubAccrual))
        return id
    }

    /** Правка доплаты [id]. */
    suspend fun updateAdjustment(id: String, amount: Long, comment: String, inClubAccrual: Boolean) {
        val old = money.adjustment(id) ?: return
        money.upsertAdjustment(old.copy(sync = old.sync.touched(clock()), amount = amount, comment = comment.trim(), inClubAccrual = inClubAccrual))
    }

    /** Удалить доплату [id]; [restore] — вернуть удалённую («Отменить»). */
    suspend fun deleteAdjustment(id: String, restore: Boolean = false) {
        val old = money.adjustment(id) ?: return
        val now = clock()
        val sync = if (restore) old.sync.touched(now).copy(deletedAt = null) else old.sync.deleted(now)
        money.upsertAdjustment(old.copy(sync = sync))
    }

    private companion object {
        /** За сколько месяцев до расчётного брать тренировки: история групп (4.12) и СПТ (4.9). */
        const val HISTORY_MONTHS = 2
    }
}

/** Тренировка из базы — во вход расчёта (4.6–4.8). */
fun TrainingEntity.pay(participations: List<ParticipationEntity>): PayTraining = PayTraining(
    id = id,
    typeId = typeId,
    locationId = locationId,
    start = start,
    durationMin = durationMin,
    status = status,
    clients = participations.map { it.status },
    splitHalf = splitHalf,
    owner = owner,
    ownerColleagueId = ownerColleagueId,
    conductedBy = conductedBy,
    colleagueId = colleagueId,
    groupId = groupId,
    coverFor = coverFor,
)

/** Тип из базы — во вход расчёта (3.3). Сплит без своего «ПТ» превращается в ПТ тренера ([ptTypeId]). */
fun TrainingTypeEntity.payType(ptTypeId: String?): PayType =
    PayType(id, kind, payUnit, countsTowardPt, tierDependent, splitFallbackTypeId ?: ptTypeId.takeIf { kind == TrainingKind.SPLIT })
