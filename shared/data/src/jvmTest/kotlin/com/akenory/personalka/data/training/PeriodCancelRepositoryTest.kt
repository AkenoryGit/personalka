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
import kotlin.test.assertTrue

/**
 * «Не будет на период» (4.2а, шаг 5.3у) и отмена ребёнка в группе без списания. «Сейчас» — вт 20.10.2026 15:40.
 * Имена придуманы.
 */
class PeriodCancelRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private val millis = ClubTime.toMillis(now)
    private var tick = millis
    private lateinit var calendar: CalendarRepository
    private lateinit var cards: TrainingCardRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)

    private val brass = "Брасс: согласование рук и ног, 8 × 25 м."

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        cards = calendar.cards
        clients = ClientRepository(db) { tick++ }
        groups = GroupRepository(db) { tick++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private suspend fun typeId(short: String) = settings.types.first().single { it.shortName == short }.id

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private suspend fun training(start: LocalDateTime, vararg clientIds: String, type: String = "ПТ", groupId: String? = null, plan: String = "", repeat: Repeat? = null): String {
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(start, typeId(type), clientIds.toList(), groupId, placeId("ББ"), 50, repeat, plan)))
        return db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start }.id
    }

    private suspend fun t(id: String) = db.trainingDao().training(id)!!

    private suspend fun statusOf(id: String, clientId: String) = db.trainingDao().participationsOf(id).single { it.clientId == clientId }.status

    @Test
    fun cancelsEverythingInThePeriodWithOneReason(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val belov = clients.create(ClientDraft("Белов", "Артём"))
        val group = groups.create(GroupDraft("Чт 18:00 ББ", typeId("Группа"), placeId("ББ")))
        groups.addMember(group, orlov, LocalDate(2026, 9, 1))
        groups.addMember(group, belov, LocalDate(2026, 9, 1))
        // Каждый вторник 17:00 с планом сегодня; сегодня же в 16:00 разовая — до неё 20 минут.
        val first = training(at(tuesday, 17), orlov, plan = brass, repeat = Repeat(setOf(DayOfWeek.TUESDAY), null))
        val soon = training(at(tuesday, 16), orlov)
        val thursday = training(at(LocalDate(2026, 10, 22), 18), type = "Группа", groupId = group)
        val to = LocalDate(2026, 11, 3)

        val preview = cards.periodPreview(orlov, tuesday, to)
        // Вт 20.10 16:00 и 17:00, чт 22.10 группа, вт 27.10, вт 03.11.
        assertEquals(PeriodPreview(5, tuesday, to, charged = 1), preview)

        val result = assertNotNull(cards.cancelPeriod(orlov, tuesday, to, "Отъезд"))
        assertEquals(s("Отменено 5_тренировок: 20.10–03.11"), result.message)
        assertEquals(TrainingStatus.CANCELLED, t(first).status)
        assertEquals("Отъезд", t(first).cancelReason)
        assertEquals(ParticipationStatus.CANCELLED, statusOf(first, orlov))
        assertEquals(ParticipationStatus.CANCELLED_CHARGED, statusOf(soon, orlov))
        // Группа идёт — отменён только он, без списания.
        assertEquals(TrainingStatus.PLANNED, t(thursday).status)
        assertEquals(ParticipationStatus.CANCELLED, statusOf(thursday, orlov))
        assertEquals(ParticipationStatus.EXPECTED, statusOf(thursday, belov))
        // План сегодняшней — на первую после периода, вт 10.11.
        val after = db.trainingDao().trainingsBetween(at(LocalDate(2026, 11, 10), 0), at(LocalDate(2026, 11, 11), 0)).single()
        assertEquals(brass, after.plan)
        assertEquals(PeriodPreview.EMPTY, cards.periodPreview(orlov, tuesday, to))

        // «Отменить» в сообщении — всё как было.
        cards.undo(result.undo)
        assertEquals(TrainingStatus.PLANNED, t(first).status)
        assertEquals(ParticipationStatus.EXPECTED, statusOf(thursday, orlov))
        assertTrue(db.trainingDao().training(after.id)!!.plan.isNullOrBlank())
    }

    @Test
    fun periodBeyondTheCalendarCreatesSeriesTrainingsFirst(): Unit = runBlocking {
        // Горизонт 8 недель — по 15.12; лето «нас не будет» лежит дальше.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        training(at(tuesday, 17), orlov, repeat = Repeat(setOf(DayOfWeek.TUESDAY), null))
        val from = LocalDate(2027, 1, 5)
        val to = LocalDate(2027, 1, 19)
        assertEquals(3, cards.periodPreview(orlov, from, to).count)
        val result = assertNotNull(cards.cancelPeriod(orlov, from, to, "Отъезд"))
        assertEquals(s("Отменены 3_тренировки: 05.01–19.01"), result.message)
        val list = db.trainingDao().trainingsBetween(at(from, 0), at(to, 23))
        assertEquals(3, list.size)
        list.forEach { assertEquals(TrainingStatus.CANCELLED, it.status) }
        // Обычное продление потом их не дублирует.
        calendar.forms.extendSeries()
        assertEquals(3, db.trainingDao().trainingsBetween(at(from, 0), at(to, 23)).size)
    }

    @Test
    fun groupChildCancelledLateIsNotCharged(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val belov = clients.create(ClientDraft("Белов", "Артём"))
        val group = groups.create(GroupDraft("Вт 16:00 ББ", typeId("Группа"), placeId("ББ")))
        groups.addMember(group, orlov, LocalDate(2026, 9, 1))
        groups.addMember(group, belov, LocalDate(2026, 9, 1))
        val today = training(at(tuesday, 16), type = "Группа", groupId = group)
        // За 20 минут и даже с переключателем — в группе без списания (решение 01.11.2026).
        assertNotNull(cards.cancel(today, listOf(orlov), "Не сможет", now, charged = true))
        assertEquals(ParticipationStatus.CANCELLED, statusOf(today, orlov))
    }
}
