package com.akenory.personalka.data.reconcile

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.report1c.NameMatch
import com.akenory.personalka.domain.report1c.Report1c
import com.akenory.personalka.domain.report1c.Row1c
import com.akenory.personalka.domain.report1c.Service1c
import com.akenory.personalka.domain.report1c.Status1c
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сверка с 1С из базы (шаг 4.2а, ТЗ 8): услуги и имена один раз, сохранение пунктов, повторная сверка. Имена придуманы. */
class Report1cRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private lateinit var repo: Report1cRepository
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 10, 18, 12, 0))
    private lateinit var pt: String
    private lateinit var group: String
    private lateinit var bb: String
    private lateinit var db1: String

    private val ptName = "Персональная тренировка. ББ"
    private val groupName = "Детская групповая тренировка в бассейне 50 минут"
    private val file = "Отчет по тренировкам report-20261018T104841.pdf"

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        val types = settings.types.first()
        pt = types.single { it.shortName == "ПТ" }.id
        group = types.single { it.shortName == "Мини" }.id
        val places = settings.places.first()
        bb = places.single { it.shortName == "ББ" }.id
        db1 = places.single { it.shortName == "ДБ" }.id
        repo = Report1cRepository(db) { millis }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun row(at: LocalDateTime, client: String, status: Status1c = Status1c.CAME, service: String = ptName) =
        Row1c(at, Service1c.PERSONAL, service, client, status, 1, 1, 0)

    private fun report(vararg rows: Row1c) =
        Report1c("Соколов Андрей Петрович", LocalDate(2026, 10, 1), LocalDate(2026, 10, 31), rows.toList(), rows.size, null)

    private suspend fun client(last: String, first: String): String =
        Samples.client(last, first).also { db.clientDao().upsertClient(it.copy(name1c = null)) }.id

    private suspend fun visit(clientId: String, start: LocalDateTime, status: ParticipationStatus, type: String = pt, place: String = bb): String {
        val t = Samples.training(type, place, start).copy(status = TrainingStatus.DONE)
        db.trainingDao().upsertTraining(t)
        db.trainingDao().upsertParticipation(Samples.participation(t.id, clientId).copy(status = status))
        return t.id
    }

    @Test
    fun servicesAndNamesAreAskedOnce() = runBlocking {
        val egor = client("Пестов", "Егор")
        val ada = client("Дельфенурова", "Аделина")
        val r = report(row(LocalDateTime(2026, 10, 8, 9, 0), "Пестов Егор Романович", service = groupName), row(LocalDateTime(2026, 10, 9, 9, 0), "Дельфинурова Аделина"))
        val q = repo.questions(r)
        assertEquals(listOf(groupName, ptName), q.services)
        assertEquals(listOf("Дельфинурова Аделина"), q.names.map { it.name1c })
        assertEquals(NameMatch.Ask(listOf(ada)), q.names.single().match)

        repo.saveService(groupName, group, db1)
        repo.saveService(ptName, pt, bb)
        repo.saveAlias("Дельфинурова Аделина", ada)
        assertTrue(repo.questions(r).none)
        assertEquals("Дельфинурова Аделина", db.clientDao().client(ada)!!.name1c, "«ФИО как в 1С» запомнилось")
        assertEquals(null, db.clientDao().client(egor)!!.name1c, "Найденный сам — без отметки")
        // Повтор правит ту же запись.
        repo.saveService(ptName, pt, db1)
        assertEquals(2, db.report1cDao().services().size)
    }

    @Test
    fun checkStoresItemsAndRepeatClosesFixed() = runBlocking {
        val egor = client("Пестов", "Егор")
        val vera = client("Чернова", "Вера")
        val gleb = client("Орехов", "Глеб")
        repo.saveService(groupName, group, db1)
        repo.saveService(ptName, pt, bb)
        val wrong = visit(egor, LocalDateTime(2026, 10, 8, 9, 0), ParticipationStatus.ATTENDED, group, db1)
        visit(vera, LocalDateTime(2026, 10, 9, 9, 0), ParticipationStatus.ATTENDED)
        visit(gleb, LocalDateTime(2026, 10, 10, 18, 0), ParticipationStatus.ATTENDED)
        // После выгрузки 18.10 10:48 — не сверяется; группа коллеги — тоже.
        visit(gleb, LocalDateTime(2026, 10, 18, 11, 0), ParticipationStatus.ATTENDED)
        val lena = Samples.training(group, db1, LocalDateTime(2026, 10, 11, 9, 0)).copy(owner = Owner.COLLEAGUE)
        db.trainingDao().upsertTraining(lena)
        db.trainingDao().upsertParticipation(Samples.participation(lena.id, vera).copy(status = ParticipationStatus.ATTENDED))
        val r = report(
            row(LocalDateTime(2026, 10, 8, 9, 0), "Пестов Егор Романович", Status1c.CANCELLED, groupName),
            row(LocalDateTime(2026, 10, 9, 9, 15), "Чернова Вера"),
            row(LocalDateTime(2026, 10, 12, 9, 0), "Чернова Вера"),
        )

        val (id, result) = repo.check(r, file)
        assertEquals(1, result.matched)
        val items = repo.items(id).first()
        assertEquals(listOf(ReconcileItemKind.STATUS_DIFF, ReconcileItemKind.ONLY_APP, ReconcileItemKind.ONLY_1C), items.map { it.kind })
        assertEquals(listOf(egor, gleb, vera), items.map { it.clientId }, "У строки 1С — найденный клиент")
        val rec = repo.reconciliation(id).first()!!
        assertEquals(LocalDateTime(2026, 10, 18, 10, 48, 41), rec.checkedUntil)
        assertEquals(3 to 1, rec.rowsTotal to rec.matched)

        val (status, onlyApp, only1c) = items
        repo.resolve(onlyApp.id, ReconcileResolution.ASKED_ADMIN)
        repo.resolve(only1c.id, ReconcileResolution.IGNORED)
        assertEquals(setOf(status.id, onlyApp.id), repo.unresolved().first().map { it.id }.toSet())

        // Исправили статус в приложении и загрузили тот же период снова — та же сверка, пункт статуса закрыт сам.
        db.trainingDao().upsertParticipation(db.trainingDao().participationsOf(wrong).single().let { it.copy(status = ParticipationStatus.CANCELLED) })
        millis += 60_000
        val (again, second) = repo.check(r, file)
        assertEquals(id, again)
        assertEquals(2, second.matched)
        assertEquals(1, repo.history().first().size)
        val after = repo.items(id).first().associateBy { it.id }
        assertEquals(ReconcileResolution.FIXED_IN_APP, after.getValue(status.id).resolution)
        assertEquals(ReconcileResolution.ASKED_ADMIN, after.getValue(onlyApp.id).resolution, "«Написал администратору» сохранилось")
        assertEquals(ReconcileResolution.IGNORED, after.getValue(only1c.id).resolution)
        assertEquals(listOf(onlyApp.id), repo.unresolved().first().map { it.id })

        // Выгрузка позже — тренировка 18.10 11:00 уже сверяется: в 1С её нет.
        val (_, third) = repo.check(r, "Отчет по тренировкам report-20261019T090000.pdf")
        assertEquals(LocalDateTime(2026, 10, 18, 11, 0), third.onlyInApp.last().visit!!.start)
    }

    @Test
    fun addAndFixInApp() = runBlocking {
        val egor = client("Пестов", "Егор")
        val vera = client("Чернова", "Вера")
        repo.saveService(groupName, group, db1)
        repo.saveService(ptName, pt, bb)
        val groupAt = LocalDateTime(2026, 10, 10, 9, 0)
        val g = visit(vera, groupAt, ParticipationStatus.ATTENDED, group, db1)
        val wrong = visit(egor, LocalDateTime(2026, 10, 8, 9, 0), ParticipationStatus.ATTENDED, group, db1)
        val extra = visit(vera, LocalDateTime(2026, 10, 12, 18, 0), ParticipationStatus.ATTENDED)
        val service = visit(egor, LocalDateTime(2026, 10, 13, 18, 0), ParticipationStatus.ATTENDED, group, db1)
        val r = report(
            row(LocalDateTime(2026, 10, 8, 9, 0), "Пестов Егор", Status1c.CANCELLED, groupName),
            row(groupAt, "Чернова Вера", service = groupName),
            row(groupAt, "Пестов Егор", service = groupName),
            row(LocalDateTime(2026, 10, 11, 10, 0), "Орехова Ева Игоревна"),
            row(LocalDateTime(2026, 10, 13, 18, 0), "Пестов Егор"),
        )
        val (id, _) = repo.check(r, file)
        val view = repo.view(id).first()
        val byKind = view.groupBy { it.item.kind }
        assertEquals(2, byKind.getValue(ReconcileItemKind.STATUS_DIFF).size)
        assertEquals("Орехова Ева", byKind.getValue(ReconcileItemKind.UNKNOWN_CLIENT).single().name)
        val onlyApp = byKind.getValue(ReconcileItemKind.ONLY_APP).single()
        assertEquals("ПТ · ББ" to "ПТ", onlyApp.app to onlyApp.what)

        // Егор был в группе 10.10 по 1С, а в приложении его нет — добавляется в ту же группу.
        val egorInGroup = byKind.getValue(ReconcileItemKind.ONLY_1C).single()
        assertTrue(repo.addFromReport(egorInGroup.item.id))
        assertEquals(ParticipationStatus.ATTENDED, db.trainingDao().participationRow(g, egor)!!.status)
        // Неизвестная Ева — новый клиент и новая тренировка, имя запомнено.
        assertTrue(repo.addFromReport(byKind.getValue(ReconcileItemKind.UNKNOWN_CLIENT).single().item.id))
        val eva = db.clientDao().observeAllClients().first().single { it.lastName == "Орехова" }
        assertEquals("Орехова Ева Игоревна", eva.name1c)
        val evaTraining = db.trainingDao().trainingsOfClient(eva.id).single()
        assertEquals(LocalDateTime(2026, 10, 11, 10, 0) to TrainingStatus.DONE, evaTraining.start to evaTraining.status)

        // «Исправить в приложении»: статус как в 1С, лишняя — отмена, услуга — тип и место из 1С.
        byKind.getValue(ReconcileItemKind.STATUS_DIFF).forEach { assertTrue(repo.fixInApp(it.item.id)) }
        assertTrue(repo.fixInApp(onlyApp.item.id))
        assertEquals(ParticipationStatus.CANCELLED, db.trainingDao().participationRow(wrong, egor)!!.status)
        assertEquals(ParticipationStatus.CANCELLED, db.trainingDao().participationRow(extra, vera)!!.status)
        assertEquals(pt to bb, db.trainingDao().training(service)!!.let { it.typeId to it.locationId })
        assertTrue(repo.unresolved().first().isEmpty())

        // Повторная сверка того же отчёта — всё совпало.
        val (_, again) = repo.check(r, file)
        assertEquals(emptyList(), again.diffs)
    }
}
