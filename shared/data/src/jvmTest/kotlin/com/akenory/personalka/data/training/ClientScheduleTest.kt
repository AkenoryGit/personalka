package com.akenory.personalka.data.training

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.FormField
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Расписание в карточке клиента (шаг 1.13б, ТЗ 5.9, 4.4). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class ClientScheduleTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var forms: TrainingRepository
    private lateinit var clients: ClientRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val tueThu = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        forms = CalendarRepository(db) { millis }.forms
        clients = ClientRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private suspend fun typeId(short: String) = settings.types.first().single { it.shortName == short }.id

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private fun day(n: Int) = tuesday.plus(n, DateTimeUnit.DAY)

    private suspend fun series(clientId: String, start: LocalDateTime, days: Set<DayOfWeek> = tueThu): String {
        assertIs<SaveResult.Saved>(forms.create(TrainingDraft(start, typeId("ПТ"), listOf(clientId), null, placeId("ББ"), 50, Repeat(days))))
        return db.scheduleDao().seriesOfClient(clientId).last().id
    }

    /** Тренировки, кроме удалённых, по времени. */
    private suspend fun all() = db.trainingDao().trainingsBetween(at(day(-60), 0), at(day(120), 0))

    private suspend fun setPlan(start: LocalDateTime, plan: String) {
        val t = all().single { it.start == start }
        db.trainingDao().upsertTraining(t.copy(plan = plan))
    }

    @Test
    fun seriesOfClientForTabAndForm(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val id = series(orlova, at(day(2), 13, 30))

        val list = forms.seriesOf(orlova).first()
        assertEquals(1, list.size)
        val s = list.single()
        assertEquals(tueThu, s.weekdays)
        assertEquals(LocalTime(13, 30), s.startTime)
        assertEquals("ПТ" to "ББ", s.typeShort to s.placeShort)
        assertEquals(day(2), s.validFrom)
        assertNull(s.validTo)

        // Форма «Изменить с даты…» — с первого дня серии, которая ещё не началась; дни — в повторе.
        val stored = forms.storedSeries(id)!!
        assertEquals(at(day(2), 13, 30), stored.draft.start)
        assertEquals(listOf(orlova), stored.draft.clientIds)
        assertEquals(Repeat(tueThu), stored.draft.repeat)
    }

    @Test
    fun changeFromKeepsPastAndCarriesPlan(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        // Серия вт и чт 13:30 с 06.10: сегодняшняя 13:30 уже прошла.
        val id = series(orlova, at(day(-14), 13, 30))
        setPlan(at(day(2), 13, 30), "Старт с тумбочки")
        val before = all()

        // С ср 21.10 — ср и пт в 18:00.
        val draft = forms.storedSeries(id)!!.draft
        assertEquals(at(tuesday, 13, 30), draft.start)
        val edit = draft.copy(start = at(day(1), 18), repeat = Repeat(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        val result = assertIs<SaveResult.Saved>(forms.changeFrom(id, edit))
        assertEquals(s("Расписание изменено с ср_21.10"), result.message)

        val after = all()
        // Прошлое не тронуто.
        assertEquals(before.filter { it.start < at(day(1), 0) }, after.filter { it.start < at(day(1), 0) })
        // Четверг выпал, его план — на пятницу 23.10, первую тренировку новой серии после него.
        val future = after.filter { it.start >= at(day(1), 0) }
        assertEquals(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), future.map { it.start.dayOfWeek }.toSet())
        assertEquals(LocalTime(18, 0), future.map { it.start.time }.distinct().single())
        assertEquals("Старт с тумбочки", future.single { it.start.date == day(3) }.plan)

        val list = forms.seriesOf(orlova).first().sortedBy { it.validFrom }
        assertEquals(listOf(day(-14) to tuesday, day(1) to null), list.map { it.validFrom to it.validTo })

        forms.undo(result.undo)
        assertEquals(before.map { Triple(it.id, it.start, it.plan) }, all().map { Triple(it.id, it.start, it.plan) })
        assertEquals(1, forms.seriesOf(orlova).first().size)
    }

    @Test
    fun changeFromNotInPast(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val id = series(orlova, at(day(-14), 13, 30))
        val draft = forms.storedSeries(id)!!.draft.copy(start = at(day(-1), 13, 30))
        val result = assertIs<SaveResult.Invalid>(forms.changeFrom(id, draft))
        assertEquals("Не раньше сегодняшнего дня", result.problems[FormField.DATE])
    }

    @Test
    fun endSeriesDeletesFutureKeepsPlan(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val id = series(orlova, at(day(-14), 13, 30))
        setPlan(at(day(7), 13, 30), "Кроль на спине")
        val before = all()

        assertEquals(
            "Не раньше вчерашнего дня",
            assertIs<SaveResult.Invalid>(forms.endSeries(id, day(-2))).problems[FormField.DATE],
        )

        // Последний день — чт 22.10: тренировки после него удалены, план вт 27.10 — у клиента.
        val result = assertIs<SaveResult.Saved>(forms.endSeries(id, day(2)))
        assertEquals("Серия завершена", result.message)
        assertEquals(day(2), all().last().start.date)
        assertEquals(day(2), forms.seriesOf(orlova).first().single().validTo)
        assertEquals("Кроль на спине", db.clientDao().client(orlova)!!.pendingPlan)

        forms.undo(result.undo)
        assertEquals(before.map { it.id }, all().map { it.id })
        assertNull(forms.seriesOf(orlova).first().single().validTo)
        assertNull(db.clientDao().client(orlova)!!.pendingPlan)
    }

    @Test
    fun archiveEndsSeriesAndUndoReturnsIt(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val id = series(orlova, at(day(-14), 13, 30))
        setPlan(at(day(2), 13, 30), "Старт с тумбочки")
        val before = all()

        // Шаг 1.13д: в архиве серия заканчивается сегодня, будущие тренировки удаляются, план — в отложенный.
        val undo = clients.archive(orlova)!!
        assertEquals(tuesday, db.scheduleDao().series(id)!!.validTo)
        assertEquals(before.filter { it.start < at(day(1), 0) }.map { it.id }, all().map { it.id })
        assertEquals("Старт с тумбочки", db.clientDao().client(orlova)!!.pendingPlan)
        // Продление закончившуюся серию не вернёт.
        assertEquals(0, forms.extendSeries())

        clients.undoArchive(undo)
        assertEquals(before.map { it.id to it.plan }.toSet(), all().map { it.id to it.plan }.toSet())
        assertNull(db.scheduleDao().series(id)!!.validTo)
        assertNull(db.clientDao().client(orlova)!!.pendingPlan)
    }

    @Test
    fun archiveInSplitKeepsSeriesWithSecond(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val belov = clients.create(ClientDraft("Белов", "Артём"))
        val split = typeId("Сплит")
        assertIs<SaveResult.Saved>(forms.create(TrainingDraft(at(day(2), 13, 30), split, listOf(orlova, belov), null, placeId("ББ"), 50, Repeat(tueThu))))

        clients.archive(orlova)
        // Белов занимается дальше по новой серии, Орловой в будущих тренировках нет.
        assertEquals(listOf(belov), db.scheduleDao().seriesOfClient(belov).filter { it.validTo == null }.map { s -> db.scheduleDao().seriesClients(s.id).single().clientId })
        val future = all().filter { it.start > at(tuesday, 15, 40) }
        assertTrue(future.isNotEmpty())
        assertTrue(future.all { t -> db.trainingDao().participationsOf(t.id).map { it.clientId } == listOf(belov) })
    }

    @Test
    fun privateNoteSavedAndShownInForm(): Unit = runBlocking {
        val id = clients.create(ClientDraft("Орлова", "Ксения", privateNote = "  Плавала в другом городе 2 года "))
        val stored = db.clientDao().client(id)!!
        assertEquals("Плавала в другом городе 2 года", stored.privateNote)
        clients.update(id, ClientDraft("Орлова", "Ксения", privateNote = ""))
        assertNull(db.clientDao().client(id)!!.privateNote)
    }
}
