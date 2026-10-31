package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.series.SeriesPattern
import com.akenory.personalka.domain.series.SeriesRules
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Запреты, дежурства и наложения (ТЗ 4.5). «Сейчас» — вт 20.10.2026, 15:40, как в макетах.
 * Места: «bb» — Большой бассейн, «db» — Детский бассейн. Главный запрет — как на макете календаря:
 * ДБ 17:00–18:00, групповая тренировка, каждый вт и чт с 01.09.2026.
 */
class RestrictionRulesTest {
    private val now = LocalDateTime(2026, 10, 20, 15, 40)

    private fun day(d: Int, m: Int = 10) = LocalDate(2026, m, d)
    private fun time(h: Int, min: Int = 0) = LocalTime(h, min)
    private fun at(date: LocalDate, h: Int, min: Int = 0) = LocalDateTime(date, time(h, min))

    private val dbGroup = Restriction(
        id = "db-group",
        locationId = "db",
        reason = "Групповая тренировка",
        startTime = time(17),
        endTime = time(18),
        weekdays = setOf(TUESDAY, THURSDAY),
        validFrom = day(1, 9),
    )

    private fun oneOff(id: String, location: String, date: LocalDate, from: LocalTime, to: LocalTime, reason: String = id) =
        Restriction(id, location, reason, from, to, date = date, validFrom = date)

    private fun pt(date: LocalDate, h: Int, min: Int = 0, place: String = "db", duration: Int = 50) =
        Placement(at(date, h, min), duration, place)

    private fun duty(date: LocalDate, h: Int, min: Int = 0, place: String = "bb", duration: Int) =
        Placement(at(date, h, min), duration, place, isDuty = true)

    // --- Главная проверка плана ---

    /** Шаг 1.4 и критерий этапа 1 (11.1): в ДБ — предупреждение, в ББ в то же время — нет. */
    @Test
    fun dbWarnsBbDoesNot() {
        val inDb = RestrictionRules.check(before = null, after = pt(day(20), 17), restrictions = listOf(dbGroup))
        val confirm = assertIs<RestrictionCheck.Confirm>(inDb)
        assertEquals(
            listOf(RestrictionHit(dbGroup, at(day(20), 17), at(day(20), 18))),
            confirm.hits,
        )
        assertEquals("db", confirm.hits.single().locationId)
        assertEquals("Групповая тренировка", confirm.hits.single().reason)

        val inBb = RestrictionRules.check(before = null, after = pt(day(20), 17, place = "bb"), restrictions = listOf(dbGroup))
        assertEquals(RestrictionCheck.Save(overRestriction = false), inBb)
    }

    // --- Интервал [начало, конец) ---

    @Test
    fun touchingEdgesDoNotOverlap() {
        fun flag(h: Int, min: Int, duration: Int = 50) = RestrictionRules.flag(pt(day(20), h, min, duration = duration), listOf(dbGroup))
        assertFalse(flag(16, 10), "16:10–17:00 кончается в начале запрета")
        assertFalse(flag(18, 0), "18:00–18:50 начинается в конце запрета")
        assertTrue(flag(16, 11), "16:11–17:01 заходит на минуту")
        assertTrue(flag(17, 59), "17:59–18:49 начинается до конца запрета")
        assertTrue(flag(17, 15, 30), "17:15–17:45 внутри запрета")
        assertTrue(flag(16, 30, 120), "16:30–18:30 накрывает запрет целиком")
    }

    @Test
    fun personalAndClubRestrictionsBothApplyInOrder() {
        // Свой запрет «Свои дела» ДБ 16:30–17:15 только во вт 20.10 и запрет клуба на группу 17:00–18:00.
        val own = oneOff("own", "db", day(20), time(16, 30), time(17, 15), "Свои дела")
        val hits = RestrictionRules.hits(pt(day(20), 16, 40), listOf(dbGroup, own))
        assertEquals(listOf("own", "db-group"), hits.map { it.restriction.id })
    }

    // --- Даты запрета ---

    @Test
    fun weeklyRestrictionWorksOnItsDaysInPeriod() {
        assertTrue(RestrictionRules.occursOn(dbGroup, day(20)), "вторник")
        assertTrue(RestrictionRules.occursOn(dbGroup, day(22)), "четверг")
        assertFalse(RestrictionRules.occursOn(dbGroup, day(21)), "среда")
        assertFalse(RestrictionRules.occursOn(dbGroup, day(25, 8)), "вторник до начала периода")
        assertTrue(RestrictionRules.occursOn(dbGroup, day(1, 9)), "первый день периода — вторник")

        val untilEndOfOctober = dbGroup.copy(validTo = day(27))
        assertTrue(RestrictionRules.occursOn(untilEndOfOctober, day(27)), "последний день включительно")
        assertFalse(RestrictionRules.occursOn(untilEndOfOctober, day(29)), "после конца периода")
    }

