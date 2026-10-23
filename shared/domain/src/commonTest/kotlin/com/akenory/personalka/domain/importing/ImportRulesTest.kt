package com.akenory.personalka.domain.importing

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Правила импорта из таблиц (шаг 1.17, ТЗ 9.2). */
class ImportRulesTest {
    @Test
    fun statuses() {
        assertEquals(ImportedStatus(TrainingStatus.PLANNED, ParticipationStatus.EXPECTED), ImportRules.status("Запланировано"))
        assertEquals(ImportedStatus(TrainingStatus.DONE, ParticipationStatus.ATTENDED), ImportRules.status(" проведено "))
        assertEquals(ImportedStatus(TrainingStatus.CANCELLED, ParticipationStatus.CANCELLED), ImportRules.status("Отменено"))
        assertEquals(
            ImportedStatus(TrainingStatus.CANCELLED, ParticipationStatus.CANCELLED_CHARGED),
            ImportRules.status("Отмена со списанием"),
        )
        assertNull(ImportRules.status(""))
        assertNull(ImportRules.status("Перенос"))
    }

    @Test
    fun trainingTime() {
        assertEquals(LocalTime(10, 0), ImportRules.time("10.00"))
        assertEquals(LocalTime(10, 0), ImportRules.time("10:00"))
        assertEquals(LocalTime(9, 30), ImportRules.time("9.30"))
        assertEquals(LocalTime(9, 30), ImportRules.time("9,30"))
        assertEquals(LocalTime(18, 0), ImportRules.time("18"))
        assertEquals(LocalTime(17, 0), ImportRules.time("17.00–17.50"))
        assertNull(ImportRules.time(""))
        assertNull(ImportRules.time("9.3"))
        assertNull(ImportRules.time("25.00"))
        assertNull(ImportRules.time("утро"))
    }

    @Test
    fun datesTypedAsText() {
        assertEquals(LocalDate(2026, 10, 22), ImportRules.date("22.10.2026"))
        assertEquals(LocalDate(2026, 10, 22), ImportRules.date("22.10.26"))
        assertEquals(LocalDate(2026, 3, 5), ImportRules.date("5/3/2026"))
        assertEquals(LocalDate(2026, 10, 22), ImportRules.date("2026-10-22"))
        assertNull(ImportRules.date("22.10"))
        assertNull(ImportRules.date("31.02.2026"))
    }

    @Test
    fun styles() {
        assertEquals(SwimStyle.FREESTYLE, ImportRules.style("в/ст"))
        assertEquals(SwimStyle.FREESTYLE, ImportRules.style("50 в / ст"))
        assertEquals(SwimStyle.BACKSTROKE, ImportRules.style("н/сп"))
        assertEquals(SwimStyle.BREASTSTROKE, ImportRules.style("Брасс"))
        assertEquals(SwimStyle.BUTTERFLY, ImportRules.style("батт"))
        assertEquals(SwimStyle.BUTTERFLY, ImportRules.style("дельфин"))
        assertEquals(SwimStyle.MEDLEY, ImportRules.style("к/п"))
        assertEquals(SwimStyle.MEDLEY, ImportRules.style("комплекс"))
        assertNull(ImportRules.style("ноги"))
    }

    @Test
    fun distances() {
        assertEquals(50, ImportRules.distance("50"))
        assertEquals(100, ImportRules.distance("100 в/ст"))
        assertEquals(25, ImportRules.distance("25м"))
        assertNull(ImportRules.distance("75"))
        assertNull(ImportRules.distance("в/ст"))
    }

    @Test
    fun resultTimesFromTheTz() {
        assertEquals(ResultTime(6317, 25), ImportRules.resultTime("1.03,17"))
        assertEquals(ResultTime(7475, 25), ImportRules.resultTime("1,14.75"))
        assertEquals(ResultTime(5900, 25), ImportRules.resultTime("59.00"))
        assertEquals(ResultTime(5216, 25), ImportRules.resultTime("52.16"))
        assertEquals(ResultTime(5699, 50), ImportRules.resultTime("56,99 (50м бассейн)"))
        assertEquals(ResultTime(5950, 25), ImportRules.resultTime("59.5"))
        assertEquals(ResultTime(3120, 50), ImportRules.resultTime("31.20 (50 м)"))
        assertNull(ImportRules.resultTime("1.75,10"))
        assertNull(ImportRules.resultTime("сошёл"))
        assertNull(ImportRules.resultTime("59"))
    }

