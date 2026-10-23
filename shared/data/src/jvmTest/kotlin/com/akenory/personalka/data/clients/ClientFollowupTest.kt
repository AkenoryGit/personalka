package com.akenory.personalka.data.clients

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.model.WaitStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** «Без записи» в базе (шаг 2.4, ТЗ 4.15): посещения и запись дальше из тренировок, «Написал», «Пауза до…». */
class ClientFollowupTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var repo: ClientRepository
    private lateinit var ptId: String
    private lateinit var sptId: String
    private lateinit var groupId: String
    private lateinit var bbId: String

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        val types = settings.types.first()
        ptId = types.single { it.shortName == "ПТ" }.id
        sptId = types.single { it.shortName == "СПТ" }.id
        groupId = types.single { it.shortName == "Мини" }.id
        bbId = settings.places.first().single { it.shortName == "ББ" }.id
        repo = ClientRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun create(last: String, first: String): String = repo.create(ClientDraft(last, first))

    private suspend fun training(start: LocalDateTime, typeId: String, clientId: String, done: Boolean) {
        val training = Samples.training(typeId, bbId, start).copy(status = if (done) TrainingStatus.DONE else TrainingStatus.PLANNED)
        db.trainingDao().upsertTraining(training)
        db.trainingDao().upsertParticipation(
            Samples.participation(training.id, clientId).copy(
                status = if (done) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED,
                behavior = null,
                cancelReason = null,
                notifiedAt = null,
            ),
        )
    }

    private suspend fun followup(at: LocalDateTime = now) = repo.followup.observe(at).first()

    @Test
    fun mockupListFromTrainings(): Unit = runBlocking {
        val yakovleva = create("Яковлева", "Татьяна")
        val zakharov = create("Захаров", "Никита")
        val sokolova = create("Соколова", "Вера")
        val booked = create("Морозов", "Дмитрий")
        val groupOnly = create("Белова", "Ева")
        training(LocalDateTime(2026, 10, 12, 10, 0), sptId, yakovleva, done = true)
        training(LocalDateTime(2026, 10, 9, 8, 0), ptId, zakharov, done = true)
        training(LocalDateTime(2026, 10, 20, 15, 0), ptId, sokolova, done = true)
        training(LocalDateTime(2026, 10, 19, 10, 0), ptId, booked, done = true)
        training(LocalDateTime(2026, 10, 22, 10, 0), ptId, booked, done = false)
        training(LocalDateTime(2026, 10, 19, 9, 0), groupId, groupOnly, done = true)
        // Ходит и на ПТ, и в группу: группа впереди, а следующей ПТ нет — в списке.
        training(LocalDateTime(2026, 10, 24, 9, 0), groupId, sokolova, done = false)

        val list = followup()
        assertEquals(listOf("Яковлева", "Захаров", "Соколова"), list.rows.map { it.client.lastName })
        assertEquals(LocalDate(2026, 10, 12), list.rows[0].item.afterSpt)
        assertEquals("СПТ" to "ББ", list.rows[0].typeShort to list.rows[0].placeShort)
        assertEquals(setOf(yakovleva, zakharov, sokolova), list.noBooking)
        assertEquals(14, list.days)
    }

    @Test
    fun bookingRemovesAndArchiveRemoves(): Unit = runBlocking {
        val a = create("Захаров", "Никита")
        val b = create("Соколова", "Вера")
        training(LocalDateTime(2026, 10, 16, 8, 0), ptId, a, done = true)
        training(LocalDateTime(2026, 10, 16, 9, 0), ptId, b, done = true)
        assertEquals(2, followup().rows.size)

        // «Записать» — у клиента появилась будущая ПТ: он уходит из списка сам.
        training(LocalDateTime(2026, 10, 23, 8, 0), ptId, a, done = false)
        assertEquals(listOf(b), followup().rows.map { it.client.id })
        // «Больше не ходит» — архив.
        repo.archive(b)
        assertTrue(followup().rows.isEmpty())
    }

    @Test
    fun contactedAndPauseHideAndUndoReturns(): Unit = runBlocking {
        val a = create("Захаров", "Никита")
        training(LocalDateTime(2026, 10, 16, 8, 0), ptId, a, done = true)

        val before = repo.followup.contacted(a)!!
        assertTrue(followup().rows.isEmpty())
        assertEquals(setOf(a), followup().noBooking)
        // Через 3 дня — снова в списке с «писали 20.10».
        assertEquals(LocalDate(2026, 10, 20), followup(LocalDateTime(2026, 10, 23, 9, 0)).rows.single().item.contactedOn)
        repo.followup.undo(before)
        assertEquals(1, followup().rows.size)

        repo.followup.pause(a, LocalDate(2026, 10, 25), "  отпуск ")
        assertTrue(followup().rows.isEmpty())
        assertEquals("отпуск", db.clientDao().client(a)!!.followupPauseNote)
        assertEquals(1, followup(LocalDateTime(2026, 10, 25, 9, 0)).rows.size)
    }

    @Test
    fun waitlistAndSettings(): Unit = runBlocking {
        val a = create("Захаров", "Никита")
        training(LocalDateTime(2026, 10, 16, 8, 0), ptId, a, done = true)
        // Клиент из листа ожидания — в своём разделе (4.16).
        db.clientDao().upsertWaitEntry(
            WaitEntryEntity(id = "w1", sync = Samples.sync(), clientId = a, wantsSeries = true, wantsOneOff = false, typeId = ptId),
        )
        assertTrue(followup().rows.isEmpty())
        db.clientDao().upsertWaitEntry(db.clientDao().waitEntry("w1")!!.copy(status = WaitStatus.CLOSED))
        assertEquals(1, followup().rows.size)

        // N = 3: посещение 16.10 уже старше; выключено — пусто.
        val dao = db.coachDao()
        val settings = dao.settings(dao.currentCoach()!!.id)!!
        dao.upsertSettings(settings.copy(followupDays = 3))
        assertTrue(followup().rows.isEmpty())
        dao.upsertSettings(settings.copy(followupEnabled = false))
        assertEquals(Followup.EMPTY, followup())
    }
}
