package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.client.ClientStats
import com.akenory.personalka.domain.format.ClientReportFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.TrainingStatus.CANCELLED
import com.akenory.personalka.domain.model.TrainingStatus.DONE
import com.akenory.personalka.domain.model.TrainingStatus.PLANNED
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.MonthPay
import com.akenory.personalka.domain.money.PayRate
import com.akenory.personalka.domain.money.PayTraining
import com.akenory.personalka.domain.money.PayType
import com.akenory.personalka.domain.money.TierScheme
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Отчёты → Клиент» (ТЗ 5.15, шаг 5.3к): числа клиента сходятся с его карточкой и с «Зарплатой». Клиенты придуманы. */
class ClientReportTest {

    private fun rub(r: Int): Long = r * 100L
    private val jan = LocalDate(2026, 1, 1)
    private val september = YearMonth(2026, 9)
    private val october = YearMonth(2026, 10)
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val split = PayType("split", TrainingKind.SPLIT, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true, splitFallbackTypeId = "pt")
    private val group = PayType("group", TrainingKind.GROUP, PayUnit.PER_CLIENT, countsTowardPt = false, tierDependent = false)
    private val types = listOf(pt, split, group)
    private val rates = listOf(
        PayRate("pt", "bb", jan, rub(920), rub(960), rub(1000)),
        PayRate("split", null, jan, rub(1550), rub(1600)),
        PayRate("group", null, jan, rub(270)),
    )
    private val tiers = listOf(TierScheme(jan, ptTypeIds = setOf("pt", "split")))

    /** Тренировка и участие в ней Орловой (первый клиент списка), остальные клиенты — чужие. */
    private class Row(val training: PayTraining, val mine: ClientParticipation?)

    private fun row(
        id: String,
        type: String,
        day: Int,
        status: TrainingStatus,
        mine: ParticipationStatus?,
        others: List<ParticipationStatus> = emptyList(),
        month: YearMonth = october,
        hour: Int = 10,
        owner: Owner = Owner.SELF,
        by: ConductedBy = ConductedBy.SELF,
        noticeBefore: Int? = null,
        reason: String? = null,
    ): Row {
        val start = LocalDateTime(month.year, month.month, day, hour, 0)
        val training = PayTraining(
            id, type, "bb", start, 50, status, listOfNotNull(mine) + others,
            owner = owner, ownerColleagueId = "lena".takeIf { owner == Owner.COLLEAGUE },
            conductedBy = by, colleagueId = "lena".takeIf { by != ConductedBy.SELF },
        )
        val notified = noticeBefore?.let { LocalDateTime(start.date, LocalTime.fromSecondOfDay(start.time.toSecondOfDay() - it * 60)) }
        return Row(training, mine?.let { ClientParticipation(id, it, notified, reason) })
    }

    private fun rows(): List<Row> = listOf(
        row("sep", "pt", 15, DONE, ATTENDED, month = september),
        row("pt1", "pt", 5, DONE, ATTENDED),
        row("pt2", "pt", 6, CANCELLED, CANCELLED_CHARGED, noticeBefore = 40, reason = "Не сможет"),
        row("pt3", "pt", 7, CANCELLED, ParticipationStatus.CANCELLED, noticeBefore = 180, reason = "Отъезд"),
        row("other", "pt", 8, DONE, null, others = listOf(ATTENDED)),
        row("g1", "group", 3, DONE, ATTENDED, others = List(3) { ATTENDED }),
        row("g2", "group", 10, DONE, ParticipationStatus.CANCELLED, others = List(2) { ATTENDED }),
        row("g3", "group", 17, DONE, ATTENDED, others = listOf(ATTENDED), by = ConductedBy.SELF_AND_COLLEAGUE),
        // Группа коллеги, которую вёл коллега: у клиента — «проведено», в «Зарплату» не идёт.
        row("g4", "group", 17, DONE, ATTENDED, others = listOf(ATTENDED), hour = 11, owner = Owner.COLLEAGUE, by = ConductedBy.COLLEAGUE),
        row("sp1", "split", 14, DONE, CANCELLED_CHARGED, others = listOf(ATTENDED)),
        row("now", "pt", 20, PLANNED, EXPECTED, hour = 15),
        row("next", "pt", 22, PLANNED, EXPECTED),
    )

    /** Отменённая заранее на будущее: в карточке уже «Отменено», в отчёт ещё не входит. */
    private val futureCancel = row("pt4", "pt", 27, CANCELLED, ParticipationStatus.CANCELLED, reason = "Отъезд")

    private fun months(list: List<Row>): List<Pair<MonthInput, MonthPay>> = listOf(september, october).map { m ->
        MonthInput(m, list.map { it.training }, types, rates, tiers).let { it to it.pay() }
    }

    private fun mine(list: List<Row>) = list.mapNotNull { it.mine }.associateBy { it.trainingId }

    private fun report(list: List<Row>, from: LocalDate, to: LocalDate) =
        ClientReportRules.report("kid", months(list), mine(list), from, to, now, listOf("bb"))

