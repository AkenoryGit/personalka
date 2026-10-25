package com.akenory.personalka.data.money

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalaryDaysRules
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
import kotlin.test.assertTrue

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

    @Test
    fun closedMonthAndChart(): Unit = runBlocking {
        setUp()
        val cards = CalendarRepository(db) { now }.cards
        assertEquals(emptySet(), cards.closedMonths.first())
        months.savePlan(october, rub(200_000))
        months.setClosed(october, true)
        assertEquals(setOf(october), cards.closedMonths.first())
        assertTrue(months.month(october, today).first().view.closed)
        // План и сверка — одна запись месяца; снятие сверки план не трогает.
        months.setClosed(october, false)
        assertEquals(emptySet(), cards.closedMonths.first())
        assertEquals(rub(200_000), months.month(october, today).first().view.plan)

        // Доплата ложится на графике в день внесения (20.10), линия кончается на «Заработано».
        months.addAdjustment(october, rub(1200), "Судейство", inClubAccrual = true)
        val view = months.month(october, today).first().view
        assertEquals(rub(3 * 920), view.chart.earned[15])
        assertEquals(rub(4 * 920), view.chart.earned[19])
        assertEquals(view.pay.earned, view.chart.earned[20])
        assertEquals(view.pay.forecast, view.chart.forecast.last())
    }

    @Test
    fun dayAndPeriod(): Unit = runBlocking {
        setUp()
        val data = months.month(october, today).first()
        // День 15.10 — три ПТ, у каждой тренировка месяца для строки «09:00 ПТ · ББ».
        val day = SalaryDaysRules.day(data.view.days, LocalDate(2026, 10, 15))
        assertEquals(3, day.size)
        assertTrue(day.all { data.trainings[it.trainingId]?.start?.hour == 9 })
        // Период через два месяца: сентябрь пуст, октябрь с 1-го по 15-е — три ПТ.
        val period = months.period(LocalDate(2026, 9, 16), LocalDate(2026, 10, 15), today).first()
        assertEquals(2, period.view.parts.size)
        assertEquals(listOf(0L, rub(3 * 920)), period.view.parts.map { it.earned })
        assertEquals(rub(3 * 920), period.view.total)
        // Весь октябрь — ровно «Заработано».
        assertEquals(data.view.pay.earned, months.period(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31), today).first().view.total)
    }

    /** «Коллеги» (3.8): взаиморасчёт из базы, схема коллеги и «Рассчитались» — запись, повтор и снятие. */
    @Test
    fun colleaguesAndSettled(): Unit = runBlocking {
        setUp()
        val group = typeId("Группа")
        val bb = placeId("ББ")
        settings.rates.saveRates(group, LocalDate(2026, 1, 1), mapOf(bb to RateAmounts(rub(270))))
        val lena = settings.colleagues.add("Лена", PaySchemeKind.HALF_OFFICIAL, today)
        val coach = db.coachDao().currentCoach()!!.id
        // Группа Лены 14.10, провёл я (замена), 8 детей: Лена отдаёт 2 160 × (1 − 0,065) = 2 019,60 ₽.
        db.trainingDao().upsertTraining(
            TrainingEntity(
                "g1", SyncFields.new(coach, now), group, bb, LocalDateTime(2026, 10, 14, 18, 0), 60, groupId = "grp",
                status = TrainingStatus.DONE, owner = Owner.COLLEAGUE, ownerColleagueId = lena,
            ),
        )
        repeat(8) { db.trainingDao().upsertParticipation(ParticipationEntity("pg$it", SyncFields.new(coach, now), "g1", "c$it", ParticipationStatus.ATTENDED)) }

        val data = months.month(october, today).first()
        val row = data.view.colleagues.single()
        assertEquals(lena, row.colleagueId)
        assertEquals(rub(2019) + 60, row.total)
        assertEquals(GroupCase.THEIRS_BY_ME, row.cases.single().case)
        assertEquals(PaySchemeKind.HALF_OFFICIAL, data.colleagueSchemes[lena])
        assertNull(row.settled)

        months.settle(october, lena, row.total)
        assertTrue(months.month(october, today).first().view.colleagues.single().isSettled)
        months.settle(october, lena, row.total)
        assertEquals(1, db.moneyDao().observeSettlements(october).first().size, "Повтор не заводит вторую запись")
        months.settle(october, lena, null)
        assertNull(months.month(october, today).first().view.colleagues.single().settled, "Отметка снята")
    }
}
