package com.akenory.personalka.domain.report1c

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Reconcile1cRulesTest {
    private val pt = "Персональная тренировка. ББ"
    private val group = "Детская групповая тренировка в бассейне 50 минут"
    private val services = mapOf(pt to ServiceTarget("pt", "bb"), group to ServiceTarget("group", "db"))

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m)

    private fun row(at: LocalDateTime, client: String = "Пестов Егор Романович", status: Status1c = Status1c.CAME, service: String = pt) =
        Row1c(at, if (service == pt) Service1c.PERSONAL else Service1c.GROUP, service, client, status, 1, 1, 0)

    private fun visit(
        id: String,
        at: LocalDateTime,
        status: ParticipationStatus = ATTENDED,
        client: String = "egor",
        type: String = "pt",
        place: String = "bb",
        kind: TrainingKind = TrainingKind.PERSONAL,
        owner: Owner = Owner.SELF,
    ) = AppVisit(id, client, at, type, place, kind, owner, status)

    private fun report(vararg rows: Row1c) = Report1c("Тренеров Иван Петрович", LocalDate(2026, 10, 1), LocalDate(2026, 10, 31), rows.toList(), rows.size, null)

    private fun compare(report: Report1c, vararg visits: AppVisit) =
        Reconcile1cRules.compare(report, visits.toList(), mapOf("пестов егор" to "egor", "орехова ева" to "eva"), services)

    @Test
    fun names() {
        val clients = listOf(
            Client1c("egor", ClientName("Пестов", "Егор", "Романович")),
            Client1c("max1", ClientName("Пестов", "Максим")),
            Client1c("max2", ClientName("Пестов", "Максим", "Ильич")),
            Client1c("ada", ClientName("Дельфенурова", "Аделина")),
            Client1c("fedor", ClientName("Родионов", "Федор")),
        )
        assertEquals(NameMatch.Found("egor"), Reconcile1cRules.match("ПЕСТОВ  Егор Иванович", emptyMap(), clients))
        assertEquals(NameMatch.Found("fedor"), Reconcile1cRules.match("Родионов Фёдор Андреевич", emptyMap(), clients))
        assertEquals(NameMatch.Ask(listOf("max1", "max2")), Reconcile1cRules.match("Пестов Максим Ильич", emptyMap(), clients))
        assertEquals(NameMatch.Found("max2"), Reconcile1cRules.match("Пестов Максим Ильич", mapOf("пестов максим" to "max2"), clients))
        assertEquals(NameMatch.Ask(listOf("ada")), Reconcile1cRules.match("Дельфинурова Аделина", emptyMap(), clients))
        assertEquals(NameMatch.Unknown, Reconcile1cRules.match("Орехова Ева", emptyMap(), clients))
        // Связь на удалённого клиента не действует — ищется заново.
        assertEquals(NameMatch.Found("egor"), Reconcile1cRules.match("Пестов Егор", mapOf("пестов егор" to "gone"), clients))
        val names = Reconcile1cRules.names(report(row(at(1, 8)), row(at(2, 8), "Пёстов Егор")), emptyMap(), clients)
        assertEquals(listOf("пестов егор"), names.keys.toList())
    }

    @Test
    fun nearestTrainingWithinHourAndHalf() {
        val r = compare(
            report(row(at(8, 9)), row(at(8, 18))),
            visit("a", at(8, 10, 30)), visit("b", at(8, 9, 15)), visit("c", at(8, 19, 31)),
        )
        // 09:00 ↔ 09:15 (ближе, чем 10:30); 18:00 и 19:31 — дальше 90 минут.
        assertEquals(1, r.matched)
        assertEquals(listOf(ReconcileItemKind.ONLY_APP, ReconcileItemKind.ONLY_1C, ReconcileItemKind.ONLY_APP), r.diffs.map { it.kind })
        assertEquals(listOf("a", "c"), r.onlyInApp.map { it.visit!!.trainingId })
        assertEquals(at(8, 18), r.onlyIn1c.single().row!!.at)
    }

    @Test
    fun otherDayIsNoPair() {
        val r = compare(report(row(at(8, 23, 30))), visit("a", at(9, 0, 10)))
        assertEquals(2, r.diffs.size)
    }

    @Test
    fun statuses() {
        assertTrue(Reconcile1cRules.sameStatus(Status1c.CAME, ATTENDED))
        assertTrue(Reconcile1cRules.sameStatus(Status1c.CAME, CANCELLED_CHARGED))
        assertTrue(Reconcile1cRules.sameStatus(Status1c.CANCELLED, CANCELLED))
        assertTrue(Reconcile1cRules.sameStatus(Status1c.NO_SHOW, CANCELLED))
        assertTrue(!Reconcile1cRules.sameStatus(Status1c.CANCELLED, CANCELLED_CHARGED))
        assertTrue(!Reconcile1cRules.sameStatus(Status1c.CAME, EXPECTED))
        assertTrue(!Reconcile1cRules.sameStatus(Status1c.CANCELLED, EXPECTED))
        val r = compare(
            report(row(at(8, 9), status = Status1c.CANCELLED, service = group), row(at(9, 9)), row(at(10, 9))),
            visit("g", at(8, 9), type = "group", place = "db", kind = TrainingKind.GROUP),
            visit("e", at(9, 9), EXPECTED),
            visit("s", at(10, 9), type = "split"),
        )
        assertEquals(listOf("g", "e", "s"), r.differ.map { it.visit!!.trainingId })
        assertEquals(listOf(true, true, false), r.differ.map { it.statusDiffers })
        assertEquals(listOf(false, false, true), r.differ.map { it.serviceDiffers })
    }

    @Test
    fun cancelsWithoutPairAreNotDiffs() {
        val r = compare(
            report(row(at(8, 9), status = Status1c.CANCELLED), row(at(9, 9), status = Status1c.NO_SHOW)),
            visit("a", at(12, 9), CANCELLED),
            visit("b", at(13, 9), CANCELLED_CHARGED),
        )
        assertEquals(3, r.matched)
        assertEquals(listOf("b"), r.onlyInApp.map { it.visit!!.trainingId })
    }

    @Test
    fun unknownNameOrService() {
        val r = compare(report(row(at(8, 9), "Иванов Иван"), row(at(9, 9), service = "Аквааэробика")), visit("a", at(9, 9)))
        assertEquals(listOf(ReconcileItemKind.UNKNOWN_CLIENT, ReconcileItemKind.ONLY_APP, ReconcileItemKind.UNKNOWN_SERVICE), r.diffs.map { it.kind })
        assertEquals(3, r.onlyIn1c.size + r.onlyInApp.size)
        assertEquals(listOf("Аквааэробика"), Reconcile1cRules.unknownServices(report(row(at(8, 9)), row(at(9, 9), service = "Аквааэробика")), services.keys))
    }

    @Test
    fun whatIsChecked() {
        val rep = report(row(at(1, 9)))
        val until = Reconcile1cRules.until(rep, LocalDateTime(2026, 10, 18, 10, 48, 41), at(26, 12))
        assertEquals(LocalDateTime(2026, 10, 18, 10, 48, 41), until)
        assertEquals(LocalDateTime(2026, 10, 31, 23, 59, 59), Reconcile1cRules.until(rep, null, LocalDateTime(2026, 11, 3, 9, 0)))
        val kept = Reconcile1cRules.visitsToCheck(
            listOf(
                visit("ok", at(18, 9)),
                visit("later", at(18, 11)),
                visit("duty", at(5, 9), kind = TrainingKind.DUTY),
                visit("lena", at(5, 9), kind = TrainingKind.GROUP, owner = Owner.COLLEAGUE),
                visit("sept", LocalDateTime(2026, 9, 30, 9, 0)),
            ),
            rep, until,
        )
        assertEquals(listOf("ok"), kept.map { it.trainingId })
    }

    @Test
    fun repeatCheckClosesFixed() {
        val first = compare(report(row(at(8, 9), status = Status1c.CANCELLED), row(at(9, 9)), row(at(10, 9))), visit("a", at(8, 9)), visit("c", at(11, 9)))
        val keys = first.diffs.map { it.key }
        // Статус 08.10, только в 1С 09.10 и 10.10, только в приложении 11.10.
        assertEquals(4, keys.size)
        val previous = mapOf(keys[0] to ReconcileResolution.ASKED_ADMIN, keys[1] to ReconcileResolution.IGNORED, keys[2] to ReconcileResolution.OPEN)
        // Отметили «a» отменой — расхождение статуса ушло; остальное — как было.
        val second = compare(report(row(at(8, 9), status = Status1c.CANCELLED), row(at(9, 9)), row(at(10, 9))), visit("a", at(8, 9), CANCELLED), visit("c", at(11, 9)))
        val carry = Reconcile1cRules.carry(previous, second.diffs)
        assertEquals(setOf(keys[0]), carry.closed)
        assertEquals(mapOf(keys[1] to ReconcileResolution.IGNORED, keys[2] to ReconcileResolution.OPEN, keys[3] to ReconcileResolution.OPEN), carry.resolutions)
    }

    @Test
    fun adminLines() {
        val r = compare(
            report(row(at(8, 9), status = Status1c.CANCELLED, service = group), row(at(9, 9))),
            visit("g", at(8, 9), type = "group", place = "db", kind = TrainingKind.GROUP),
            visit("s", at(9, 9), type = "split"),
            visit("x", at(11, 18, 30), client = "eva", status = CANCELLED_CHARGED),
        )
        val (g, s) = r.differ
        assertEquals(
            "08.10 09:00, группа, Пестов Егор — был на тренировке, в 1С «Отменил». Прошу исправить",
            ReconcileFormat.adminLine(g, "группа", "Пестов Егор", Sex.MALE),
        )
        assertEquals(
            "09.10 09:00, сплит, Пестов Егор — был(а) на тренировке, в 1С «Персональная тренировка. ББ». Прошу исправить",
            ReconcileFormat.adminLine(s, "сплит", "Пестов Егор", null),
        )
        assertEquals(
            "11.10 18:30, ПТ, Орехова Ева — отмена со списанием, в 1С нет. Прошу добавить",
            ReconcileFormat.adminLine(r.onlyInApp.single(), "ПТ", "Орехова Ева", Sex.FEMALE),
        )
        val notMarked = compare(report(row(at(9, 9))), visit("e", at(9, 9), EXPECTED)).differ.single()
        assertNull(ReconcileFormat.adminLine(notMarked, "ПТ", "Пестов Егор", Sex.MALE))
        assertEquals("Пришел", ReconcileFormat.status1c(Status1c.CAME))
    }
}

