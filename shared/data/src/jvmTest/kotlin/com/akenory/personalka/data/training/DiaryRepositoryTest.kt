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
import com.akenory.personalka.domain.training.DiaryFields
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.domain.training.DiarySlot
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

/** Дневник (шаг 2.1, ТЗ 5.7, 4.3). «Сейчас» — вт 20.10.2026 15:40, как в макетах. Имена придуманы. */
class DiaryRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var diaries: DiaryRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val thursday = LocalDate(2026, 10, 22)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        calendar.reminders.saveDiary(true)
        diaries = calendar.diaries
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

    private suspend fun client(last: String, first: String) = clients.create(ClientDraft(last, first))

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private suspend fun training(start: LocalDateTime, vararg clientIds: String, type: String = "ПТ", groupId: String? = null, plan: String = ""): String {
        assertIs<SaveResult.Saved>(calendar.forms.create(TrainingDraft(start, typeId(type), clientIds.toList(), groupId, placeId("ББ"), 50, null, plan)))
        return db.trainingDao().trainingsBetween(start, at(start.date.plus(1, DateTimeUnit.DAY), 0)).first { it.start == start }.id
    }

    /** День макетов: сплит 09:00, Орлова 13:30 и группа 12:00 проведены, у всех пустой факт. */
    private suspend fun day(): Day {
        val kovalev = client("Ковалёв", "Тимур")
        val kovaleva = client("Ковалёва", "Алиса")
        val orlova = client("Орлова", "Ксения")
        val split = training(at(tuesday, 9), kovalev, kovaleva, type = "Сплит")
        val pt = training(at(tuesday, 13, 30), orlova, plan = "Кроль на груди: дыхание на 3 гребка")
        val group = groups.create(GroupDraft("Вт, Чт 12:00 ББ", typeId("Группа"), placeId("ББ")))
        groups.addMember(group, client("Белов", "Артём"), LocalDate(2026, 9, 1))
        val groupTraining = training(at(tuesday, 12), type = "Группа", groupId = group)
        calendar.cards.closeEnded()
        assertNotNull(calendar.cards.markDone(groupTraining))
        return Day(split, pt, groupTraining, group, orlova, kovalev, kovaleva)
    }

    private data class Day(
        val split: String,
        val pt: String,
        val group: String,
        val groupId: String,
        val orlova: String,
        val kovalev: String,
        val kovaleva: String,
    )

    @Test
    fun dayIsFilledInARow(): Unit = runBlocking {
        val d = day()
        assertEquals(TrainingStatus.DONE, db.trainingDao().training(d.pt)!!.status)
        // Проверка шага: записи за день — по времени, «Следующая без дневника» идёт по ним подряд.
        val missing = diaries.missing.first()
        assertEquals(listOf(d.split, d.group, d.pt), missing.map { it.id })
        assertEquals(listOf("Ковалёв / Ковалёва", "Вт, Чт 12:00 ББ (1)", s("Орлова_К.")), missing.map { it.title })
        assertEquals(d.group, DiaryRules.next(missing.first().slot, missing.map { it.slot })?.id)

        diaries.saveFact(d.split, "Сделали по 6 стартов с тумбочки.")
        val left = diaries.missing.first()
        assertEquals(listOf(d.group, d.pt), left.map { it.id })
        assertEquals(d.group, DiaryRules.next(DiarySlot(d.split, at(tuesday, 9)), left.map { it.slot })?.id)
        // Пробелы — не факт: дневник всё ещё пуст.
        diaries.saveFact(d.group, "   ")
        assertNull(db.trainingDao().training(d.group)!!.fact)
        assertEquals(2, diaries.missing.first().size)
        // Карточка: дневник заполнен — точки нет, кнопка «Дневник» есть.
        val card = assertNotNull(calendar.cards.card(d.split).first())
        assertTrue(card.keepsDiary)
        assertEquals(false, card.noDiary)
    }

    @Test
    fun fieldsByTypeAndDraftOnEveryInput(): Unit = runBlocking {
        val d = day()
        val pt = assertNotNull(diaries.diary(d.pt))
        assertEquals(DiaryFields(plan = true, fact = true, behavior = true), pt.fields)
        assertEquals(s("Орлова_К."), pt.title)
        assertEquals(d.orlova, pt.clientId)
        assertEquals("Кроль на груди: дыхание на 3 гребка", pt.plan)
        assertEquals(listOf(s("Орлова_К.")), pt.behavior.map { it.name })

        // Сплит — поведение у каждого ребёнка; группа — без поведения.
        val split = assertNotNull(diaries.diary(d.split))
        assertEquals(listOf(s("Ковалёв_Т."), s("Ковалёва_А.")), split.behavior.map { it.name })
        assertNull(split.clientId)
        val group = assertNotNull(diaries.diary(d.group))
        assertEquals(DiaryFields(plan = true, fact = true, behavior = false), group.fields)
        assertTrue(group.behavior.isEmpty())
        assertEquals(d.groupId, group.groupId)

        // Черновик — каждая правка сразу в базе: закрыли и открыли — всё на месте.
        diaries.saveFact(d.pt, "Проплыли 6 × 25")
        diaries.saveFact(d.pt, "Проплыли 6 × 25 м кролем")
        diaries.saveBehavior(d.pt, d.orlova, "Работала внимательно")
        diaries.savePlan(d.pt, "Кроль на груди: дыхание на 3 гребка, 6 × 25 м")
        diaries.saveBehavior(d.split, d.kovaleva, "Внимательна")
        val again = assertNotNull(diaries.diary(d.pt))
        assertEquals("Проплыли 6 × 25 м кролем", again.fact)
        assertEquals("Работала внимательно", again.behavior.single().text)
        assertEquals("Кроль на груди: дыхание на 3 гребка, 6 × 25 м", again.plan)
        assertEquals(listOf("", "Внимательна"), assertNotNull(diaries.diary(d.split)).behavior.map { it.text })
        // Поведение видно и в карточке.
        assertEquals("Работала внимательно", assertNotNull(calendar.cards.card(d.pt).first()).clients.single().behavior)
    }

    @Test
    fun nextPlanGoesToNextTrainingOrPending(): Unit = runBlocking {
        val d = day()
        // Следующей у Орловой нет — план ждёт в отложенном плане клиента (4.3).
        assertNull(assertNotNull(diaries.diary(d.pt)).next)
        diaries.saveNextPlan(d.pt, "Закрепить дыхание на 50 м")
        assertEquals("Закрепить дыхание на 50 м", db.clientDao().client(d.orlova)!!.pendingPlan)
        assertEquals("Закрепить дыхание на 50 м", assertNotNull(diaries.diary(d.pt)).nextPlan)

        // Появилась тренировка в чт — «План на следующую · чт 22.10, 13:30» пишет в её план.
        val next = training(at(thursday, 13, 30), d.orlova)
        val diary = assertNotNull(diaries.diary(d.pt))
        assertEquals(at(thursday, 13, 30), diary.next)
        diaries.saveNextPlan(d.pt, "Добавить повороты")
        assertEquals("Добавить повороты", db.trainingDao().training(next)!!.plan)

        // Группа — следующая той же группы.
        val groupNext = training(at(thursday, 12), type = "Группа", groupId = d.groupId)
        assertEquals(at(thursday, 12), assertNotNull(diaries.diary(d.group)).next)
        diaries.saveNextPlan(d.group, "Дельфин: работа рук")
        assertEquals("Дельфин: работа рук", db.trainingDao().training(groupNext)!!.plan)
    }

    @Test
    fun noDiaryForCancelledAndDuty(): Unit = runBlocking {
        val orlov = client("Орлов", "Максим")
        val id = training(at(tuesday, 17), orlov)
        assertNotNull(calendar.cards.cancel(id, emptyList(), null, now))
        assertNull(diaries.diary(id))
        assertTrue(diaries.missing.first().isEmpty())
    }
}
