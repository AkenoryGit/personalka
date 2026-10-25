package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Ставки и ступени» в базе (шаг 3.6): версии ставок по местам и ступени с месяца. */
class RatesRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var now = 1_792_500_000_000L // 20.10.2026
    private lateinit var settings: SettingsRepository
    private val rates get() = settings.rates

    @BeforeTest
    fun open() {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { now }
        runBlocking { settings.completeFirstRun(CoachName("Соколов", "Андрей", null), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes) }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun rub(r: Long) = r * 100
    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    @Test
    fun ratesAreWrittenAsVersions(): Unit = runBlocking {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        val db2 = placeId("ДБ")
        val jan = LocalDate(2026, 1, 1)
        assertEquals(2, rates.saveRates(pt, jan, mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)), db2 to RateAmounts(rub(830)), placeId("Зал") to null)))
        // Ошибка в ДБ с 01.01 правится в той же версии, с 01.11 ББ — новая версия.
        assertEquals(1, rates.saveRates(pt, jan, mapOf(db2 to RateAmounts(rub(830), rub(860)))))
        now += 1000
        assertEquals(1, rates.saveRates(pt, LocalDate(2026, 11, 1), mapOf(bb to RateAmounts(rub(950), rub(990), rub(1030)), db2 to RateAmounts(rub(830), rub(860)))))

        val all = rates.rates.first().map { it.rate }
        assertEquals(3, all.size)
        assertEquals(listOf(jan, jan, LocalDate(2026, 11, 1)), all.map { it.validFrom })
        assertEquals(rub(860), all.single { it.locationId == db2 }.amountT2)
        assertEquals(rub(950), all.single { it.validFrom.month.ordinal == 10 }.amountT1)
        // Записи ушли в очередь синхронизации со служебными полями тренера.
        val coach = settings.coach.first()!!.id
        assertEquals(setOf(coach), db.referenceDao().allRateVersions().map { it.sync.coachId }.toSet())

        // Стереть версию с 01.11 — снова действует январская.
        assertEquals(1, rates.saveRates(pt, LocalDate(2026, 11, 1), mapOf(bb to null)))
        assertEquals(2, rates.rates.first().size)
    }

    @Test
    fun tiersFromMonthReplaceSameMonth(): Unit = runBlocking {
        val ids = setOf(typeId("ПТ"), typeId("Сплит"))
        rates.saveTiers(YearMonth(2026, 10), TierDraft(true, 101, 151, ids))
        rates.saveTiers(YearMonth(2026, 10), TierDraft(true, 100, 150, ids))
        rates.saveTiers(YearMonth(2026, 11), TierDraft(false, 100, 150, setOf(typeId("ПТ"))))
        val tiers = rates.tiers.first()
        assertEquals(listOf(LocalDate(2026, 10, 1), LocalDate(2026, 11, 1)), tiers.map { it.validFrom })
        assertEquals(100, tiers[0].t2From)
        assertEquals(ids, tiers[0].ptTypeIds)
        assertEquals(false, tiers[1].enabled)
    }

    @Test
    fun ptIsTheSplitFallback(): Unit = runBlocking {
        assertEquals(typeId("ПТ"), RatesRepository.ptTypeId(settings.types.first()))
    }
}
