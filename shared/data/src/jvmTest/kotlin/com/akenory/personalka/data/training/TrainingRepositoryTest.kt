package com.akenory.personalka.data.training

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.RelationRole
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.training.FormField
import com.akenory.personalka.domain.training.Repeat
import com.akenory.personalka.domain.training.TrainingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Форма тренировки (шаг 1.13, ТЗ 5.5, 4.4, 4.5). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class TrainingRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var forms: TrainingRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)
    private val tueThu = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
        forms = calendar.forms
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

    private fun day(n: Int) = LocalDate(2026, 10, 20).plus(n, DateTimeUnit.DAY)

    /** ПТ в ББ у [clientId]. */
    private suspend fun pt(start: LocalDateTime, clientId: String, repeat: Repeat? = null, place: String = "ББ", plan: String = "") =
        TrainingDraft(start, typeId("ПТ"), listOf(clientId), null, placeId(place), 50, repeat, plan)

    private suspend fun saved(result: SaveResult): SaveResult.Saved = assertIs<SaveResult.Saved>(result)

    /** Тренировки, кроме удалённых, по времени. */
    private suspend fun all() = db.trainingDao().trainingsBetween(at(day(-60), 0), at(day(120), 0))

    private suspend fun clientsOf(id: String) =
        db.trainingDao().participationsOf(id).sortedWith(compareBy({ it.sync.createdAt }, { it.id })).map { it.clientId }

    @Test
    fun formDataLastTypeFirstRecentClientsAndRelatives(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val belova = client("Белова", "Ева")
        val belov = client("Белов", "Игорь")
        clients.addRelation(belova, belov, RelationRole(RelationKind.SIBLING))
        saved(forms.create(pt(at(tuesday, 17, 15), morozov)))
        millis += 1_000
        saved(forms.create(TrainingDraft(at(tuesday, 18), typeId("СПТ"), listOf(belova), null, placeId("ДБ"))))

        val form = forms.form().first()
        // Последний тип первым, дежурства в форме тренировки нет (у него своя форма).
        assertEquals(listOf("СПТ", "ПТ", "Сплит", "Мини-группа", "Группа", "Грудничковое", "Гидрореабилитация", "Кинезио"), form.types.map { it.name })
        assertEquals(listOf(belova, morozov), form.recentClientIds)
        assertEquals(placeId("ББ"), form.lastPlaceByType[typeId("ПТ")])
        assertEquals(placeId("ДБ"), form.lastPlaceByType[typeId("СПТ")])
        assertEquals(listOf(belov), form.relatives[belova])
        assertEquals(listOf(belova), form.relatives[belov])
        // Клиенты — по алфавиту, «Белов» раньше «Белова».
        assertEquals(listOf("Белов", "Белова", "Морозов"), form.clients.map { it.name.lastName })
        assertEquals(s("Морозов_Д."), form.clients.last().label)
        assertSame(calendar.copied, forms.copied)
    }

    @Test
    fun createSingleWithPlanAndUndo(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val result = saved(forms.create(pt(at(tuesday, 17, 15), morozov, plan = " 8 × 25 м кроль ")))
        assertEquals(s("Создано: вт_20.10, 17:15"), result.message)
        val t = all().single()
        assertEquals(at(tuesday, 17, 15), t.start)
        assertEquals(50, t.durationMin)
        assertEquals("8 × 25 м кроль", t.plan)
        assertNull(t.seriesId)
        assertFalse(t.overRestriction)
        assertEquals(listOf(morozov), clientsOf(t.id))

        // «Отменить» — тренировки и участия нет.
        forms.undo(result.undo)
        assertEquals(emptyList(), all())
        assertEquals(emptyList(), db.trainingDao().participationsOf(t.id))
    }

    @Test
    fun splitKeepsClientOrder(): Unit = runBlocking {
        val sidorov = client("Сидоров", "Олег")
        val petrov = client("Петров", "Игорь")
        saved(forms.create(TrainingDraft(at(tuesday, 18), typeId("Сплит"), listOf(sidorov, petrov), null, placeId("ББ"))))
        // Порядок выбора в форме — порядок в блоке, а не алфавит.
        assertEquals("Сидоров / Петров", calendar.trainings(tuesday, day(1)).first().single().title)
    }

    @Test
    fun requiredFieldsNotSaved(): Unit = runBlocking {
        val result = forms.create(TrainingDraft(at(tuesday, 17, 15), typeId("ПТ"), emptyList(), null, placeId("ББ")))
        assertEquals(SaveResult.Invalid(mapOf(FormField.CLIENTS to "Выберите клиента")), result)
        assertEquals(emptyList(), all())
    }

    @Test
    fun seriesForEightWeeksWithPlanOnFirst(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val result = saved(forms.create(pt(at(tuesday, 13, 30), morozov, Repeat(tueThu), plan = "Старт с тумбы")))
        assertEquals("Создано: каждый вт и чт, 13:30", result.message)

        // ТЗ 4.4: вт 20.10 при 8 неделях — по вт 15.12, 17 тренировок; дата из формы может быть уже прошедшей.
        val list = all()
        assertEquals(17, list.size)
        assertEquals(at(tuesday, 13, 30), list.first().start)
        assertEquals(LocalDate(2026, 12, 15), list.last().start.date)
        assertEquals(listOf("Старт с тумбы"), list.mapNotNull { it.plan })
        assertEquals("Старт с тумбы", list.first().plan)
        assertTrue(list.all { clientsOf(it.id) == listOf(morozov) })

        val series = db.scheduleDao().series(list.first().seriesId!!)!!
        assertEquals(tueThu, series.weekdays)
        assertEquals(LocalTime(13, 30), series.startTime)
        assertEquals(tuesday, series.validFrom)
        assertNull(series.validTo)
        assertEquals(LocalDate(2026, 12, 15), series.generatedUntil)
        assertEquals(listOf(morozov), db.scheduleDao().seriesClients(series.id).map { it.clientId })

        forms.undo(result.undo)
        assertEquals(emptyList(), all())
        assertTrue(db.scheduleDao().series(series.id)!!.sync.deletedAt != null)
        assertEquals(emptyList(), db.scheduleDao().seriesClients(series.id))
    }

    @Test
    fun seriesUntilDate(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        saved(forms.create(pt(at(tuesday, 13, 30), morozov, Repeat(setOf(DayOfWeek.TUESDAY), LocalDate(2026, 11, 3)))))
        assertEquals(listOf(tuesday, day(7), day(14)), all().map { it.start.date })
    }

    @Test
    fun restrictionAsksBeforeWriting(): Unit = runBlocking {
        db.scheduleDao().upsertBlock(Samples.block(placeId("ДБ")).copy(skipDates = emptyList()))
        val morozov = client("Морозов", "Даниил")

        // Одна тренировка: окно со строкой запрета; «Поставить» — с флагом.
        val draft = pt(at(tuesday, 17, 15), morozov, place = "ДБ")
        assertEquals(SaveResult.Confirm(listOf("Детский бассейн 17:00–18:00: групповая тренировка")), forms.create(draft))
        assertEquals(emptyList(), all())
        saved(forms.create(draft, confirmed = true))
        assertTrue(all().single().overRestriction)

        // Серия — одно окно на все тренировки (4.5): каждый вт в 17:30 — 9 из 9.
        val series = pt(at(tuesday, 17, 30), morozov, Repeat(setOf(DayOfWeek.TUESDAY)), place = "ДБ")
        assertEquals(
            SaveResult.Confirm(listOf(s("Детский бассейн 17:00–18:00: групповая тренировка — 9 из 9_тренировок, первая вт_20.10"))),
            forms.create(series),
        )
        assertEquals(1, all().size)
        saved(forms.create(series, confirmed = true))
        assertEquals(10, all().size)
        assertTrue(all().all { it.overRestriction })
        // В большом бассейне запрета нет — окна тоже.
        saved(forms.create(pt(at(tuesday, 17, 15), morozov, place = "ББ")))
    }

    @Test
    fun dutySeriesIgnoresRestrictions(): Unit = runBlocking {
        db.scheduleDao().upsertBlock(Samples.block(placeId("ДБ")).copy(skipDates = emptyList()))
        val form = forms.form().first()
        assertEquals("Дежурство", form.duty?.name)

        // Дежурство ДБ 16:00–19:00 каждый вт — поверх запрета 17:00–18:00, но без окна и без флага (4.5).
        val draft = TrainingDraft(at(tuesday, 16), form.duty!!.id, locationId = placeId("ДБ"), durationMin = 180, repeat = Repeat(setOf(DayOfWeek.TUESDAY)))
        val result = saved(forms.create(draft))
        assertEquals(s("Создано: каждый вт, 16:00"), result.message)
        assertEquals(9, all().size)
        assertTrue(all().none { it.overRestriction })
        assertTrue(forms.stored(all().first().id)!!.isDuty)

        // Без клиентов не ругается, а без места — да.
        assertIs<SaveResult.Invalid>(forms.create(draft.copy(locationId = null, repeat = null)))
        forms.undo(result.undo)
        assertEquals(emptyList(), all())
    }

    @Test
    fun dutyForColleague(): Unit = runBlocking {
        val duty = forms.form().first().duty!!.id
        // Дежурство ББ 11:00–15:00 за коллегу: «за» в начале не хранится.
        val draft = TrainingDraft(at(tuesday, 11), duty, locationId = placeId("ББ"), durationMin = 240, coverFor = " за Александра ")
        saved(forms.create(draft))
        val id = all().single().id
        assertEquals("Александра", all().single().coverFor)
        assertEquals("Александра", forms.stored(id)!!.draft.coverFor)

        // Поправили имя, потом стёрли — дежурство стало своим.
        saved(forms.update(id, forms.stored(id)!!.draft.copy(coverFor = "Дмитрия Мальцева")))
        assertEquals("Дмитрия Мальцева", all().single().coverFor)
        val moved = saved(forms.update(id, forms.stored(id)!!.draft.copy(start = at(tuesday, 12))))
        assertEquals("Дмитрия Мальцева", all().single().coverFor, "Перенос пометку не стирает")
        forms.undo(moved.undo)
        saved(forms.update(id, forms.stored(id)!!.draft.copy(coverFor = "")))
        assertNull(all().single().coverFor)

        // У тренировки пометки не бывает, даже если она пришла в черновике.
        val morozov = client("Морозов", "Даниил")
        saved(forms.create(pt(at(tuesday, 17, 15), morozov, place = "ББ").copy(coverFor = "Александра")))
        assertTrue(all().none { it.coverFor != null })
    }

    @Test
    fun groupTakesMembersOnDate(): Unit = runBlocking {
        val group = groups.create(GroupDraft("Сб 9:00 ББ", typeId("Группа"), placeId("ББ")))
        val belova = client("Белова", "Ева")
        val gusev = client("Гусев", "Борис")
        groups.addMember(group, belova, LocalDate(2026, 9, 1))
        groups.addMember(group, gusev, LocalDate(2026, 11, 1))
        val saturday = LocalDate(2026, 10, 24)
        val draft = TrainingDraft(at(saturday, 9), typeId("Группа"), emptyList(), group, placeId("ББ"), 60)

        val one = saved(forms.create(draft))
        assertEquals(listOf(belova), clientsOf(all().single().id))
        forms.undo(one.undo)

        // Серия: на каждую дату — состав на эту дату (4.4).
        saved(forms.create(draft.copy(repeat = Repeat(setOf(DayOfWeek.SATURDAY)))))
        val list = all()
        assertEquals(listOf(belova), clientsOf(list.first().id))
        assertEquals(setOf(belova, gusev), clientsOf(list.first { it.start.date == LocalDate(2026, 11, 7) }.id).toSet())
        assertTrue(list.all { it.groupId == group })
    }

    @Test
    fun singleEditMovesAndChangesClients(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val belova = client("Белова", "Ева")
        saved(forms.create(pt(at(tuesday, 17, 15), morozov)))
        val id = all().single().id
        val stored = forms.stored(id)!!
        assertEquals(pt(at(tuesday, 17, 15), morozov), stored.draft)
        assertNull(stored.seriesRepeat)

        val result = saved(forms.update(id, stored.draft.copy(start = at(day(2), 18), clientIds = listOf(belova), plan = "Брасс")))
        assertEquals(s("Перенесено на чт_22.10, 18:00"), result.message)
        assertEquals(at(day(2), 18), all().single().start)
        assertEquals("Брасс", all().single().plan)
        assertEquals(listOf(belova), clientsOf(id))

        forms.undo(result.undo)
        assertEquals(at(tuesday, 17, 15), all().single().start)
        assertNull(all().single().plan)
        assertEquals(listOf(morozov), clientsOf(id))

        // Только план — «Сохранено».
        assertEquals(SAVED, saved(forms.update(id, stored.draft.copy(plan = "Кроль"))).message)
    }

    @Test
    fun seriesEditAsksOnlyThisOrFollowing(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        saved(forms.create(pt(at(tuesday, 13, 30), morozov, Repeat(tueThu))))
        val nextTuesday = day(7)
        val id = all().single { it.start.date == nextTuesday }.id
        val stored = forms.stored(id)!!
        assertEquals(Repeat(tueThu), stored.seriesRepeat)
        val later = stored.draft.copy(start = at(nextTuesday, 14))

        // Макет «Изменение тренировки серии»: вопрос с серией и новым временем, ничего не записано.
        assertEquals(
            SaveResult.AskScope(s("Морозов_Д. · каждый вт и чт, 13:30. Новое время — 14:00–14:50."), nextTuesday),
            forms.update(id, later),
        )
        assertEquals(at(nextTuesday, 13, 30), db.trainingDao().training(id)!!.start)

        // «Только эту»: отдельная, её дата — в пропусках серии.
        val only = saved(forms.update(id, later, EditScope.ONLY_THIS))
        val t = db.trainingDao().training(id)!!
        assertEquals(at(nextTuesday, 14), t.start)
        assertTrue(t.seriesDetached)
        assertEquals(listOf(nextTuesday), db.scheduleDao().series(t.seriesId!!)!!.skipDates)
        forms.undo(only.undo)
        assertFalse(db.trainingDao().training(id)!!.seriesDetached)
        assertEquals(emptyList(), db.scheduleDao().series(t.seriesId!!)!!.skipDates)

        // План — не расписание: вопроса нет.
        assertEquals(SAVED, saved(forms.update(id, stored.draft.copy(plan = "Ласты"))).message)
    }

    @Test
    fun followingMovesTheRestOfSeries(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        saved(forms.create(pt(at(tuesday, 13, 30), morozov, Repeat(tueThu))))
        val oldSeries = all().first().seriesId!!
        val nextTuesday = day(7)
        val id = all().single { it.start.date == nextTuesday }.id
        val draft = forms.stored(id)!!.draft.copy(start = at(nextTuesday, 14))

        val result = saved(forms.update(id, draft, EditScope.FOLLOWING))
        assertEquals("Изменены эта и следующие", result.message)
        val list = all()
        assertEquals(17, list.size)
        // До 27.10 — прежнее время и прежняя серия, с 27.10 — 14:00 в новой.
        assertEquals(listOf(LocalTime(13, 30), LocalTime(13, 30)), list.filter { it.start.date < nextTuesday }.map { it.start.time })
        assertTrue(list.filter { it.start.date >= nextTuesday }.all { it.start.time == LocalTime(14, 0) && it.seriesId != oldSeries })
        assertEquals(day(6), db.scheduleDao().series(oldSeries)!!.validTo)
        val newSeries = db.scheduleDao().series(list.last().seriesId!!)!!
        assertEquals(LocalTime(14, 0), newSeries.startTime)
        assertEquals(nextTuesday, newSeries.validFrom)
        assertEquals(listOf(morozov), db.scheduleDao().seriesClients(newSeries.id).map { it.clientId })

        // «Отменить» — всё по-старому: одна серия, 13:30.
        forms.undo(result.undo)
        assertTrue(all().all { it.start.time == LocalTime(13, 30) && it.seriesId == oldSeries })
        assertNull(db.scheduleDao().series(oldSeries)!!.validTo)
        assertTrue(db.scheduleDao().series(newSeries.id)!!.sync.deletedAt != null)
    }

    @Test
    fun pastSeriesTrainingChangesOnlyItselfWithoutQuestion(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        // Серия задним числом с вт 13.10.
        saved(forms.create(pt(at(day(-7), 13, 30), morozov, Repeat(setOf(DayOfWeek.TUESDAY)))))
        val past = all().first()
        assertEquals(day(-7), past.start.date)

        // Прошлое «Эту и следующие» не меняет (4.4) — спрашивать нечего, меняется только эта.
        val result = forms.update(past.id, forms.stored(past.id)!!.draft.copy(start = at(day(-7), 12)))
        saved(result)
        assertTrue(db.trainingDao().training(past.id)!!.seriesDetached)
        assertEquals(LocalTime(13, 30), all().last().start.time)
    }

    @Test
    fun singleBecomesSeries(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val thursday = day(2)
        saved(forms.create(pt(at(thursday, 10), morozov)))
        val id = all().single().id

        val result = saved(forms.update(id, forms.stored(id)!!.draft.copy(repeat = Repeat(setOf(DayOfWeek.THURSDAY)))))
        assertEquals("Создано: каждый чт, 10:00", result.message)
        // Та же тренировка — первая в серии, дальше каждый чт до горизонта (по 10.12).
        val list = all()
        assertEquals(8, list.size)
        assertEquals(id, list.first().id)
        assertTrue(list.all { it.seriesId == list.first().seriesId && it.seriesId != null && !it.seriesDetached })

        forms.undo(result.undo)
        assertEquals(listOf(id), all().map { it.id })
        assertNull(all().single().seriesId)
    }

    @Test
    fun extendSeriesUpToHorizonOnce(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        saved(forms.create(pt(at(tuesday, 13, 30), morozov, Repeat(tueThu))))
        assertEquals(0, forms.extendSeries())

        // Через неделю горизонт — вт 22.12: добавились чт 17.12 и вт 22.12; второй запуск ничего не дублирует.
        millis += 7 * 24 * 60 * 60 * 1000L
        assertEquals(2, forms.extendSeries())
        assertEquals(0, forms.extendSeries())
        assertEquals(listOf(LocalDate(2026, 12, 17), LocalDate(2026, 12, 22)), all().takeLast(2).map { it.start.date })
        assertTrue(all().takeLast(2).all { clientsOf(it.id) == listOf(morozov) })
    }

    @Test
    fun suggestStartIsNearestFreeHour(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        assertEquals(at(tuesday, 16), forms.suggestStart(50))
        saved(forms.create(pt(at(tuesday, 16), morozov)))
        saved(forms.create(pt(at(tuesday, 17), morozov)))
        assertEquals(at(tuesday, 18), forms.suggestStart(50))
        // 90 минут с 18:00 задели бы 19:00.
        saved(forms.create(pt(at(tuesday, 19), morozov)))
        assertEquals(at(tuesday, 20), forms.suggestStart(90))
    }

    private companion object {
        /** Изменение без переноса. */
        const val SAVED = "Сохранено"
    }
}
