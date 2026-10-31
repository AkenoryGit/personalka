package com.akenory.personalka.data.money

import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ColleagueSchemeVersionEntity
import com.akenory.personalka.data.db.entity.ColleagueSettlementEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.MonthAdjustmentEntity
import com.akenory.personalka.data.db.entity.MonthRecordEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.money.AdvanceState
import com.akenory.personalka.domain.money.CancelRules
import com.akenory.personalka.domain.money.DutyCheck
import com.akenory.personalka.domain.money.DutyCheckRules
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.OwnerScheme
import com.akenory.personalka.domain.money.OwnerSchemes
import com.akenory.personalka.domain.money.PayAdjustment
import com.akenory.personalka.domain.money.PayRate
import com.akenory.personalka.domain.money.PayRules
import com.akenory.personalka.domain.money.PayScheme
import com.akenory.personalka.domain.money.PayTraining
import com.akenory.personalka.domain.money.PayType
import com.akenory.personalka.domain.money.Payouts
import com.akenory.personalka.domain.money.SalaryDaysRules
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.money.SalaryMonthRules
import com.akenory.personalka.domain.money.SalaryPeriod
import com.akenory.personalka.domain.money.SalaryYear
import com.akenory.personalka.domain.money.SalaryYearRules
import com.akenory.personalka.domain.money.SptCase
import com.akenory.personalka.domain.money.TierScheme
import com.akenory.personalka.domain.money.Versions
import com.akenory.personalka.domain.training.CancelReasons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

/**
 * Месяц экрана «Зарплата» (5.12) и всё, что к нему нужно: [view] — расчёт, [adjustments] — доплаты и удержания
 * в порядке внесения, имена типов, мест и коллег — по id. [input] — вход расчёта (для периода, 3.7в);
 * [trainings] — тренировки месяца по id, [whom] — за кого тренировка: клиенты или название группы (день графика).
 * [groups] — названия групп по id, [colleagueSchemes] — схема выплат коллеги в этом месяце (вкладка «Коллеги», 3.8).
 * [duties] — сверка дежурств месяца (раздел «Сверки», 5.14).
 */
data class SalaryMonthData(
    val view: SalaryMonth,
    val adjustments: List<MonthAdjustmentEntity>,
    val types: Map<String, TrainingTypeEntity>,
    val places: Map<String, LocationEntity>,
    val colleagues: Map<String, String>,
    val input: MonthInput? = null,
    val trainings: Map<String, TrainingEntity> = emptyMap(),
    val whom: Map<String, String> = emptyMap(),
    val groups: Map<String, String> = emptyMap(),
    val colleagueSchemes: Map<String, PaySchemeKind> = emptyMap(),
    val duties: DutyCheck = DutyCheck(emptyList(), null, canCheck = false),
    val payTypes: Boolean = true,
)

/** «Период» (5.12, 3.7в): расчёт [view] и месяцы, которые он задевает, — для имён, доплат и тренировок. */
data class SalaryPeriodData(val view: SalaryPeriod, val months: List<SalaryMonthData>)