class Reconcile1cGuessTest {
    private data class T(val name: String, val kind: TrainingKind)

    private val types = listOf(
        T("ПТ", TrainingKind.PERSONAL), T("Сплит", TrainingKind.SPLIT), T("Мини-группа", TrainingKind.GROUP),
        T("Группа", TrainingKind.GROUP), T("СПТ", TrainingKind.SPT), T("Дежурство", TrainingKind.DUTY),
    )
    private val places = listOf("ББ" to "Большой бассейн", "ДБ" to "Детский бассейн", "Зал" to "Тренажёрный зал")

    private fun type(name: String, service: Service1c = Service1c.PERSONAL) =
        Reconcile1cRules.guessType(name, service, types, { it.kind }, { it.name })?.name

    private fun place(name: String) = Reconcile1cRules.guessPlace(name, places, { it.first }, { it.second })?.first

    @Test
    fun sixServicesOfOctober() {
        assertEquals("ПТ" to "ББ", type("Персональная тренировка. ББ") to place("Персональная тренировка. ББ"))
        assertEquals("ПТ" to "ДБ", type("Персональная тренировка. Детский бассейн 45 минут") to place("Персональная тренировка. Детский бассейн 45 минут"))
        assertEquals("Мини-группа" to "ББ", type("Мини-группа ББ", Service1c.GROUP) to place("Мини-группа ББ"))
        assertEquals("Группа" to "ДБ", type("Детская групповая тренировка в бассейне 50 минут", Service1c.GROUP) to place("Детская групповая тренировка в бассейне 50 минут"))
        assertEquals("Сплит" to "ББ", type("Сплит. ББ") to place("Сплит. ББ"))
        assertEquals("СПТ" to null, type("Стартовая тренировка бассейн") to place("Стартовая тренировка бассейн"))
    }
}
