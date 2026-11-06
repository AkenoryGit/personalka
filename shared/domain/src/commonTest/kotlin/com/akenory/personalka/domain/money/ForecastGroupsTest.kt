package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Прогноз групп и остаток дня (ТЗ 4.12, 5.2, шаг 5.3ш, просьба Дмитрия 06.11.2026): до начала — среднее пришедших,
 * но не больше тех, кто ещё может прийти; группа началась — только отмеченные; «будет» за сегодня — по той же
 * ступени, что «Заработано»; «будет N ПТ» за месяц — с обычной долей отмен.
 */
class ForecastGroupsTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val november = YearMonth(2026, 11)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, group)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("group", null, jan, rub(270)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt")))
    private val owners = OwnerSchemes()

    private var seq = 0
    private fun at(day: Int, hour: Int = 18, minute: Int = 0) = LocalDateTime(2026, 11, day, hour, minute)

    private fun groupOn(day: Int, status: TrainingStatus, clients: List<ParticipationStatus>, hour: Int = 18) =
        PayTraining("g${seq++}", "group", "bb", at(day, hour), 60, status, clients, groupId = "sat")

    private fun pt(day: Int, status: TrainingStatus = TrainingStatus.DONE, hour: Int = 10) =
        PayTraining("p${seq++}", "pt", "bb", at(day, hour), 50, status, listOf(if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED))

    /** Четыре проведённые группы: 8, 9, 8 и 7 детей из 12 — в среднем 8. */
    private val history = listOf(8, 9, 8, 7).mapIndexed { i, kids ->
        groupOn(i + 1, TrainingStatus.DONE, List(12) { if (it < kids) ATTENDED else CANCELLED })
    }

    private fun forecastOf(training: PayTraining, now: LocalDateTime) =
        PayRules.month(november, history + training, types, rates, tiers, owners, now = now)
            .forecastTrainings.single { it.trainingId == training.id }

    @Test
    fun beforeStartAverageNotWholeRoster() {
        val today = groupOn(6, TrainingStatus.PLANNED, List(12) { EXPECTED })
        val pay = forecastOf(today, at(6, 15))
        assertEquals(8, pay.paidClients)
        assertEquals(rub(8 * 270), pay.earned)
    }

    @Test
    fun cancelledInAdvanceLowerTheAverage() {
        // Шестеро отменены заранее («Не будет на период»): прийти могут шестеро — ждём 6, а не 8.
        val today = groupOn(6, TrainingStatus.PLANNED, List(12) { if (it < 6) CANCELLED else EXPECTED })
        assertEquals(6, forecastOf(today, at(6, 15)).paidClients)
    }

    @Test
    fun startedGroupCountsOnlyMarked() {
        // Идёт: отмечены пятеро пришедших и одна отмена со списанием, остальные ещё не отмечены — 6, а не 8.
        val clients = List(12) { i -> when { i < 5 -> ATTENDED; i == 5 -> CANCELLED_CHARGED; else -> EXPECTED } }
        val today = groupOn(6, TrainingStatus.PLANNED, clients)
        assertEquals(6, forecastOf(today, at(6, 18, 20)).paidClients)
        // До начала с теми же отметками — среднее.
        assertEquals(8, forecastOf(today, at(6, 17, 59)).paidClients)
    }

    @Test
    fun endedUnmarkedGroupCountsNothing() {
        val today = groupOn(6, TrainingStatus.PLANNED, List(12) { EXPECTED })
        val pay = forecastOf(today, at(6, 20, 19))
        assertEquals(0, pay.paidClients)
        assertEquals(0, pay.earned)
    }

    @Test
    fun todayAheadAtTheTierOfEarned() {
        // Проведено 90 ПТ (ступень 1), по расписанию ещё 20 до конца месяца — прогнозная ступень 2.
        // Сегодня ещё одна ПТ: «будет» за сегодня считает её по 920 ₽, как и «Заработано», а не по 960 ₽.
        val done = List(90) { pt(2) }
        val later = List(19) { pt(27, TrainingStatus.PLANNED) }
        val todayPt = pt(6, TrainingStatus.PLANNED, hour = 21)
        val input = MonthInput(november, done + later + todayPt, types, rates, tiers, owners, now = at(6, 20))
        val view = SalaryMonthRules.build(input, emptyList(), emptyMap(), null, LocalDate(2026, 11, 6), "pt", "bb")
        assertEquals(2, view.pay.forecastTier)
        assertEquals(rub(920), view.todayAhead)
    }

    @Test
    fun monthPtCountWithUsualCancels() {
        // Обычно отменяется каждая четвёртая: из 20 запланированных ждём 15 — «29 ПТ · будет 44», а не 49.
        val done = List(29) { pt(2) }
        val planned = List(20) { pt(27, TrainingStatus.PLANNED) }
        val pay = PayRules.month(november, done + planned, types, rates, tiers, owners, cancels = CancelShare(10, 40))
        assertEquals(49, pay.forecastPtCount)
        assertEquals(44, pay.likelyPtCount)
    }
}
