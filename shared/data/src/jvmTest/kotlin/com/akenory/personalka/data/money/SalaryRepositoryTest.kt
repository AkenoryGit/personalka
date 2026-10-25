package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Месяц «Зарплаты» из базы: тренировки и ставки → расчёт, план, аванс и доплаты (шаг 3.7). */
class SalaryRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var now = 1_792_500_000_000L // 20.10.2026
    private val today = LocalDate(2026, 10, 20)
    private val october = YearMonth(2026, 10)
    private lateinit var settings: SettingsRepository
    private val months get() = settings.months
    private var seq = 0

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

    private suspend fun training(type: String, place: String, day: Int, status: TrainingStatus, clients: ParticipationStatus) {
        val coach = db.coachDao().currentCoach()!!.id
        val id = "t${seq++}"
        db.trainingDao().upsertTraining(TrainingEntity(id, SyncFields.new(coach, now), type, place, LocalDateTime(2026, 10, day, 9, 0), 50, status = status))
        db.trainingDao().upsertParticipation(ParticipationEntity("p$id", SyncFields.new(coach, now), id, "client", clients))
    }

    private suspend fun setUp() {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        settings.salary.finish(
            SalarySetupDraft(
                category = CoachCategory.MASTER,
                scheme = PaySchemeKind.FULL_OFFICIAL,
                advance = rub(10_000),
                schemeFrom = YearMonth(2026, 1),
                rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)))),
                services = emptyMap(),
                tiers = TierDraft(true, 101, 151, setOf(pt, typeId("Сплит"))),
            ),
            today,
        )
        repeat(3) { training(pt, bb, 15, TrainingStatus.DONE, ParticipationStatus.ATTENDED) }
        training(pt, bb, 16, TrainingStatus.DONE, ParticipationStatus.CANCELLED_CHARGED)
        training(pt, bb, 17, TrainingStatus.CANCELLED, ParticipationStatus.CANCELLED)
        training(pt, bb, 28, TrainingStatus.PLANNED, ParticipationStatus.EXPECTED)
    }

    @Test
    fun monthFromDatabase(): Unit = runBlocking {
        setUp()
        val data = months.month(october, today).first()
        val view = data.view
        assertEquals(rub(3680), view.pay.earned)
        assertEquals(rub(4600), view.pay.forecast)
        assertEquals(4, view.pay.ptCount)
        assertEquals(1, view.breakdown.size)
        assertEquals(placeId("ББ"), view.breakdown.single().locationId)
        assertEquals("ПТ", data.types[view.breakdown.single().typeId]?.shortName)
        assertNull(view.plan)
        // Полностью официально: на карту 3 680 − 13 %, аванс ожидаемый.
        assertEquals(rub(10_000), view.payout.advance)
        assertEquals(rub(3680) - 47_840, view.totals.onHand)
    }

    @Test
    fun planAdvanceAndAdjustments(): Unit = runBlocking {
        setUp()
        months.savePlan(YearMonth(2026, 9), rub(150_000))
        var view = months.month(october, today).first().view
        assertEquals(rub(150_000), view.plan)
        assertEquals(false, view.planOwn)

        months.savePlan(october, rub(200_000))
        months.saveAdvance(october, rub(9_500))
        val id = months.addAdjustment(october, rub(1200), " Судейство ", inClubAccrual = true)!!
        now += 1_000
        months.addAdjustment(october, -rub(500), "Штраф", inClubAccrual = false)
        var data = months.month(october, today).first()
        view = data.view
        assertEquals(rub(200_000), view.plan)
        assertEquals(rub(9_500), view.advanceActual)
        assertEquals(listOf("Судейство", "Штраф"), data.adjustments.map { it.comment })
        assertEquals(rub(3680 + 1200 - 500), view.pay.earned)
        assertEquals(rub(3680 + 1200), view.pay.accrued)

        months.deleteAdjustment(id)
        assertEquals(listOf("Штраф"), months.month(october, today).first().adjustments.map { it.comment })
        months.deleteAdjustment(id, restore = true)
        data = months.month(october, today).first()
        assertEquals(2, data.adjustments.size)
        // План и аванс октября — одна запись месяца.
        assertEquals(1, db.moneyDao().monthRecords().count { it.month == october })
    }
}
