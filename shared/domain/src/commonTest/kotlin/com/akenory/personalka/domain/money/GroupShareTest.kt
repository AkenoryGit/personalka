package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
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

/** Группы, мини-группы, дежурства и деление групп с коллегами (ТЗ 4.7, 4.8): примеры 8–16 ТЗ 12. */
class GroupShareTest {

    // Ставки Мастер тренера (ТЗ 3.3): группа · ББ 270 ₽ за ребёнка, мини-группа · ББ 530 ₽, дежурство 200 ₽ в час.
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val mini = PayType("mini", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val types = listOf(group, mini, duty)

    private val from = LocalDate(2026, 1, 1)
    private fun rub(r: Int): Long = r * 100L
    private val rates = listOf(
        PayRate("group", "bb", from, rub(270)),
        PayRate("mini", "bb", from, rub(530)),
        PayRate("duty", null, from, rub(200)),
    )
    private val october = YearMonth(2026, 10)

    // Я и Лена — половина официально (w = 0,065), если не сказано иное.
    private val halfOfficial = OwnerSchemes(
        self = listOf(OwnerScheme(from, officialShareBp = 5000, ndflBp = 1300)),
        colleagues = mapOf("lena" to listOf(OwnerScheme.of(from, PaySchemeKind.HALF_OFFICIAL))),
    )

    private var seq = 0
    private fun t(
        type: String = "group",
        children: Int = 1,
        owner: Owner = Owner.SELF,
        by: ConductedBy = ConductedBy.SELF,
        minutes: Int = 45,
        day: Int = 15,
        status: TrainingStatus = TrainingStatus.DONE,
    ) = PayTraining(
        "t${seq++}", type, "bb", LocalDateTime(2026, 10, day, 18, 0), minutes, status, List(children) { ATTENDED },
        owner = owner,
        ownerColleagueId = "lena".takeIf { owner == Owner.COLLEAGUE },
        conductedBy = by,
        colleagueId = "lena".takeIf { by != ConductedBy.SELF },
    )

    private fun single(training: PayTraining, owners: OwnerSchemes = halfOfficial) =
        PayRules.pay(training, types.associateBy { it.id }, rates, tier = 1, owners = owners)

    private fun month(trainings: List<PayTraining>) =
        PayRules.month(october, trainings, types, rates, emptyList(), halfOfficial)

    @Test
    fun example8_myGroupAlone() {
        val pay = single(t(children = 12))
        assertEquals(rub(3240), pay.amount)
        assertEquals(rub(3240), pay.earned)
        assertEquals(rub(3240), pay.accrued)
        assertEquals(0, pay.settlement)
        assertEquals(null, pay.colleagueId)
    }

    @Test
    fun example9_myGroupTogetherWithLena() {
        val pay = single(t(children = 12, by = ConductedBy.SELF_AND_COLLEAGUE))
        assertEquals(rub(1620), pay.earned)
        assertEquals(rub(3240), pay.accrued)
        // Я отдаю Лене 3 240 × 0,935 / 2 = 1 514,70 ₽.
        assertEquals(-151_470, pay.settlement)
        assertEquals("lena", pay.colleagueId)
    }

    @Test
    fun example10_lenasGroupTogether() {
        val pay = single(t(children = 6, owner = Owner.COLLEAGUE, by = ConductedBy.SELF_AND_COLLEAGUE))
        assertEquals(rub(810), pay.earned)
        assertEquals(0, pay.accrued)
        // Лена отдаёт мне 1 620 × 0,935 / 2 = 757,35 ₽.
        assertEquals(75_735, pay.settlement)
        assertEquals("lena", pay.colleagueId)
    }

    @Test
    fun example11_lenaIsIp() {
        val ip = halfOfficial.copy(colleagues = mapOf("lena" to listOf(OwnerScheme.of(from, PaySchemeKind.IP))))
        val pay = single(t(children = 6, owner = Owner.COLLEAGUE, by = ConductedBy.SELF_AND_COLLEAGUE), ip)
        assertEquals(rub(810), pay.settlement)
    }

    @Test
    fun example12_lenasGroupIAlone() {
        val pay = single(t(children = 8, owner = Owner.COLLEAGUE, by = ConductedBy.SELF))
        assertEquals(rub(2160), pay.earned)
        assertEquals(0, pay.accrued)
        // 2 160 × 0,935 = 2 019,60 ₽.
        assertEquals(201_960, pay.settlement)
    }

    @Test
    fun example13_myGroupLenaAlone() {
        val pay = single(t(children = 10, by = ConductedBy.COLLEAGUE))
        assertEquals(0, pay.earned)
        assertEquals(rub(2700), pay.accrued)
        // 2 700 × 0,935 = 2 524,50 ₽ — w по моей схеме.
        assertEquals(-252_450, pay.settlement)
    }

    @Test
    fun example14_monthSettlementWithLena() {
        val m = month(
            listOf(
                t(children = 12, by = ConductedBy.SELF_AND_COLLEAGUE),
                t(children = 6, owner = Owner.COLLEAGUE, by = ConductedBy.SELF_AND_COLLEAGUE),
                t(children = 8, owner = Owner.COLLEAGUE, by = ConductedBy.SELF),
            ),
        )
        // Лена должна мне 757,35 + 2 019,60 − 1 514,70 = 1 262,25 ₽.
        assertEquals(mapOf("lena" to 126_225L), m.settlements)
        assertEquals(rub(1620 + 810 + 2160), m.earned)
        assertEquals(rub(3240), m.accrued)
        assertEquals(12 + 6 + 8, m.groupChildren)
    }

    @Test
    fun example15_miniGroup() {
        assertEquals(rub(1590), single(t(type = "mini", children = 3)).amount)
    }

    @Test
    fun example16_duties() {
        val m = month(listOf(t(type = "duty", children = 0, minutes = 240), t(type = "duty", children = 0, minutes = 150)))
        assertEquals(listOf(rub(800), rub(500)), m.trainings.map { it.amount })
        assertEquals(rub(1300), m.earned)
        assertEquals(390, m.dutyMinutes)
    }

    @Test
    fun ownerSchemeIsTakenOnTrainingDay() {
        // До 20.10 Лена — ИП, с 20.10 — половина официально.
        val owners = halfOfficial.copy(
            colleagues = mapOf(
                "lena" to listOf(
                    OwnerScheme.of(from, PaySchemeKind.IP),
                    OwnerScheme.of(LocalDate(2026, 10, 20), PaySchemeKind.HALF_OFFICIAL),
                ),
            ),
        )
        val before = single(t(children = 8, owner = Owner.COLLEAGUE, day = 19), owners)
        val after = single(t(children = 8, owner = Owner.COLLEAGUE, day = 20), owners)
        assertEquals(rub(2160), before.settlement)
        assertEquals(201_960, after.settlement)
        // Схемы нет на дату — w = 0; полностью официально — 0,13.
        assertEquals(0, PayRules.ownerTax(t(), OwnerSchemes()))
        val full = OwnerSchemes(self = listOf(OwnerScheme.of(from, PaySchemeKind.FULL_OFFICIAL)))
        assertEquals(rub(2700) * 87 / 100, single(t(children = 10, by = ConductedBy.COLLEAGUE), full).settlement * -1)
    }

    @Test
    fun groupChildrenAreOnlyPaidAndOnlyWhereIConducted() {
        val withCancels = t(children = 0).copy(clients = listOf(ATTENDED, CANCELLED_CHARGED, CANCELLED))
        val m = month(listOf(withCancels, t(children = 10, by = ConductedBy.COLLEAGUE), t(type = "mini", children = 3)))
        assertEquals(2 + 3, m.groupChildren)
        val cancelledDuty = t(type = "duty", children = 0, minutes = 240, status = TrainingStatus.CANCELLED)
        assertEquals(0, month(listOf(cancelledDuty)).dutyMinutes)
    }

    @Test
    fun forecastUsesShareToo() {
        val planned = t(children = 0, by = ConductedBy.SELF_AND_COLLEAGUE, status = TrainingStatus.PLANNED)
            .copy(clients = List(12) { EXPECTED })
        val m = month(listOf(planned))
        assertEquals(0, m.earned)
        assertEquals(rub(1620), m.forecast)
    }
}
