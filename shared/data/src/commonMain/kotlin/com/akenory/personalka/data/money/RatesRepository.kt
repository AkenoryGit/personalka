package com.akenory.personalka.data.money

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.RateVersionEntity
import com.akenory.personalka.data.db.entity.TierSchemeVersionEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.settings.draft
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.money.PayRate
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.RateChange
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SavedRate
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.money.TierScheme
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * «Ставки и ступени» (ТЗ 5.16, 3.3): версии ставок по типу и месту и версии ступеней. Что писать, решает
 * [RateRules]; здесь — только запись в базу одной транзакцией.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class RatesRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val reference = db.referenceDao()
    private val coaches = db.coachDao()

    /** Все версии ставок по дате начала. */
    val rates: Flow<List<SavedRate>> = reference.observeRateVersions().map { list -> list.map { it.saved() } }

    /** Все версии ступеней по дате начала. */
    val tiers: Flow<List<TierScheme>> = reference.observeTierSchemeVersions().map { list -> list.map { it.scheme() } }

    /**
     * «Сохранить» в форме ставок типа [typeId]: [entered] — введённое по местам (`null` — поля пустые), [from] —
     * «Действуют с». Возвращает число записанных изменений: 0 — всё совпало с тем, что уже действует.
     */
    suspend fun saveRates(typeId: String, from: LocalDate, entered: Map<String?, RateAmounts?>): Int {
        val coach = coaches.currentCoach() ?: return 0
        val now = clock()
        val versions = reference.rateVersions(typeId)
        val changes = RateRules.changes(versions.map { it.saved() }, from, entered)
        val byId = versions.associateBy { it.id }
        db.withWriteTransaction {
            changes.forEach { change ->
                when (change) {
                    is RateChange.Add -> reference.upsertRateVersion(
                        RateVersionEntity(
                            id = Ids.newId(now),
                            sync = SyncFields.new(coach.id, now),
                            typeId = typeId,
                            locationId = change.placeId,
                            validFrom = from,
                            amountT1 = change.amounts.t1,
                            amountT2 = change.amounts.t2,
                            amountT3 = change.amounts.t3,
                            sptCloseBonus = change.amounts.sptBonus,
                        ),
                    )
                    is RateChange.Replace -> byId[change.id]?.let { old ->
                        reference.upsertRateVersion(
                            old.copy(
                                sync = old.sync.touched(now),
                                amountT1 = change.amounts.t1,
                                amountT2 = change.amounts.t2,
                                amountT3 = change.amounts.t3,
                                sptCloseBonus = change.amounts.sptBonus,
                                // Суммы правил сам тренер: связь с сеткой клуба больше не верна.
                                clubService = null,
                            ),
                        )
                    }
                    is RateChange.Remove -> byId[change.id]?.let { reference.upsertRateVersion(it.copy(sync = it.sync.deleted(now))) }
                }
            }
        }
        return changes.size
    }

    /**
     * Ступени с месяца [month] (ТЗ 3.3): версия с 1-го числа; версия с этой датой уже есть — правится она.
     * Порядок «Считать как ПТ» — порядок [ptTypeIds], повторов нет.
     */
    suspend fun saveTiers(month: YearMonth, draft: TierDraft) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val from = RateRules.tierFrom(month)
        val same = reference.tierSchemeVersions().lastOrNull { it.validFrom == from }
        val ids = draft.ptTypeIds.toList()
        reference.upsertTierSchemeVersion(
            same?.copy(sync = same.sync.touched(now), enabled = draft.enabled, t2From = draft.t2From, t3From = draft.t3From, ptTypeIds = ids)
                ?: TierSchemeVersionEntity(
                    id = Ids.newId(now),
                    sync = SyncFields.new(coach.id, now),
                    validFrom = from,
                    enabled = draft.enabled,
                    t2From = draft.t2From,
                    t3From = draft.t3From,
                    ptTypeIds = ids,
                ),
        )
    }

    companion object {
        /**
         * Тип «ПТ» для «Надбавок как у ПТ»: тот же, в который превращается сплит при одном клиенте
         * (`ReferenceRules.splitFallback`); архивные не берутся.
         */
        fun ptTypeId(types: List<TrainingTypeEntity>): String? {
            val active = types.filter { !it.archived }
            return ReferenceRules.splitFallback(active.map { it.draft() })?.let { active[it].id }
        }
    }
}

/** Версия ставки из базы — во вход расчёта (3.2) и формы ставок. */
fun RateVersionEntity.pay(): PayRate = PayRate(typeId, locationId, validFrom, amountT1, amountT2, amountT3, sptCloseBonus)

private fun RateVersionEntity.saved(): SavedRate = SavedRate(id, pay())

/** Версия ступеней из базы — во вход расчёта (4.6). */
fun TierSchemeVersionEntity.scheme(): TierScheme = TierScheme(validFrom, enabled, t2From, t3From, ptTypeIds.toSet())
