package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Кабинет родителя (ТЗ 6.3) на данных макета 0.7: «сейчас» — вт 20.10.2026, 15:41. */
class CabinetRulesTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val now = LocalDateTime(2026, 10, 20, 15, 41)

    private fun pt(
        start: String,
        status: TrainingStatus = TrainingStatus.PLANNED,
        participation: ParticipationStatus = ParticipationStatus.EXPECTED,
        plan: String? = null,
        fact: String? = null,
        behavior: String? = null,
        reason: String? = null,
        notified: String? = null,
    ) = CabinetTraining(
        start = LocalDateTime.parse(start),
        typeName = "Персональная",
        placeName = "Большой бассейн",
        trainingStatus = status,
        participationStatus = participation,
        plan = plan,
        fact = fact,
        behavior = behavior,
        showBehavior = true,
        cancelReason = reason,
        notifiedAt = notified?.let(LocalDateTime::parse),
    )

    private fun done(start: String, plan: String? = null, fact: String? = null, behavior: String? = null) =
        pt(start, TrainingStatus.DONE, ParticipationStatus.ATTENDED, plan, fact, behavior)

    @Test
    fun upcomingTakesPlannedAndCancelledInBetween() {
        val list = listOf(
            pt("2026-10-22T13:30", plan = "Старт с тумбочки"),
            pt("2026-10-27T13:30"),
            pt("2026-10-29T13:30", TrainingStatus.CANCELLED, reason = "По инициативе клуба: санитарный день"),
            pt("2026-11-03T13:30"),
            pt("2026-11-05T13:30"),
            pt("2026-11-10T13:30"),
            pt("2026-11-12T13:30", TrainingStatus.CANCELLED, reason = "Отъезд"),
        )
        val upcoming = CabinetRules.upcoming(list, now, 4)
        assertEquals(
            listOf("2026-10-22T13:30", "2026-10-27T13:30", "2026-10-29T13:30", "2026-11-03T13:30", "2026-11-05T13:30"),
            upcoming.map { it.start.toString() },
        )
        // Меньше запланированных, чем в настройке, — видны все будущие отмены.
        assertEquals(4, CabinetRules.upcoming(list.drop(3), now, 4).size)
        assertEquals(5, CabinetRules.upcoming(list.drop(2), now, 10).size)
    }

    @Test
    fun upcomingShowsPlanOnlyAtFirst() {
        val page = CabinetRules.page(input(listOf(pt("2026-10-22T13:30", plan = "Старт"), pt("2026-10-27T13:30", plan = "Повороты"))))
        assertEquals(listOf(CabinetFormat.PLAN to "Старт"), page.upcoming[0].fields)
        assertEquals(emptyList(), page.upcoming[1].fields)
        assertNull(page.upcoming[0].status)
        assertEquals(s("чт_22.10 · 13:30"), page.upcoming[0].whenText)
        assertEquals("Персональная · Большой бассейн", page.upcoming[0].typePlace)
    }

    @Test
    fun cancelledAheadIsGrayWithReason() {
        val item = CabinetRules.item(pt("2026-10-31T09:00", TrainingStatus.CANCELLED, reason = "По инициативе клуба: санитарный день"), 1)
        assertEquals(ClientOutcome.CANCELLED, item.status)
        assertEquals("По инициативе клуба: санитарный день", item.note)
        assertEquals(emptyList(), item.fields)
    }

    @Test
    fun chargedCancelShowsNotice() {
        val item = CabinetRules.item(
            pt("2026-10-13T13:30", participation = ParticipationStatus.CANCELLED_CHARGED, reason = "Не сможет", notified = "2026-10-13T12:50"),
        )
        assertEquals(ClientOutcome.CANCELLED_CHARGED, item.status)
        assertEquals(s("Не сможет · предупредили за_40_мин"), item.note)
        val noReason = CabinetRules.item(pt("2026-10-13T13:30", participation = ParticipationStatus.CANCELLED_CHARGED, notified = "2026-10-13T13:35"))
        assertEquals("Предупредили после начала", noReason.note)
    }

    @Test
    fun historyShowsOnlyFilledFieldsOfType() {
        val item = CabinetRules.item(done("2026-10-15T13:30", plan = "Кроль", fact = " ", behavior = "Стеснялась прыгать"))
        assertEquals(listOf(CabinetFormat.PLAN to "Кроль", CabinetFormat.BEHAVIOR to "Стеснялась прыгать"), item.fields)
        val group = CabinetRules.item(done("2026-10-17T09:00", plan = "Ныряние", behavior = "Шумел").copy(showBehavior = false))
        assertEquals(listOf(CabinetFormat.PLAN to "Ныряние"), group.fields)
    }

    @Test
    fun countersAndHistoryByMonth() {
        val list = listOf(
            done("2026-10-20T13:30"),
            done("2026-10-15T13:30"),
            pt("2026-10-13T13:30", participation = ParticipationStatus.CANCELLED_CHARGED),
            pt("2026-10-08T13:30", participation = ParticipationStatus.CANCELLED, reason = "Отъезд"),
            done("2026-09-29T13:30"),
            done("2026-09-24T13:30"),
            pt("2026-09-22T13:30", participation = ParticipationStatus.CANCELLED),
            done("2026-08-27T13:30"),
            // Будущая отмена — в «Ближайших», но не в счётчиках.
            pt("2026-10-29T13:30", TrainingStatus.CANCELLED),
            pt("2026-10-22T13:30"),
        )
        val page = CabinetRules.page(input(list))
        assertEquals("Октябрь", page.monthColumn)
        assertEquals(
            listOf(
                CabinetCounter(ClientOutcome.DONE, 2, 5),
                CabinetCounter(ClientOutcome.CANCELLED, 1, 2),
                CabinetCounter(ClientOutcome.CANCELLED_CHARGED, 1, 1),
            ),
            page.counters,
        )
        assertEquals(s("Октябрь_2026"), page.currentMonth!!.title)
        assertEquals(s("4_тренировки"), page.currentMonth!!.count)
        assertEquals(listOf(s("вт_20.10 · 13:30"), s("чт_15.10 · 13:30"), s("вт_13.10 · 13:30"), s("чт_08.10 · 13:30")), page.currentMonth!!.items.map { it.whenText })
        assertEquals(s("4_тренировки"), page.earlier!!.count)
        assertEquals(listOf(s("Сентябрь_2026") to s("3_тренировки"), s("Август_2026") to s("1_тренировка")), page.earlier!!.months.map { it.title to it.count })
    }

    @Test
    fun emptyClientHasNoSections() {
        val page = CabinetRules.page(input(emptyList()))
        assertEquals(emptyList(), page.upcoming)
        assertEquals(emptyList(), page.counters)
        assertNull(page.currentMonth)
        assertNull(page.earlier)
    }

    @Test
    fun headerAndRule() {
        val page = CabinetRules.page(input(emptyList()).copy(cancelNoticeMinutes = 90))
        assertEquals("Орлова Ксения", page.clientName)
        assertEquals("Тренер: Дмитрий Дудник", page.coachLine)
        assertEquals("Данные на 20.10.2026, 15:41", page.dataLine)
        assertEquals(
            s("Если вы предупреждаете об отмене меньше чем за 1_час 30_минут до начала, тренировка считается проведённой и списывается."),
            page.rule,
        )
        assertNull(CabinetRules.page(input(emptyList()).copy(syncedAt = null)).dataLine)
        assertEquals(s("Неверный код. Осталось 3_попытки."), CabinetFormat.wrongCode(3))
    }

    private fun input(trainings: List<CabinetTraining>) = CabinetInput(
        clientName = "Орлова Ксения",
        coachName = "Дмитрий Дудник",
        syncedAt = now,
        now = now,
        upcomingCount = 4,
        cancelNoticeMinutes = 60,
        trainings = trainings,
    )
}