    @Test
    fun skippedDayDoesNotWork() {
        val skipped = dbGroup.copy(skipDates = setOf(day(22)))
        assertFalse(RestrictionRules.occursOn(skipped, day(22)))
        assertTrue(RestrictionRules.occursOn(skipped, day(20)))
        assertFalse(RestrictionRules.flag(pt(day(22), 17), listOf(skipped)))
    }

    @Test
    fun oneOffWorksOnlyOnItsDate() {
        val saturday = oneOff("sat", "bb", day(24), time(9), time(12), "Соревнования клуба")
        assertTrue(RestrictionRules.occursOn(saturday, day(24)))
        assertFalse(RestrictionRules.occursOn(saturday, day(31)), "та же суббота неделей позже")
        assertFalse(RestrictionRules.occursOn(saturday.copy(skipDates = setOf(day(24))), day(24)))
    }

    @Test
    fun wholeDayAndMidnightEnd() {
        val wholeDay = oneOff("clean", "bb", day(24), time(0), time(0), "Бассейн на чистке")
        val hit = RestrictionRules.hits(pt(day(24), 7, place = "bb"), listOf(wholeDay)).single()
        assertEquals(at(day(24), 0), hit.start)
        assertEquals(at(day(25), 0), hit.end, "конец 00:00 — это полночь в конце дня")
        assertFalse(RestrictionRules.flag(pt(day(25), 7, place = "bb"), listOf(wholeDay)))

        val lateEvening = oneOff("late", "db", day(20), time(23), time(0))
        assertTrue(RestrictionRules.flag(pt(day(20), 23, 10), listOf(lateEvening)), "23:10–24:00")
        assertFalse(RestrictionRules.flag(pt(day(20), 22, 10), listOf(lateEvening)), "22:10–23:00")
    }

    @Test
    fun brokenRestrictionNeverWorks() {
        val reversed = dbGroup.copy(startTime = time(18), endTime = time(17))
        assertFalse(RestrictionRules.occursOn(reversed, day(20)))
        assertFalse(RestrictionRules.flag(pt(day(20), 17), listOf(reversed)))
    }

    @Test
    fun formProblems() {
        assertNull(RestrictionRules.problem(dbGroup))
        assertNull(RestrictionRules.problem(dbGroup.copy(endTime = time(0))), "до конца дня")
        assertNull(RestrictionRules.problem(oneOff("day", "bb", day(24), time(0), time(0))), "весь день")
        assertEquals(
            RestrictionProblem.NO_DATE,
            RestrictionRules.problem(dbGroup.copy(weekdays = emptySet(), date = null)),
        )
        assertEquals(RestrictionProblem.TIME_REVERSED, RestrictionRules.problem(dbGroup.copy(endTime = time(17))))
        assertEquals(RestrictionProblem.TIME_REVERSED, RestrictionRules.problem(dbGroup.copy(startTime = time(18), endTime = time(17))))
        assertEquals(RestrictionProblem.PERIOD_REVERSED, RestrictionRules.problem(dbGroup.copy(validTo = day(31, 8))))
        // У разового период не используется.
        assertNull(RestrictionRules.problem(oneOff("one", "db", day(24), time(9), time(10)).copy(validTo = day(1, 9))))
    }

    @Test
    fun dayListForCalendarAndFreeSlots() {
        val bbMorning = oneOff("bb-morning", "bb", day(20), time(9), time(10), "Аквааэробика")
        val all = listOf(dbGroup, bbMorning)
        assertEquals(listOf("bb-morning", "db-group"), RestrictionRules.on(all, day(20)).map { it.restriction.id })
        assertEquals(listOf("bb-morning"), RestrictionRules.on(all, day(20), locationId = "bb").map { it.restriction.id })
        assertEquals(emptyList(), RestrictionRules.on(all, day(21)))
    }

    // --- Окно при сохранении ---

