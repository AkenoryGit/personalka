package com.akenory.personalka.domain.series

import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ParticipantState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SATURDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Серии (ТЗ 4.4). «Сейчас» — вт 20.10.2026, 15:40, как в макетах. Клиенты и группы придуманы.
 * Серия ПТ — вт и чт 13:30, 50 минут, Большой бассейн; при 8 неделях она доходит до вт 15.12.
 */
class SeriesRulesTest {
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private fun day(d: Int, m: Int = 10, y: Int = 2026) = LocalDate(y, m, d)
    private fun at(date: LocalDate, h: Int, min: Int = 0) = LocalDateTime(date, LocalTime(h, min))

    private fun ptPattern(
        weekdays: Set<DayOfWeek> = setOf(TUESDAY, THURSDAY),
        time: LocalTime = LocalTime(13, 30),
        clients: List<String> = listOf("orlova"),
    ) = SeriesPattern(
        typeId = "pt",
        locationId = "bb",
        weekdays = weekdays,
        startTime = time,
        durationMin = 50,
        clientIds = clients,
    )

    /** Серия и её сохранённые тренировки; id тренировки — «t» и дата: «t2026-10-22». */
    private class Saved(val series: Series, val trainings: List<SeriesTraining>) {
        fun on(date: LocalDate) = trainings.single { it.date == date }
        fun with(vararg changed: SeriesTraining): Saved =
            Saved(series, trainings.map { t -> changed.firstOrNull { it.id == t.id } ?: t })
    }

    private fun NewTraining.saved() = SeriesTraining(
        id = "t$date",
        seriesId = seriesId,
        start = start,
        durationMin = durationMin,
        typeId = typeId,
        locationId = locationId,
        groupId = groupId,
        plan = plan,
        participants = clientIds.map { ParticipantState(it) },
    )

    private fun created(
        pattern: SeriesPattern = ptPattern(),
        validFrom: LocalDate = day(20),
        validTo: LocalDate? = null,
        members: List<GroupMember> = emptyList(),
    ): Saved {
        val c = SeriesRules.create("s1", pattern, validFrom, validTo, now, members = members)
        return Saved(c.series.single(), c.created.map { it.saved() })
    }

    private fun edit(t: SeriesTraining, start: LocalDateTime = t.start, location: String = t.locationId) = TrainingEdit(
        start = start,
        durationMin = t.durationMin,
        typeId = t.typeId,
        locationId = location,
        clientIds = if (t.groupId == null) t.participants.map { it.clientId } else emptyList(),
        groupId = t.groupId,
    )

    // --- Создание ---

    @Test
    fun createMakesTrainingsEightWeeksAhead() {
        assertEquals(TUESDAY, day(20).dayOfWeek)
        val c = SeriesRules.create("s1", ptPattern(), day(20), null, now)

        val dates = c.created.map { it.date }
        assertEquals(17, dates.size) // 9 вторников с 20.10 по 15.12 и 8 четвергов с 22.10 по 10.12
        assertEquals(day(20), dates.first())
        assertEquals(day(15, 12), dates.last())
        assertTrue(dates.all { it.dayOfWeek == TUESDAY || it.dayOfWeek == THURSDAY })
        assertTrue(c.created.all { it.start.time == LocalTime(13, 30) && it.durationMin == 50 && it.clientIds == listOf("orlova") })
        assertEquals(day(15, 12), c.series.single().generatedUntil)
        assertEquals(day(15, 12), SeriesRules.generationEnd(c.series.single(), day(20)))
    }

    @Test
    fun createFromTheChosenDateEvenIfItHasPassed() {
        // Тренер вносит занятие задним числом: сегодня в 13:30 уже было — оно создаётся, дальше закроется само.
        val c = SeriesRules.create("s1", ptPattern(), day(20), null, now)
        assertEquals(at(day(20), 13, 30), c.created.first().start)
    }

    @Test
    fun createStopsAtSeriesEnd() {
        val c = SeriesRules.create("s1", ptPattern(), day(20), day(5, 11), now)
        assertEquals(listOf(day(20), day(22), day(27), day(29), day(3, 11), day(5, 11)), c.created.map { it.date })
        assertEquals(day(5, 11), c.series.single().generatedUntil)
    }

