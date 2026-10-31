package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.backup.XlsxCell
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.GroupReportFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Отчёты → Группы» (ТЗ 5.15, шаг 5.3о). «Сейчас» — сб 31.10.2026 12:00. Имена придуманы. */
class GroupReportTest {
    private val now = LocalDateTime(2026, 10, 31, 12, 0)
    private val from = LocalDate(2026, 10, 1)
    private val to = LocalDate(2026, 10, 31)
    private fun day(d: Int, m: Int = 10) = LocalDate(2026, m, d)

    private val sat = GroupReportGroup("sat", "Сб 9:00 ББ", colleague = "Лена")
    private val tue = GroupReportGroup("tue", "Вт 18:00 ДБ")
    private val kids = (1..12).associate { "k$it" to ClientName("Фамилия${'А' + it}", "Имя$it") }
    private val names = kids + mapOf("guest" to ClientName("Гусев", "Борис"), "gone" to ClientName("Ушедшая", "Вера"))

    private fun t(
        id: String,
        d: Int,
        people: Map<String, ParticipationStatus>,
        status: TrainingStatus = TrainingStatus.DONE,
        group: String = "sat",
        m: Int = 10,
        notified: Set<String> = emptySet(),
    ) = GroupReportTraining(id, group, LocalDateTime(day(d, m), kotlinx.datetime.LocalTime(9, 0)), status, people.map { (k, s) -> GroupReportPerson(k, s, k in notified) })

    private fun all(s: ParticipationStatus, vararg except: Pair<String, ParticipationStatus>) =
        kids.keys.associateWith { s } + except.toMap()

    private val members = mapOf(
        "sat" to kids.keys.map { GroupMember(it, day(1, 9), coach = if (it == "k12") Owner.COLLEAGUE else Owner.SELF) } +
            GroupMember("gone", day(1, 9), toDate = day(20)),
    )

    private val trainings = listOf(
        // Сентябрь: k1 пришёл впервые 26.09 — его история с сентября.
        t("s26", 26, mapOf("k1" to ATTENDED), m = 9),
        // 03.10: k2 ещё ни разу не был — «не пришёл» в его историю не идёт.
        t("o03", 3, all(ATTENDED, "k2" to CANCELLED) + ("gone" to ATTENDED)),
        t("o10", 10, all(ATTENDED, "k2" to ATTENDED, "k4" to CANCELLED_CHARGED, "k5" to CANCELLED) + ("guest" to ATTENDED)),
        t("o17", 17, all(ATTENDED, "k5" to CANCELLED, "k3" to CANCELLED) + ("gone" to CANCELLED), notified = setOf("k3")),
        t("o24", 24, all(ATTENDED, "k5" to CANCELLED)),
        // 31.10 09:00 ещё не отмечена; 07.11 — будущая; отменённая целиком 27.10 — не в N.
        t("o31", 31, all(EXPECTED), status = TrainingStatus.PLANNED),
        t("n07", 7, all(EXPECTED), status = TrainingStatus.PLANNED, m = 11),
        t("o27", 27, all(CANCELLED), status = TrainingStatus.CANCELLED),
    )

    private fun report(chosen: Set<String>? = null) =
        GroupReportRules.report(listOf(sat, tue), trainings, members, names, from, to, now, chosen)

