package com.akenory.personalka.data.calendar

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Тренировки календаря в базе (шаг 1.9, ТЗ 5.3). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class CalendarRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db)
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

    /** Тренировка с клиентами в порядке записи; у каждого участия — свой момент создания. */
    private suspend fun training(
        type: String,
        place: String,
        start: LocalDateTime,
        minutes: Int = 50,
        clientIds: List<String> = emptyList(),
        groupId: String? = null,
        status: TrainingStatus = TrainingStatus.PLANNED,
        statuses: List<ParticipationStatus> = clientIds.map { ParticipationStatus.EXPECTED },
    ): String {
        val t = Samples.training(typeId(type), placeId(place), start).copy(durationMin = minutes, groupId = groupId, status = status)
        db.trainingDao().upsertTraining(t)
        clientIds.forEachIndexed { i, id ->
            val p = Samples.participation(t.id, id)
            db.trainingDao().upsertParticipation(
                p.copy(sync = p.sync.copy(createdAt = millis + i), status = statuses[i], behavior = null, cancelReason = null, notifiedAt = null, chargeOverridden = false),
            )
        }
        return t.id
    }

    private suspend fun client(last: String, first: String) = clients.create(ClientDraft(last, first))

    private suspend fun day(date: LocalDate = tuesday) = calendar.trainings(date, date.plus(1, DateTimeUnit.DAY)).first()

    @Test
    fun titlesTypePlaceAndColor(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val petrov = client("Петров", "Игорь")
        val sidorov = client("Сидоров", "Олег")
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(8, 0)), clientIds = listOf(morozov))
        // Сидорова записали первым — он и в имени первый, хоть по алфавиту Петров раньше.
        training("Сплит", "ББ", LocalDateTime(tuesday, LocalTime(9, 0)), clientIds = listOf(sidorov, petrov))
        training("Гидро", "ДБ", LocalDateTime(tuesday, LocalTime(11, 0)))

        val list = day()
        assertEquals(listOf(s("Морозов_Д."), "Сидоров / Петров", "Гидрореабилитация"), list.map { it.title })
        val pt = list.first()
        assertEquals("ПТ", pt.typeShort)
        assertEquals("ББ", pt.placeShort)
        assertEquals("blue", pt.color)
        assertEquals(50, pt.durationMin)
        assertFalse(pt.cancelled)
    }

    @Test
    fun groupShowsNameAndWaitingKids(): Unit = runBlocking {
        val groupId = groups.create(GroupDraft("Вт, Чт 18:00 ДБ", typeId("Группа"), placeId("ДБ")))
        val kids = listOf(client("Белова", "Ева"), client("Гусев", "Борис"), client("Ильин", "Глеб"))
        training(
            "Группа", "ДБ", LocalDateTime(tuesday, LocalTime(18, 0)), minutes = 60, clientIds = kids, groupId = groupId,
            statuses = listOf(ParticipationStatus.EXPECTED, ParticipationStatus.CANCELLED, ParticipationStatus.EXPECTED),
        )
        // Отменённого ребёнка не ждут: в скобках двое.
        assertEquals("Вт, Чт 18:00 ДБ (2)", day().single().title)
    }

    @Test
    fun cancelledShownDutyAndDeletedNot(): Unit = runBlocking {
        val zaitseva = client("Зайцева", "Мария")
        training(
            "ПТ", "ББ", LocalDateTime(tuesday, LocalTime(10, 0)), clientIds = listOf(zaitseva), status = TrainingStatus.CANCELLED,
            statuses = listOf(ParticipationStatus.CANCELLED_CHARGED),
        )
        // Дежурство — заливка под тренировками (шаг 1.10), а не блок.
        training("Деж", "ББ", LocalDateTime(tuesday, LocalTime(11, 0)), minutes = 240)
        val gone = training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(12, 0)))
        db.trainingDao().training(gone)!!.let { db.trainingDao().upsertTraining(it.copy(sync = it.sync.deleted(millis))) }

        val cancelled = day().single()
        assertTrue(cancelled.cancelled)
        assertEquals(s("Зайцева_М."), cancelled.title)
    }

    @Test
    fun daysAreHalfOpen(): Unit = runBlocking {
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(0, 0)))
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(23, 30)))
        training("ПТ", "ББ", LocalDateTime(LocalDate(2026, 10, 21), LocalTime(0, 0)))
        training("ПТ", "ББ", LocalDateTime(LocalDate(2026, 10, 19), LocalTime(23, 59)))
        assertEquals(listOf(LocalTime(0, 0), LocalTime(23, 30)), day().map { it.start.time })
        // Три дня сразу — по времени.
        assertEquals(4, calendar.trainings(LocalDate(2026, 10, 19), LocalDate(2026, 10, 22)).first().size)
    }

    @Test
    fun namesFollowEdits(): Unit = runBlocking {
        val id = client("Белова", "Ева")
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(10, 0)), clientIds = listOf(id))
        assertEquals(s("Белова_Е."), day().single().title)
        clients.update(id, ClientDraft("Белова-Смирнова", "Ева"))
        assertEquals(s("Белова-Смирнова_Е."), day().single().title)
    }

    @Test
    fun workHoursFromSettings(): Unit = runBlocking {
        assertEquals(WorkHours(LocalTime(7, 0), LocalTime(23, 0)), calendar.workHours.first())
        val coach = db.coachDao().currentCoach()!!
        val stored = db.coachDao().settings(coach.id)!!
        db.coachDao().upsertSettings(stored.copy(workDayStart = LocalTime(8, 0), workDayEnd = LocalTime(21, 0)))
        assertEquals(WorkHours(LocalTime(8, 0), LocalTime(21, 0)), calendar.workHours.first())
    }
}
