package com.akenory.personalka.data.calendar

import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.training.EditScope
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.CalendarBadge.CHARGED
import com.akenory.personalka.domain.calendar.CalendarBadge.DONE
import com.akenory.personalka.domain.calendar.CalendarBadge.NO_DIARY
import com.akenory.personalka.domain.calendar.CalendarBadge.OVER_DUTY
import com.akenory.personalka.domain.calendar.CalendarBadge.OVER_RESTRICTION
import com.akenory.personalka.domain.calendar.MoveProblem
import com.akenory.personalka.domain.calendar.ZoneKind
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
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
import kotlin.test.assertTrue

/** Тренировки, значки, зоны и перенос в календаре (шаги 1.9–1.11, ТЗ 5.3). «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
class CalendarRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private var millis = ClubTime.toMillis(now)
    private lateinit var calendar: CalendarRepository
    private lateinit var clients: ClientRepository
    private lateinit var groups: GroupRepository
    private lateinit var settings: SettingsRepository
    private val tuesday = LocalDate(2026, 10, 20)

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        calendar = CalendarRepository(db) { millis }
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

    /** Тренировка с клиентами в порядке записи; у каждого участия — свой момент создания. */
    private suspend fun training(
        type: String,
        place: String,
        start: LocalDateTime,
        minutes: Int = 50,
        clientIds: List<String> = emptyList(),
        groupId: String? = null,
        status: TrainingStatus = TrainingStatus.PLANNED,
        statuses: List<ParticipationStatus> = clientIds.map { ParticipationStatus.EXPECTED },
        fact: String? = null,
        overRestriction: Boolean = false,
    ): String {
        val t = Samples.training(typeId(type), placeId(place), start)
            .copy(durationMin = minutes, groupId = groupId, status = status, fact = fact, overRestriction = overRestriction)
        db.trainingDao().upsertTraining(t)
        clientIds.forEachIndexed { i, id ->
            val p = Samples.participation(t.id, id)
            db.trainingDao().upsertParticipation(
                p.copy(sync = p.sync.copy(createdAt = millis + i), status = statuses[i], behavior = null, cancelReason = null, notifiedAt = null, chargeOverridden = false),
            )
        }
        return t.id
    }

    private suspend fun client(last: String, first: String) = clients.create(ClientDraft(last, first))

    private suspend fun day(date: LocalDate = tuesday) = calendar.trainings(date, date.plus(1, DateTimeUnit.DAY)).first()

    private suspend fun zones(date: LocalDate = tuesday) = calendar.zones(date, date.plus(1, DateTimeUnit.DAY)).first()

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    /** Запрет ДБ 17:00–18:00 «Групповая тренировка» по вт и чт с 01.09.2026, без пропусков. */
    private suspend fun groupRestriction(): String {
        val block = Samples.block(placeId("ДБ")).copy(skipDates = emptyList())
        db.scheduleDao().upsertBlock(block)
        return block.id
    }

    private suspend fun flag(id: String) = db.trainingDao().training(id)!!.overRestriction

    @Test
    fun titlesTypePlaceAndColor(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val petrov = client("Петров", "Игорь")
        val sidorov = client("Сидоров", "Олег")
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(8, 0)), clientIds = listOf(morozov))
        // Сидорова записали первым — он и в имени первый, хоть по алфавиту Петров раньше.
        training("Сплит", "ББ", LocalDateTime(tuesday, LocalTime(9, 0)), clientIds = listOf(sidorov, petrov))
        training("Гидро", "ДБ", LocalDateTime(tuesday, LocalTime(11, 0)))

        val list = day()
        assertEquals(listOf(s("Морозов_Д."), "Сидоров / Петров", "Гидрореабилитация"), list.map { it.title })
        val pt = list.first()
        assertEquals("ПТ", pt.typeShort)
        assertEquals("ББ", pt.placeShort)
        // Цвет — у места (ББ синий), оттенок — у типа (ПТ светлая, сплит — вторая, шаг 1.13в).
        assertEquals("blue", pt.color)
        assertEquals(1, pt.shade)
        assertEquals(2, list[1].shade)
        assertEquals("pink", list[2].color)
        assertEquals(50, pt.durationMin)
        assertFalse(pt.cancelled)
    }

    @Test
    fun groupShowsNameAndWaitingKids(): Unit = runBlocking {
        val groupId = groups.create(GroupDraft("Вт, Чт 18:00 ДБ", typeId("Группа"), placeId("ДБ")))
        val kids = listOf(client("Белова", "Ева"), client("Гусев", "Борис"), client("Ильин", "Глеб"))
        training(
            "Группа", "ДБ", LocalDateTime(tuesday, LocalTime(18, 0)), minutes = 60, clientIds = kids, groupId = groupId,
            statuses = listOf(ParticipationStatus.EXPECTED, ParticipationStatus.CANCELLED, ParticipationStatus.EXPECTED),
        )
        // Отменённого ребёнка не ждут: в скобках двое.
        assertEquals("Вт, Чт 18:00 ДБ (2)", day().single().title)
    }

    @Test
    fun cancelledShownDutyAndDeletedNot(): Unit = runBlocking {
        val zaitseva = client("Зайцева", "Мария")
        training(
            "ПТ", "ББ", LocalDateTime(tuesday, LocalTime(10, 0)), clientIds = listOf(zaitseva), status = TrainingStatus.CANCELLED,
            statuses = listOf(ParticipationStatus.CANCELLED_CHARGED),
        )
        // Дежурство — заливка под тренировками (шаг 1.10), а не блок.
        training("Деж", "ББ", LocalDateTime(tuesday, LocalTime(11, 0)), minutes = 240)
        val gone = training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(12, 0)))
        db.trainingDao().training(gone)!!.let { db.trainingDao().upsertTraining(it.copy(sync = it.sync.deleted(millis))) }

        val cancelled = day().single()
        assertTrue(cancelled.cancelled)
        assertEquals(s("Зайцева_М."), cancelled.title)
    }

    @Test
    fun daysAreHalfOpen(): Unit = runBlocking {
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(0, 0)))
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(23, 30)))
        training("ПТ", "ББ", LocalDateTime(LocalDate(2026, 10, 21), LocalTime(0, 0)))
        training("ПТ", "ББ", LocalDateTime(LocalDate(2026, 10, 19), LocalTime(23, 59)))
        assertEquals(listOf(LocalTime(0, 0), LocalTime(23, 30)), day().map { it.start.time })
        // Три дня сразу — по времени.
        assertEquals(4, calendar.trainings(LocalDate(2026, 10, 19), LocalDate(2026, 10, 22)).first().size)
    }

    @Test
    fun namesFollowEdits(): Unit = runBlocking {
        val id = client("Белова", "Ева")
        training("ПТ", "ББ", LocalDateTime(tuesday, LocalTime(10, 0)), clientIds = listOf(id))
        assertEquals(s("Белова_Е."), day().single().title)
        clients.update(id, ClientDraft("Белова-Смирнова", "Ева"))
        assertEquals(s("Белова-Смирнова_Е."), day().single().title)
    }

    @Test
    fun workHoursFromSettings(): Unit = runBlocking {
        assertEquals(WorkHours(LocalTime(7, 0), LocalTime(23, 0)), calendar.workHours.first())
        val coach = db.coachDao().currentCoach()!!
        val stored = db.coachDao().settings(coach.id)!!
        db.coachDao().upsertSettings(stored.copy(workDayStart = LocalTime(8, 0), workDayEnd = LocalTime(21, 0)))
        assertEquals(WorkHours(LocalTime(8, 0), LocalTime(21, 0)), calendar.workHours.first())
    }

    @Test
    fun badgesFromStatusesFlagsDiaryAndDuty(): Unit = runBlocking {
        val morozov = client("Морозов", "Даниил")
        val zaitseva = client("Зайцева", "Мария")
        training("ПТ", "ББ", at(tuesday, 8), clientIds = listOf(morozov), status = TrainingStatus.DONE, fact = "Кроль 8 × 25 м")
        training("ПТ", "ББ", at(tuesday, 9), clientIds = listOf(morozov), status = TrainingStatus.DONE)
        training(
            "ПТ", "ББ", at(tuesday, 10), clientIds = listOf(zaitseva), status = TrainingStatus.CANCELLED,
            statuses = listOf(ParticipationStatus.CANCELLED_CHARGED),
        )
        // Дежурство ББ 11:00–15:00; тренировка в ДБ поверх него — тоже накладка: тренер дежурит, где бы ни стояла.
        training("Деж", "ББ", at(tuesday, 11), minutes = 240)
        training("ПТ", "ДБ", at(tuesday, 12), clientIds = listOf(morozov))
        training("ПТ", "ДБ", at(tuesday, 17), clientIds = listOf(morozov), overRestriction = true)

        assertEquals(
            listOf(listOf(DONE), listOf(NO_DIARY, DONE), listOf(CHARGED), listOf(OVER_DUTY), listOf(OVER_RESTRICTION)),
            day().map { it.badges },
        )

        // Дневник выключен в настройках — точки нет; отменённое дежурство накладкой не считается.
        val coach = db.coachDao().currentCoach()!!
        db.coachDao().upsertSettings(db.coachDao().settings(coach.id)!!.copy(diaryEnabled = false))
        val duty = db.trainingDao().trainingsBetween(at(tuesday, 11), at(tuesday, 12)).single()
        db.trainingDao().upsertTraining(duty.copy(status = TrainingStatus.CANCELLED))
        assertEquals(listOf(listOf(DONE), listOf(DONE), listOf(CHARGED), emptyList(), listOf(OVER_RESTRICTION)), day().map { it.badges })
    }

    @Test
    fun zonesOfDays(): Unit = runBlocking {
        val restriction = Samples.block(placeId("ДБ"))
        db.scheduleDao().upsertBlock(restriction)
        training("Деж", "ББ", at(tuesday, 11), minutes = 240)
        // Отменённое дежурство и удалённый запрет не видны.
        training("Деж", "ББ", at(tuesday, 19), minutes = 120, status = TrainingStatus.CANCELLED)
        val gone = Samples.block(placeId("ББ"))
        db.scheduleDao().upsertBlock(gone.copy(sync = gone.sync.deleted(millis)))

        val (duty, hatched) = zones()
        assertEquals(ZoneKind.DUTY, duty.kind)
        assertEquals(11 * 60 to 15 * 60, duty.start to duty.end)
        assertEquals("Дежурство", duty.title)
        assertEquals("ББ 11:00–15:00 — дежурство", duty.text)
        assertEquals("Дежурство · ББ", duty.label)
        assertEquals("blue", duty.color, "Дежурство — цвета своего места")
        // Место зоны — для фильтра мест в шапке (шаг 1.12).
        assertEquals(placeId("ББ"), duty.placeId)

        assertEquals(ZoneKind.RESTRICTION, hatched.kind)
        assertEquals(restriction.id, hatched.id)
        assertEquals(tuesday, hatched.date)
        assertEquals("Запрет", hatched.title)
        assertEquals("ДБ 17:00–18:00 — групповая тренировка", hatched.text)
        assertEquals("ДБ · групповая тренировка", hatched.label)
        assertEquals("Каждый вт и чт", hatched.repeat)
        assertEquals(placeId("ДБ"), hatched.placeId)

        // Неделя: запрет во вт 20.10 и вт 27.10, чт 22.10 пропущен; дежурство — одно.
        val week = calendar.zones(tuesday, LocalDate(2026, 10, 28)).first()
        assertEquals(
            listOf(tuesday to ZoneKind.DUTY, tuesday to ZoneKind.RESTRICTION, LocalDate(2026, 10, 27) to ZoneKind.RESTRICTION),
            week.map { it.date to it.kind },
        )
    }

    @Test
    fun skipDayRecalculatesFutureFlagsAndUndo(): Unit = runBlocking {
        val id = groupRestriction()
        val today = training("ПТ", "ДБ", at(tuesday, 17), overRestriction = true)
        val nextWeek = training("ПТ", "ДБ", at(LocalDate(2026, 10, 27), 17), overRestriction = true)
        // Прошлая сохраняет флаг, с которым прошла.
        val past = training("ПТ", "ДБ", at(LocalDate(2026, 10, 13), 17), overRestriction = true)

        calendar.skipRestrictionDay(id, tuesday)
        assertEquals(listOf(tuesday), db.scheduleDao().block(id)!!.skipDates)
        assertTrue(zones().isEmpty())
        assertEquals(listOf(false, true, true), listOf(flag(today), flag(nextWeek), flag(past)))
        // Повторное нажатие ничего не меняет.
        val version = db.scheduleDao().block(id)!!.sync.version
        calendar.skipRestrictionDay(id, tuesday)
        assertEquals(version, db.scheduleDao().block(id)!!.sync.version)

        // «Отменить» — запрет снова в этот день, флаг вернулся.
        calendar.unskipRestrictionDay(id, tuesday)
        assertEquals(emptyList(), db.scheduleDao().block(id)!!.skipDates)
        assertEquals(1, zones().size)
        assertEquals(listOf(true, true, true), listOf(flag(today), flag(nextWeek), flag(past)))
    }

    @Test
    fun recalcSamePlaceOnlyNeverDuty(): Unit = runBlocking {
        val id = groupRestriction()
        // Флаги расходятся с запретами: в ББ запрета нет, дежурство с запретами не сверяется, а чт 22.10 в ДБ — под ним.
        val otherPlace = training("ПТ", "ББ", at(tuesday, 17), overRestriction = true)
        val duty = training("Деж", "ДБ", at(tuesday, 17), minutes = 60, overRestriction = true)
        val samePlace = training("ПТ", "ДБ", at(LocalDate(2026, 10, 22), 17))
        assertEquals(DayOfWeek.THURSDAY, LocalDate(2026, 10, 22).dayOfWeek)

        // Любое изменение запретов пересчитывает флаг будущих: пропущен чт 29.10.
        calendar.skipRestrictionDay(id, LocalDate(2026, 10, 29))
        assertEquals(listOf(false, false, true), listOf(flag(otherPlace), flag(duty), flag(samePlace)))
    }

    @Test
    fun moveAsksBeforeRestrictionThenUndo(): Unit = runBlocking {
        groupRestriction()
        val client = client("Орлов", "Максим")
        val id = training("ПТ", "ДБ", at(tuesday, 16), clientIds = listOf(client), statuses = listOf(ParticipationStatus.EXPECTED))
        val version = db.trainingDao().training(id)!!.sync.version

        // Под запрет того же места — сначала окно, в базе ничего не меняется (4.5).
        val ask = calendar.move(id, at(tuesday, 17, 15))
        assertEquals(MoveResult.Confirm(listOf("Детский бассейн 17:00–18:00: групповая тренировка")), ask)
        assertEquals(at(tuesday, 16), db.trainingDao().training(id)!!.start)
        assertEquals(version, db.trainingDao().training(id)!!.sync.version)

        // «Поставить» — перенесено с флагом; клиент и статус те же.
        val moved = assertIs<MoveResult.Moved>(calendar.move(id, at(tuesday, 17, 15), confirmed = true))
        val stored = db.trainingDao().training(id)!!
        assertEquals(at(tuesday, 17, 15), stored.start)
        assertTrue(stored.overRestriction)
        assertEquals(TrainingStatus.PLANNED, stored.status)
        assertEquals(listOf(OVER_RESTRICTION), day().single().badges)
        assertEquals(listOf(client), db.trainingDao().participationsOf(id).map { it.clientId })

        // «Отменить» — на прежнем месте и без флага.
        calendar.undoMove(moved.undo)
        assertEquals(at(tuesday, 16), db.trainingDao().training(id)!!.start)
        assertFalse(flag(id))
    }

    @Test
    fun moveToOtherDayAndPlaceWithoutWindow(): Unit = runBlocking {
        val restriction = groupRestriction()
        val id = training("ПТ", "ББ", at(tuesday, 16))
        // В ББ запрета нет — окна нет; на другой день — в той же записи.
        assertIs<MoveResult.Moved>(calendar.move(id, at(LocalDate(2026, 10, 27), 17, 15)))
        assertEquals(at(LocalDate(2026, 10, 27), 17, 15), db.trainingDao().training(id)!!.start)
        assertFalse(flag(id))
        // Уже под запретом и уходит из-под него — флаг снимается без окна.
        val over = training("ПТ", "ДБ", at(LocalDate(2026, 10, 27), 17), overRestriction = true)
        assertIs<MoveResult.Moved>(calendar.move(over, at(LocalDate(2026, 10, 27), 18)))
        assertFalse(flag(over))
        // Чт 22.10 у запрета пропущен — туда без окна, а в чт 29.10 — с окном.
        calendar.skipRestrictionDay(restriction, LocalDate(2026, 10, 22))
        val evening = training("ПТ", "ДБ", at(tuesday, 19))
        assertIs<MoveResult.Confirm>(calendar.move(evening, at(LocalDate(2026, 10, 29), 17)))
        assertIs<MoveResult.Moved>(calendar.move(evening, at(LocalDate(2026, 10, 22), 17)))
        assertFalse(flag(evening))
    }

    @Test
    fun cancelledAndMarkedStayPut(): Unit = runBlocking {
        val tomorrow = at(LocalDate(2026, 10, 21), 9)
        val cancelled = training("ПТ", "ББ", at(tuesday, 18), status = TrainingStatus.CANCELLED)
        val done = training("ПТ", "ББ", at(LocalDate(2026, 10, 19), 9), status = TrainingStatus.DONE)
        val group = groups.create(GroupDraft("Вт, Чт 18:00 ДБ", typeId("Группа"), placeId("ДБ")))
        val kid = client("Ильин", "Глеб")
        val marked = training("Группа", "ДБ", at(tuesday, 15), groupId = group, clientIds = listOf(kid), statuses = listOf(ParticipationStatus.ATTENDED))

        assertEquals(MoveResult.Refused(MoveProblem.CANCELLED), calendar.move(cancelled, tomorrow))
        assertEquals(MoveResult.Refused(MoveProblem.MARKED_IN_FUTURE), calendar.move(done, tomorrow))
        assertEquals(MoveResult.Refused(MoveProblem.MARKED_IN_FUTURE), calendar.move(marked, tomorrow))
        // Проведённую — в прошлом можно: исправить время задним числом.
        assertIs<MoveResult.Moved>(calendar.move(done, at(LocalDate(2026, 10, 19), 10)))
        // Через полночь и удалённую — нет.
        assertEquals(MoveResult.Refused(null), calendar.move(cancelled, at(tuesday, 23, 30)))
        assertEquals(MoveResult.Refused(null), calendar.move("нет такой", tomorrow))
        // Отметки видны блоку: в будущее его не тащат.
        assertEquals(mapOf(cancelled to false, marked to true), day().associate { it.id to it.marked })
    }

    @Test
    fun seriesTrainingMovesOnlyItself(): Unit = runBlocking {
        val client = client("Морозов", "Даниил")
        val series = Samples.series(typeId("ПТ"), placeId("ББ"))
        db.scheduleDao().upsertSeries(series)
        db.scheduleDao().upsertSeriesClient(Samples.seriesClient(series.id, client))
        val nextTuesday = LocalDate(2026, 10, 27)
        val id = training("ПТ", "ББ", at(nextTuesday, 13, 30), clientIds = listOf(client), statuses = listOf(ParticipationStatus.EXPECTED))
        db.trainingDao().upsertTraining(db.trainingDao().training(id)!!.copy(seriesId = series.id))

        // Сначала вопрос (4.4): ничего не записано.
        val ask = assertIs<MoveResult.AskScope>(calendar.move(id, at(LocalDate(2026, 10, 28), 14)))
        assertEquals(s("Морозов_Д. · каждый вт и чт, 13:30. Новый день — ср_28.10. Новое время — 14:00–14:50."), ask.question)
        assertEquals(nextTuesday, ask.date)
        assertEquals(at(nextTuesday, 13, 30), db.trainingDao().training(id)!!.start)

        // «Только эту»: отдельная, её дата — в пропусках серии, чтобы продление не поставило вторую.
        val moved = assertIs<MoveResult.Moved>(calendar.move(id, at(LocalDate(2026, 10, 28), 14), scope = EditScope.ONLY_THIS))
        assertTrue(db.trainingDao().training(id)!!.seriesDetached)
        assertEquals(listOf(nextTuesday, LocalDate(2026, 11, 3)), db.scheduleDao().series(series.id)!!.skipDates)

        // «Отменить» — снова тренировка серии на своей дате.
        calendar.undoMove(moved.undo)
        assertFalse(db.trainingDao().training(id)!!.seriesDetached)
        assertEquals(listOf(LocalDate(2026, 11, 3)), db.scheduleDao().series(series.id)!!.skipDates)
        assertEquals(at(nextTuesday, 13, 30), db.trainingDao().training(id)!!.start)
    }

    @Test
    fun copyRemembersTypePlaceDurationAndClients(): Unit = runBlocking {
        assertNull(calendar.copied.value)
        val first = client("Сидоров", "Иван")
        val second = client("Петров", "Олег")
        val split = training("Сплит", "ББ", at(tuesday, 18), minutes = 60, clientIds = listOf(first, second))
        assertTrue(calendar.copy(split))
        assertEquals(TrainingCopy(typeId("Сплит"), placeId("ББ"), 60, listOf(first, second), null), calendar.copied.value)

        // У группы — сама группа, не дети.
        val group = groups.create(GroupDraft("Сб 9:00 ББ", typeId("Группа"), placeId("ББ")))
        val lesson = training("Группа", "ББ", at(tuesday, 9), groupId = group, clientIds = listOf(first))
        assertTrue(calendar.copy(lesson))
        assertEquals(TrainingCopy(typeId("Группа"), placeId("ББ"), 50, emptyList(), group), calendar.copied.value)
        assertFalse(calendar.copy("нет такой"))
    }

    @Test
    fun dragStepFromSettings(): Unit = runBlocking {
        assertEquals(15, calendar.dragStep.first())
        val coach = db.coachDao().currentCoach()!!
        val stored = db.coachDao().settings(coach.id)!!
        db.coachDao().upsertSettings(stored.copy(dragStepMinutes = 30))
        assertEquals(30, calendar.dragStep.first())
    }

    @Test
    fun weekViewDaysFromSettings(): Unit = runBlocking {
        assertEquals(3, calendar.weekViewDays.first())
        val coach = db.coachDao().currentCoach()!!
        val stored = db.coachDao().settings(coach.id)!!
        db.coachDao().upsertSettings(stored.copy(weekViewDays = 5))
        assertEquals(5, calendar.weekViewDays.first())
    }

    @Test
    fun placesForFilterInCoachOrderWithoutArchived(): Unit = runBlocking {
        assertEquals(listOf("ББ", "ДБ", "Зал"), calendar.places.first().map { it.shortName })
        assertEquals("Большой бассейн", calendar.places.first().first().name)
        // Порядок — как у тренера в «Местах и типах»; архивное место в фильтре не нужно.
        settings.reorderPlaces(listOf(placeId("Зал"), placeId("ББ"), placeId("ДБ")))
        settings.updatePlace(placeId("ДБ"), PlaceDraft("Детский бассейн", "ДБ"), archived = true)
        assertEquals(listOf("Зал", "ББ"), calendar.places.first().map { it.shortName })
    }
}