    @Test
    fun blockAndKids() {
        val r = report()
        val b = r.blocks.single()
        assertEquals("sat", b.group.id, "Вт без тренировок в периоде не показывается")
        assertEquals(4, b.conducted)
        // Пришли: 03.10 — 11 + Вера = 12; 10.10 — 10 + гость = 11; 17.10 — 10; 24.10 — 11. В среднем 11,0 из 12.
        assertEquals(110, b.averageTenths)
        assertEquals(12, b.size)
        assertEquals("проведено 4 · в среднем пришли 11,0 из 12", GroupReportFormat.summary(b))
        assertEquals(1, b.notMarked)
        assertEquals(1, b.cancelledWhole)
        assertEquals(12, b.kids.size)

        val k1 = b.kids.single { it.clientId == "k1" }
        assertEquals(4, k1.total)
        assertEquals(4, k1.came)
        assertEquals(100, k1.percent)

        val k2 = b.kids.single { it.clientId == "k2" }
        assertEquals(3, k2.total, "03.10 — до первого «пришёл»")
        assertEquals(3, k2.came)

        val k3 = b.kids.single { it.clientId == "k3" }
        assertEquals(1, k3.cancelled, "Предупредили — «отменено»")
        assertEquals(0, k3.noShow)

        val k4 = b.kids.single { it.clientId == "k4" }
        assertEquals(1, k4.charged)
        assertEquals(75, k4.percent)

        val k5 = b.kids.single { it.clientId == "k5" }
        assertEquals(3, k5.noShow)
        assertEquals(3, k5.missedInRow)
        assertEquals(day(3), k5.last)
        assertEquals("1 из 4 · 25${RuText.NBSP}%", GroupReportFormat.share(k5))
        assertEquals("не пришёл 3", GroupReportFormat.counts(k5))
        assertEquals("пропустил 3 подряд", GroupReportFormat.missed(k5))
        assertNull(GroupReportFormat.missed(k4))

        assertTrue(b.kids.single { it.clientId == "k12" }.colleague, "Ребёнок Лены")
        assertEquals(listOf("gone"), b.left.map { it.clientId })
        assertEquals(listOf("guest" to 1), b.guests.map { it.clientId to it.came })
        assertEquals("На раз: Гусев Борис · 1", GroupReportFormat.guests(b))
    }

    @Test
    fun chosenGroupsAndEmpty() {
        assertTrue(report(chosen = setOf("tue")).isEmpty)
        assertEquals(setOf("sat"), GroupReportRules.withTrainings(trainings, from, to, now))
        assertEquals("Группы: все · 4", GroupReportFormat.picked(4, 4))
        assertEquals("Группы: 2 из 4", GroupReportFormat.picked(2, 4))
    }

    @Test
    fun shareTextAndExcel() {
        val r = report()
        val text = GroupReportFormat.shareText("октябрь 2026", r)
        assertTrue(text.startsWith("Посещаемость групп · октябрь 2026\n\nСб 9:00 ББ\nпроведено 4"))
        assertTrue("ФамилияЕ Имя5 — 1 из 4 (25${RuText.NBSP}%), последний раз 03.10, пропустил 3 подряд" in text)
        assertTrue("+7" !in text && "телефон" !in text.lowercase())

        val sheets = GroupReportFormat.sheets(r)
        assertEquals(listOf("Итоги", "Сб 9:00 ББ"), sheets.map { it.name })
        assertEquals(13, sheets[0].rows.size, "12 в составе и ушедшая")
        val journal = sheets[1]
        assertEquals(listOf("Ребёнок", "03.10", "10.10", "17.10", "24.10", "Пришёл", "Из", "%"), journal.headers)
        val k5 = journal.rows.single { (it[0] as XlsxCell.Text).value == "ФамилияЕ Имя5" }
        assertEquals(listOf("+", "н", "н", "н"), k5.subList(1, 5).map { (it as XlsxCell.Text).value })
        val k2 = journal.rows.single { (it[0] as XlsxCell.Text).value == "ФамилияВ Имя2" }
        assertNull(k2[1], "До первого «пришёл» — пусто")
        assertEquals("otchet-gruppy-20261031.xlsx", GroupReportFormat.fileName(day(31)))

        // Повторы и запрещённые знаки в именах листов.
        val twins = GroupReport(from, to, listOf(r.blocks[0], r.blocks[0].copy(group = GroupReportGroup("x", "Сб 9:00 ББ")), r.blocks[0].copy(group = GroupReportGroup("y", "Итоги"))))
        assertEquals(listOf("Итоги", "Сб 9:00 ББ", "Сб 9:00 ББ (2)", "Итоги (2)"), GroupReportFormat.sheets(twins).map { it.name })
    }
}
