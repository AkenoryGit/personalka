package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.SettlementFormat
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Вкладка «Коллеги» (ТЗ 5.12, 4.8): цифры октября из макета PayColleagues — «Лена отдаёт вам 883,58 ₽». */
class SettlementsTest {

    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val from = LocalDate(2026, 1, 1)
    private fun rub(r: Int): Long = r * 100L
    private val october = YearMonth(2026, 10)
    private val owners = OwnerSchemes(
        self = listOf(OwnerScheme(from, officialShareBp = 5000, ndflBp = 1300)),
        colleagues = mapOf("lena" to listOf(OwnerScheme.of(from, PaySchemeKind.HALF_OFFICIAL))),
    )

    private var seq = 0
    private fun t(
        day: Int,
        children: Int,
        owner: Owner,
        by: ConductedBy,
        groupId: String,
        status: TrainingStatus = TrainingStatus.DONE,
    ) = PayTraining(
        "t${seq++}", "group", "bb", LocalDateTime(2026, 10, day, 9, 0), 45, status,
        List(children) { if (status == TrainingStatus.PLANNED) EXPECTED else ATTENDED },
        owner = owner,
        ownerColleagueId = "lena".takeIf { owner == Owner.COLLEAGUE },
        conductedBy = by,
        colleagueId = "lena".takeIf { by != ConductedBy.SELF },
        groupId = groupId,
    )

    // Суббота 9:00 — моя группа вместе с Леной; понедельник — группа Лены вместе; 14.10 — замена у Лены.
    private val october2026 = listOf(
        t(3, 11, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat"),
        t(10, 12, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat"),
        t(17, 10, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat"),
        t(5, 7, Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE, "mon"),
        t(12, 8, Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE, "mon"),
        t(19, 9, Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE, "mon"),
        t(14, 8, Owner.COLLEAGUE, ConductedBy.SELF, "wed"),
        t(16, 12, Owner.SELF, ConductedBy.SELF, "fri"),
        t(15, 9, Owner.COLLEAGUE, ConductedBy.COLLEAGUE, "thu"),
    )

    private fun input(trainings: List<PayTraining>) = MonthInput(
        october, trainings, listOf(group), listOf(PayRate("group", "bb", from, rub(270))), emptyList(), owners,
    )

    private fun colleagues(trainings: List<PayTraining> = october2026, settled: Map<String, Long> = emptyMap()): List<ColleagueSettlement> {
        val input = input(trainings)
        return SettlementRules.colleagues(input, input.pay(), settled)
    }

    @Test
    fun octoberWithLenaAsInMockup() {
        val lena = colleagues().single()
        assertEquals("lena", lena.colleagueId)
        assertEquals(88358, lena.total, "Лена отдаёт вам 883,58 ₽")
        assertEquals(
            listOf(GroupCase.MINE_TOGETHER, GroupCase.THEIRS_TOGETHER, GroupCase.THEIRS_BY_ME),
            lena.cases.map { it.case },
            "Только случаи с коллегой: моя группа без неё и группа, которую Лена вела одна, сюда не попадают",
        )
        val (mine, theirs, replaced) = lena.cases
        assertEquals(-416543, mine.amount)
        assertEquals(listOf(-138848L, -151470L, -126225L), mine.trainings.map { it.amount }, "По каждой тренировке, половина вверх")
        assertEquals(listOf(297000L, 324000L, 270000L), mine.trainings.map { it.gross })
        assertEquals(33, mine.children)
        assertEquals(302941, theirs.amount)
        assertEquals(201960, replaced.amount)
        assertEquals(lena.total, lena.cases.sumOf { it.amount }, "Случаи складываются в итог")
        assertEquals("½ × (1 − 0,065) от каждой", SettlementFormat.formula(mine))
        assertEquals("(1 − 0,065) от каждой", SettlementFormat.formula(replaced))
        assertEquals("Лена отдаёт вам 883,58 ₽", SettlementFormat.total("Лена", lena.total))
    }

    @Test
    fun forecastSettledAndChangedSince() {
        val planned = october2026 + t(24, 12, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat", TrainingStatus.PLANNED) +
            t(31, 12, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat", TrainingStatus.PLANNED)
        val lena = colleagues(planned).single()
        assertEquals(88358, lena.total, "Запланированные в итог по проведённым не идут")
        assertEquals(88358 - 138848 - 138848, lena.forecast, "Две субботы вместе: ждём по 11 детей — среднее прошлых суббот (4.12)")
        assertEquals("По расписанию к 31.10 — вы отдаёте Лене ≈ 1 890 ₽", SettlementFormat.forecast(october, "Лена", lena.forecast))

        val settled = colleagues(settled = mapOf("lena" to 88358L)).single()
        assertTrue(settled.isSettled)
        assertNull(settled.changedSince)
        val changed = colleagues(october2026 + t(20, 8, Owner.COLLEAGUE, ConductedBy.SELF, "wed"), mapOf("lena" to 88358L)).single()
        assertFalse(changed.isSettled, "После отметки добавилась замена — отметка устарела")
        assertEquals(201960, changed.changedSince)
    }

    @Test
    fun colleagueOnlyWithPlannedOrMarkIsShown() {
        val onlyPlanned = listOf(t(31, 10, Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE, "sat", TrainingStatus.PLANNED))
        val lena = colleagues(onlyPlanned).single()
        assertEquals(0, lena.total)
        assertTrue(lena.cases.isEmpty())
        assertTrue(lena.forecast < 0)
        assertEquals(listOf("lena"), colleagues(emptyList(), mapOf("lena" to 100L)).map { it.colleagueId })
        assertTrue(colleagues(emptyList()).isEmpty())
    }

    @Test
    fun shareTextHasDatesChildrenAndSumsOnly() {
        val lena = colleagues().single()
        val text = SettlementFormat.shareText(october, lena) { "Группа · ББ" }
        assertTrue(text.startsWith("Взаиморасчёт за октябрь 2026\n"), text)
        assertTrue("Моя группа, вели вместе (Группа · ББ) — я отдаю тебе 4 165,43 ₽" in text, text)
        assertTrue("сб 03.10 09:00 · 11 детей · группа 2 970 ₽ → 1 388,48 ₽" in text, text)
        assertTrue("Твоя группа, замена (Группа · ББ) — ты отдаёшь мне 2 019,60 ₽" in text, text)
        assertTrue(text.endsWith("Итого: ты отдаёшь мне 883,58 ₽"), text)
    }

    @Test
    fun taxShareText() {
        assertEquals("0,065", SettlementFormat.taxShare(6_500_000))
        assertEquals("0,13", SettlementFormat.taxShare(13_000_000))
        assertEquals("1", SettlementFormat.taxShare(100_000_000))
    }
}
