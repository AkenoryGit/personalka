package com.akenory.personalka.domain.report1c

import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Сверка на присланном отчёте 1С (ТЗ 8, шаг 4.2): ручная сверка за 1–17 октября 2026 нашла 3 расхождения статусов —
 * трое детей отмечены в приложении пришедшими, а в 1С «Отменил». Приложение здесь — те же тренировки, что в отчёте.
 */
class Reconcile1cFileTest {
    private val report = assertIs<Report1cRead.Read>(
        Report1cParser.parse(assertNotNull(javaClass.getResourceAsStream("/report1c/otchet-oktyabr-2026.pdf")).readBytes()),
    ).report

    private val services = mapOf(
        "Персональная тренировка. ББ" to ServiceTarget("pt", "bb"),
        "Персональная тренировка. Детский бассейн 45 минут" to ServiceTarget("pt", "db"),
        "Детская групповая тренировка в бассейне 50 минут" to ServiceTarget("group", "db"),
        "Мини-группа ББ" to ServiceTarget("mini", "bb"),
        "Сплит. ББ" to ServiceTarget("split", "bb"),
        "Стартовая тренировка бассейн" to ServiceTarget("spt", "db"),
    )
    private val kinds = mapOf(
        "pt" to TrainingKind.PERSONAL, "group" to TrainingKind.GROUP, "mini" to TrainingKind.GROUP,
        "split" to TrainingKind.SPLIT, "spt" to TrainingKind.SPT,
    )
    private val clients = report.rows.map { it.client }.distinctBy(Names1c::key).mapIndexed { i, name ->
        Client1c("c$i", assertNotNull(Reconcile1cRules.nameOf(name)))
    }

    /** Тренировки приложения — как в отчёте; [attended] — кого тренер отметил пришедшим вопреки 1С. */
    private fun app(attended: Set<Pair<String, LocalDateTime>>): List<AppVisit> = report.rows.mapIndexed { i, row ->
        val target = services.getValue(row.serviceName)
        val client = clients.single { Names1c.key(it.name.short) == Names1c.key(row.client) }.id
        val status = if (Names1c.key(row.client) to row.at in attended) ParticipationStatus.ATTENDED else Reconcile1cRules.appStatus(row.status)
        AppVisit("t$i", client, row.at, target.typeId, target.locationId, kinds.getValue(target.typeId), Owner.SELF, status)
    }

    private fun compare(visits: List<AppVisit>, until: LocalDateTime): Reconcile1c {
        val clientOf = Reconcile1cRules.names(report, emptyMap(), clients).mapValues { (_, v) -> (v.second as NameMatch.Found).clientId }
        val period = report.copy(rows = report.rows.filter { it.at < until })
        return Reconcile1cRules.compare(period, Reconcile1cRules.visitsToCheck(visits, period, until), clientOf, services)
    }

    @Test
    fun threeKnownDiffsInFirstHalfOfOctober() {
        val wrong = setOf(
            "пестов егор" to LocalDateTime(2026, 10, 8, 9, 0),
            "орехов глеб" to LocalDateTime(2026, 10, 10, 9, 0),
            "чернова вера" to LocalDateTime(2026, 10, 13, 9, 0),
        )
        val result = compare(app(wrong), LocalDateTime(2026, 10, 18, 0, 0))
        assertEquals(3, result.diffs.size)
        assertTrue(result.diffs.all { it.kind == ReconcileItemKind.STATUS_DIFF && it.statusDiffers && !it.serviceDiffers })
        assertEquals(wrong, result.diffs.map { Names1c.key(it.row!!.client) to it.row.at }.toSet())
        assertEquals(203 - 3, result.matched)
        assertEquals("Совпало 200 · Расхождений 3", com.akenory.personalka.domain.format.ReconcileFormat.summary(result))
    }

    @Test
    fun wholeReportMatchesItself() {
        val result = compare(app(emptySet()), LocalDateTime(2026, 10, 26, 0, 0))
        assertEquals(0, result.diffs.size)
        assertEquals(289, result.matched)
    }

    @Test
    fun everyNameIsFoundAndEveryServiceAsked() {
        assertEquals(76, clients.size)
        assertEquals(services.keys.toList().sorted(), Reconcile1cRules.unknownServices(report, emptySet()).sorted())
        assertEquals(emptyList(), Reconcile1cRules.unknownServices(report, services.keys))
        assertEquals(LocalDate(2026, 10, 31), report.to)
    }
}
