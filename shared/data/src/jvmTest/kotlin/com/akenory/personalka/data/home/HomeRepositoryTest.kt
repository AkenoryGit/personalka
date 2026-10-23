package com.akenory.personalka.data.home

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
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.Repeat
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

/** «Главная» без денег (шаг 1.16, ТЗ 5.2): что читается из базы. «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class HomeRepositoryTest {
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
        calendar = CalendarRepository(db) { millis }
        clients = ClientRepository(db) { millis++ }
        groups = GroupRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun typeId(short: String) = settings.types.first().single { it.shortName == short }.id

    private suspend fun placeId(short: String) = settings.places.first().single { it.shortName == short }.id

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    @Test
    fun nextWithSeriesAndPlanAndOldUnmarkedGroup(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        calendar.forms.create(
            TrainingDraft(at(tuesday, 16, 20), typeId("ПТ"), listOf(orlov), null, placeId("ББ"), 50, Repeat(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), null), plan = "Ноги в ластах 4 × 50 м"),
        )
        // Группа неделю назад: никто не отмечен — до сих пор без отметки, хоть и раньше сегодняшнего дня.
        val group = groups.create(GroupDraft("Вт 12:00 ББ", typeId("Группа"), placeId("ББ")))
        groups.addMember(group, clients.create(ClientDraft("Белов", "Артём")), LocalDate(2026, 9, 1))
        groups.addMember(group, clients.create(ClientDraft("Аксёнова", "Вера")), LocalDate(2026, 9, 1))
        calendar.forms.create(TrainingDraft(at(LocalDate(2026, 10, 13), 12, 0), typeId("Группа"), emptyList(), group, placeId("ББ"), 50))
        calendar.cards.closeEnded()

        val data = calendar.home.data(tuesday).first()
        val up = HomeRules.upcoming(data.trainings, now)
        val next = up.next!!
        assertEquals(s("Орлов_М."), next.title)
        assertEquals("Ноги в ластах 4 × 50 м", next.plan)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), next.weekdays)
        assertEquals("ББ", next.placeShort)
        // Серия продлена вперёд: следующая — в четверг.
        assertEquals(at(LocalDate(2026, 10, 22), 16, 20), up.later?.start)

        val todo = HomeRules.todo(data.trainings, now, data.settings, backup = null).single()
        assertEquals(TodoKind.UNMARKED, todo.kind)
        assertEquals(1, todo.count)
        val unmarked = data.trainings.single { it.id == todo.trainingId }
        assertEquals(TrainingKind.GROUP, unmarked.state.kind)
        assertEquals("Вт 12:00 ББ (2)", unmarked.title)

        // «Все пришли» — группа отмечена, пункт исчез.
        calendar.cards.markDone(unmarked.id)
        val after = calendar.home.data(tuesday).first()
        assertEquals(emptyList(), HomeRules.todo(after.trainings, now, after.settings, backup = null))
    }
}
