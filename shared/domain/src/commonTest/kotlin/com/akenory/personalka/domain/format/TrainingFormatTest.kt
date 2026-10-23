package com.akenory.personalka.domain.format

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки формы тренировки и вопроса для серии из макетов «Новая тренировка» и «Изменение тренировки серии» (шаг 0.4). */
class TrainingFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val tue = LocalDate(2026, 10, 20)
    private val tueThu = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)

    @Test
    fun fields() {
        assertEquals(s("вт_20.10.2026"), TrainingFormat.date(tue))
        assertEquals("20:00–20:50", TrainingFormat.timeRange(LocalDateTime(tue, LocalTime(20, 0)), LocalDateTime(tue, LocalTime(20, 50))))
        assertEquals("до 31.12.2026", TrainingFormat.until(LocalDate(2026, 12, 31)))
        assertEquals("Ещё 3", TrainingFormat.moreTypes(3))
        assertEquals("Новый клиент «Иванов Пётр»", TrainingFormat.newClient(" Иванов Пётр "))
        assertEquals("Каждый вт и чт · бессрочно", TrainingFormat.seriesRepeat(tueThu, null))
        assertEquals("Каждый вт и чт до 31.12.2026", TrainingFormat.seriesRepeat(tueThu, LocalDate(2026, 12, 31)))
    }

    @Test
    fun scopeQuestionLikeMockup() {
        // Макет: «Никитин А. · каждый вт и чт, 12:00. Новое время — 12:30–13:20.», «Только эту — вт 20.10».
        val series = TrainingFormat.series(s("Никитин_А."), tueThu, LocalTime(12, 0))
        assertEquals(s("Никитин_А. · каждый вт и чт, 12:00"), series)
        val changes = TrainingFormat.changes(
            start = LocalDateTime(tue, LocalTime(12, 30)),
            end = LocalDateTime(tue, LocalTime(13, 20)),
            dayChanged = false,
            timeChanged = true,
            place = null,
            type = null,
            clientsChanged = false,
        )
        assertEquals(s("Никитин_А. · каждый вт и чт, 12:00. Новое время — 12:30–13:20."), TrainingFormat.scopeQuestion(series, changes))
        assertEquals(s("Только эту — вт_20.10"), TrainingFormat.onlyThis(tue))

        // Перенос на другой день, место и тип — по предложению на каждое.
        val moved = TrainingFormat.changes(
            start = LocalDateTime(LocalDate(2026, 10, 23), LocalTime(12, 0)),
            end = LocalDateTime(LocalDate(2026, 10, 23), LocalTime(12, 50)),
            dayChanged = true,
            timeChanged = false,
            place = "ДБ",
            type = "Сплит",
            clientsChanged = true,
        )
        assertEquals(s("Новый день — пт_23.10. Новое место — ДБ. Новый тип — Сплит. Другие клиенты."), moved)
        // Каждую среду, каждое воскресенье — род по первому дню.
        assertEquals(s("Белова_Е. · каждую ср, 09:00"), TrainingFormat.series(s("Белова_Е."), setOf(DayOfWeek.WEDNESDAY), LocalTime(9, 0)))
        assertEquals(s("Белова_Е. · каждую ср."), TrainingFormat.scopeQuestion(s("Белова_Е. · каждую ср"), ""))
    }

    @Test
    fun messages() {
        assertEquals(s("Создано: вт_20.10, 17:15"), TrainingFormat.created(LocalDateTime(tue, LocalTime(17, 15))))
        assertEquals("Создано: каждый вт и чт, 17:15", TrainingFormat.createdSeries(tueThu, LocalTime(17, 15)))
    }

    @Test
    fun seriesConflict() {
        // ТЗ 4.5: одно окно на серию — «9 из 17 тренировок, первая вт 20.10».
        val conflict = ZoneFormat.conflict("Детский бассейн", 17 * 60, 18 * 60, "Групповая тренировка")
        assertEquals(
            s("Детский бассейн 17:00–18:00: групповая тренировка — 9 из 17_тренировок, первая вт_20.10"),
            TrainingFormat.batchConflict(conflict, 9, 17, tue),
        )
        assertEquals(
            s("Детский бассейн 17:00–18:00: групповая тренировка — 1 из 21_тренировки, первая вт_20.10"),
            TrainingFormat.batchConflict(conflict, 1, 21, tue),
        )
        // Одна тренировка — только её день.
        assertEquals(s("Детский бассейн 17:00–18:00: групповая тренировка — вт_20.10"), TrainingFormat.batchConflict(conflict, 1, 1, tue))
    }
}