/**
 * Деньги месяца из базы (ТЗ 5.12, 4.6–4.12): тренировки с участием, ставки, ступени, схемы, СПТ, доплаты и план
 * переводятся во вход расчёта `shared/domain/money`, а он решает всё остальное. Здесь же — план, аванс и доплаты.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SalaryRepository(
    private val db: PersonalkaDatabase,
    private val clock: () -> Long,
    // Виды оплаты (решение Дмитрия 27.10.2026): открыта ли «С налогом 6,5 %» у аккаунта. Закрыта — свои схемы и
    // схемы коллег считаются «100 % 10-го», а их названий в данных экрана нет (SalarySetupRules.payTypes).
    private val payTypes: Flow<Boolean> = flowOf(true),
) {
    private val trainingDao = db.trainingDao()
    private val reference = db.referenceDao()
    private val money = db.moneyDao()
    private val coaches = db.coachDao()
    private val clients = db.clientDao()

    private data class Sources(
        val trainings: List<TrainingEntity>,
        val participations: List<ParticipationEntity>,
        val types: List<TrainingTypeEntity>,
        val rates: List<PayRate>,
        val tiers: List<TierScheme>,
    )

    private data class Names(
        val places: List<LocationEntity>,
        val colleagues: Map<String, String>,
        val clients: Map<String, String>,
        val groups: Map<String, String>,
    )

    private data class Money(
        val schemes: List<PayScheme>,
        val colleagueSchemes: Map<String, List<ColleagueSchemeVersionEntity>>,
        val spt: List<SptCaseEntity>,
        val adjustments: List<MonthAdjustmentEntity>,
        val records: List<MonthRecordEntity>,
        val payTypes: Boolean = true,
    )

    /**
     * Месяц [month] на день [today]; пересчитывается сам при каждой правке тренировок, ставок и денег. Тренировки
     * берутся и за два месяца до него: прогноз ждёт в группе столько детей, сколько было на 4 последних (4.12),
     * а доплата СПТ месяца покупки берёт тип и место самой СПТ (4.9) — окно СПТ месяц.
     */
    fun month(month: YearMonth, today: LocalDate): Flow<SalaryMonthData> = months(listOf(month), today).map { it.single() }

    /**
     * Вкладка «Год» (5.12, 3.8б): месяцы года [year] до текущего, каждый — тем же расчётом, что вкладка «Месяц»
     * (те же два месяца истории), поэтому октябрь в «Годе» и в «Месяце» совпадает до копейки.
     */
    fun year(year: Int, today: LocalDate): Flow<SalaryYear> {
        val list = SalaryYearRules.months(year, today)
        if (list.isEmpty()) return flowOf(SalaryYearRules.build(year, emptyList(), today))
        return months(list, today).map { all -> SalaryYearRules.build(year, all.map { it.view }, today) }.flowOn(Dispatchers.Default)
    }

    /**
     * Месяцы [list] тем же расчётом, что «Зарплата», — для «Отчётов» (5.15, шаг 5.3и): числа отчёта и разбивки
     * зарплаты считаются из одних и тех же MonthInput и MonthPay.
     */
    fun monthsData(list: List<YearMonth>, today: LocalDate): Flow<List<SalaryMonthData>> =
        if (list.isEmpty()) flowOf(emptyList()) else months(list, today)

    /** Месяцы [list] (по возрастанию) одним чтением базы: тренировки — с двух месяцев до первого по конец последнего. */
    private fun months(list: List<YearMonth>, today: LocalDate): Flow<List<SalaryMonthData>> {
        val first = list.first()
        val last = list.last()
        val from = first.firstDay.minus(HISTORY_MONTHS, DateTimeUnit.MONTH).atTime(LocalTime(0, 0))
        val to = last.lastDay.plus(1, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
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
            if (first == last) money.observeAdjustments(first) else money.observeAdjustmentsBetween(first, last),
            money.observeMonthRecords(),
        ) { schemes, colleagueSchemes, spt, adjustments, records ->
            Money(
                schemes.map { it.payScheme() },
                colleagueSchemes.groupBy { it.colleagueId },
                spt, adjustments, records,
            )
        }.combine(payTypes) { m, open ->
            if (open) {
                m
            } else {
                m.copy(
                    schemes = m.schemes.map(SalarySetupRules::plain),
                    colleagueSchemes = m.colleagueSchemes.mapValues { (_, list) -> list.map { it.copy(kind = PaySchemeKind.NONE) } },
                    payTypes = false,
                )
            }
        }
        // Отметки «Рассчитались» (3.8): у коллеги с двумя записями (два телефона) — последняя изменённая.
        val settled = (if (first == last) money.observeSettlements(first) else money.observeSettlementsBetween(first, last))
            .map { all -> all.groupBy { it.month }.mapValues { (_, marks) -> marks.associate { it.colleagueId to it.amount } } }
        val names = combine(
            reference.observeLocations(),
            money.observeColleagues(),
            clients.observeAllClients(),
            clients.observeGroups(),
        ) { places, colleagues, people, groups ->
            Names(
                places,
                colleagues.associate { it.id to it.name },
                people.associate { it.id to "${it.lastName} ${it.firstName}".trim() },
                groups.associate { it.id to it.name },
            )
        }
        return combine(sources, moneySources, names, settled) { s, m, n, marks ->
            val byTraining = s.participations.groupBy { it.trainingId }
            list.map { month ->
                // У каждого месяца — своё окно, как при чтении одного месяца: прогноз групп берёт 4 последние (4.12).
                val start = month.firstDay.minus(HISTORY_MONTHS, DateTimeUnit.MONTH)
                val own = if (first == last) s else s.copy(trainings = s.trainings.filter { it.start.date >= start && it.start.date <= month.lastDay })
                val ownMoney = if (first == last) m else m.copy(adjustments = m.adjustments.filter { it.month == month })
                build(month, today, own, ownMoney, n, marks[month].orEmpty(), byTraining)
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Заработок с [from] по [to] (3.7в): месяцы периода считаются так же, как в «Заработано», каждый по своей
     * ступени, а период складывается из их частей. Пересчитывается сам при правках.
     */
    fun period(from: LocalDate, to: LocalDate, today: LocalDate): Flow<SalaryPeriodData> {
        val months = generateSequence(from.yearMonth) { it.firstDay.plus(1, DateTimeUnit.MONTH).yearMonth }.takeWhile { it <= to.yearMonth }.toList()
        return combine(months.map { month(it, today) }) { all ->
            val list = all.toList()
            val view = SalaryDaysRules.period(list.mapNotNull { d -> d.input?.let { it to d.view.pay } }, from, to, today)
            SalaryPeriodData(view, list)
        }.flowOn(Dispatchers.Default)
    }

    private fun build(
        month: YearMonth,
        today: LocalDate,
        s: Sources,
        m: Money,
        n: Names,
        settled: Map<String, Long>,
        byTraining: Map<String, List<ParticipationEntity>>,
    ): SalaryMonthData {
        val places = n.places
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
            owners = OwnerSchemes(
                m.schemes.map { it.owner() },
                m.colleagueSchemes.mapValues { (_, list) -> list.map { OwnerScheme.of(it.validFrom, it.kind) } },
            ),
            spt = spt,
            adjustments = m.adjustments.map { PayAdjustment(it.amount, it.inClubAccrual, ClubTime.today(it.sync.createdAt)) },
            cancels = CancelRules.share(trainings, types, today),
        )
        // Две записи одного месяца с двух телефонов — берётся последняя изменённая, как в MoneyDao.monthRecord.
        val records = m.records.groupBy { it.month }.mapValues { (_, list) -> list.maxWith(compareBy({ it.sync.updatedAt }, { it.id })) }
        val plans = records.mapNotNull { (k, v) -> v.planAmount?.let { k to it } }.toMap()
        val activePlaces = places.filter { !it.archived }.map { it.id }
        val view = SalaryMonthRules.build(
            input, m.schemes, plans, records[month]?.advanceActual, today, ptTypeId,
            SalaryMonthRules.ptPlace(PayRules.inMonth(trainings, month), ptTypeId, activePlaces),
            closed = records[month]?.closed == true,
            settled = settled,
        )
        val ofMonth = s.trainings.filter { it.start.date.yearMonth == month }
        val shown = view.days.mapNotNull { it.trainingId }.toSet()
        val whom = ofMonth.filter { it.id in shown }.associate { t ->
            val group = t.groupId?.let { n.groups[it] }
            t.id to (group ?: byTraining[t.id].orEmpty().mapNotNull { n.clients[it.clientId] }.joinToString(", "))
        }
        return SalaryMonthData(
            view, m.adjustments, s.types.associateBy { it.id }, places.associateBy { it.id }, n.colleagues,
            input = input, trainings = ofMonth.associateBy { it.id }, whom = whom, groups = n.groups,
            colleagueSchemes = if (!m.payTypes) emptyMap() else m.colleagueSchemes.mapNotNull { (id, list) ->
                Versions.activeIn(list, month) { it.validFrom }?.let { id to it.kind }
            }.toMap(),
            payTypes = m.payTypes,
            duties = DutyCheckRules.build(input, view.pay, today, records[month]?.dutiesCheckedMin),
        )
    }

    /** План месяца [month] (4.12); `null` — стереть, тогда месяц возьмёт план прошлого. */
    suspend fun savePlan(month: YearMonth, amount: Long?) = updateRecord(month) { it.copy(planAmount = amount) }

    /**
     * Аванс месяца [month] (4.11): для пункта «Введите сумму аванса» на «Главной» (5.2) и уведомления 25-го (5.19).
     * Читает только схемы выплат и записи месяцев — не пересчитывает зарплату. У схемы без аванса — `null`.
     */
    fun advance(month: YearMonth): Flow<AdvanceState?> =
        combine(coaches.observePaySchemeVersions(), money.observeMonthRecords(), payTypes) { schemes, records, open ->
            // Две записи месяца с двух телефонов — последняя изменённая, как в build.
            val record = records.filter { it.month == month }.maxWithOrNull(compareBy({ it.sync.updatedAt }, { it.id }))
            // Без видов оплаты аванса нет (27.10.2026).
            if (open) Payouts.advance(month, schemes.map { it.payScheme() }, record?.advanceActual) else null
        }

    /** Фактический аванс месяца [month] (4.11); `null` — снова ожидаемый. */
    suspend fun saveAdvance(month: YearMonth, amount: Long?) = updateRecord(month) { it.copy(advanceActual = amount) }

    /** «Месяц сверен» (5.12): [closed] — закрыть месяц, правки его тренировок спросят подтверждение (4.1). */
    suspend fun setClosed(month: YearMonth, closed: Boolean) = updateRecord(month) { it.copy(closed = closed) }

    /** «Дежурства сверены» (5.14): [minutes] — часы проведённых дежурств, на которых сверили; `null` — снять. */
    suspend fun setDutiesChecked(month: YearMonth, minutes: Int?) = updateRecord(month) { it.copy(dutiesCheckedMin = minutes) }

    private suspend fun updateRecord(month: YearMonth, change: (MonthRecordEntity) -> MonthRecordEntity) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val old = money.monthRecord(month)
        val record = old ?: MonthRecordEntity(Ids.newId(now), SyncFields.new(coach.id, now), month)
        val changed = change(record)
        if (old != null && changed == old) return
        money.upsertMonthRecord(if (old == null) changed else changed.copy(sync = old.sync.touched(now)))
    }

    /**
     * «Рассчитались» с коллегой [colleagueId] за месяц [month] (4.11, 5.12): запоминается итог [amount], на котором
     * отметили; `null` — снять отметку. Повторная отметка после изменения расчёта правит ту же запись.
     */
    suspend fun settle(month: YearMonth, colleagueId: String, amount: Long?) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val old = money.settlement(month, colleagueId)
        when {
            amount == null -> old?.let { money.upsertSettlement(it.copy(sync = it.sync.deleted(now))) }
            old == null -> money.upsertSettlement(ColleagueSettlementEntity(Ids.newId(now), SyncFields.new(coach.id, now), month, colleagueId, amount))
            old.amount != amount -> money.upsertSettlement(old.copy(sync = old.sync.touched(now), amount = amount))
        }
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
    coaches = participations.map { it.coach },
    splitHalf = splitHalf,
    owner = owner,
    ownerColleagueId = ownerColleagueId,
    conductedBy = conductedBy,
    colleagueId = colleagueId,
    groupId = groupId,
    coverFor = coverFor,
    byUs = CancelReasons.isWithoutCharge(cancelReason) || participations.any { CancelReasons.isWithoutCharge(it.cancelReason) },
)

/** Тип из базы — во вход расчёта (3.3). Сплит без своего «ПТ» превращается в ПТ тренера ([ptTypeId]). */
fun TrainingTypeEntity.payType(ptTypeId: String?): PayType =
    PayType(id, kind, payUnit, countsTowardPt, tierDependent, splitFallbackTypeId ?: ptTypeId.takeIf { kind == TrainingKind.SPLIT })
