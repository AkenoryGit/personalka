package com.akenory.personalka.data.money

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.groups.sharing
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.Sharing
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Коллеги, поля совместной группы и «Кто ведёт» в карточке (шаг 3.7г, ТЗ 4.8, 5.4, 5.10, 5.16). «Сейчас» —
 * вс 25.10.2026 10:00. Имена придуманы.
 */
class ColleagueRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 25, 10, 0)
    private val today = now.date
    private var millis = ClubTime.toMillis(now)
    private lateinit var settings: SettingsRepository
    private lateinit var groups: GroupRepository
    private lateinit var calendar: CalendarRepository
    private lateinit var groupTypeId: String
    private lateinit var bbId: String

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis++ }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        groupTypeId = settings.types.first().single { it.shortName == "Группа" }.id
        bbId = settings.places.first().single { it.shortName == "ББ" }.id
        groups = GroupRepository(db) { millis++ }
        calendar = CalendarRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun groupTraining(groupId: String, date: LocalDate, sharing: Sharing = Sharing.SELF, status: TrainingStatus = TrainingStatus.PLANNED): String {
        val t = Samples.training(groupTypeId, bbId, LocalDateTime(date, LocalTime(9, 0))).copy(
            groupId = groupId,
            status = status,
            owner = sharing.owner,
            ownerColleagueId = sharing.ownerColleagueId,
            conductedBy = sharing.conductedBy,
            colleagueId = sharing.colleagueId,
        )
        db.trainingDao().upsertTraining(t)
        return t.id
    }

    private suspend fun sharingOf(trainingId: String): Sharing = db.trainingDao().training(trainingId)!!.sharing()

    @Test
    fun colleagueSchemeStartsInJanuaryAndChangesFromMonth(): Unit = runBlocking {
        val lena = settings.colleagues.add(" Лена ", PaySchemeKind.NONE, today)
        val row = settings.colleagues.rows(today).first().single()
        assertEquals("Лена", row.colleague.name)
        assertEquals(PaySchemeKind.NONE, row.kind)
        assertEquals(listOf(LocalDate(2026, 1, 1)), row.schemes.map { it.validFrom })

        // Смена схемы с ноября: октябрь — по прежней.
        settings.colleagues.update(lena, "Лена", PaySchemeKind.HALF_OFFICIAL, LocalDate(2026, 11, 1))
        assertEquals(PaySchemeKind.NONE, settings.colleagues.rows(today).first().single().kind)
        assertEquals(PaySchemeKind.HALF_OFFICIAL, settings.colleagues.rows(LocalDate(2026, 11, 2)).first().single().kind)
        // Та же схема с того же месяца — новой версии нет; другая в том же месяце — правка версии.
        settings.colleagues.update(lena, "Лена", PaySchemeKind.HALF_OFFICIAL, LocalDate(2026, 11, 1))
        settings.colleagues.update(lena, "Елена", PaySchemeKind.NONE, LocalDate(2026, 11, 1))
        val after = settings.colleagues.rows(LocalDate(2026, 11, 2)).first().single()
        assertEquals("Елена", after.colleague.name)
        assertEquals(PaySchemeKind.NONE, after.kind)
        assertEquals(2, after.schemes.size)

        // Схема коллеги идёт в расчёт: доля налога владельца группы (4.8).
        settings.colleagues.update(lena, "Елена", PaySchemeKind.HALF_OFFICIAL, LocalDate(2026, 11, 1))
        val owners = db.moneyDao().colleagueSchemes().filter { it.colleagueId == lena }.map { it.kind }
        assertEquals(listOf(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL), owners)

        settings.colleagues.archive(lena, true)
        assertTrue(settings.colleagues.rows(today).first().single().colleague.archived)
    }

    @Test
    fun groupSharingGoesToFutureUnchangedTrainings(): Unit = runBlocking {
        val lena = settings.colleagues.add("Лена", PaySchemeKind.NONE, today)
        val ira = settings.colleagues.add("Ира", PaySchemeKind.NONE, today)
        val group = groups.create(GroupDraft("Сб 9:00 ББ", groupTypeId, bbId))
        assertEquals(Sharing.SELF, db.clientDao().group(group)!!.sharing())

        val past = groupTraining(group, LocalDate(2026, 10, 24), status = TrainingStatus.DONE)
        val next = groupTraining(group, LocalDate(2026, 10, 31))
        val replaced = groupTraining(group, LocalDate(2026, 11, 7), Sharing(conductedBy = ConductedBy.COLLEAGUE, colleagueId = ira))

        val together = Sharing(Owner.SELF, null, ConductedBy.SELF_AND_COLLEAGUE, lena)
        groups.update(group, GroupDraft("Сб 9:00 ББ", groupTypeId, bbId, together))
        assertEquals(together, db.clientDao().group(group)!!.sharing())
        assertEquals(together, sharingOf(next))
        // Прошедшая и изменённая в карточке — как были.
        assertEquals(Sharing.SELF, sharingOf(past))
        assertEquals(Sharing(conductedBy = ConductedBy.COLLEAGUE, colleagueId = ira), sharingOf(replaced))

        // Группа коллеги: коллега в тренировке — владелец.
        groups.update(group, GroupDraft("Сб 9:00 ББ", groupTypeId, bbId, Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF)))
        assertEquals(Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF, lena), sharingOf(next))
    }

    @Test
    fun cardShowsSharingOnlyWithCoCoachingAndSavesWithUndo(): Unit = runBlocking {
        val lena = settings.colleagues.add("Лена", PaySchemeKind.NONE, today)
        val group = groups.create(GroupDraft("Сб 9:00 ББ", groupTypeId, bbId))
        val id = groupTraining(group, LocalDate(2026, 10, 31))
        val cards = calendar.cards

        // Выключено — строки «Кто ведёт» нет (10.7).
        assertNull(assertNotNull(cards.card(id).first()).sharing)
        settings.salary.saveCoCoaching(true)
        val shown = assertNotNull(assertNotNull(cards.card(id).first()).sharing)
        assertEquals(Sharing.SELF, shown.sharing)
        assertEquals(listOf(lena to "Лена"), shown.choices)

        val together = Sharing(Owner.SELF, null, ConductedBy.SELF_AND_COLLEAGUE, lena)
        val result = assertNotNull(cards.setSharing(id, together))
        assertEquals("Кто ведёт — сохранено", result.message)
        assertEquals(together, sharingOf(id))
        assertNull(cards.setSharing(id, together))

        // Архивный коллега, уже выбранный в тренировке, остаётся в выборе; новых архивных нет.
        settings.colleagues.archive(lena, true)
        assertEquals(listOf(lena to "Лена"), assertNotNull(cards.card(id).first()!!.sharing).choices)

        cards.undo(result.undo)
        assertEquals(Sharing.SELF, sharingOf(id))
        assertEquals(emptyList(), assertNotNull(cards.card(id).first()!!.sharing).choices)
    }

    @Test
    fun calendarShowsWhoLeadsOnlyWithCoCoaching(): Unit = runBlocking {
        val lena = settings.colleagues.add("Лена", PaySchemeKind.NONE, today)
        val group = groups.create(GroupDraft("Сб 9:00 ББ", groupTypeId, bbId))
        val mine = groupTraining(group, LocalDate(2026, 10, 27))
        val together = groupTraining(group, LocalDate(2026, 10, 28), Sharing(Owner.SELF, null, ConductedBy.SELF_AND_COLLEAGUE, lena))
        val hers = groupTraining(group, LocalDate(2026, 10, 29), Sharing(Owner.COLLEAGUE, lena, ConductedBy.SELF, lena))
        val cancelled = groupTraining(group, LocalDate(2026, 10, 30), Sharing(Owner.SELF, null, ConductedBy.COLLEAGUE, lena), TrainingStatus.CANCELLED)
        suspend fun who() = calendar.trainings(today, LocalDate(2026, 11, 1)).first().associate { it.id to it.who?.full }

        // Выключено — на блоках ничего нового (шаг 3.9л).
        assertTrue(who().values.all { it == null })
        settings.salary.saveCoCoaching(true)
        val on = who()
        assertEquals("Один", on[mine])
        assertEquals("Вдвоём · Лена", on[together])
        assertEquals("Один · числится Лена", on[hers])
        assertNull(on[cancelled])
    }
}
