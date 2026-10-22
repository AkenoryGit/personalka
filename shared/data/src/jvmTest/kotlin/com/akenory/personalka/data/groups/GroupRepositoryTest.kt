package com.akenory.personalka.data.groups

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Группы и состав с датами в базе (шаг 1.8, ТЗ 5.10, 4.4). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class GroupRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var groups: GroupRepository
    private lateinit var clients: ClientRepository
    private lateinit var groupTypeId: String
    private lateinit var bbId: String

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        groupTypeId = settings.types.first().single { it.shortName == "Группа" }.id
        bbId = settings.places.first().single { it.shortName == "ББ" }.id
        groups = GroupRepository(db) { millis++ }
        clients = ClientRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun day(d: Int, m: Int = 10) = LocalDate(2026, m, d)

    private suspend fun kid(last: String, first: String) = clients.create(ClientDraft(last, first))

    private suspend fun newGroup(): String = groups.create(GroupDraft("Сб 9:00 ББ", groupTypeId, bbId))

    /** Тренировка группы в сб 09:00 с ожидаемыми детьми. */
    private suspend fun training(date: LocalDate, groupId: String, vararg kids: String, status: TrainingStatus = TrainingStatus.PLANNED): String {
        val t = Samples.training(groupTypeId, bbId, LocalDateTime(date, LocalTime(9, 0))).copy(groupId = groupId, status = status)
        db.trainingDao().upsertTraining(t)
        kids.forEach { kid ->
            db.trainingDao().upsertParticipation(
                Samples.participation(t.id, kid).copy(status = ParticipationStatus.EXPECTED, behavior = null, cancelReason = null, notifiedAt = null, chargeOverridden = false),
            )
        }
        return t.id
    }

    private suspend fun kidsOn(trainingId: String): Set<String> = db.trainingDao().participationsOf(trainingId).map { it.clientId }.toSet()

    @Test
    fun groupListedWithPlaceScheduleAndMembers(): Unit = runBlocking {
        val id = newGroup()
        val anya = kid("Белова", "Аня")
        groups.addMember(id, anya, day(1, 9))
        val series = Samples.series(groupTypeId, bbId).copy(groupId = id, weekdays = setOf(DayOfWeek.SATURDAY), startTime = LocalTime(9, 0))
        db.scheduleDao().upsertSeries(series)

        val row = groups.rows(now.date).first().single()
        assertEquals("Сб 9:00 ББ", row.group.name)
        assertEquals("Группа", row.type?.shortName)
        assertEquals("ББ", row.place?.shortName)
        assertEquals(listOf(anya), row.membersOn(now.date).map { it.clientId })
        assertEquals(setOf(DayOfWeek.SATURDAY), row.schedule.single().weekdays)

        groups.update(id, GroupDraft(" Сб 9:30 ББ ", groupTypeId, bbId))
        assertEquals("Сб 9:30 ББ", groups.rows(now.date).first().single().group.name)
        // Ребёнок из группы виден в списке клиентов с расписанием группы.
        assertEquals("Группа", clients.rows(now).first().single().schedule.single().typeShort)
    }

    @Test
    fun pastCompositionDoesNotChange(): Unit = runBlocking {
        val id = newGroup()
        val anya = kid("Белова", "Аня")
        val boris = kid("Гусев", "Борис")
        groups.addMember(id, anya, day(1, 9))
        val sat17 = training(day(17), id, anya, status = TrainingStatus.DONE)
        db.trainingDao().upsertParticipation(db.trainingDao().participationsOf(sat17).single().copy(status = ParticipationStatus.ATTENDED))
        val sat24 = training(day(24), id, anya)
        val sat31 = training(day(31), id, anya)
        val nov7 = training(day(7, 11), id, anya, status = TrainingStatus.CANCELLED)

        // Борис в составе с 20.10: в прошедшую его нет, в будущие — ожидается, в отменённую — нет.
        val borisInGroup = groups.addMember(id, boris, day(20))
        assertEquals(setOf(anya), kidsOn(sat17))
        assertEquals(setOf(anya, boris), kidsOn(sat24))
        assertEquals(setOf(anya, boris), kidsOn(sat31))
        assertEquals(setOf(anya), kidsOn(nov7))

        // Аня не ходит с 26.10: в сб 24.10 она ещё есть, в сб 31.10 — уже нет; прошлое и отметка «пришла» на месте.
        val anyaInGroup = db.clientDao().membershipsOf(anya).single()
        groups.updateMember(anyaInGroup.id, day(1, 9), lastDay = day(25))
        assertEquals(setOf(anya, boris), kidsOn(sat24))
        assertEquals(setOf(boris), kidsOn(sat31))
        assertEquals(ParticipationStatus.ATTENDED, db.trainingDao().participationsOf(sat17).single().status)
        assertEquals(setOf(anya), kidsOn(nov7))

        // Бориса добавили по ошибке — убрали совсем; «Отменить» — вернули той же записью участия.
        val borisOn24 = db.trainingDao().participationRow(sat24, boris)!!.id
        groups.removeMember(borisInGroup)
        assertEquals(setOf(anya), kidsOn(sat24))
        assertEquals(emptySet(), kidsOn(sat31))
        groups.restoreMember(borisInGroup)
        assertEquals(setOf(anya, boris), kidsOn(sat24))
        assertEquals(borisOn24, db.trainingDao().participationRow(sat24, boris)!!.id)
    }

    @Test
    fun guestOnceStaysWhenCompositionChanges(): Unit = runBlocking {
        val id = newGroup()
        val anya = kid("Белова", "Аня")
        val vera = kid("Зайцева", "Вера")
        groups.addMember(id, anya, day(1, 9))
        val sat24 = training(day(24), id, anya)
        val sat31 = training(day(31), id, anya)

        // Вера «на раз» в сб 24.10, а с 31.10 — в составе: на 24.10 она гость и остаётся им.
        groups.addGuest(sat24, vera)
        assertEquals(setOf(anya, vera), kidsOn(sat24))
        val inGroup = groups.addMember(id, vera, day(31))
        assertEquals(setOf(anya, vera), kidsOn(sat24))
        assertEquals(setOf(anya, vera), kidsOn(sat31))
        groups.removeMember(inGroup)
        assertEquals(setOf(anya, vera), kidsOn(sat24), "Гость «на раз» правкой состава не убирается")
        assertEquals(setOf(anya), kidsOn(sat31))

        groups.removeGuest(sat24, vera)
        assertEquals(setOf(anya), kidsOn(sat24))
        // Гость — только в тренировку группы.
        val personal = Samples.training(groupTypeId, bbId, LocalDateTime(day(23), LocalTime(10, 0)))
        db.trainingDao().upsertTraining(personal)
        assertTrue(runCatching { groups.addGuest(personal.id, vera) }.isFailure)
    }

    @Test
    fun childLeavesAndComesBack(): Unit = runBlocking {
        val id = newGroup()
        val anya = kid("Белова", "Аня")
        val first = groups.addMember(id, anya, day(1, 9))
        groups.updateMember(first, day(1, 9), lastDay = day(10))
        groups.addMember(id, anya, day(1, 11))
        val row = groups.rows(now.date).first().single()
        assertEquals(2, row.members.size)
        assertTrue(row.membersOn(now.date).isEmpty(), "Сейчас Ани в группе нет")
        assertEquals(listOf(anya), row.membersOn(day(7, 11)).map { it.clientId })
        assertEquals(listOf(anya), row.membersOn(day(5)).map { it.clientId })
    }

    @Test
    fun archivedClientLeavesGroupsAndUndoBringsBack(): Unit = runBlocking {
        val id = newGroup()
        val anya = kid("Белова", "Аня")
        groups.addMember(id, anya, day(1, 9))
        val later = groups.addMember(newGroup2(), anya, day(1, 11))
        val sat17 = training(day(17), id, anya, status = TrainingStatus.DONE)
        val sat24 = training(day(24), id, anya)
        val before24 = db.trainingDao().participationRow(sat24, anya)!!.id

        val undo = assertNotNull(clients.archive(anya))
        val membership = db.clientDao().membershipsOf(anya).single()
        assertEquals(day(19), membership.toDate, "Последний день в группе — вчера")
        assertNotNull(db.clientDao().groupMember(later)!!.sync.deletedAt, "В группу, куда ещё не пришла, — не придёт")
        assertEquals(emptySet(), kidsOn(sat24))
        assertEquals(setOf(anya), kidsOn(sat17))

        clients.undoArchive(undo)
        assertNull(db.clientDao().membershipsOf(anya).single { it.groupId == id }.toDate)
        assertNull(db.clientDao().groupMember(later)!!.sync.deletedAt)
        assertEquals(setOf(anya), kidsOn(sat24))
        assertEquals(before24, db.trainingDao().participationRow(sat24, anya)!!.id)
    }

    private suspend fun newGroup2(): String = groups.create(GroupDraft("Вт 18:00 ББ", groupTypeId, bbId))

    @Test
    fun onlyGroupWithoutHistoryIsDeleted(): Unit = runBlocking {
        val empty = newGroup()
        val anya = kid("Белова", "Аня")
        groups.addMember(empty, anya, day(1, 9))
        assertTrue(groups.delete(empty))
        assertTrue(groups.rows(now.date).first().isEmpty())
        assertTrue(db.clientDao().membershipsOf(anya).isEmpty())

        val used = newGroup2()
        training(day(24), used)
        assertFalse(groups.canDelete(used))
        assertFalse(groups.delete(used))
        assertEquals(listOf("Вт 18:00 ББ"), groups.rows(now.date).first().map { it.group.name })
    }
}
