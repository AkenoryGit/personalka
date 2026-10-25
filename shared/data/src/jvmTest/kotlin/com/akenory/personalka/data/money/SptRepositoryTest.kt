package com.akenory.personalka.data.money

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.money.PurchaseProblem
import com.akenory.personalka.domain.money.SptPrompt
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.TrainingDraft
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** СПТ в приложении (шаг 3.7а, ТЗ 4.9): дело при «Проведено», вопрос на ПТ, «Купил блок со скидкой». Имена придуманы. */
class SptRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 10, 20, 15, 40))
    private lateinit var settings: SettingsRepository
    private lateinit var calendar: CalendarRepository
    private lateinit var clients: ClientRepository
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        clients = ClientRepository(db) { millis }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private suspend fun training(start: LocalDateTime, clientId: String, type: String): String {
        val typeId = settings.types.first().single { it.shortName == type }.id
        val placeId = settings.places.first().single { it.shortName == "ББ" }.id
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(start, typeId, listOf(clientId), null, placeId, 50, null, "")))
        return db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start }.id
    }

    private suspend fun cases() = db.trainingDao().sptCases()

    @Test
    fun doneSptOpensCaseAndUndoRemovesIt(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val spt = training(at(tuesday, 15, 0), orlov, "СПТ")
        assertTrue(cases().isEmpty())

        val done = assertNotNull(calendar.cards.markDone(spt))
        val case = cases().single()
        assertEquals(orlov, case.clientId)
        assertEquals(tuesday, case.sptDate)
        assertEquals(LocalDate(2026, 11, 20), case.windowUntil)
        assertEquals(SptCaseStatus.OPEN, case.status)
        val waiting = calendar.cards.spt.waiting(tuesday).first().single()
        assertEquals("Орлов", waiting.name.lastName)
        assertEquals(false, waiting.remind)

        // «Отменить» у «Проведено» — дело уходит; снова «Проведено» — новое дело.
        calendar.cards.undo(done.undo)
        assertTrue(cases().isEmpty())
        assertNotNull(calendar.cards.markDone(spt))
        assertEquals(1, cases().size)
        // «Снять «Проведено»» — дело тоже уходит.
        assertNotNull(calendar.cards.unmarkDone(spt))
        assertTrue(cases().isEmpty())
    }

    @Test
    fun firstPaidPtAsksAndPurchaseCloses(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val spt = training(at(tuesday, 15, 0), orlov, "СПТ")
        calendar.cards.markDone(spt)
        val pt = training(at(LocalDate(2026, 10, 22), 10, 0), orlov, "ПТ")
        val later = training(at(LocalDate(2026, 10, 24), 10, 0), orlov, "ПТ")
        // Ещё не проведена — вопроса нет.
        assertNull(calendar.cards.spt.ask(pt).first())

        millis = ClubTime.toMillis(LocalDateTime(2026, 10, 24, 12, 0))
        calendar.cards.closeEnded()
        val ask = assertNotNull(calendar.cards.spt.ask(pt).first())
        assertIs<SptPrompt.CloseNow>(ask.prompt)
        assertEquals(LocalDate(2026, 10, 22), ask.purchaseDate)
        assertEquals("Орлов М.", ask.name.replace('\u00A0', ' '))
        // Вопрос — только на первой оплаченной ПТ.
        assertNull(calendar.cards.spt.ask(later).first())

        val caseId = ask.case.id
        assertEquals(PurchaseProblem.AFTER_WINDOW, calendar.cards.spt.close(caseId, LocalDate(2026, 11, 21)).second)
        assertEquals(PurchaseProblem.BEFORE_SPT, calendar.cards.spt.close(caseId, LocalDate(2026, 10, 19)).second)
        val saved = assertNotNull(calendar.cards.spt.close(caseId, LocalDate(2026, 10, 22)).first)
        assertEquals(SptCaseStatus.CLOSED, cases().single().status)
        assertNull(calendar.cards.spt.ask(pt).first())
        assertTrue(calendar.cards.spt.waiting(LocalDate(2026, 10, 24)).first().isEmpty())
        val view = clients.spt.ofClient(orlov, LocalDate(2026, 10, 24)).first().single()
        assertEquals(SptCaseStatus.CLOSED, view.status)
        assertEquals("СПТ", view.typeShort)

        // «Отменить» — снова ждёт покупки; «Снять отметку» у закрытой — тоже.
        calendar.cards.spt.undo(saved)
        assertEquals(SptCaseStatus.OPEN, cases().single().status)
        calendar.cards.spt.close(caseId, LocalDate(2026, 10, 23))
        calendar.cards.spt.reopen(caseId)
        assertEquals(SptCaseStatus.OPEN, cases().single().status)
        assertNull(cases().single().purchaseDate)
    }

    @Test
    fun windowPassedBecomesNotClosedAndAsksRetro(): Unit = runBlocking {
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val spt = training(at(tuesday, 15, 0), orlov, "СПТ")
        calendar.cards.markDone(spt)
        // За 3 дня до конца окна — в сводку.
        assertTrue(calendar.cards.spt.waiting(LocalDate(2026, 11, 17)).first().single().remind)

        val pt = training(at(LocalDate(2026, 11, 25), 10, 0), orlov, "ПТ")
        millis = ClubTime.toMillis(LocalDateTime(2026, 11, 25, 12, 0))
        calendar.cards.closeEnded()
        assertEquals(SptCaseStatus.NOT_CLOSED, cases().single().status)
        val ask = assertNotNull(calendar.cards.spt.ask(pt).first())
        assertEquals(SptPrompt.AskRetro(ask.case, LocalDate(2026, 11, 20)), ask.prompt)
        assertEquals(LocalDate(2026, 11, 20), ask.purchaseDate)
        // «Да» с датой в окне — закрыта задним числом.
        assertNotNull(calendar.cards.spt.close(ask.case.id, LocalDate(2026, 11, 10)).first)
        assertEquals(SptCaseStatus.CLOSED, cases().single().status)
        assertTrue(calendar.cards.spt.waiting(LocalDate(2026, 11, 25)).first().isEmpty())
    }
}
