package com.akenory.personalka.data.clients

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.WaitDraft
import com.akenory.personalka.domain.client.WaitRules
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.model.WaitStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Лист ожидания в базе (шаг 2.4а, ТЗ 4.16): одна запись на клиента, закрытие, серия закрывает «постоянно», уборка. */
class ClientWaitListTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var repo: ClientRepository
    private lateinit var calendar: CalendarRepository
    private lateinit var ptId: String
    private lateinit var bbId: String

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        ptId = settings.types.first().single { it.shortName == "ПТ" }.id
        bbId = settings.places.first().single { it.shortName == "ББ" }.id
        repo = ClientRepository(db) { millis++ }
        calendar = CalendarRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private val petrov = WaitWant(series = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), ranges = listOf(WaitRules.MORNING))

    @Test
    fun oneActiveEntryPerClientAndChoices(): Unit = runBlocking {
        val id = repo.create(ClientDraft("Петров", "Кирилл"))
        val first = repo.waitlist.save(WaitDraft(id, petrov.copy(typeId = ptId, locationId = bbId, note = "  можно сплит с братом ")))
        val second = repo.waitlist.save(WaitDraft(id, petrov.copy(typeId = ptId, oneOff = true, until = LocalDate(2026, 10, 31))))
        assertEquals(first, second)
        val row = repo.waitlist.observe().first().single()
        assertTrue(row.want.oneOff)
        assertEquals(LocalDate(2026, 10, 31), row.want.until)
        // Вторая запись сменила место на «любое» и стёрла заметку — форма шлёт всё целиком.
        assertNull(row.placeShort)
        assertEquals("ПТ", row.typeShort)
        assertEquals(now.date, row.since)
        // Чипы формы: индивидуальные типы без групп, места.
        val choices = repo.waitlist.choices().first()
        assertTrue(choices.types.none { it.shortName == "Мини" })
        assertTrue(choices.types.any { it.shortName == "СПТ" })
        assertTrue(choices.places.isNotEmpty())
        // Клиент из листа ожидания — не в «Не записаны дальше».
        assertTrue(repo.followup.observe(now).first().rows.isEmpty())
    }

    @Test
    fun closeContactedAndRestore(): Unit = runBlocking {
        val id = repo.create(ClientDraft("Петров", "Кирилл"))
        val entry = repo.waitlist.save(WaitDraft(id, petrov.copy(typeId = ptId)))!!
        val contacted = repo.waitlist.contacted(entry)!!
        assertEquals(millis - 1, repo.waitlist.entry(entry)!!.contactedAt)
        repo.waitlist.restore(contacted)
        assertNull(repo.waitlist.entry(entry)!!.contactedAt)

        val before = repo.waitlist.close(entry)!!
        assertTrue(repo.waitlist.observe().first().isEmpty())
        assertEquals(WaitStatus.CLOSED, repo.waitlist.entry(entry)!!.status)
        assertFalse(repo.waitlist.hasTrainings(id))
        repo.waitlist.restore(before)
        assertEquals(1, repo.waitlist.observe().first().size)
    }

    @Test
    fun seriesClosesSeriesEntryOneOffClosesOnlyOneOff(): Unit = runBlocking {
        val petrovId = repo.create(ClientDraft("Петров", "Кирилл"))
        val smirnovaId = repo.create(ClientDraft("Смирнова", "Ирина"))
        val series = repo.waitlist.save(WaitDraft(petrovId, petrov.copy(typeId = ptId)))!!
        val oneOff = repo.waitlist.save(WaitDraft(smirnovaId, WaitWant(series = false, oneOff = true, typeId = ptId)))!!

        // Разовая тренировка запись «постоянно» не закрывает.
        assertNull(repo.waitlist.bookedOnce(series))
        assertEquals(WaitStatus.WAITING, repo.waitlist.entry(series)!!.status)
        // Серия для клиента — закрывает сама.
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 21, 8, 0), ptId, listOf(petrovId), null, bbId, 50, Repeat(setOf(DayOfWeek.WEDNESDAY))))
        assertEquals(WaitStatus.BOOKED, repo.waitlist.entry(series)!!.status)

        val before = repo.waitlist.bookedOnce(oneOff)!!
        assertEquals(WaitStatus.BOOKED, repo.waitlist.entry(oneOff)!!.status)
        repo.waitlist.restore(before)
        assertEquals(WaitStatus.WAITING, repo.waitlist.entry(oneOff)!!.status)
        assertTrue(repo.waitlist.hasTrainings(petrovId))
    }

    @Test
    fun tidyClosesExpiredAndPurgesOld(): Unit = runBlocking {
        val id = repo.create(ClientDraft("Смирнова", "Ирина"))
        val entry = repo.waitlist.save(WaitDraft(id, WaitWant(series = false, oneOff = true, typeId = ptId, until = LocalDate(2026, 10, 20))))!!
        repo.waitlist.tidy()
        assertEquals(WaitStatus.WAITING, repo.waitlist.entry(entry)!!.status)
        // Следующий день после срока — закрыта.
        millis = ClubTime.toMillis(LocalDateTime(2026, 10, 21, 9, 0))
        repo.waitlist.tidy()
        assertEquals(WaitStatus.CLOSED, repo.waitlist.entry(entry)!!.status)
        // Через 90 дней после закрытия — удалена.
        millis = ClubTime.toMillis(LocalDateTime(2027, 1, 19, 9, 0))
        repo.waitlist.tidy()
        assertNull(repo.waitlist.entry(entry))
    }
}