    @Test
    fun windowOnlyWhenPutOnRestrictedTime() {
        val restrictions = listOf(dbGroup)
        val at17 = pt(day(20), 17)

        // Правка плана или клиентов: место и время прежние — окна нет, флаг остаётся.
        assertEquals(RestrictionCheck.Save(true), RestrictionRules.check(at17, at17, restrictions))
        // Перенос под запрет.
        assertIs<RestrictionCheck.Confirm>(RestrictionRules.check(pt(day(20), 15), at17, restrictions))
        // Смена места ББ → ДБ.
        assertIs<RestrictionCheck.Confirm>(RestrictionRules.check(pt(day(20), 17, place = "bb"), at17, restrictions))
        // Длительность 50 → 90: 16:00–17:30 заходит на запрет.
        assertIs<RestrictionCheck.Confirm>(
            RestrictionRules.check(pt(day(20), 16), pt(day(20), 16, duration = 90), restrictions),
        )
        // Перенос из-под запрета — окна нет, флаг снимается.
        assertEquals(RestrictionCheck.Save(false), RestrictionRules.check(at17, pt(day(20), 18), restrictions))
    }

    @Test
    fun dutyIsNeverCheckedAgainstRestrictions() {
        val dutyInDb = duty(day(20), 16, place = "db", duration = 180)
        assertEquals(emptyList(), RestrictionRules.hits(dutyInDb, listOf(dbGroup)))
        assertEquals(RestrictionCheck.Save(false), RestrictionRules.check(null, dutyInDb, listOf(dbGroup)))
        assertNull(RestrictionRules.checkAll(listOf(dutyInDb), listOf(dbGroup)))
    }

    // --- Серия: одно окно на все тренировки ---

    private fun seriesPlacements(time: LocalTime, place: String = "db") = SeriesRules.create(
        id = "s",
        pattern = SeriesPattern("pt", place, setOf(TUESDAY, THURSDAY), time, 50, clientIds = listOf("orlova")),
        validFrom = day(20),
        validTo = null,
        now = now,
    ).created.map { it.placement() }

    @Test
    fun seriesGetsOneWindowForAllTrainings() {
        val competition = oneOff("competition", "db", day(29), time(18), time(21), "Соревнования клуба")
        val restrictions = listOf(competition, dbGroup)

        // Вт и чт 17:30 в ДБ: все 17 тренировок под группой, 29.10 — ещё и под соревнованиями.
        val at1730 = RestrictionRules.checkAll(seriesPlacements(time(17, 30)), restrictions)!!
        assertEquals(17, at1730.checked)
        assertEquals(17, at1730.conflicting)
        assertEquals(listOf("db-group", "competition"), at1730.groups.map { it.restriction.id })
        assertEquals(17, at1730.groups[0].dates.size)
        assertEquals(day(20), at1730.groups[0].dates.first())
        assertEquals(listOf(day(29)), at1730.groups[1].dates)

        // Вт и чт 18:00: группа кончается в 18:00, под запретом только 29.10.
        val at18 = RestrictionRules.checkAll(seriesPlacements(time(18)), restrictions)!!
        assertEquals(1, at18.conflicting)
        assertEquals(listOf(ConflictGroup(competition, listOf(day(29)))), at18.groups)

        // В ББ — окна нет.
        assertNull(RestrictionRules.checkAll(seriesPlacements(time(17, 30), place = "bb"), restrictions))
    }

    // --- Изменились запреты ---

    @Test
    fun flagsAreRecalculatedForNotStartedTrainingsOnly() {
        val trainings = listOf(
            PlacedTraining("started", pt(day(20), 15, 30, duration = 120)),
            PlacedTraining("starts-now", pt(day(20), 15, 40, duration = 140)),
            PlacedTraining("today-17", pt(day(20), 17)),
            PlacedTraining("skipped", pt(day(27), 17), overRestriction = true),
            PlacedTraining("bb", pt(day(22), 17, place = "bb")),
            PlacedTraining("cancelled", pt(day(22), 17), cancelled = true),
            PlacedTraining("duty", duty(day(22), 16, place = "db", duration = 240), overRestriction = true),
            PlacedTraining("already", pt(day(29), 17), overRestriction = true),
        )
        // Добавлен запрет на группу, но во вт 27.10 группы нет.
        val restrictions = listOf(dbGroup.copy(skipDates = setOf(day(27))))
        assertEquals(
            listOf(
                FlagChange("today-17", true),
                FlagChange("skipped", false),
                FlagChange("cancelled", true),
                FlagChange("duty", false),
            ),
            RestrictionRules.recalc(trainings, restrictions, now),
        )
    }

