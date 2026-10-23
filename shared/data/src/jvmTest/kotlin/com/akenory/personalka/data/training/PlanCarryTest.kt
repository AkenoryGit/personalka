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
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.CarryChoice
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

/**
 * Перенос плана (шаг 2.2, ТЗ 4.3) — все четыре случая таблицы через карточку: отмена, вопрос, отложенный план
 * и «Вернуть». «Сейчас» — вт 20.10.2026 15:40, как в макете «После отмены». Имена придуманы.
 */
class PlanCarryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var cards: TrainingCardRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val nextTuesday = LocalDate(2026, 10, 27)

    private val brass = "Брасс: согласование рук и ног, 8 × 25 м."
    private val back = "Кроль на спине: 4 × 50 м, повороты."

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        cards = calendar.cards
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

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private suspend fun training(start: LocalDateTime, vararg clientIds: String, type: String = "ПТ", groupId: String? = null, plan: String = ""): String {
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(start, typeId(type), clientIds.toList(), groupId, placeId("ББ"), 50, null, plan)))
        return db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start }.id
    }

    private suspend fun t(id: String) = db.trainingDao().training(id)!!

    private suspend fun cancel(id: String, vararg clientIds: String) = assertNotNull(cards.cancel(id, clientIds.toList(), "Не сможет", now))

    @Test
    fun emptyNextGetsPlanSilently(): Unit = runBlocking {
        // Случай 1, макет «После отмены»: Орлов М., вт 17:00 отменён — план на вт 27.10, где плана нет.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val next = training(at(nextTuesday, 17), orlov)

        val result = cancel(today)
        assertNull(result.carry)
        assertTrue(result.message.endsWith(s(". План перенесён на вт_27.10")), result.message)
        assertEquals(brass, t(next).plan)
        assertEquals(today, t(next).planCarriedFromId)
        assertEquals(brass, t(next).planCarriedText)
        // У отменённой план остаётся, а в карточке — куда он ушёл.
        assertEquals(brass, t(today).plan)
        assertEquals(nextTuesday, cards.card(today).first()!!.carriedTo)

        // «Отменить» в сообщении — и отмена, и перенос.
        cards.undo(result.undo)
        assertEquals(TrainingStatus.PLANNED, t(today).status)
        assertNull(t(next).plan)
        assertNull(t(next).planCarriedFromId)
    }

    @Test
    fun plannedNextAsks(): Unit = runBlocking {
        // Случай 2, макет «У следующей уже есть план»: сначала вопрос, ничего не перенесено.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val next = training(at(nextTuesday, 17), orlov, plan = back)

        val asked = cancel(today)
        val ask = assertNotNull(asked.carry)
        assertEquals(s("Орлов_М. · вт_27.10, 17:00"), ask.whom)
        assertEquals(brass, ask.moving)
        assertEquals(back, ask.existing)
        assertEquals(TrainingStatus.CANCELLED, t(today).status)
        assertEquals(back, t(next).plan)

        // «Добавить с новой строки» — главная: ничего не теряется.
        val added = cards.carry(asked, CarryChoice.ADD)
        assertTrue(added.message.endsWith(s("План перенесён на вт_27.10")), added.message)
        assertEquals("$back\n$brass", t(next).plan)
        assertEquals(today, t(next).planCarriedFromId)
        // «Отменить» — всё как до отмены.
        cards.undo(added.undo)
        assertEquals(TrainingStatus.PLANNED, t(today).status)
        assertEquals(back, t(next).plan)

        // «Заменить» — только перенесённый.
        val replaced = cards.carry(cancel(today), CarryChoice.REPLACE)
        assertEquals(brass, t(next).plan)
        cards.undo(replaced.undo)

        // «Оставить как есть» — сообщение об отмене, план следующей не тронут.
        val asked2 = cancel(today)
        val kept = cards.carry(asked2, CarryChoice.KEEP)
        assertEquals(asked2.message, kept.message)
        assertEquals(back, t(next).plan)
        assertNull(t(next).planCarriedFromId)
        assertNull(cards.card(today).first()!!.carriedTo)
    }

    @Test
    fun noNextWaitsInPendingPlan(): Unit = runBlocking {
        // Случай 3: следующей нет — план ждёт у клиента и встаёт в первую новую тренировку с пустым планом.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)

        val result = cancel(today)
        assertTrue(result.message.endsWith(". План — в первую новую тренировку"), result.message)
        assertEquals(brass, db.clientDao().client(orlov)!!.pendingPlan)

        // «Вернуть» — из отложенного он уходит.
        assertNotNull(cards.restore(today))
        assertNull(db.clientDao().client(orlov)!!.pendingPlan)

        cancel(today)
        // С планом из формы отложенный не трогается; первая без плана — забирает его.
        val withPlan = training(at(nextTuesday, 17), orlov, plan = back)
        assertEquals(back, t(withPlan).plan)
        val first = training(at(LocalDate(2026, 10, 29), 17), orlov)
        assertEquals(brass, t(first).plan)
        assertNull(db.clientDao().client(orlov)!!.pendingPlan)
        val second = training(at(LocalDate(2026, 10, 31), 17), orlov)
        assertNull(t(second).plan)
    }

    @Test
    fun returnTakesPlanBack(): Unit = runBlocking {
        // Случай 4: отмену вернули. Текст не меняли — он уходит из следующей молча.
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        val today = training(at(tuesday, 17), orlov, plan = brass)
        val next = training(at(nextTuesday, 17), orlov)
        cancel(today)
        val returned = assertNotNull(cards.restore(today))
        assertNull(returned.uncarry)
        assertEquals(s("Тренировка возвращена. План убран из вт_27.10"), returned.message)
        assertEquals(TrainingStatus.PLANNED, t(today).status)
        assertNull(t(next).plan)
        assertNull(t(next).planCarriedFromId)

        // Текст правили — вопрос; «Убрать перенесённое» оставляет дописанное.
        cancel(today)
        calendar.diaries.savePlan(next, "$brass\nСтарт с тумбы")
        val asked = assertNotNull(cards.restore(today))
        val ask = assertNotNull(asked.uncarry)
        assertEquals(brass, ask.carried)
        assertEquals("$brass\nСтарт с тумбы", ask.current)
        val removed = cards.uncarry(asked, remove = true)
        assertEquals("Старт с тумбы", t(next).plan)
        assertNull(t(next).planCarriedFromId)
        // «Отменить» — снова отменена, план следующей как был.
        cards.undo(removed.undo)
        assertEquals(TrainingStatus.CANCELLED, t(today).status)
        assertEquals("$brass\nСтарт с тумбы", t(next).plan)
        assertEquals(today, t(next).planCarriedFromId)

        // «Оставить как есть» — план не трогается, связь с отменённой снимается.
        val kept = cards.uncarry(assertNotNull(cards.restore(today)), remove = false)
        assertEquals(s("Тренировка возвращена"), kept.message)
        assertEquals("$brass\nСтарт с тумбы", t(next).plan)
        assertNull(t(next).planCarriedFromId)
    }

    @Test
    fun groupCarriesOnlyWhenWholeGroupCancelled(): Unit = runBlocking {
        val group = groups.create(GroupDraft("Вт, Чт 18:00 ББ", typeId("Группа"), placeId("ББ")))
        val belov = clients.create(ClientDraft("Белов", "Артём"))
        val nosova = clients.create(ClientDraft("Носова", "Ева"))
        groups.addMember(group, belov, LocalDate(2026, 9, 1))
        groups.addMember(group, nosova, LocalDate(2026, 9, 1))
        val today = training(at(tuesday, 18), type = "Группа", groupId = group, plan = brass)
        val next = training(at(LocalDate(2026, 10, 22), 18), type = "Группа", groupId = group)

        // Отменили одного ребёнка — тренировка идёт, план на месте.
        val one = cancel(today, belov)
        assertNull(t(next).plan)
        cards.undo(one.undo)

        // Отменили всю группу — план на следующую тренировку той же группы.
        val all = cancel(today)
        assertTrue(all.message.endsWith(s("План перенесён на чт_22.10")), all.message)
        assertEquals(brass, t(next).plan)
    }
}
