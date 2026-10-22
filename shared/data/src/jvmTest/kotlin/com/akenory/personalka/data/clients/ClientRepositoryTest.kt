package com.akenory.personalka.data.clients

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.RelationRole
import com.akenory.personalka.domain.client.Relations
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Клиенты и родственники в базе (шаг 1.7, ТЗ 5.8, 5.9, 4.17). Имена придуманы. */
class ClientRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private val today = now.date
    private var millis = ClubTime.toMillis(now)
    private lateinit var repo: ClientRepository
    private lateinit var ptId: String
    private lateinit var groupTypeId: String
    private lateinit var bbId: String

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        val types = settings.types.first()
        ptId = types.single { it.shortName == "ПТ" }.id
        groupTypeId = types.single { it.shortName == "Группа" }.id
        bbId = settings.places.first().single { it.shortName == "ББ" }.id
        repo = ClientRepository(db) { millis++ }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun create(last: String, first: String, sex: Sex? = null, birth: LocalDate? = null): String =
        repo.create(ClientDraft(last, first, sex = sex, birthDate = birth))

    private suspend fun client(id: String) = db.clientDao().client(id)!!

    private suspend fun words(clientId: String): List<String> =
        repo.relatives(clientId, today).first().map { Relations.word(it.link.role, it.relative.sex, it.link.label) + " · " + it.relative.firstName }

    private suspend fun planned(start: LocalDateTime, vararg clientIds: String, groupId: String? = null): String {
        val training = Samples.training(ptId, bbId, start).copy(groupId = groupId)
        db.trainingDao().upsertTraining(training)
        clientIds.forEach {
            db.trainingDao().upsertParticipation(Samples.participation(training.id, it).copy(status = ParticipationStatus.EXPECTED, behavior = null, cancelReason = null, notifiedAt = null))
        }
        return training.id
    }

    // --- Список ---

    @Test
    fun createdClientIsCleanedAndAdultIsCounted(): Unit = runBlocking {
        val id = repo.create(
            ClientDraft(" Яковлева ", "Татьяна", " ", Sex.FEMALE, LocalDate(1992, 4, 3), isAdult = false, "сама", "8 912 384-20-17", " "),
        )
        val c = client(id)
        assertEquals("Яковлева", c.lastName)
        assertNull(c.middleName)
        assertTrue(c.isAdult, "34 года — взрослая, хоть флажок и не отмечен")
        assertEquals("+79123842017", c.contactPhone)
        assertNull(c.mediaUrl)
        assertEquals(ClientStatus.ACTIVE, c.status)
        assertEquals(
            ClientDraft("Яковлева", "Татьяна", "", Sex.FEMALE, LocalDate(1992, 4, 3), true, "сама", "+79123842017", ""),
            repo.client(id).first()!!.draft(),
        )
    }

    @Test
    fun rowsShowScheduleAndNextTraining(): Unit = runBlocking {
        val ksenia = create("Орлова", "Ксения")
        val adelina = create("Дельфенурова", "Аделина")
        val series = Samples.series(ptId, bbId).copy(weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), startTime = LocalTime(13, 30))
        db.scheduleDao().upsertSeries(series)
        db.scheduleDao().upsertSeriesClient(Samples.seriesClient(series.id, ksenia))
        // Закончившаяся серия в строку не попадает.
        val old = Samples.series(ptId, bbId).copy(validTo = LocalDate(2026, 8, 31))
        db.scheduleDao().upsertSeries(old)
        db.scheduleDao().upsertSeriesClient(Samples.seriesClient(old.id, ksenia))
        // Группа «Сб 09:00» — по составу группы.
        val group = Samples.group(groupTypeId, bbId)
        db.clientDao().upsertGroup(group)
        db.clientDao().upsertGroupMember(Samples.member(group.id, adelina, LocalDate(2026, 9, 1)))
        val groupSeries = Samples.series(groupTypeId, bbId).copy(groupId = group.id, weekdays = setOf(DayOfWeek.SATURDAY), startTime = LocalTime(9, 0))
        db.scheduleDao().upsertSeries(groupSeries)
        // Тренировки: прошедшая, отменённая и две будущие.
        planned(LocalDateTime(2026, 10, 20, 13, 30), ksenia)
        val cancelled = planned(LocalDateTime(2026, 10, 21, 13, 30), ksenia)
        db.trainingDao().upsertTraining(db.trainingDao().training(cancelled)!!.copy(status = TrainingStatus.CANCELLED))
        planned(LocalDateTime(2026, 10, 27, 13, 30), ksenia)
        planned(LocalDateTime(2026, 10, 22, 13, 30), ksenia)

        val rows = repo.rows(now).first().associateBy { it.client.firstName }
        val k = rows.getValue("Ксения")
        assertEquals(1, k.schedule.size)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), k.schedule.single().weekdays)
        assertEquals("ПТ", k.schedule.single().typeShort)
        assertEquals("ББ", k.schedule.single().placeShort)
        assertEquals(LocalDateTime(2026, 10, 22, 13, 30), k.next)
        val a = rows.getValue("Аделина")
        assertEquals("Группа", a.schedule.single().typeShort)
        assertNull(a.next)
    }

    @Test
    fun archiveAndBack(): Unit = runBlocking {
        val id = create("Захаров", "Никита")
        assertNotNull(repo.archive(id))
        assertEquals(ClientStatus.ARCHIVED, client(id).status)
        assertEquals(listOf(ClientStatus.ARCHIVED), repo.rows(now).first().map { it.client.status })
        assertNull(repo.archive(id), "Второй раз в архив не убирается")
        repo.unarchive(id)
        assertEquals(ClientStatus.ACTIVE, client(id).status)
    }

    // --- Родственники (4.17) ---

    @Test
    fun fatherInOneCardIsSonInTheOther(): Unit = runBlocking {
        val artem = create("Ковалёв", "Артём", Sex.MALE, LocalDate(2016, 2, 14))
        val sergey = create("Ковалёв", "Сергей", Sex.MALE)
        repo.addRelation(artem, sergey, RelationRole(RelationKind.PARENT))
        assertEquals(listOf("Отец · Сергей"), words(artem))
        assertEquals(listOf("Сын · Артём"), words(sergey))
    }

    @Test
    fun relationSetsUnknownSexAndChangesInBothCards(): Unit = runBlocking {
        val artem = create("Ковалёв", "Артём")
        val natalia = create("Ковалёва", "Наталья")
        val alisa = create("Ковалёва", "Алиса", Sex.FEMALE)
        // «Мать» и ответ «Артём для Натальи: сын» задают пол обоим.
        val id = repo.addRelation(artem, natalia, RelationRole(RelationKind.PARENT), relativeSex = Sex.FEMALE, clientSex = Sex.MALE)
        repo.addRelation(artem, alisa, RelationRole(RelationKind.SIBLING))
        assertEquals(Sex.FEMALE, client(natalia).sex)
        assertEquals(Sex.MALE, client(artem).sex)
        assertEquals(listOf("Мать · Наталья", "Сестра · Алиса"), words(artem))
        assertEquals(listOf("Сын · Артём"), words(natalia))
        assertEquals(listOf("Брат · Артём"), words(alisa))

        // «Изменить связь» из карточки Натальи: Артём — племянник; у Артёма сразу «Тётя».
        repo.updateRelation(id, natalia, RelationRole(RelationKind.AUNT_UNCLE, senior = false))
        assertEquals(listOf("Тётя · Наталья", "Сестра · Алиса"), words(artem))
        assertEquals(listOf("Племянник · Артём"), words(natalia))

        // «Убрать связь» — из обеих; «Отменить» — обратно; та же запись.
        repo.removeRelation(id)
        assertEquals(listOf("Сестра · Алиса"), words(artem))
        assertTrue(words(natalia).isEmpty())
        repo.restoreRelation(id)
        assertEquals(listOf("Племянник · Артём"), words(natalia))
    }

    @Test
    fun relationWithSamePersonIsOneRecordEvenAfterRemoval(): Unit = runBlocking {
        val artem = create("Ковалёв", "Артём", Sex.MALE)
        val natalia = create("Ковалёва", "Наталья", Sex.FEMALE)
        val first = repo.addRelation(artem, natalia, RelationRole(RelationKind.PARENT))
        repo.removeRelation(first)
        // Добавили снова со второй стороны: та же запись, старший теперь — Наталья как и было.
        val again = repo.addRelation(natalia, artem, RelationRole(RelationKind.PARENT, senior = false))
        assertEquals(first, again)
        assertEquals(1, db.clientDao().relationRowsBetween(artem, natalia).size)
        assertEquals(listOf("Мать · Наталья"), words(artem))
    }

    @Test
    fun newRelativeCreatedWithRelation(): Unit = runBlocking {
        val artem = create("Ковалёв", "Артём", Sex.MALE)
        val id = repo.createRelative(artem, "Ковалёва", "Наталья", RelationRole(RelationKind.PARENT), relativeSex = Sex.FEMALE)
        val natalia = client(id)
        assertEquals(Sex.FEMALE, natalia.sex)
        assertTrue(natalia.isAdult, "Мать — взрослая")
        assertEquals(listOf("Сын · Артём"), words(id))
        // Без выбранного пола — по фамилии.
        val sister = repo.createRelative(artem, "Ковалёва", "Алиса", RelationRole(RelationKind.SIBLING))
        assertEquals(Sex.FEMALE, client(sister).sex)
        assertEquals(false, client(sister).isAdult)
    }

    @Test
    fun archivedRelativeStaysInRelations(): Unit = runBlocking {
        val artem = create("Ковалёв", "Артём", Sex.MALE)
        val alisa = create("Ковалёва", "Алиса", Sex.FEMALE)
        repo.addRelation(artem, alisa, RelationRole(RelationKind.SIBLING))
        repo.archive(alisa)
        val row = repo.relatives(artem, today).first().single()
        assertEquals(ClientStatus.ARCHIVED, row.relative.status)
    }

    // --- Объединение (5.8, 4.17) ---

    @Test
    fun mergeMovesEverythingAndDropsDuplicate(): Unit = runBlocking {
        val keep = create("Дельфенурова", "Аделина", Sex.FEMALE, LocalDate(2017, 5, 12))
        val gone = repo.create(ClientDraft("Дельфинурова", "Аделина", contactName = "мама", contactPhone = "+79001112233"))
        val mother = create("Дельфенурова", "Ирина", Sex.FEMALE)
        val brother = create("Дельфенуров", "Олег", Sex.MALE)
        repo.addRelation(keep, mother, RelationRole(RelationKind.PARENT))
        repo.addRelation(gone, mother, RelationRole(RelationKind.PARENT))
        repo.addRelation(gone, brother, RelationRole(RelationKind.SIBLING))
        repo.addRelation(gone, keep, RelationRole(RelationKind.SIBLING))
        val both = planned(LocalDateTime(2026, 10, 24, 9, 0), keep, gone)
        val onlyGone = planned(LocalDateTime(2026, 10, 31, 9, 0), gone)
        db.trainingDao().upsertResult(Samples.result(gone))
        val series = Samples.series(ptId, bbId)
        db.scheduleDao().upsertSeries(series)
        db.scheduleDao().upsertSeriesClient(Samples.seriesClient(series.id, gone))

        repo.merge(keep, gone)

        assertNotNull(client(gone).sync.deletedAt)
        assertEquals(listOf(keep), repo.rows(now).first().filter { it.client.firstName == "Аделина" }.map { it.client.id })
        val merged = client(keep)
        assertEquals(LocalDate(2017, 5, 12), merged.birthDate)
        assertEquals("+79001112233", merged.contactPhone, "Пустое поле заполнено из второй карточки")
        assertEquals(listOf(keep), db.trainingDao().participationsOf(both).map { it.clientId })
        assertEquals(listOf(keep), db.trainingDao().participationsOf(onlyGone).map { it.clientId })
        assertEquals(1, db.trainingDao().resultsOf(keep).size)
        assertEquals(listOf(keep), db.scheduleDao().seriesClients(series.id).map { it.clientId })
        // Мать одна, брат перешёл, связь дублей между собой исчезла.
        assertEquals(listOf("Мать · Ирина", "Брат · Олег"), words(keep))
        assertEquals(listOf("Сестра · Аделина"), words(brother))
        assertEquals(listOf("Дочь · Аделина"), words(mother))
    }

    // --- Удалить полностью (3.1) ---

    @Test
    fun eraseRemovesClientDataAndFutureTrainings(): Unit = runBlocking {
        val gone = create("Захаров", "Никита", Sex.MALE)
        val brother = create("Захаров", "Илья", Sex.MALE)
        val friend = create("Ильин", "Матвей", Sex.MALE)
        repo.addRelation(gone, brother, RelationRole(RelationKind.SIBLING))
        val past = planned(LocalDateTime(2026, 10, 15, 13, 30), gone)
        db.trainingDao().upsertTraining(db.trainingDao().training(past)!!.copy(status = TrainingStatus.DONE, plan = "Старт", fact = "Проплыл 4 × 25"))
        val future = planned(LocalDateTime(2026, 10, 22, 13, 30), gone)
        val split = planned(LocalDateTime(2026, 10, 23, 13, 30), gone, friend)
        db.trainingDao().upsertResult(Samples.result(gone))
        val series = Samples.series(ptId, bbId)
        db.scheduleDao().upsertSeries(series)
        db.scheduleDao().upsertSeriesClient(Samples.seriesClient(series.id, gone))

        repo.erase(gone)

        assertNull(db.clientDao().client(gone), "Карточка стёрта сразу, а не помечена")
        assertTrue(db.trainingDao().resultsOf(gone).isEmpty())
        assertTrue(db.clientDao().relationRowsBetween(gone, brother).isEmpty())
        assertTrue(words(brother).isEmpty())
        // Прошедшая осталась для зарплаты, но без записей о клиенте.
        val kept = db.trainingDao().training(past)!!
        assertEquals(TrainingStatus.DONE, kept.status)
        assertNull(kept.plan)
        assertNull(kept.fact)
        // Будущая только с ним — удалена, сплит остался со вторым.
        assertNotNull(db.trainingDao().training(future)!!.sync.deletedAt)
        assertEquals(listOf(friend), db.trainingDao().participationsOf(split).map { it.clientId })
        // Серия без клиентов закончилась вчера.
        assertEquals(LocalDate(2026, 10, 19), db.scheduleDao().series(series.id)!!.validTo)
        assertTrue(db.scheduleDao().seriesClients(series.id).isEmpty())
        assertEquals(listOf("Илья", "Матвей"), repo.rows(now).first().map { it.client.firstName }.sorted())
    }
}
