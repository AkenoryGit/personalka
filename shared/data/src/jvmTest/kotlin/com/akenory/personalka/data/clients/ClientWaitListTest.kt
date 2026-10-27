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
        val row = repo.waitlist.observe(now).first().single()
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
        assertTrue(repo.waitlist.observe(now).first().isEmpty())
        assertEquals(WaitStatus.CLOSED, repo.waitlist.entry(entry)!!.status)
        assertFalse(repo.waitlist.hasTrainings(id))
        repo.waitlist.restore(before)
        assertEquals(1, repo.waitlist.observe(now).first().size)
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

    // --- Освободившееся время (шаг 2.4б) ---

    private fun dbId(short: String) = runBlocking { db.referenceDao().locations().single { it.shortName == short }.id }

    @Test
    fun oneOffCancelShowsWhoWaitsOnceAndRecordsMatch(): Unit = runBlocking {
        val smirnova = repo.create(ClientDraft("Смирнова", "Ирина"))
        val petrovId = repo.create(ClientDraft("Петров", "Кирилл"))
        val orlov = repo.create(ClientDraft("Орлов", "Максим"))
        repo.waitlist.save(WaitDraft(smirnova, WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = ptId, until = LocalDate(2026, 10, 31))))
        repo.waitlist.save(WaitDraft(petrovId, petrov.copy(typeId = ptId, locationId = bbId)))
        // ПТ Орлова сегодня, вт 20.10 в 17:00 в ДБ — пример проверки 0.8.
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 20, 17, 0), ptId, listOf(orlov), null, dbId("ДБ"), 50))
        val training = db.trainingDao().trainingsAfter(now).single()

        // В панели отмены — кто ждёт: Смирнова (разово), не Петров.
        assertEquals("Это время ждут: Смирнова\u00A0И. (разово)", calendar.cards.waitingLine(training.id))
        calendar.cards.cancel(training.id, emptyList(), "Не сможет", now)

        val rows = repo.waitlist.observe(now).first()
        assertEquals("Смирнова", rows.first().client.lastName)
        val match = rows.first().match!!
        assertEquals(LocalDateTime(2026, 10, 20, 17, 0), match.at)
        assertFalse(match.series)
        assertNull(rows.single { it.client.id == petrovId }.match)

        // Время заняли другой тренировкой — совпадение исчезло само.
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 20, 17, 0), ptId, listOf(petrovId), null, bbId, 50))
        assertNull(repo.waitlist.observe(now).first().single { it.client.id == smirnova }.match)
    }

    @Test
    fun seriesEndOnTuesday8ShowsWhoWaitsSeries(): Unit = runBlocking {
        val morozov = repo.create(ClientDraft("Морозов", "Дмитрий"))
        val smirnova = repo.create(ClientDraft("Смирнова", "Ирина"))
        val kovalev = repo.create(ClientDraft("Ковалёв", "Артём"))
        val entry = repo.waitlist.save(WaitDraft(morozov, WaitWant(series = true, days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), ranges = listOf(WaitRules.MORNING), typeId = ptId)))!!
        repo.waitlist.save(WaitDraft(smirnova, WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = ptId)))
        // Серия Ковалёва: вт 08:00 в ББ с 13.10.
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 13, 8, 0), ptId, listOf(kovalev), null, bbId, 50, Repeat(setOf(DayOfWeek.TUESDAY))))
        val seriesId = db.scheduleDao().seriesOfClient(kovalev).single().id

        // В подтверждении «Завершить» — кто ждёт это время: Морозов постоянно; Смирнова ждёт вечер — нет.
        assertEquals("Это время ждут: Морозов\u00A0Д. (постоянно)", repo.waitlist.waitingForSeriesEnd(seriesId, now.date))
        calendar.forms.endSeries(seriesId, now.date)

        val stored = repo.waitlist.entry(entry)!!
        assertEquals(LocalDate(2026, 10, 27), stored.matchDate)
        assertFalse(stored.matchOneOff)
        val row = repo.waitlist.observe(now).first().first()
        assertEquals("Морозов", row.client.lastName)
        assertTrue(row.match!!.series)

        // «Не подходит» — совпадения нет, запись ждёт дальше.
        repo.waitlist.notSuitable(entry)
        assertNull(repo.waitlist.observe(now).first().single { it.entry.id == entry }.match)
        assertEquals(WaitStatus.WAITING, repo.waitlist.entry(entry)!!.status)
    }

    // --- «Напомнить с» (шаг 3.9х) ---

    @Test
    fun remindFromHidesSkipsMatchesAndRemindsOnTheDay(): Unit = runBlocking {
        val smirnova = repo.create(ClientDraft("Смирнова", "Ирина"))
        val belova = repo.create(ClientDraft("Белова", "Аня"))
        val orlov = repo.create(ClientDraft("Орлов", "Максим"))
        // Смирнова уехала до 10.11 и скрыта; Белова — с 01.11, видна в конце списка.
        val away = repo.waitlist.save(
            WaitDraft(
                smirnova,
                WaitWant(series = false, oneOff = true, ranges = listOf(WaitRules.EVENING), typeId = ptId, note = "уехали до 10.11", remindFrom = LocalDate(2026, 11, 10), hideUntil = true),
            ),
        )!!
        repo.waitlist.save(WaitDraft(belova, petrov.copy(typeId = ptId, remindFrom = LocalDate(2026, 11, 1))))
        repo.waitlist.save(WaitDraft(orlov, petrov.copy(typeId = ptId)))
        val rows = repo.waitlist.observe(now).first()
        assertEquals(listOf("Орлов", "Белова", "Смирнова"), rows.map { it.client.lastName })
        val hidden = rows.single { it.client.id == smirnova }
        assertTrue(hidden.hidden && hidden.deferred && !hidden.due)
        assertEquals("уехали до 10.11", hidden.want.note)
        val later = rows.single { it.client.id == belova }
        assertTrue(later.deferred && !later.hidden)

        // Освободился вечер сегодня — Смирнову не предлагают: её нет до 10.11.
        val kovalev = repo.create(ClientDraft("Ковалёв", "Артём"))
        calendar.forms.create(TrainingDraft(LocalDateTime(2026, 10, 20, 17, 0), ptId, listOf(kovalev), null, bbId, 50))
        val training = db.trainingDao().trainingsAfter(now).single()
        assertNull(calendar.cards.waitingLine(training.id))

        // 10.11 — «Пора записать», строка видна и выше остальных.
        val day = LocalDateTime(2026, 11, 10, 9, 0)
        millis = ClubTime.toMillis(day)
        val due = repo.waitlist.observe(day).first()
        assertEquals("Смирнова", due.first().client.lastName)
        assertTrue(due.first().due && !due.first().hidden)
        // «Написал» — напоминание снято, запись ждёт дальше.
        repo.waitlist.contacted(away)
        assertFalse(repo.waitlist.observe(day).first().single { it.client.id == smirnova }.due)
    }
}
