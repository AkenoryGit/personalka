package com.akenory.personalka.data.calendar

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.restrictions.RestrictionSaveResult
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.Vacation
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.restriction.RestrictionDraft
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

/** Раздел «Календарь» в настройках (шаг 1.13г, ТЗ 5.16, 3.2, 4.4). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class CalendarSettingsTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var repo: CalendarSettingsRepository
    private lateinit var settings: SettingsRepository
    private lateinit var clients: ClientRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val tueThu = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        repo = calendar.settings
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

    private suspend fun all() = db.trainingDao().trainingsBetween(at(day(-60), 0), at(day(400), 0))

    /** Серия ПТ вт и чт 13:30 в ББ с 06.10. */
    private suspend fun series(clientId: String): String {
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(day(-14), 13, 30), typeId("ПТ"), listOf(clientId), null, placeId("ББ"), 50, Repeat(tueThu))))
        return db.scheduleDao().seriesOfClient(clientId).last().id
    }

    @Test
    fun workHoursAndStepGoStraightToCalendar(): Unit = runBlocking {
        assertEquals(CalendarPrefs(), repo.prefs.first())
        assertNull(repo.saveWorkHours(LocalTime(6, 0), LocalTime(0, 0)))
        // Проверка шага: новые часы работы сразу меняют сетку календаря.
        assertEquals(WorkHours(LocalTime(6, 0), LocalTime(0, 0)), calendar.workHours.first())
        assertEquals("Конец позже начала", repo.saveWorkHours(LocalTime(9, 0), LocalTime(8, 0)))
        assertEquals(WorkHours(LocalTime(6, 0), LocalTime(0, 0)), calendar.workHours.first())

        repo.saveDragStep(5)
        assertEquals(5, calendar.dragStep.first())
        assertEquals(CalendarPrefs(LocalTime(6, 0), LocalTime(0, 0), 5, 8), repo.prefs.first())
    }

    @Test
    fun longerHorizonExtendsSeriesAtOnce(): Unit = runBlocking {
        val id = series(clients.create(ClientDraft("Орлова", "Ксения")))
        val last = { list: List<TrainingEntity> -> list.filter { it.seriesId == id }.maxOf { it.start.date } }
        // 8 недель: по вт 15.12.
        assertEquals(LocalDate(2026, 12, 15), last(all()))
        repo.saveHorizon(12)
        assertEquals(12, repo.prefs.first().horizonWeeks)
        // 12 недель: по вт 12.01.2027.
        assertEquals(LocalDate(2027, 1, 12), last(all()))
        // Меньший горизонт уже созданное не удаляет.
        repo.saveHorizon(4)
        assertEquals(LocalDate(2027, 1, 12), last(all()))
    }

    @Test
    fun vacationDeletesSeriesDaysKeepsOneOffAndUndoes(): Unit = runBlocking {
        val orlova = clients.create(ClientDraft("Орлова", "Ксения"))
        val id = series(orlova)
        // План на чт 22.10 — первый день отпуска.
        val thursday = all().single { it.start == at(day(2), 13, 30) }
        db.trainingDao().upsertTraining(thursday.copy(plan = "Старт с тумбочки"))
        // Разовая тренировка в отпуске остаётся.
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(day(3), 10), typeId("ПТ"), listOf(orlova), null, placeId("ББ"), 50)))
        // Дежурство серией каждую пятницу — тоже серия, тоже уходит.
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(day(3), 15), typeId("Деж"), emptyList(), null, placeId("ББ"), 240, Repeat(setOf(DayOfWeek.FRIDAY)))))
        val before = all()

        // Отпуск чт 22.10 — чт 29.10: чт 22, вт 27, чт 29 и дежурства пт 23.
        val result = assertIs<VacationResult.Saved>(repo.vacation(Vacation(day(2), day(9))))
        assertEquals(s("Отпуск_22.10–29.10: удалено 4_тренировки"), result.message)
        val after = all()
        assertEquals(before.size - 4, after.size)
        assertTrue(after.none { it.seriesId != null && it.start.date in day(2)..day(9) })
        assertTrue(after.any { it.start == at(day(3), 10) })
        // План — на первую тренировку после отпуска, вт 03.11.
        assertEquals("Старт с тумбочки", after.single { it.start == at(day(14), 13, 30) }.plan)
        assertEquals(listOf(day(2), day(7), day(9)), db.scheduleDao().series(id)!!.skipDates)
        // Продление дни отпуска не вернёт.
        assertEquals(0, calendar.forms.extendSeries())

        repo.undo(result.undo)
        assertEquals(before.map { it.id to it.plan }.toSet(), all().map { it.id to it.plan }.toSet())
        assertEquals(emptyList(), db.scheduleDao().series(id)!!.skipDates)
    }

    @Test
    fun vacationDatesChecked(): Unit = runBlocking {
        assertEquals(VacationResult.Invalid("Не раньше сегодняшнего дня", null), repo.vacation(Vacation(day(-1), day(3))))
        assertEquals(VacationResult.Invalid(null, "Не раньше первого дня"), repo.vacation(Vacation(day(3), day(2))))
        // Серий нет — отпуск записан, удалять нечего.
        assertEquals(s("Отпуск_20.10–26.10 записан"), assertIs<VacationResult.Saved>(repo.vacation(Vacation(day(0), day(6)))).message)
    }

    @Test
    fun zoneListsOwnClubAndDuties(): Unit = runBlocking {
        val pool = placeId("ДБ")
        assertIs<RestrictionSaveResult.Saved>(
            calendar.restrictions.save(null, RestrictionDraft(pool, LocalTime(17, 0), LocalTime(18, 0), "Групповая тренировка", true, tueThu, LocalDate(2026, 9, 1))),
        )
        // Разовый вчера — уже не действует.
        calendar.restrictions.save(null, RestrictionDraft(pool, LocalTime(9, 0), LocalTime(10, 0), "", date = day(-1)))
        calendar.restrictions.save(null, RestrictionDraft(pool, LocalTime(9, 0), LocalTime(10, 0), "Чистка", date = day(3)))
        db.scheduleDao().upsertBlock(Samples.block(placeId("ББ")).copy(reason = "ДЮСШ", weekdays = setOf(DayOfWeek.MONDAY)))
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(day(3), 15), typeId("Деж"), emptyList(), null, placeId("ББ"), 240, Repeat(setOf(DayOfWeek.FRIDAY)))))
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(day(5), 11), typeId("Деж"), emptyList(), null, placeId("ДБ"), 240)))

        val lists = repo.zones(now).first()
        assertEquals(
            listOf("ДБ 17:00–18:00 — групповая тренировка" to "Каждый вт и чт", s("ДБ 09:00–10:00 — чистка") to s("пт_23.10.2026")),
            lists.own.map { it.title to it.subtitle },
        )
        assertTrue(lists.own.all { it.id != null })
        assertEquals(listOf("ББ 17:00–18:00 — ДЮСШ" to "Каждый пн"), lists.club.map { it.title to it.subtitle })
        assertNull(lists.club.single().id)
        assertEquals(
            listOf("ББ 15:00–19:00" to "Каждую пт с 23.10.2026", "ДБ 11:00–15:00" to s("вс_25.10.2026")),
            lists.duties.map { it.title to it.subtitle },
        )
        // Дежурство серией открывает свою ближайшую тренировку — пт 23.10.
        assertEquals(at(day(3), 15), db.trainingDao().training(lists.duties.first().id!!)!!.start)
    }
}