    @Test
    fun createBeyondHorizonMakesNothingYet() {
        val c = SeriesRules.create("s1", ptPattern(), day(1, 2, 2027), null, now)
        assertTrue(c.created.isEmpty())
        assertEquals(day(31, 1, 2027), c.series.single().generatedUntil)
    }

    @Test
    fun createChecksPatternAndPeriod() {
        assertFailsWith<IllegalArgumentException> { SeriesRules.create("s1", ptPattern(weekdays = emptySet()), day(20), null, now) }
        assertFailsWith<IllegalArgumentException> { SeriesRules.create("s1", ptPattern(time = LocalTime(23, 30)), day(20), null, now) }
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.create("s1", ptPattern().copy(durationMin = 3), day(20), null, now)
        }
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.create("s1", ptPattern().copy(groupId = "sat9"), day(20), null, now)
        }
        assertFailsWith<IllegalArgumentException> { SeriesRules.create("s1", ptPattern(), day(20), day(19), now) }
        assertFailsWith<IllegalArgumentException> { SeriesRules.create("s1", ptPattern(), day(20), null, now, horizonWeeks = 0) }
        // 23:10 + 50 минут — ровно до полуночи, можно.
        SeriesRules.create("s1", ptPattern(time = LocalTime(23, 10)), day(20), null, now)
    }

    @Test
    fun groupSeriesTakesCompositionOnEachDate() {
        val members = listOf(
            GroupMember("a", day(1, 9)),
            GroupMember("b", day(1, 9), toDate = day(31)),
            GroupMember("c", day(7, 11)),
        )
        val pattern = SeriesPattern("group", "bb", setOf(SATURDAY), LocalTime(9, 0), 60, groupId = "sat9")
        val c = SeriesRules.create("s1", pattern, day(24), null, now, members = members)

        assertEquals(listOf("a", "b"), c.created.single { it.date == day(24) }.clientIds)
        assertEquals(listOf("a", "b"), c.created.single { it.date == day(31) }.clientIds)
        assertEquals(listOf("a", "c"), c.created.single { it.date == day(7, 11) }.clientIds)
        assertTrue(c.created.all { it.groupId == "sat9" })
    }

    @Test
    fun dutyIsSeriesWithoutClients() {
        val duty = SeriesPattern("duty", "bb", setOf(TUESDAY), LocalTime(11, 0), 240)
        val c = SeriesRules.create("d1", duty, day(20), null, now)
        assertEquals(9, c.created.size)
        assertTrue(c.created.all { it.clientIds.isEmpty() && it.groupId == null && it.durationMin == 240 })
    }

    // --- Продление ---

    @Test
    fun extendIsIdempotent() {
        val s = created()
        assertEquals(SeriesChanges.NONE, SeriesRules.extend(s.series, s.trainings, now))

        val thursday = at(day(22), 9, 0)
        val first = SeriesRules.extend(s.series, s.trainings, thursday)
        assertEquals(listOf(day(17, 12)), first.created.map { it.date })
        assertEquals(day(17, 12), first.series.single().generatedUntil)

        val again = SeriesRules.extend(first.series.single(), s.trainings + first.created.map { it.saved() }, thursday)
        assertEquals(SeriesChanges.NONE, again)
    }

    @Test
    fun extendOnDayWithoutNewTrainingWritesNothing() {
        val s = created()
        // В среду горизонт — ср 16.12: у серии вт и чт, новых дней нет, серия не перезаписывается.
        assertEquals(SeriesChanges.NONE, SeriesRules.extend(s.series, s.trainings, at(day(21), 8, 0)))
    }

    @Test
    fun extendDoesNotDuplicateDateThatAlreadyHasTraining() {
        val s = created()
        // Тренировка на 17.12 уже пришла с другого телефона, а generatedUntil ещё старый.
        val synced = SeriesTraining("x", "s1", at(day(17, 12), 13, 30), 50, "pt", "bb", participants = listOf(ParticipantState("orlova")))
        val c = SeriesRules.extend(s.series, s.trainings + synced, at(day(22), 9, 0))
        assertTrue(c.created.isEmpty())
        assertEquals(day(17, 12), c.series.single().generatedUntil)
    }

    @Test
    fun extendRespectsSkipDatesAndSeriesEnd() {
        val s = created()
        val skipped = s.series.copy(skipDates = setOf(day(17, 12)))
        assertTrue(SeriesRules.extend(skipped, s.trainings, at(day(22), 9, 0)).created.isEmpty())

        val ended = s.series.copy(validTo = day(10, 12))
        assertEquals(SeriesChanges.NONE, SeriesRules.extend(ended, s.trainings, at(day(22), 9, 0)))
    }

    @Test
    fun extendNeverCreatesPast() {
        val stale = Series("s1", ptPattern(), validFrom = day(1), generatedUntil = day(10))
        val c = SeriesRules.extend(stale, emptyList(), now)
        // Сегодня в 13:30 уже прошло — первая создаётся в чт 22.10.
        assertEquals(day(22), c.created.first().date)
        assertEquals(16, c.created.size)
    }

    @Test
    fun extendAllUsesEachGroupComposition() {
        val pt = created().series
        val group = Series(
            "g1",
            SeriesPattern("group", "bb", setOf(SATURDAY), LocalTime(9, 0), 60, groupId = "sat9"),
            validFrom = day(24),
            generatedUntil = day(12, 12),
        )
        val c = SeriesRules.extendAll(
            listOf(pt, group),
            emptyList(),
            at(day(26, 10), 8, 0),
            membersByGroup = mapOf("sat9" to listOf(GroupMember("a", day(1, 9)))),
        )
        val sat = c.created.single { it.seriesId == "g1" }
        assertEquals(day(19, 12), sat.date)
        assertEquals(listOf("a"), sat.clientIds)
    }

    // --- Только эту ---

    @Test
    fun onlyThisDetachesAndKeepsStatusAndPlan() {
        val s = created()
        val thu = s.on(day(22)).copy(plan = "Старты с тумбы")
        val c = SeriesRules.changeOnlyThis(s.series, thu, edit(thu, start = at(day(22), 14, 0)))

        val moved = c.updated.single()
        assertEquals(at(day(22), 14, 0), moved.start)
        assertTrue(moved.detached)
        assertEquals(TrainingStatus.PLANNED, moved.status)
        assertEquals("Старты с тумбы", moved.plan)
        assertEquals(setOf(day(22)), c.series.single().skipDates)
        assertTrue(c.created.isEmpty() && c.deleted.isEmpty())
    }

    @Test
    fun onlyThisMovedToAnotherDayFreesItsOwnDateOnce() {
        val s = created()
        val c = SeriesRules.changeOnlyThis(s.series, s.on(day(22)), edit(s.on(day(22)), start = at(day(23), 13, 30)))
        val series = c.series.single()
        assertEquals(setOf(day(22)), series.skipDates)

        // Второй перенос той же тренировки ничего не добавляет: её дата уже занята.
        val moved = c.updated.single()
        val again = SeriesRules.changeOnlyThis(series, moved, edit(moved, start = at(day(23), 15, 0)))
        assertTrue(again.series.isEmpty())
        assertEquals(at(day(23), 15, 0), again.updated.single().start)
    }

    @Test
    fun questionOnlyForScheduleChanges() {
        val s = created()
        val t = s.on(day(22))
        assertFalse(SeriesRules.asksScope(t, edit(t)))
        assertTrue(SeriesRules.asksScope(t, edit(t, start = at(day(22), 14, 0))))
        assertTrue(SeriesRules.asksScope(t, edit(t, location = "db")))
        assertTrue(SeriesRules.asksScope(t, edit(t).copy(clientIds = listOf("orlova", "nosova"))))
        assertFalse(SeriesRules.asksScope(t.copy(seriesId = null), edit(t, start = at(day(22), 14, 0))))
    }

    // --- Эту и следующие ---

    @Test
    fun followingTimeChangeMovesSeriesAndKeepsPlans() {
        val s = created()
        val s29 = s.on(day(29)).copy(plan = "Старты с тумбы")
        val all = s.with(s29)
        val c = SeriesRules.changeFollowing(all.series, s29, edit(s29, start = at(day(29), 14, 0)), all.trainings, "s2", now)

        val (old, new) = c.series
        assertEquals(day(28), old.validTo)
        assertEquals("s2", new.id)
        assertEquals(day(29), new.validFrom)
        assertEquals(LocalTime(14, 0), new.pattern.startTime)
        assertEquals(setOf(TUESDAY, THURSDAY), new.pattern.weekdays)
        assertEquals(day(15, 12), new.generatedUntil)

        assertTrue(c.created.isEmpty())
        assertTrue(c.deleted.isEmpty())
        assertEquals(14, c.updated.size) // все с чт 29.10 по вт 15.12 — те же записи, новое время
        assertTrue(c.updated.all { it.seriesId == "s2" && it.start.time == LocalTime(14, 0) && !it.detached })
        assertEquals("Старты с тумбы", c.updated.single { it.id == s29.id }.plan)
        assertTrue(c.updated.none { it.date <= day(27) }) // прошлое и вт 27.10 — по-старому
    }

    @Test
    fun followingDayChangeReplacesThatWeekday() {
        val s = created()
        val thu = s.on(day(22)).copy(plan = "Поворот у стенки")
        val all = s.with(thu)
        val c = SeriesRules.changeFollowing(all.series, thu, edit(thu, start = at(day(23), 13, 30)), all.trainings, "s2", now)

        val (old, new) = c.series
        assertEquals(day(21), old.validTo)
        assertEquals(setOf(TUESDAY, FRIDAY), new.pattern.weekdays)
        assertEquals(day(22), new.validFrom)

        val moved = c.updated.single { it.id == thu.id }
        assertEquals(at(day(23), 13, 30), moved.start)
        assertEquals("Поворот у стенки", moved.plan)
        assertFalse(moved.detached)

        assertEquals((0..6).map { day(29).plusDays(7 * it) }, c.deleted.map { it.date }) // четверги 29.10–10.12
        assertEquals((0..6).map { day(30).plusDays(7 * it) }, c.created.map { it.date }) // пятницы 30.10–11.12
        assertTrue(c.updated.filter { it.id != thu.id }.all { it.date.dayOfWeek == TUESDAY && it.seriesId == "s2" })
        assertTrue(c.pendingPlans.isEmpty())
    }

    @Test
    fun followingKeepsTrainingChangedOnlyThis() {
        val s = created()
        val detach = SeriesRules.changeOnlyThis(s.series, s.on(day(5, 11)), edit(s.on(day(5, 11)), start = at(day(5, 11), 14, 0)))
        val all = Saved(detach.series.single(), s.with(detach.updated.single()).trainings)

        val t = all.on(day(22))
        val c = SeriesRules.changeFollowing(all.series, t, edit(t, start = at(day(22), 13, 0)), all.trainings, "s2", now)

        val kept = c.updated.single { it.date == day(5, 11) }
        assertEquals(LocalTime(14, 0), kept.start.time)
        assertTrue(kept.detached)
        assertEquals("s2", kept.seriesId)
        assertTrue(day(5, 11) in c.series.last().skipDates)
        assertTrue(c.created.isEmpty()) // на 05.11 второй тренировки нет
    }

    @Test
    fun changeFromCarriesPlanOfRemovedDayToNextTraining() {
        val monWed = created(ptPattern(weekdays = setOf(MONDAY, WEDNESDAY), time = LocalTime(17, 0)), validFrom = day(19))
        val mon = monWed.on(day(26)).copy(plan = "Поворот у стенки")
        val all = monWed.with(mon)

        val c = SeriesRules.changeFrom(all.series, day(26), ptPattern(time = LocalTime(17, 0)), all.trainings, "s2", now)
        assertTrue(mon.id in c.deleted.map { it.id })
        val tue = c.created.single { it.date == day(27) }
        assertEquals("Поворот у стенки", tue.plan)
        assertTrue(c.pendingPlans.isEmpty())
    }

    @Test
    fun plansOfSeveralRemovedDaysAreAddedLineByLine() {
        val monWed = created(ptPattern(weekdays = setOf(MONDAY, WEDNESDAY), time = LocalTime(17, 0)), validFrom = day(19))
        val all = monWed.with(monWed.on(day(26)).copy(plan = "Поворот у стенки"), monWed.on(day(28)).copy(plan = "Старты"))

        val c = SeriesRules.changeFrom(all.series, day(26), ptPattern(weekdays = setOf(FRIDAY)), all.trainings, "s2", now)
        assertEquals("Поворот у стенки\nСтарты", c.created.single { it.date == day(30) }.plan)
    }

    @Test
    fun cancelledInAdvanceMovesWithSeriesOrGoesWithItsDay() {
        val s = created()
        val cancelled = s.on(day(5, 11)).copy(
            status = TrainingStatus.CANCELLED,
            participants = listOf(ParticipantState("orlova", CANCELLED, "Отъезд", at(day(20), 10, 0))),
        )
        val all = s.with(cancelled)
        val t = all.on(day(22))

        // Новое время: отмена остаётся отменой, но в 14:00.
        val time = SeriesRules.changeFollowing(all.series, t, edit(t, start = at(day(22), 14, 0)), all.trainings, "s2", now)
        val moved = time.updated.single { it.id == cancelled.id }
        assertEquals(LocalTime(14, 0), moved.start.time)
        assertEquals(TrainingStatus.CANCELLED, moved.status)
        assertEquals(CANCELLED, moved.participants.single().status)

        // Четверги стали пятницами: отменённого четверга в расписании больше нет.
        val dayMove = SeriesRules.changeFollowing(all.series, t, edit(t, start = at(day(23), 13, 30)), all.trainings, "s2", now)
        assertTrue(cancelled.id in dayMove.deleted.map { it.id })
    }

    @Test
    fun startedTrainingIsNeverTouched() {
        val s = created()
        val during = at(day(22), 13, 40) // чт 22.10, тренировка 13:30 идёт
        val c = SeriesRules.changeFrom(s.series, day(22), ptPattern(time = LocalTime(14, 0)), s.trainings, "s2", during)

        assertTrue(c.updated.none { it.date == day(22) })
        assertTrue(c.created.none { it.date == day(22) }) // второй в этот день не будет
        assertTrue(c.updated.any { it.date == day(27) && it.start.time == LocalTime(14, 0) })
    }

    @Test
    fun followingOnlyFromTodayOn() {
        val s = created(validFrom = day(13))
        val past = s.on(day(15))
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.changeFollowing(s.series, past, edit(past, start = at(day(15), 14, 0)), s.trainings, "s2", now)
        }
        val thu = s.on(day(22))
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.changeFollowing(s.series, thu, edit(thu, start = at(day(19), 13, 30)), s.trainings, "s2", now)
        }
        assertFailsWith<IllegalArgumentException> {
            SeriesRules.changeFrom(s.series, day(19), ptPattern(), s.trainings, "s2", now)
        }
    }

    @Test
    fun seriesNotStartedYetChangesItself() {
        val s = created(validFrom = day(22))
        val first = s.on(day(22))
        val c = SeriesRules.changeFollowing(s.series, first, edit(first, start = at(day(22), 14, 0)), s.trainings, "s2", now)

        val only = c.series.single()
        assertEquals("s1", only.id)
        assertEquals(day(22), only.validFrom)
        assertEquals(LocalTime(14, 0), only.pattern.startTime)
        assertTrue(c.updated.all { it.seriesId == "s1" })
        assertTrue(c.created.isEmpty() && c.deleted.isEmpty())
    }

    @Test
    fun movingOntoAnotherWeekdayOfSeriesMergesThatWeek() {
        val s = created()
        val thu = s.on(day(29))
        val c = SeriesRules.changeFollowing(s.series, thu, edit(thu, start = at(day(3, 11), 13, 30)), s.trainings, "s2", now)

        assertEquals(setOf(TUESDAY), c.series.last().pattern.weekdays)
        assertEquals(at(day(3, 11), 13, 30), c.updated.single { it.id == thu.id }.start)
        // На вт 03.11 одна тренировка — перенесённая; прежняя вторничная удалена.
        assertTrue(s.on(day(3, 11)).id in c.deleted.map { it.id })
        assertTrue(c.created.isEmpty())
        assertTrue(c.updated.none { it.id == s.on(day(27)).id })
    }

    @Test
    fun followingInGroupKeepsChildrenAndGuests() {
        val members = listOf(GroupMember("a", day(1, 9)), GroupMember("b", day(1, 9)))
        val group = created(SeriesPattern("group", "bb", setOf(SATURDAY), LocalTime(9, 0), 60, groupId = "sat9"), day(24), members = members)
        val sat31 = group.on(day(31)).let { it.copy(participants = it.participants + ParticipantState("guest")) }
        val all = group.with(sat31)
        val sat24 = all.on(day(24))

        val c = SeriesRules.changeFollowing(all.series, sat24, edit(sat24, location = "db"), all.trainings, "g2", now, members = members)
        assertEquals("db", c.series.last().pattern.locationId)
        val moved = c.updated.single { it.id == sat31.id }
        assertEquals("db", moved.locationId)
        assertEquals(listOf("a", "b", "guest"), moved.participants.map { it.clientId })
    }

    // --- Пропуск даты ---

    @Test
    fun skipDeletesAndRemembersDate() {
        val s = created()
        val thu = s.on(day(5, 11)).copy(plan = "Старты")
        val all = s.with(thu)
        val c = SeriesRules.skip(all.series, thu, all.trainings, now)

        assertEquals(listOf(thu.id), c.deleted.map { it.id })
        assertEquals(setOf(day(5, 11)), c.series.single().skipDates)
        assertEquals("Старты", c.updated.single { it.date == day(10, 11) }.plan)

        // Ни продление, ни изменение серии дату не возвращают.
        val skipped = c.series.single()
        val left = all.trainings.filter { it.id != thu.id }
        assertTrue(SeriesRules.extend(skipped.copy(generatedUntil = day(1, 11)), left, now).created.none { it.date == day(5, 11) })
        val t = all.on(day(22))
        val follow = SeriesRules.changeFollowing(skipped, t, edit(t, start = at(day(22), 14, 0)), left, "s2", now)
        assertTrue(follow.created.none { it.date == day(5, 11) })
    }

    @Test
    fun skipMovedTrainingJustDeletesIt() {
        val s = created()
        val detach = SeriesRules.changeOnlyThis(s.series, s.on(day(22)), edit(s.on(day(22)), start = at(day(23), 13, 30)))
        val series = detach.series.single()
        val moved = detach.updated.single()

        val c = SeriesRules.skip(series, moved, s.with(moved).trainings, now)
        assertEquals(listOf(moved.id), c.deleted.map { it.id })
        assertTrue(c.series.isEmpty()) // 22.10 уже в пропусках, 23.10 не день серии
    }

    @Test
    fun skipOnlyFuture() {
        val s = created()
        assertFailsWith<IllegalArgumentException> { SeriesRules.skip(s.series, s.on(day(20)), s.trainings, now) }
    }

    // --- Отпуск ---

    @Test
    fun vacationDeletesAndSkipsAlsoBeyondHorizon() {
        val s = created()
        val tue = s.on(day(3, 11)).copy(plan = "Поворот у стенки")
        val all = s.with(tue)

        val short = SeriesRules.vacation(listOf(all.series), all.trainings, day(2, 11), day(8, 11), now)
        assertEquals(listOf(day(3, 11), day(5, 11)), short.deleted.map { it.date })
        assertEquals(setOf(day(3, 11), day(5, 11)), short.series.single().skipDates)
        assertEquals("Поворот у стенки", short.updated.single { it.date == day(10, 11) }.plan)

        val newYear = SeriesRules.vacation(listOf(s.series), s.trainings, day(28, 12), day(10, 1, 2027), now)
        assertTrue(newYear.deleted.isEmpty())
        val skip = newYear.series.single().skipDates
        assertEquals(setOf(day(29, 12), day(31, 12), day(5, 1, 2027), day(7, 1, 2027)), skip)

        val later = SeriesRules.extend(newYear.series.single(), s.trainings, at(day(22, 12), 8, 0))
        assertTrue(later.created.none { it.date in skip })
        assertTrue(later.created.any { it.date == day(12, 1, 2027) })
    }

    @Test
    fun vacationLeavesPastStartedAndCharged() {
        val s = created()
        val charged = SeriesTraining(
            "late", "s1", at(day(20), 16, 0), 50, "pt", "bb",
            participants = listOf(ParticipantState("orlova", CANCELLED_CHARGED, "Не сможет", at(day(20), 15, 30))),
        )
        val all = s.trainings + charged
        val c = SeriesRules.vacation(listOf(s.series), all, day(20), day(23), now)
        assertEquals(listOf(day(22)), c.deleted.map { it.date })
        assertTrue(c.deleted.none { it.id == "late" || it.id == s.on(day(20)).id })
    }

    // --- Конец серии и архив ---

    @Test
    fun endSeriesDeletesAfterLastDateAndKeepsPlans() {
        val s = created()
        val all = s.with(s.on(day(12, 11)).copy(plan = "Старты"))
        val c = SeriesRules.end(all.series, day(5, 11), all.trainings, now)

        assertEquals(day(5, 11), c.series.single().validTo)
        assertEquals(11, c.deleted.size) // с вт 10.11 по вт 15.12: 6 вторников и 5 четвергов
        assertTrue(c.deleted.all { it.date > day(5, 11) })
        assertEquals(listOf("Старты"), c.pendingPlans)

        assertFailsWith<IllegalArgumentException> { SeriesRules.end(s.series, day(18), s.trainings, now) }
        SeriesRules.end(s.series, day(19), s.trainings, now) // вчера — можно: с сегодня тренировок нет
    }

    @Test
    fun archivedClientSeriesEndsTodayAndLaterTodayIsDeleted() {
        val s = created()
        val morning = at(day(20), 12, 0)
        val c = SeriesRules.archiveClient("orlova", listOf(s.series), s.trainings, morning) { error("новая серия не нужна") }

        assertEquals(day(20), c.series.single().validTo)
        assertEquals(17, c.deleted.size) // сегодняшняя в 13:30 ещё не началась
        assertEquals(SeriesChanges.NONE, SeriesRules.archiveClient("nobody", listOf(s.series), s.trainings, morning) { "x" })
    }

    @Test
    fun archivedClientLeavesSplitToTheOther() {
        val split = created(ptPattern(clients = listOf("nosova", "zaitseva")).copy(typeId = "split"))
        val c = SeriesRules.archiveClient("nosova", listOf(split.series), split.trainings, now) { "s2" }

        val (old, new) = c.series
        assertEquals(day(19), old.validTo)
        assertEquals(listOf("zaitseva"), new.pattern.clientIds)
        assertEquals(day(20), new.validFrom)
        assertEquals(day(15, 12), new.generatedUntil)
        assertEquals(16, c.updated.size) // с чт 22.10; сегодняшняя в 13:30 уже прошла
        assertTrue(c.updated.all { it.seriesId == "s2" && it.participants.map { p -> p.clientId } == listOf("zaitseva") })
        assertTrue(c.deleted.isEmpty())
    }

    // --- Состав группы ---

    @Test
    fun groupCompositionUpdatesFutureUnmarkedTrainings() {
        val before = listOf(GroupMember("a", day(1, 9)), GroupMember("b", day(1, 9)))
        val after = listOf(GroupMember("a", day(1, 9)), GroupMember("b", day(1, 9), toDate = day(31)), GroupMember("c", day(7, 11)))
        fun sat(d: LocalDate, vararg kids: ParticipantState, status: TrainingStatus = TrainingStatus.PLANNED) =
            SeriesTraining("t$d", "g1", at(d, 9, 0), 60, "group", "bb", groupId = "sat9", status = status, participants = kids.toList())

        val past = sat(day(17), ParticipantState("a", ATTENDED), ParticipantState("b", ATTENDED), status = TrainingStatus.DONE)
        val sat24 = sat(day(24), ParticipantState("a"), ParticipantState("b"))
        val sat7 = sat(day(7, 11), ParticipantState("a"), ParticipantState("b"), ParticipantState("guest"))
        val cancelled = sat(
            day(14, 11), ParticipantState("a", CANCELLED), ParticipantState("b", CANCELLED), status = TrainingStatus.CANCELLED,
        )

        val c = SeriesRules.updateGroupMembers(listOf(past, sat24, sat7, cancelled), before, after, now)
        val changed = c.updated.single()
        assertEquals(sat7.id, changed.id)
        assertEquals(listOf("a", "guest", "c"), changed.participants.map { it.clientId })
        assertNull(c.updated.firstOrNull { it.id == sat24.id })
    }
}
