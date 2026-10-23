package com.akenory.personalka.data.results

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.ResultFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ResultKind
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
import kotlin.test.assertNull

/** Результаты в базе (шаг 2.3, ТЗ 5.11): запись, рекорд в сообщении, правка, удаление и «Отменить». Имена придуманы. */
class ResultRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 10, 20, 15, 40))
    private lateinit var clients: ClientRepository
    private lateinit var repo: ResultRepository
    private lateinit var clientId: String
    private val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 25)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        SettingsRepository(db) { millis }.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        clients = ClientRepository(db) { millis++ }
        repo = clients.results
        clientId = clients.create(ClientDraft("Орлова", "Ксения"))
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun draft(cs: Int, day: Int = 10, kind: ResultKind = ResultKind.TRAINING, name: String? = null) =
        ResultDraft(clientId, LocalDate(2026, 10, day), free50, cs, kind, name)

    private suspend fun times() = repo.results(clientId).first().map { it.timeCs }

    @Test
    fun saveAndRecord() = runBlocking {
        // Первый на дистанции — просто «сохранён»; быстрее прежнего — «Личный рекорд, − 7,00».
        assertEquals(ResultFormat.SAVED, repo.save(draft(4662, day = 1)).message)
        val record = repo.save(draft(3962, kind = ResultKind.COMPETITION, name = "  Кубок города "))
        assertEquals("Личный рекорд, −${RuText.NBSP}7,00", record.message)
        assertEquals(ResultFormat.SAVED, repo.save(draft(4030, day = 15)).message)
        val list = repo.results(clientId).first()
        assertEquals(listOf(4662, 3962, 4030), list.map { it.timeCs })
        assertEquals("Кубок города", list[1].eventName)
        // «Отменить» новый — его больше нет.
        repo.undo(record.undo)
        assertEquals(listOf(4662, 4030), times())
    }

    @Test
    fun editAndDelete() = runBlocking {
        repo.save(draft(4662, day = 1))
        val id = repo.results(clientId).first().single().id
        // Правка: сам с собой не сравнивается; название старта у тренировки не хранится.
        val edited = repo.save(draft(4500, kind = ResultKind.TRAINING, name = "Кубок"), id)
        assertEquals(ResultFormat.SAVED, edited.message)
        val after = repo.results(clientId).first().single()
        assertEquals(4500 to null, after.timeCs to after.eventName)
        assertEquals(id, after.id)
        repo.undo(edited.undo)
        assertEquals(listOf(4662), times())
        // Удаление мягкое, «Отменить» возвращает.
        val undo = repo.delete(id)!!
        assertEquals(emptyList(), times())
        assertNull(repo.delete(id))
        repo.undo(undo)
        assertEquals(listOf(4662), times())
    }

    @Test
    fun goesWithMergeAndErase() = runBlocking {
        // Объединение переносит результаты (5.8), «Удалить полностью» их стирает (5.9).
        repo.save(draft(4662))
        val twin = clients.create(ClientDraft("Орлова", "Ксюша"))
        repo.save(draft(4100).copy(clientId = twin))
        clients.merge(clientId, twin)
        assertEquals(listOf(4662, 4100), times())
        clients.erase(clientId)
        assertEquals(emptyList(), times())
    }
}