    @Test
    fun monthRowsByPair() {
        val list = rows() + futureCancel
        val r = report(list, october.firstDay, october.lastDay)
        assertEquals(
            listOf(
                ReportKey("pt", "bb"), ReportKey("split", "bb"),
                ReportKey("group", "bb", GroupCase.MINE_ALONE), ReportKey("group", "bb", GroupCase.MINE_TOGETHER), ReportKey("group", "bb"),
            ),
            r.rows.map { it.key },
        )
        assertEquals(ReportCounts(done = 1, charged = 1, cancelled = 1, inSalary = 2), r.row(ReportKey("pt", "bb"))!!.counts)
        // Сплит: клиент списан — тренировка оплачена и у него она «в зарплату».
        assertEquals(ReportCounts(charged = 1, inSalary = 1), r.row(ReportKey("split", "bb"))!!.counts)
        val alone = r.row(ReportKey("group", "bb", GroupCase.MINE_ALONE))!!
        assertEquals(ReportCounts(done = 1, cancelled = 1, inSalary = 1), alone.counts)
        assertEquals(1, alone.absent)
        assertEquals("пришёл 1 · не пришёл 1", ClientReportFormat.rowNote(alone))
        val theirs = r.row(ReportKey("group", "bb"))!!
        assertTrue(theirs.theirsByColleague)
        assertEquals(ReportCounts(done = 1), theirs.counts)
        assertEquals("Группа · ББ · группа коллеги, вёл коллега", ClientReportFormat.rowTitle(theirs, "Группа", "ББ"))
        assertEquals("Группа · ББ · вдвоём с коллегой", ClientReportFormat.rowTitle(r.row(ReportKey("group", "bb", GroupCase.MINE_TOGETHER))!!, "Группа", "ББ"))

        assertEquals(ReportCounts(done = 4, charged = 2, cancelled = 2, inSalary = 5), r.total)
        assertEquals("В зарплату: 5 из 8", ClientReportFormat.salaryLine(r.total))
        assertEquals(1, r.ahead)
        assertEquals(1, r.waiting)
        assertEquals(LocalDate(2026, 10, 20), r.counted)
    }

    @Test
    fun visitsByDateWithNotice() {
        val r = report(rows() + futureCancel, october.firstDay, october.lastDay)
        assertEquals(listOf("g1", "pt1", "pt2", "pt3", "g2", "sp1", "g3", "g4"), r.visits.map { it.trainingId })
        val charged = r.visits.first { it.trainingId == "pt2" }
        assertEquals(ClientOutcome.CANCELLED_CHARGED, charged.outcome)
        assertEquals(40L, charged.noticeMinutes)
        assertEquals("Не сможет · предупредили за${NBSP}40${NBSP}мин", ClientReportFormat.visitNote(charged))
        assertEquals("Отъезд · предупредили за${NBSP}3${NBSP}ч", ClientReportFormat.visitNote(r.visits.first { it.trainingId == "pt3" }))
        val absent = r.visits.first { it.trainingId == "g2" }
        assertTrue(absent.absent)
        assertEquals("Не пришёл", ClientReportFormat.visitStatus(absent))
        assertNull(ClientReportFormat.visitNote(absent))
        assertFalse(absent.inSalary)
        assertEquals("вт 06.10 · 10:00 · ПТ · ББ", ClientReportFormat.visitLine(charged, "ПТ · ББ").replace(NBSP, ' '))
        assertNull(ClientReportFormat.visitNote(r.visits.first { it.trainingId == "pt1" }))
    }

    /** Проверка шага: за всё время числа отчёта — те же, что счётчики в карточке клиента (5.9). */
    @Test
    fun sameAsClientCard() {
        val list = rows()
        val r = report(list, september.firstDay, now.date)
        val outcomes = list.mapNotNull { row ->
            row.mine?.let { row.training.start.date to ClientOutcome.of(row.training.status, it.status) }
        }
        val card = ClientStats.of(outcomes, now.date)
        assertEquals(card.done, r.total.done)
        assertEquals(card.cancelled, r.total.cancelled)
        assertEquals(card.charged, r.total.charged)
        // Сентябрь тоже вошёл: свой период из двух месяцев.
        assertEquals(5, r.total.done)
    }

    @Test
    fun emptyAndShare() {
        val list = rows()
        val empty = report(list, LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        assertTrue(empty.isEmpty)
        val r = report(list, october.firstDay, october.lastDay)
        val text = ClientReportFormat.shareText(
            "Орлова Ксения", "Октябрь 2026", r,
            rowName = { ClientReportFormat.rowTitle(it, it.key.typeId, "ББ") },
            visitName = { ClientReportFormat.rowTitle(it, it.key.typeId, "ББ") },
        )
        assertTrue(text.startsWith("Отчёт: Орлова Ксения · октябрь 2026 (по 20.10)\n"), text)
        assertTrue("pt · ББ ${RuText.DASH} проведено 1, со списанием 1, отменено 1, в зарплату 2" in text, text)
        assertTrue("В зарплату: 5 из 8" in text, text)
        assertTrue("(не сможет · предупредили за${NBSP}40${NBSP}мин)" in text, text)
    }
}
