package com.akenory.personalka.data.training

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * План отменённой — на ближайшей тренировке клиента (ТЗ 4.3, уточнение 25.10.2026): поставили тренировку раньше
 * той, куда план уже перенёсся, перенесли её позже, удалили или пропустили дату серии. «Сейчас» — вт 20.10.2026
 * 15:40, отменяется вт 17:00. Имена придуманы.
 */
class PlanAnchorTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var cards: TrainingCardRepository
    private lateinit var clients: ClientRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val thursday = LocalDate(2026, 10, 22)
    private val nextTuesday = LocalDate(2026, 10, 27)
    private val nextThursday = LocalDate(2026, 10, 29)

    private val brass = "Брасс: согласование рук и ног, 8 × 25 м."
    private val back = "Кроль на спине: 4 × 50 м, повороты."

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        cards = calendar.cards
        clients = ClientRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun typeId(short: String) = settings.types.first().single { it.shortName == short }.id

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private suspend fun draft(start: LocalDateTime, clientId: String, plan: String = "", repeat: Repeat? = null) =
        TrainingDraft(start, typeId("ПТ"), listOf(clientId), null, placeId("ББ"), 50, repeat, plan)

    private suspend fun create(start: LocalDateTime, clientId: String, plan: String = "", repeat: Repeat? = null): SaveResult.Saved =
        assertIs<SaveResult.Saved>(calendar.forms.create(draft(start, clientId, plan, repeat)))

    private suspend fun id(start: LocalDateTime): String =
        db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start && it.sync.deletedAt == null }.id

    private suspend fun training(start: LocalDateTime, clientId: String, plan: String = ""): String {
        create(start, clientId, plan)
        return id(start)
    }

    private suspend fun t(id: String) = db.trainingDao().training(id)!!

    private suspend fun cancel(id: String) = assertNotNull(cards.cancel(id, emptyList(), "Не сможет", now))

    @Test
    fun earlierNewTrainingTakesCarriedPlan(): Unit = runBlocking {
        // Случай Дмитрия: вт отменили — план ушёл на вт 27.10; потом поставили чт 22.10 — план должен быть на ней.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val later = training(at(nextTuesday, 17), orlov)
        cancel(today)
        assertEquals(brass, t(later).plan)

        val saved = create(at(thursday, 17), orlov)
        val nearest = id(at(thursday, 17))
        assertEquals(brass, t(nearest).plan)
        assertEquals(today, t(nearest).planCarriedFromId)
        assertEquals(brass, t(nearest).planCarriedText)
        assertNull(t(later).plan)
        assertNull(t(later).planCarriedFromId)
        // В карточке отменённой — новая дата.
        assertEquals(thursday, cards.card(today).first()!!.carriedTo)

        // «Отменить» создание — план снова на вт 27.10.
        calendar.forms.undo(saved.undo)
        assertEquals(brass, t(later).plan)
        assertEquals(today, t(later).planCarriedFromId)

        // Снова поставили и вернули отмену — план уходит с ближайшей.
        create(at(thursday, 17), orlov)
        val again = id(at(thursday, 17))
        assertNotNull(cards.restore(today))
        assertEquals(TrainingStatus.PLANNED, t(today).status)
        assertNull(t(again).plan)
        assertNull(t(later).plan)
    }

    @Test
    fun earlierTrainingWithOwnPlanGetsItAdded(): Unit = runBlocking {
        // У новой ближайшей свой план из формы — перенесённый дописывается с новой строки.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val later = training(at(nextTuesday, 17), orlov)
        cancel(today)

        val nearest = training(at(thursday, 17), orlov, plan = back)
        assertEquals("$back\n$brass", t(nearest).plan)
        assertNull(t(later).plan)
    }

    @Test
    fun editedCarriedPlanStays(): Unit = runBlocking {
        // Перенесённый план уже правили — правку не двигаем.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val later = training(at(nextTuesday, 17), orlov)
        cancel(today)
        calendar.diaries.savePlan(later, "$brass Старт с тумбы.")

        val nearest = training(at(thursday, 17), orlov)
        assertNull(t(nearest).plan)
        assertEquals("$brass Старт с тумбы.", t(later).plan)
    }

    @Test
    fun otherClientsTrainingDoesNotTakeIt(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val belova = clients.create(ClientDraft("Белова", "Ева"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val later = training(at(nextTuesday, 17), orlov)
        cancel(today)

        val other = training(at(thursday, 17), belova)
        assertNull(t(other).plan)
        assertEquals(brass, t(later).plan)
    }

    @Test
    fun movedLaterPassesPlanToNearest(): Unit = runBlocking {
        // Тренировку с перенесённым планом передвинули за следующую — план остаётся на ближайшей.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val holder = training(at(thursday, 17), orlov)
        val after = training(at(nextTuesday, 17), orlov)
        cancel(today)
        assertEquals(brass, t(holder).plan)

        val stored = assertNotNull(calendar.forms.stored(holder))
        assertIs<SaveResult.Saved>(calendar.forms.update(holder, stored.draft.copy(start = at(nextThursday, 17))))
        assertNull(t(holder).plan)
        assertEquals(brass, t(after).plan)
        assertEquals(today, t(after).planCarriedFromId)
    }

    @Test
    fun deletedPassesPlanOnOrToPending(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val holder = training(at(thursday, 17), orlov)
        val after = training(at(nextTuesday, 17), orlov)
        cancel(today)

        // Удалили ближайшую — план на следующую.
        val deleted = assertNotNull(cards.delete(holder))
        assertEquals(brass, t(after).plan)
        assertEquals(today, t(after).planCarriedFromId)
        // «Отменить» — всё как было.
        cards.undo(deleted.undo)
        assertEquals(brass, t(holder).plan)
        assertNull(t(after).plan)

        // Удалили обе — план ждёт в отложенном и встаёт в первую новую.
        cards.delete(holder)
        cards.delete(after)
        assertEquals(brass, db.clientDao().client(orlov)!!.pendingPlan)
        val fresh = training(at(nextThursday, 17), orlov)
        assertEquals(brass, t(fresh).plan)
        assertNull(db.clientDao().client(orlov)!!.pendingPlan)
    }

    @Test
    fun skippedSeriesDateGoesToNearestNotNextOfSeries(): Unit = runBlocking {
        // Серия по вторникам; план перенёсся на вт 27.10. Дату пропустили — серия отдала бы план на вт 3.11,
        // но раньше есть разовая чт 29.10 — план на ней.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        create(at(tuesday, 17), orlov, plan = brass, repeat = Repeat(setOf(DayOfWeek.TUESDAY), null))
        val today = id(at(tuesday, 17))
        val holder = id(at(nextTuesday, 17))
        val series = id(at(LocalDate(2026, 11, 3), 17))
        val single = training(at(nextThursday, 17), orlov)
        cancel(today)
        assertEquals(brass, t(holder).plan)

        assertNotNull(cards.skipDate(holder))
        assertEquals(brass, t(single).plan)
        assertEquals(today, t(single).planCarriedFromId)
        assertNull(t(series).plan)
    }

    @Test
    fun cancelledHolderCarriesAsUsual(): Unit = runBlocking {
        // Отменили и ту, куда план перенёсся, — он идёт дальше обычным переносом.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val holder = training(at(thursday, 17), orlov)
        val after = training(at(nextTuesday, 17), orlov)
        cancel(today)
        cancel(holder)
        assertEquals(brass, t(after).plan)
        assertEquals(holder, t(after).planCarriedFromId)
    }

    @Test
    fun diaryPlanForNextMovesToEarlierNewTraining(): Unit = runBlocking {
        // Уточнение 26.10.2026: «План на следующую» в дневнике — на вт 27.10; поставили чт 22.10 — план на чт.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val morning = training(at(tuesday, 10), orlov)
        val later = training(at(nextTuesday, 17), orlov)
        calendar.diaries.saveNextPlan(morning, brass)
        assertEquals(brass, t(later).plan)

        val saved = create(at(thursday, 17), orlov)
        val nearest = id(at(thursday, 17))
        assertEquals(brass, t(nearest).plan)
        assertNull(t(later).plan)
        // Дневник теперь показывает и правит план ближайшей.
        calendar.diaries.saveNextPlan(morning, back)
        assertEquals(back, t(nearest).plan)
        assertNull(t(later).plan)
        calendar.diaries.saveNextPlan(morning, brass)

        // «Отменить» создание — план снова на вт 27.10.
        calendar.forms.undo(saved.undo)
        assertEquals(brass, t(later).plan)
    }

    @Test
    fun plansWrittenBeforeStayInPlace(): Unit = runBlocking {
        // Уже расписанные планы — свои планы тренировок и «План на следующую», записанный до исправления (без
        // отметки, откуда он), — новая тренировка раньше не трогает.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val own = training(at(nextTuesday, 17), orlov, plan = back)
        val old = training(at(nextThursday, 17), orlov)
        db.trainingDao().upsertTraining(t(old).copy(plan = brass))

        val nearest = training(at(thursday, 17), orlov)
        assertNull(t(nearest).plan)
        assertEquals(back, t(own).plan)
        assertEquals(brass, t(old).plan)
    }

    @Test
    fun editedDiaryPlanStays(): Unit = runBlocking {
        // План ближайшей потом правили в её карточке — правка остаётся на месте.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val morning = training(at(tuesday, 10), orlov)
        val later = training(at(nextTuesday, 17), orlov)
        calendar.diaries.saveNextPlan(morning, brass)
        calendar.diaries.savePlan(later, "$brass Старт с тумбы.")

        val nearest = training(at(thursday, 17), orlov)
        assertNull(t(nearest).plan)
        assertEquals("$brass Старт с тумбы.", t(later).plan)
    }
}
