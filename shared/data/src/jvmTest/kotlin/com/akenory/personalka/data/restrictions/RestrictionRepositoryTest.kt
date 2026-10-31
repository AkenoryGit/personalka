package com.akenory.personalka.data.restrictions

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.TrainingFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.restriction.RestrictionDraft
import com.akenory.personalka.domain.restriction.RestrictionField
import com.akenory.personalka.domain.training.TrainingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форма запрета (шаг 1.13а, ТЗ 5.5, 4.5). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class RestrictionRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var restrictions: RestrictionRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        restrictions = calendar.restrictions
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    /** ПТ в ДБ в [date], [h]:[m]. */
    private suspend fun training(date: LocalDate, h: Int, m: Int = 0): String {
        val client = ClientRepository(db) { millis++ }.create(ClientDraft("Орлов", "Максим"))
        val type = settings.types.first().single { it.shortName == "ПТ" }.id
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(LocalDateTime(date, LocalTime(h, m)), type, listOf(client), null, placeId("ДБ"), 50)))
        return db.trainingDao().trainingsBetween(LocalDateTime(date, LocalTime(0, 0)), LocalDateTime(date, LocalTime(23, 59))).single { it.start.hour == h && it.start.minute == m }.id
    }

    private suspend fun flag(id: String) = db.trainingDao().training(id)!!.overRestriction

    /** Макет «Запрет»: ДБ 17:00–18:00, групповая тренировка, каждый вт и чт с 01.09.2026, бессрочно. */
    private suspend fun group() = RestrictionDraft(
        locationId = placeId("ДБ"),
        start = LocalTime(17, 0),
        end = LocalTime(18, 0),
        reason = "Групповая тренировка",
        weekly = true,
        weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
        date = LocalDate(2026, 9, 1),
    )

    @Test
    fun createdRestrictionFlagsFutureTrainingsAndShowsInCalendar(): Unit = runBlocking {
        val later = training(tuesday, 17, 15)
        // Прошлый вторник: прошедшая сохраняет флаг, с которым прошла.
        val past = training(LocalDate(2026, 10, 13), 17, 15)
        assertFalse(flag(later))

        val result = assertIs<RestrictionSaveResult.Saved>(restrictions.save(null, group()))
        assertEquals(TrainingFormat.RESTRICTION_CREATED, result.message)
        // Флаг пересчитан у той, что ещё не началась (4.5); штриховка — в календаре, свой запрет можно изменить.
        assertTrue(flag(later))
        assertFalse(flag(past))
        val zone = calendar.zones(tuesday, LocalDate(2026, 10, 21)).first().single()
        assertEquals("ДБ 17:00–18:00 — групповая тренировка", zone.text)
        assertEquals("Каждый вт и чт", zone.repeat)
        assertTrue(zone.editable)
        val block = db.scheduleDao().blocks().single()
        assertEquals(Scope.COACH, block.scope)

        // «Отменить» — запрета нет, флаг снят.
        restrictions.undo(result.undo)
        assertEquals(emptyList(), db.scheduleDao().blocks())
        assertFalse(flag(later))
    }

    @Test
    fun editKeepsSkippedDaysAndRecalcs(): Unit = runBlocking {
        val later = training(tuesday, 17, 15)
        assertIs<RestrictionSaveResult.Saved>(restrictions.save(null, group()))
        val id = db.scheduleDao().blocks().single().id
        calendar.skipRestrictionDay(id, LocalDate(2026, 10, 22))
        assertEquals(group(), restrictions.stored(id))

        // Сдвинули на 18:30–19:30 — тренировка 17:15–18:05 уже не под ним; пропущенный чт 22.10 остался.
        val moved = assertIs<RestrictionSaveResult.Saved>(restrictions.save(id, group().copy(start = LocalTime(18, 30), end = LocalTime(19, 30))))
        assertEquals(TrainingFormat.RESTRICTION_SAVED, moved.message)
        assertFalse(flag(later))
        assertEquals(listOf(LocalDate(2026, 10, 22)), db.scheduleDao().block(id)!!.skipDates)

        restrictions.undo(moved.undo)
        assertEquals(LocalTime(17, 0), db.scheduleDao().block(id)!!.startTime)
        assertTrue(flag(later))
    }

    @Test
    fun deleteAndUndo(): Unit = runBlocking {
        val later = training(tuesday, 17, 15)
        restrictions.save(null, group())
        val id = db.scheduleDao().blocks().single().id

        val undo = assertNotNull(restrictions.delete(id))
        assertEquals(emptyList(), db.scheduleDao().blocks())
        assertFalse(flag(later))
        assertNull(restrictions.stored(id))

        restrictions.undo(undo)
        assertEquals(id, db.scheduleDao().blocks().single().id)
        assertTrue(flag(later))
    }

    /** «Удалить → Этот и все следующие» (шаг 5.3р): с чт 22.10 запрета нет, во вт 20.10 он остаётся. */
    @Test
    fun endBeforeAndUndo(): Unit = runBlocking {
        val today = training(tuesday, 17, 15)
        val thursday = training(LocalDate(2026, 10, 22), 17, 15)
        restrictions.save(null, group())
        val id = db.scheduleDao().blocks().single().id
        assertTrue(flag(thursday))

        val (message, undo) = assertNotNull(restrictions.endBefore(id, LocalDate(2026, 10, 22)))
        assertEquals(ZoneFormat.restrictionEnded(LocalDate(2026, 10, 22)), message)
        assertEquals(LocalDate(2026, 10, 21), db.scheduleDao().block(id)!!.validTo)
        assertTrue(flag(today))
        assertFalse(flag(thursday))

        restrictions.undo(undo)
        assertNull(db.scheduleDao().block(id)!!.validTo)
        assertTrue(flag(thursday))

        // С первого дня — удаляется целиком.
        val (whole, _) = assertNotNull(restrictions.endBefore(id, LocalDate(2026, 9, 1)))
        assertEquals(ZoneFormat.RESTRICTION_DELETED, whole)
        assertEquals(emptyList(), db.scheduleDao().blocks())
    }

    @Test
    fun clubRestrictionIsNotEditable(): Unit = runBlocking {
        db.scheduleDao().upsertBlock(Samples.block(placeId("ДБ")))
        val id = db.scheduleDao().blocks().single().id
        assertNull(restrictions.stored(id))
        assertEquals(RestrictionSaveResult.Invalid(emptyMap()), restrictions.save(id, group()))
        assertNull(restrictions.delete(id))
        assertFalse(calendar.zones(tuesday, LocalDate(2026, 10, 21)).first().single().editable)
    }

    @Test
    fun problemsNotSaved(): Unit = runBlocking {
        val result = restrictions.save(null, group().copy(end = LocalTime(16, 0), weekdays = emptySet()))
        assertEquals(setOf(RestrictionField.TIME, RestrictionField.WEEKDAYS), assertIs<RestrictionSaveResult.Invalid>(result).problems.keys)
        assertEquals(emptyList(), db.scheduleDao().blocks())
    }

    @Test
    fun newRestrictionOnSeveralPlacesAndOneUndo(): Unit = runBlocking {
        val later = training(LocalDate(2026, 10, 22), 17, 15)
        val places = listOf(placeId("ББ"), placeId("ДБ"), placeId("Зал"))
        // Шаг 1.13д: по запрету на каждое место, флаг пересчитан у тренировки в ДБ.
        val draft = group().copy(locationId = places[0], otherPlaces = setOf(places[1], places[2]), reason = "Свои дела")
        val saved = assertIs<RestrictionSaveResult.Saved>(restrictions.save(null, draft))
        assertEquals("Запреты созданы в 3\u00A0местах", saved.message)
        assertEquals(places.toSet(), db.scheduleDao().blocks().map { it.locationId }.toSet())
        assertTrue(flag(later))
        // Каждый меняется сам — в форме изменения одно место.
        val one = db.scheduleDao().blocks().first()
        assertEquals(emptySet(), assertNotNull(restrictions.stored(one.id)).otherPlaces)

        restrictions.undo(saved.undo)
        assertEquals(emptyList(), db.scheduleDao().blocks())
        assertFalse(flag(later))
    }
}
