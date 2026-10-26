package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.format.CalendarSettingsFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.series.Series
import com.akenory.personalka.domain.series.SeriesPattern
import com.akenory.personalka.domain.series.SeriesRules
import com.akenory.personalka.domain.series.SeriesTraining
import com.akenory.personalka.domain.training.ParticipantState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Защита «Отпуска» (шаг 3.12, ТЗ 5.16). «Сейчас» — вт 20.10.2026 15:40, серии созданы по вс 25.10. Имена придуманы. */
class VacationNoticeRulesTest {
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private fun d(day: Int, month: Int = 10) = LocalDate(2026, month, day)
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun series(id: String, days: Set<DayOfWeek>, clients: List<String> = emptyList(), group: String? = null, type: String = "pt") =
        Series(id, SeriesPattern(type, "bb", days, LocalTime(13, 30), 50, clients, group), d(1), generatedUntil = d(25))

    private fun training(id: String, series: String, date: LocalDate, vararg clients: String) =
        SeriesTraining(id, series, LocalDateTime(date, LocalTime(13, 30)), 50, "pt", "bb", participants = clients.map { ParticipantState(it) })

    @Test
    fun daysAreDeletedAndNotYetCreatedOnes() {
        // ПТ вт и чт у Орловой; отпуск чт 22.10 — чт 29.10: чт 22 создана, вт 27 и чт 29 — ещё нет.
        val pt = series("s1", setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), listOf("orlova"))
        val trainings = listOf(training("t1", "s1", d(20), "orlova"), training("t2", "s1", d(22), "orlova"))
        val changes = SeriesRules.vacation(listOf(pt), trainings, d(22), d(29), now)
        assertEquals(
            listOf(VacationDay(d(22), listOf("orlova"), false), VacationDay(d(27), listOf("orlova"), false), VacationDay(d(29), listOf("orlova"), false)),
            VacationNoticeRules.days(pt, changes, duty = false),
        )
    }

    @Test
    fun groupDayWithoutTrainingWaitsForMembersAndDutyHasNobody() {
        val group = series("g1", setOf(DayOfWeek.FRIDAY), group = "dolphins")
        val built = SeriesTraining("t1", "g1", LocalDateTime(d(23), LocalTime(18, 0)), 45, "grp", "bb", groupId = "dolphins", participants = listOf(ParticipantState("belov")))
        val days = VacationNoticeRules.days(group, SeriesRules.vacation(listOf(group), listOf(built), d(22), d(30), now), duty = false)
        // Пт 23 — с участниками тренировки, пт 30 ещё не создана — состав на дату подставит слой данных.
        assertEquals(listOf(VacationDay(d(23), listOf("belov"), false), VacationDay(d(30), null, false)), days)

        val duty = series("d1", setOf(DayOfWeek.FRIDAY), type = "duty")
        assertEquals(listOf(VacationDay(d(23), emptyList(), true)), VacationNoticeRules.days(duty, SeriesRules.vacation(listOf(duty), emptyList(), d(22), d(23), now), duty = true))
    }

    @Test
    fun touchesMergeClientsAndPutDutiesLast() {
        val days = listOf(
            VacationDay(d(23), emptyList(), true),
            VacationDay(d(23), listOf("zakharova", "belov"), false),
            VacationDay(d(22), listOf("orlova"), false),
            VacationDay(d(27), listOf("orlova"), false),
            VacationDay(d(30), listOf("belov"), false),
        )
        val names = mapOf("orlova" to "Орлова", "belov" to "Белов", "zakharova" to "Захарова")
        assertEquals(
            listOf(
                VacationTouch("orlova", listOf(d(22), d(27))),
                VacationTouch("belov", listOf(d(23), d(30))),
                VacationTouch("zakharova", listOf(d(23))),
                VacationTouch(null, listOf(d(23))),
            ),
            VacationNoticeRules.touches(days) { names.getValue(it) },
        )
    }

    @Test
    fun openUntilWarnedOrVacationOver() {
        assertTrue(VacationNoticeRules.open(null, d(29), d(29)))
        assertFalse(VacationNoticeRules.open(1L, d(29), d(20)))
        assertFalse(VacationNoticeRules.open(null, d(29), d(30)))
    }

    @Test
    fun strings() {
        assertEquals(s("Удалить 1_тренировку"), CalendarSettingsFormat.vacationDelete(1))
        assertEquals(s("Удалить 12_тренировок"), CalendarSettingsFormat.vacationDelete(12))
        assertEquals("Записать отпуск", CalendarSettingsFormat.vacationDelete(0))
        assertEquals(s("2_тренировки · чт_22.10, вт_27.10"), CalendarSettingsFormat.vacationDays(listOf(d(22), d(27))))
        assertEquals(s("Отпуск_02.11–08.11"), CalendarSettingsFormat.vacationTitle(d(2, 11), d(8, 11)))
        assertEquals(
            s("Отпуск_02.11–08.11\nОрлова Ксения — пн_02.11, ср_04.11"),
            CalendarSettingsFormat.noticeList(CalendarSettingsFormat.vacationTitle(d(2, 11), d(8, 11)), listOf("Орлова Ксения" to listOf(d(2, 11), d(4, 11)))),
        )
    }
}
