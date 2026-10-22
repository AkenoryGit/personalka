package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки блоков из макетов «День» и «Блоки, значки, наложения» (шаг 0.3). «_» — неразрывный пробел. */
class CalendarFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val at10 = LocalDateTime(2026, 10, 20, 10, 0)

    @Test
    fun titlesLikeMockups() {
        assertEquals(s("Морозов_Д."), CalendarFormat.title(listOf(ClientName("Морозов", "Даниил")), null, 0, "ПТ"))
        assertEquals("Ковалёв / Ковалёва", CalendarFormat.title(listOf(ClientName("Ковалёв", "Илья"), ClientName("Ковалёва", "Мира")), null, 0, "Сплит"))
        assertEquals(
            "Ильин / Лаптев / Носова",
            CalendarFormat.title(listOf(ClientName("Ильин", "Глеб"), ClientName("Лаптев", "Ян"), ClientName("Носова", "Ева")), null, 0, "Мини-группа"),
        )
        assertEquals("Вт, Чт 18:00 ДБ (10)", CalendarFormat.title(emptyList(), "Вт, Чт 18:00 ДБ", 10, "Группа"))
        // Группа без детей — всё равно название группы; тренировка без клиентов — название типа.
        assertEquals("Сб 9:00 ББ (0)", CalendarFormat.title(emptyList(), "Сб 9:00 ББ", 0, "Группа"))
        assertEquals("Гидрореабилитация", CalendarFormat.title(emptyList(), null, 0, "Гидрореабилитация"))
        // Лишние пробелы из старых записей не попадают в блок.
        assertEquals(s("Белова_Е."), CalendarFormat.client(ClientName(" Белова ", " Ева")))
    }

    @Test
    fun linesByHeight() {
        assertEquals("08:00 · ББ", CalendarFormat.startPlace(LocalDateTime(2026, 10, 20, 8, 0), "ББ"))
        assertEquals(s("Белова_Е., 10:00"), CalendarFormat.oneLine(s("Белова_Е."), at10))
        assertEquals("Группа · ДБ", CalendarFormat.typePlace("Группа", "ДБ"))
        assertEquals("+2", CalendarFormat.more(2))
        assertEquals(s("Ещё 2_тренировки"), CalendarFormat.moreTitle(2))
        assertEquals(s("Ещё 5_тренировок"), CalendarFormat.moreTitle(5))
        assertEquals("10:00–10:50 · ББ", CalendarFormat.timePlace(at10, LocalDateTime(2026, 10, 20, 10, 50), "ББ"))
        // Конец в полночь — «24:00», как во всём приложении.
        assertEquals("23:10–24:00 · ДБ", CalendarFormat.timePlace(LocalDateTime(2026, 10, 20, 23, 10), LocalDateTime(2026, 10, 21, 0, 0), "ДБ"))
    }
}