    @Test
    fun groupCodes() {
        assertEquals(GroupCode(Owner.SELF, ConductedBy.SELF), ImportRules.groupCode("Д"))
        assertEquals(GroupCode(Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE), ImportRules.groupCode("Д + Л"))
        assertEquals(GroupCode(Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE), ImportRules.groupCode("л+д"))
        assertNull(ImportRules.groupCode("Л"))
    }

    @Test
    fun sheetsAndYearTypo() {
        assertEquals(Month.OCTOBER, ImportRules.sheetMonth("Октябрь группы"))
        assertEquals(Month.MAY, ImportRules.sheetMonth("Май 2026"))
        assertNull(ImportRules.sheetMonth("Настройки"))
        assertTrue(ImportRules.isGroupsSheet("Октябрь группы"))
        assertFalse(ImportRules.isGroupsSheet("Октябрь"))
        assertEquals(2026, ImportRules.sheetYear("Май 2026"))
        assertNull(ImportRules.sheetYear("Октябрь группы"))
        val dates = listOf(LocalDate(2026, 10, 1), LocalDate(2023, 10, 3), LocalDate(2026, 10, 6))
        assertEquals(2026, ImportRules.usualYear(dates))
        assertEquals(LocalDate(2026, 10, 3), ImportRules.fixYear(LocalDate(2023, 10, 3), 2026))
        assertEquals(LocalDate(2026, 10, 6), ImportRules.fixYear(LocalDate(2026, 10, 6), 2026))
    }

    @Test
    fun namesAndKeys() {
        assertEquals(ClientName("Дельфенурова", "Алиса", "Сергеевна"), ImportRules.name("  Дельфенурова  Алиса Сергеевна "))
        assertEquals(ClientName("Ковалёв", "Артём"), ImportRules.name("Ковалёв Артём"))
        assertNull(ImportRules.name("Алиса"))
        assertEquals("ковалев артем", ImportRules.nameKey(ClientName("КОВАЛЁВ", "Артём", "Игоревич")))
    }

    @Test
    fun money() {
        assertEquals(1_250_000, ImportRules.money("12 500"))
        assertEquals(1_250_050, ImportRules.money("12500,50"))
        assertEquals(-100_000, ImportRules.money("-1 000 ₽"))
        assertEquals(150_000, ImportRules.money("1500 руб."))
        assertEquals(1_250_050, ImportRules.money(12500.5))
        assertNull(ImportRules.money("много"))
    }

    @Test
    fun seriesNeedThreeTimesInFourWeeks() {
        val last = LocalDate(2026, 10, 22)
        val tuesdays = listOf(LocalDate(2026, 9, 29), LocalDate(2026, 10, 6), LocalDate(2026, 10, 13), LocalDate(2026, 10, 20))
            .map { LocalDateTime(it, LocalTime(17, 0)) }
        val thursdays = listOf(LocalDate(2026, 10, 8), LocalDate(2026, 10, 15)).map { LocalDateTime(it, LocalTime(18, 0)) }
        // Три раза в сентябре, но раньше окна — не серия.
        val old = listOf(1, 8, 15).map { LocalDateTime(LocalDate(2026, 9, it), LocalTime(10, 0)) }
        val found = ImportRules.series(tuesdays + thursdays + old, last)
        assertEquals(listOf(FoundSeries(Slot(DayOfWeek.TUESDAY, LocalTime(17, 0)), 4, LocalDate(2026, 10, 21))), found)
        assertEquals(emptyList(), ImportRules.series(emptyList(), last))
    }

    @Test
    fun groupMembersCameInLastFourWeeks() {
        val last = LocalDate(2026, 10, 22)
        val visits = listOf(
            LocalDate(2026, 9, 24) to "Ковалёв Артём",
            LocalDate(2026, 9, 25) to "Смирнова Ольга",
            LocalDate(2026, 10, 22) to "Смирнова Ольга",
        )
        assertEquals(setOf("Смирнова Ольга"), ImportRules.members(visits, last))
        assertEquals(LocalDate(2026, 9, 25), ImportRules.windowStart(last))
    }
}
