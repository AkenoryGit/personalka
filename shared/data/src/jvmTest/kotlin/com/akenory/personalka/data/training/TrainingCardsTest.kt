package com.akenory.personalka.data.training

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.CancelReasons
import com.akenory.personalka.domain.training.ClientOutcome
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
import kotlin.test.assertTrue

/** Карточка тренировки и отмена (шаг 1.14, ТЗ 5.4, 5.6, 4.1, 4.2). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class TrainingCardsTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var cards: TrainingCardRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        cards = calendar.cards
        clients = ClientRepository(db) { millis++ }
        groups = GroupRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private suspend fun typeId(short: String) = settings.types.first().single { it.shortName == short }.id

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    private suspend fun client(last: String, first: String) = clients.create(ClientDraft(last, first))

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    /** Новая тренировка типа [type] с клиентами; id — по времени начала. */
    private suspend fun training(start: LocalDateTime, vararg clientIds: String, type: String = "ПТ", repeat: Repeat? = null, plan: String = ""): String {
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(start, typeId(type), clientIds.toList(), null, placeId("ББ"), 50, repeat, plan)))
        return db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start }.id
    }

    private suspend fun card(id: String): TrainingCard = assertNotNull(cards.card(id).first())

    private suspend fun statusOf(id: String, clientId: String) = db.trainingDao().participationRow(id, clientId)!!.status

    @Test
    fun cancelFortyMinutesBeforeIsCharged(): Unit = runBlocking {
        // Проверка шага: отмена за 40 минут — со списанием, за 61 — без (порог 60, 4.2).
        val orlov = client("Орлов", "Максим")
        val late = training(at(tuesday, 16, 20), orlov)
        val early = training(at(tuesday, 16, 41), orlov)

        val charged = assertNotNull(cards.cancel(late, emptyList(), CancelReasons.CANNOT_COME, now))
        assertEquals(s("Отмена со списанием: вт_20.10, 16:20"), charged.message)
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, statusOf(late, orlov))
        val lateCard = card(late)
        assertEquals(TrainingStatus.CANCELLED, lateCard.state.status)
        assertEquals(ClientOutcome.CANCELLED_CHARGED, lateCard.outcome)
        assertEquals(now, lateCard.clients.single().state.notifiedAt)

        assertNotNull(cards.cancel(early, emptyList(), CancelReasons.CANNOT_COME, now))
        assertEquals(ParticipationStatus.CANCELLED, statusOf(early, orlov))
        assertEquals(ClientOutcome.CANCELLED, card(early).outcome)

        // «Отменить» в сообщении — снова запланирована.
        cards.undo(charged.undo)
        assertEquals(TrainingStatus.PLANNED, card(late).state.status)
        assertEquals(ParticipationStatus.EXPECTED, statusOf(late, orlov))
    }

    @Test
    fun switchAndReasonChangeCharge(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val byCoach = training(at(tuesday, 16, 0), orlov)
        val manual = training(at(tuesday, 17, 0), orlov)

        // «По инициативе тренера» — без списания и за 20 минут.
        cards.cancel(byCoach, emptyList(), CancelReasons.BY_COACH, now)
        assertEquals(ParticipationStatus.CANCELLED, statusOf(byCoach, orlov))
        // Переключатель «Со списанием» вручную: за 1 ч 20 мин, но со списанием.
        cards.cancel(manual, emptyList(), CancelReasons.CANNOT_COME, now, charged = true)
        val row = db.trainingDao().participationRow(manual, orlov)!!
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, row.status)
        assertTrue(row.chargeOverridden)
    }

    @Test
    fun cancelOneClientOfSplitKeepsTraining(): Unit = runBlocking {
        val ilin = client("Ильин", "Глеб")
        val nosova = client("Носова", "Елена")
        val id = training(at(tuesday, 16, 0), ilin, nosova, type = "Сплит")

        val result = assertNotNull(cards.cancel(id, listOf(nosova), CancelReasons.CANNOT_COME, now))
        assertEquals(s("Отменено: Носова_Е."), result.message)
        val c = card(id)
        assertEquals(TrainingStatus.PLANNED, c.state.status)
        assertEquals(listOf(ClientOutcome.PLANNED, ClientOutcome.CANCELLED_CHARGED), c.clients.map { it.outcome })

        // Вернуть её одну — снова ожидается.
        cards.restoreClient(id, nosova)
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, nosova))
        // Отменить второго клиента нельзя дважды: уже отменённый — ничего не пишется.
        cards.cancel(id, listOf(ilin), null, now)
        assertNull(cards.cancel(id, listOf(ilin), null, now))
    }

    @Test
    fun markDoneFromFifteenMinutesBefore(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val soon = training(at(tuesday, 15, 55), orlov)
        val later = training(at(tuesday, 16, 0), orlov)

        assertNull(cards.markDone(later))
        val done = assertNotNull(cards.markDone(soon))
        assertEquals("Проведено", done.message)
        val c = card(soon)
        assertEquals(TrainingStatus.DONE, c.state.status)
        assertEquals(now, ClubTime.toMinute(assertNotNull(c.state.doneAt)))
        assertEquals(ParticipationStatus.ATTENDED, statusOf(soon, orlov))
        cards.undo(done.undo)
        assertEquals(TrainingStatus.PLANNED, card(soon).state.status)
    }

    @Test
    fun groupMarksChildrenAndAllCame(): Unit = runBlocking {
        val group = groups.create(GroupDraft("Вт 15:30 ББ", typeId("Группа"), placeId("ББ")))
        val kids = listOf(client("Белов", "Артём"), client("Гришин", "Матвей"), client("Данилова", "Ева"))
        kids.forEach { groups.addMember(group, it, LocalDate(2026, 9, 1)) }
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(tuesday, 15, 30), typeId("Группа"), emptyList(), group, placeId("ББ"), 60)))
        val id = db.trainingDao().trainingsOfGroup(group).single().id

        assertTrue(cards.toggleAttendance(id, kids[0]))
        assertEquals(ParticipationStatus.ATTENDED, statusOf(id, kids[0]))
        assertTrue(cards.toggleAttendance(id, kids[0]))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, kids[0]))
        cards.toggleAttendance(id, kids[1])

        assertNotNull(cards.markDone(id))
        assertEquals(TrainingStatus.DONE, card(id).state.status)
        assertEquals(List(3) { ParticipationStatus.ATTENDED }, kids.map { statusOf(id, it) })
        // В проведённой — исправление: «не пришёл».
        cards.toggleAttendance(id, kids[2])
        assertEquals(ParticipationStatus.CANCELLED, statusOf(id, kids[2]))
    }

    @Test
    fun returnCancelledAndDelete(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val id = training(at(tuesday, 17, 0), orlov)
        cards.cancel(id, emptyList(), CancelReasons.TRIP, at(tuesday, 9, 0))
        assertEquals(CancelReasons.TRIP, card(id).state.cancelReason)

        val back = assertNotNull(cards.restore(id))
        assertEquals("Тренировка возвращена", back.message)
        assertEquals(TrainingStatus.PLANNED, card(id).state.status)
        assertEquals(ParticipationStatus.EXPECTED, statusOf(id, orlov))

        val deleted = assertNotNull(cards.delete(id))
        assertNull(cards.card(id).first())
        cards.undo(deleted.undo)
        assertNotNull(cards.card(id).first())
    }

    @Test
    fun doneCannotBeDeleted(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val id = training(at(tuesday, 15, 0), orlov)
        assertNotNull(cards.markDone(id))
        assertNull(cards.delete(id))
        assertNotNull(cards.card(id).first())
    }

    @Test
    fun deletedSeriesDateIsNotRecreated(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val first = training(at(tuesday, 17, 0), orlov, repeat = Repeat(setOf(DayOfWeek.TUESDAY), null))
        val next = db.trainingDao().trainingsBetween(at(tuesday.plus(7, DateTimeUnit.DAY), 0), at(tuesday.plus(8, DateTimeUnit.DAY), 0)).single()
        assertTrue(card(next.id).skippable)

        assertNotNull(cards.delete(next.id))
        // Продление с горизонтом больше не создаёт тренировку на удалённую дату.
        calendar.forms.extendSeries()
        val dates = db.trainingDao().trainingsOfSeries(db.trainingDao().training(first)!!.seriesId!!).map { it.start.date }
        assertTrue(tuesday.plus(7, DateTimeUnit.DAY) !in dates)
    }

    @Test
    fun skipDateCarriesPlanToNext(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        training(at(tuesday, 17, 0), orlov, repeat = Repeat(setOf(DayOfWeek.TUESDAY), null), plan = "Спина 4 × 50 м")
        val seriesTrainings = db.trainingDao().trainingsBetween(at(tuesday, 0), at(tuesday.plus(15, DateTimeUnit.DAY), 0))
        val (thisOne, nextOne) = seriesTrainings
        val result = assertNotNull(cards.skipDate(thisOne.id))
        assertEquals(s("Дата пропущена: вт_20.10"), result.message)
        assertNull(cards.card(thisOne.id).first())
        assertEquals("Спина 4 × 50 м", db.trainingDao().training(nextOne.id)!!.plan)
        cards.undo(result.undo)
        assertNotNull(cards.card(thisOne.id).first())
    }

    @Test
    fun closeEndedMarksPersonalDoneAndWaitsForGroup(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val past = training(at(tuesday, 10, 0), orlov)
        val group = groups.create(GroupDraft("Вт 12:00 ББ", typeId("Группа"), placeId("ББ")))
        groups.addMember(group, client("Белов", "Артём"), LocalDate(2026, 9, 1))
        calendar.forms.create(TrainingDraft(at(tuesday, 12, 0), typeId("Группа"), emptyList(), group, placeId("ББ"), 60))

        assertEquals(1, cards.closeEnded())
        val c = card(past)
        assertEquals(TrainingStatus.DONE, c.state.status)
        // Закрылась сама в конце — «отмечено само в 10:50».
        assertEquals(at(tuesday, 10, 50), c.state.doneAt)
        // Группу без отметок никто не закрывает; повторный вызов ничего не меняет.
        assertEquals(TrainingStatus.PLANNED, db.trainingDao().trainingsOfGroup(group).single().status)
        assertEquals(0, cards.closeEnded())
    }

    @Test
    fun cardShowsSeriesAndWarnings(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val id = training(at(tuesday, 17, 0), orlov, repeat = Repeat(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), null))
        // Дежурство 16:30–18:30 под тренировкой.
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(at(tuesday, 16, 30), typeId("Деж"), emptyList(), null, placeId("ББ"), 120)))
        val c = card(id)
        assertEquals(s("Орлов_М."), c.title)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), c.seriesWeekdays)
        assertEquals(listOf("Поверх дежурства · ББ 16:30–18:30"), c.warnings)
    }
}
