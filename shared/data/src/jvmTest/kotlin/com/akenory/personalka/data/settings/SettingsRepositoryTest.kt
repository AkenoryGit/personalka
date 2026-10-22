package com.akenory.personalka.data.settings

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Первый запуск и «Места и типы» в базе (шаг 1.6). */
class SettingsRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var now = 1_792_500_000_000L // 20.10.2026
    private lateinit var repo: SettingsRepository

    @BeforeTest
    fun open() {
        db = inMemoryPersonalkaDatabase()
        repo = SettingsRepository(db) { now }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private val name = CoachName(" Дудник ", "Дмитрий", "Викторович")

    private fun firstRun() = runBlocking {
        repo.completeFirstRun(name, ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
    }

    @Test
    fun beforeFirstRunThereIsNoCoach(): Unit = runBlocking {
        assertNull(repo.coach.first())
        assertTrue(repo.places.first().isEmpty())
    }

    @Test
    fun firstRunWritesCoachSettingsPlacesAndTypes(): Unit = runBlocking {
        firstRun()

        val coach = assertNotNull(repo.coach.first())
        assertEquals("Дудник", coach.lastName)
        assertEquals("Викторович", coach.middleName)
        assertEquals(coach.id, coach.sync.coachId, "У тренера coachId — свой id")
        val settings = assertNotNull(db.coachDao().settings(coach.id))
        assertEquals(60, settings.cancelNoticeMinutes, "Настройки по умолчанию — из ТЗ 3.2")

        val places = repo.places.first()
        assertEquals(listOf("ББ", "ДБ", "Зал"), places.map { it.shortName })
        assertEquals(listOf(0, 1, 2), places.map { it.sort })
        assertTrue(places.all { it.scope == Scope.COACH && it.sync.coachId == coach.id })

        val types = repo.types.first()
        assertEquals(ReferenceRules.defaultTypes.map { it.name }, types.map { it.name }, "Типы — в порядке ТЗ 3.3")
        assertEquals(ReferenceRules.defaultTypes, types.map { it.draft() }, "Всё из заготовок записано без потерь")
        val pt = types.single { it.name == "ПТ" }
        val split = types.single { it.kind == TrainingKind.SPLIT }
        assertEquals(pt.id, split.splitFallbackTypeId, "Сплит с одним клиентом — это ПТ")
        assertTrue(types.filter { it.kind != TrainingKind.SPLIT }.all { it.splitFallbackTypeId == null })
    }

    @Test
    fun uncheckedDefaultsAreNotCreated(): Unit = runBlocking {
        val types = ReferenceRules.defaultTypes.filter { it.name in setOf("Сплит", "Группа") }
        repo.completeFirstRun(name, ReferenceRules.defaultPlaces.take(1), types)

        assertEquals(listOf("ББ"), repo.places.first().map { it.shortName })
        val saved = repo.types.first()
        assertEquals(listOf("Сплит", "Группа"), saved.map { it.name })
        assertNull(saved.first().splitFallbackTypeId, "Без ПТ сплиту не во что превращаться")
    }

    @Test
    fun placesAreAddedEditedArchivedAndReordered(): Unit = runBlocking {
        firstRun()
        now += 1000
        val street = repo.addPlace(PlaceDraft(" Улица ", "Ул"))
        var places = repo.places.first()
        assertEquals(listOf("ББ", "ДБ", "Зал", "Ул"), places.map { it.shortName }, "Новое место — последним")
        assertEquals("Улица", places.last().name)

        val ids = places.map { it.id }
        repo.reorderPlaces(listOf(ids[1], ids[0], ids[2], ids[3]))
        places = repo.places.first()
        assertEquals(listOf("ДБ", "ББ", "Зал", "Ул"), places.map { it.shortName })
        assertEquals(1, places.single { it.shortName == "Зал" }.sync.version, "Место без перемены порядка не переписывается")
        assertEquals(2, places.single { it.shortName == "ББ" }.sync.version)

        repo.updatePlace(street, PlaceDraft("Открытая вода", "ОВ"), archived = true)
        val edited = repo.places.first().single { it.id == street }
        assertEquals("ОВ", edited.shortName)
        assertTrue(edited.archived, "Архивное место остаётся в списке — оно нужно истории")
        assertEquals(now, edited.sync.updatedAt)
    }

    @Test
    fun typeConstructorAddsAndEdits(): Unit = runBlocking {
        firstRun()
        now += 1000
        val aqua = ReferenceRules.newType(ReferenceRules.defaultTypes.map { it.color })
            .copy(name = "Аквааэробика", shortName = "Аква")
            .withKind(TrainingKind.GROUP)
            .copy(durationMin = 45, maxClients = 12)
        val id = repo.addType(aqua)

        val types = repo.types.first()
        assertEquals("Аквааэробика", types.last().name, "Новый тип — в конце списка")
        val saved = types.last()
        assertEquals(id, saved.id)
        assertEquals(aqua, saved.draft())
        assertEquals(PayUnit.PER_CLIENT, saved.payUnit)

        repo.updateType(id, aqua.copy(name = "Аквааэробика 45"), archived = true)
        val edited = assertNotNull(db.referenceDao().trainingType(id))
        assertEquals("Аквааэробика 45", edited.name)
        assertTrue(edited.archived)
        assertEquals(2, edited.sync.version)
    }

    @Test
    fun newSplitGetsPtAsFallback(): Unit = runBlocking {
        firstRun()
        val pt = repo.types.first().single { it.name == "ПТ" }
        val split = ReferenceRules.newType(emptyList()).copy(name = "Сплит ДБ", shortName = "Сп").withKind(TrainingKind.SPLIT)
        val id = repo.addType(split)
        assertEquals(pt.id, db.referenceDao().trainingType(id)?.splitFallbackTypeId)

        // Сплит переделали в группу — связь больше не нужна.
        repo.updateType(id, split.withKind(TrainingKind.GROUP), archived = false)
        assertNull(db.referenceDao().trainingType(id)?.splitFallbackTypeId)
    }

    @Test
    fun profileNameIsSaved(): Unit = runBlocking {
        firstRun()
        repo.saveName(CoachName("Дудник", "Дмитрий", " "))
        val coach = assertNotNull(repo.coach.first())
        assertNull(coach.middleName, "Пустое отчество — не пробел, а «нет»")
        assertEquals(2, coach.sync.version)
        assertFalse(coach.lastName.isBlank())
    }
}