    @Test
    fun deletedRestrictionClearsFutureFlagsButNotPastOnes() {
        val trainings = listOf(
            PlacedTraining("yesterday", pt(day(19), 17), overRestriction = true),
            PlacedTraining("thursday", pt(day(22), 17), overRestriction = true),
        )
        assertEquals(listOf(FlagChange("thursday", false)), RestrictionRules.recalc(trainings, emptyList(), now))
        // Повторный пересчёт ничего не меняет.
        val after = listOf(trainings[0], trainings[1].copy(overRestriction = false))
        assertEquals(emptyList(), RestrictionRules.recalc(after, emptyList(), now))
    }

    // --- Накладка с дежурством ---

    @Test
    fun trainingOverDutyInOtherPlace() {
        // Дежурство ББ 11:00–15:00, как на макете календаря; дежурство ДБ 14:00–16:00 отменено.
        val sameDay = listOf(
            PlacedTraining("duty-bb", duty(day(20), 11, duration = 240)),
            PlacedTraining("duty-cancelled", duty(day(20), 14, place = "db", duration = 120), cancelled = true),
            PlacedTraining("other-pt", pt(day(20), 12, 20, place = "bb")),
        )
        assertEquals(listOf("duty-bb"), RestrictionRules.dutyOverlaps(pt(day(20), 12, 20), sameDay).map { it.id }, "ПТ в ДБ")
        assertEquals(listOf("duty-bb"), RestrictionRules.dutyOverlaps(pt(day(20), 14, 30, place = "gym"), sameDay).map { it.id }, "ПТ в зале")
        assertEquals(emptyList(), RestrictionRules.dutyOverlaps(pt(day(20), 14, 30, place = "bb"), sameDay), "ПТ в том же ББ — не накладка")
        assertEquals(emptyList(), RestrictionRules.dutyOverlaps(pt(day(20), 15), sameDay), "начинается в конце дежурства")
        assertEquals(emptyList(), RestrictionRules.dutyOverlaps(pt(day(20), 10, 10), sameDay), "кончается в начале")
        assertEquals(emptyList(), RestrictionRules.dutyOverlaps(duty(day(20), 12, duration = 60), sameDay), "у дежурства накладок нет")

        // Дежурства в ББ и ДБ, тренировка в зале — под ней оба; в ДБ — только дежурство ББ.
        val twoDuties = sameDay + PlacedTraining("duty-db", duty(day(20), 14, 30, place = "db", duration = 60))
        assertEquals(
            listOf("duty-bb", "duty-db"),
            RestrictionRules.dutyOverlaps(pt(day(20), 14, 30, place = "gym"), twoDuties).map { it.id },
        )
        assertEquals(listOf("duty-bb"), RestrictionRules.dutyOverlaps(pt(day(20), 14, 30), twoDuties).map { it.id })
    }

    // --- «Удалить → Этот и все следующие» (шаг 5.3р) ---

    /** С чт 22.10: последний день — ср 21.10, запрет ещё действовал во вт 20.10; пропуски после конца отброшены. */
    @Test
    fun endBeforeCutsPeriodAndSkips() {
        val withSkips = dbGroup.copy(skipDates = setOf(day(13), day(27)))
        val cut = RestrictionRules.endBefore(withSkips, day(22))!!
        assertEquals(day(21), cut.validTo)
        assertEquals(setOf(day(13)), cut.skipDates)
        assertTrue(RestrictionRules.occursOn(cut, day(20)))
        assertFalse(RestrictionRules.occursOn(cut, day(22)))
        assertFalse(RestrictionRules.occursOn(cut, day(27)))
    }

    /** С первого дня запрета, разовый, или до этого дня все дни пропущены — удаляется целиком. */
    @Test
    fun endBeforeFirstDayOrOneOffDeletesWhole() {
        assertNull(RestrictionRules.endBefore(dbGroup, day(1, 9)))
        assertNull(RestrictionRules.endBefore(dbGroup.copy(skipDates = setOf(day(1, 9))), day(3, 9)))
        assertNull(RestrictionRules.endBefore(oneOff("pool", "bb", day(20), time(10), time(12)), day(20)))
    }

    /** Конец раньше — не переносится позже: у запрета до 15.10 «с 22.10» его не продлевает. */
    @Test
    fun endBeforeKeepsEarlierEnd() {
        val cut = RestrictionRules.endBefore(dbGroup.copy(validTo = day(15)), day(22))!!
        assertEquals(day(15), cut.validTo)
    }
}
