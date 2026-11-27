package com.akenory.personalka.data.competitions

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.results.ResultDraft
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.competition.CollectItem
import com.akenory.personalka.domain.competition.CompetitionDraft
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.EntryRule
import com.akenory.personalka.domain.format.CompetitionFormat
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Соревнования в базе (шаг 5.38, ТЗ 5.21): форма, участники, отметки, старты, итоги и «Отменить». Имена придуманы. */
class CompetitionRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 11, 27, 15, 40))
    private lateinit var clients: ClientRepository
    private lateinit var repo: CompetitionRepository
    private lateinit var lev: String
    private lateinit var mira: String

    private val day = LocalDate(2026, 12, 12)
    private val fee = CollectItem("fee", "Взнос", 150_000)
    private val paper = CollectItem("paper", "Справка")
    private val cup = CompetitionDraft(
        "Кубок города", day, 50, "Бассейн «Олимп»",
        listOf(EntryRule(Sex.MALE, 2010, 2015), EntryRule(Sex.FEMALE, 2008, 2013)), listOf(fee, paper),
    )

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        SettingsRepository(db) { millis }.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        clients = ClientRepository(db) { millis++ }
        repo = clients.competitions
        lev = clients.create(ClientDraft("Петров", "Лев", sex = Sex.MALE, birthDate = LocalDate(2014, 3, 5)))
        mira = clients.create(ClientDraft("Соколова", "Мира"))
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private suspend fun row(id: String) = assertNotNull(repo.row(id).first())

    @Test
    fun createAddMarkAndRemoveWithUndo() = runBlocking {
        val id = repo.create(cup)
        val created = row(id).competition
        assertEquals(cup.rules, created.rules)
        assertEquals(cup.items, created.items)
        assertEquals("Бассейн «Олимп»", created.place)
        // Добавить двоих; повтор того же — не дубль.
        assertEquals(2, repo.add(id, listOf(lev, mira)))
        assertEquals(0, repo.add(id, listOf(lev)))
        val people = row(id).participants
        // По фамилии; год — из карточки, у Миры даты нет.
        assertEquals(listOf("Петров" to 2014, "Соколова" to null), people.map { it.name.lastName to it.birthYear })
        val levEntry = people[0].id
        repo.setHanded(levEntry, "fee", true)
        repo.setHanded(levEntry, "paper", true)
        repo.setHanded(levEntry, "paper", false)
        assertEquals(setOf("fee"), row(id).participants[0].handedIn)
        // Убрать и «Отменить» — с отметками.
        val undo = assertNotNull(repo.remove(levEntry))
        assertEquals(listOf("Соколова"), row(id).participants.map { it.name.lastName })
        repo.undoRemove(undo)
        assertEquals(setOf("fee"), row(id).participants.first { it.id == levEntry }.handedIn)
        // Убранный и добавленный заново — та же запись, отметки прежние.
        repo.remove(levEntry)
        assertEquals(1, repo.add(id, listOf(lev)))
        assertEquals(levEntry, row(id).participants.first { it.clientId == lev }.id)
    }

    @Test
    fun removedItemDropsMarks() = runBlocking {
        val id = repo.create(cup)
        repo.add(id, listOf(lev))
        val entry = row(id).participants.single().id
        repo.setHanded(entry, "fee", true)
        repo.setHanded(entry, "paper", true)
        repo.update(id, cup.copy(items = listOf(paper), name = "Кубок области"))
        val after = row(id)
        assertEquals("Кубок области", after.competition.name)
        assertEquals(setOf("paper"), after.participants.single().handedIn)
    }

    @Test
    fun startEntryTimeAndResultGoToClientCard() = runBlocking {
        val id = repo.create(cup)
        repo.add(id, listOf(lev))
        val entry = row(id).participants.single().id
        val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 50)
        // Лучшее в 50-метровом — 38,12 (давно), в 25-метровом — быстрее, но не в счёт.
        clients.results.save(ResultDraft(lev, LocalDate(2025, 3, 1), free50, 3812, ResultKind.TRAINING))
        clients.results.save(ResultDraft(lev, LocalDate(2026, 11, 1), free50.copy(poolM = 25), 3500, ResultKind.TRAINING))
        assertEquals(CompetitionFormat.START_SAVED, repo.saveStart(entry, null, SwimStyle.FREESTYLE, 50, null, null, null))
        val start = row(id).participants.single().starts.single()
        assertEquals(free50, start.event)
        val results = repo.results.first()[lev].orEmpty()
        assertEquals(3812, CompetitionRules.entryTime(start, results, day).cs)
        // Итог: 37,05 — личный рекорд, − 1,07; результат — в карточке клиента как соревнования.
        val message = repo.saveStart(entry, start.id, SwimStyle.FREESTYLE, 50, null, 3705, 2)
        assertEquals("Личный рекорд, −${RuText.NBSP}1,07", message)
        val card = clients.results.results(lev).first().single { it.date == day }
        assertEquals(ResultKind.COMPETITION to "Кубок города", card.kind to card.eventName)
        assertEquals(free50 to 3705, card.event to card.timeCs)
        val finished = row(id).participants.single().starts.single()
        assertEquals(3705 to 2, finished.resultCs to finished.place)
        // Заявочное время не берёт результат самих соревнований.
        assertEquals(3812, CompetitionRules.entryTime(finished, repo.results.first()[lev].orEmpty(), day).cs)
        // Правка времени меняет тот же результат; соревнование переименовали — и название старта.
        assertEquals(ResultFormat.SAVED, repo.saveStart(entry, start.id, SwimStyle.FREESTYLE, 50, null, 3900, 2))
        repo.update(id, cup.copy(name = "Кубок области"))
        val edited = clients.results.results(lev).first().single { it.date == day }
        assertEquals(card.id, edited.id)
        assertEquals(3900 to "Кубок области", edited.timeCs to edited.eventName)
        // Удалили результат в карточке — у старта его больше нет.
        clients.results.delete(card.id)
        assertNull(row(id).participants.single().starts.single().resultCs)
        // Время стёрли у старта — результат из карточки убирается.
        repo.saveStart(entry, start.id, SwimStyle.FREESTYLE, 50, null, 3800, null)
        assertEquals(1, clients.results.results(lev).first().count { it.date == day })
        repo.saveStart(entry, start.id, SwimStyle.FREESTYLE, 50, null, null, null)
        assertTrue(clients.results.results(lev).first().none { it.date == day })
    }

    @Test
    fun deleteCompetitionKeepsResultsAndUndoReturnsAll() = runBlocking {
        val id = repo.create(cup)
        repo.add(id, listOf(lev, mira))
        val entry = row(id).participants.first().id
        repo.setHanded(entry, "fee", true)
        repo.saveStart(entry, null, SwimStyle.BACKSTROKE, 100, 8490, 8400, 1)
        val undo = assertNotNull(repo.delete(id))
        assertNull(repo.row(id).first())
        assertEquals(1, clients.results.results(lev).first().size)
        repo.undoDelete(undo)
        val back = row(id)
        assertEquals(2, back.participants.size)
        assertEquals(setOf("fee"), back.participants.first().handedIn)
        assertEquals(8490, back.participants.first().starts.single().entryCs)
        // Старт удалён и возвращён.
        val startUndo = assertNotNull(repo.deleteStart(back.participants.first().starts.single().id))
        assertTrue(row(id).participants.first().starts.isEmpty())
        repo.undoDeleteStart(startUndo)
        assertEquals(1, row(id).participants.first().starts.size)
    }

    @Test
    fun eraseAndMergeClients() = runBlocking {
        val id = repo.create(cup)
        val twin = clients.create(ClientDraft("Петров", "Лёва"))
        repo.add(id, listOf(lev, twin, mira))
        val people = row(id).participants
        val levEntry = people.first { it.clientId == lev }.id
        val twinEntry = people.first { it.clientId == twin }.id
        repo.setHanded(levEntry, "fee", true)
        repo.setHanded(twinEntry, "paper", true)
        repo.saveStart(twinEntry, null, SwimStyle.FREESTYLE, 50, null, null, null)
        // Объединение: отметки и старты складываются в участника оставшегося.
        clients.merge(lev, twin)
        val merged = row(id).participants.filter { it.clientId == lev }.single()
        assertEquals(levEntry, merged.id)
        assertEquals(setOf("fee", "paper"), merged.handedIn)
        assertEquals(1, merged.starts.size)
        assertEquals(2, row(id).participants.size)
        // «Удалить полностью» стирает участие.
        clients.erase(mira)
        assertEquals(listOf(lev), row(id).participants.map { it.clientId })
    }

    @Test
    fun birthAndSexFromPick() = runBlocking {
        clients.saveBirthAndSex(mira, LocalDate(2012, 7, 1), Sex.FEMALE)
        val client = assertNotNull(clients.client(mira).first())
        assertEquals(LocalDate(2012, 7, 1) to Sex.FEMALE, client.birthDate to client.sex)
        assertEquals(false, client.isAdult)
    }
}
