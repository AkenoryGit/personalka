package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.client.ClientName
import kotlinx.datetime.LocalDate
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
    fun blockNamesInFull() {
        // Шаг 3.9д: на блоке — фамилия и имя без сокращений, у сплита — оба клиента.
        assertEquals("Морозов Даниил", CalendarFormat.blockName(listOf(ClientName(" Морозов ", "Даниил ", "Ильич")), null, 0, "ПТ"))
        assertEquals("Ковалёв Илья / Ковалёва Мира", CalendarFormat.blockName(listOf(ClientName("Ковалёв", "Илья"), ClientName("Ковалёва", "Мира")), null, 0, "Сплит"))
        assertEquals("Вт, Чт 18:00 ДБ (10)", CalendarFormat.blockName(listOf(ClientName("Ильин", "Глеб")), "Вт, Чт 18:00 ДБ", 10, "Группа"))
        assertEquals("Гидрореабилитация", CalendarFormat.blockName(emptyList(), null, 0, "Гидрореабилитация"))
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

    @Test
    fun dragHintAndMovedMessage() {
        val at1715 = LocalDateTime(2026, 10, 22, 17, 15)
        // ТЗ 5.3 и макет «Перенос на другой день»: подсказка с заглавной, сообщение — посреди фразы.
        assertEquals(s("Чт_22.10 · 17:15–18:15"), CalendarFormat.dragHint(at1715, LocalDateTime(2026, 10, 22, 18, 15)))
        assertEquals(s("Ср_21.10 · 12:15–13:05"), CalendarFormat.dragHint(LocalDateTime(2026, 10, 21, 12, 15), LocalDateTime(2026, 10, 21, 13, 5)))
        assertEquals(s("Перенесено на чт_22.10, 17:15"), CalendarFormat.moved(at1715))
        assertEquals(s("вт_20.10, 10:00"), CalendarFormat.at(at10))
    }

    @Test
    fun viewLabels() {
        // Переключатель вида в шапке (макеты «3 дня»): склонение по числу дней.
        assertEquals("День", CalendarFormat.view(CalendarView.Day))
        assertEquals(s("3_дня"), CalendarFormat.view(CalendarView.Days(3)))
        assertEquals(s("5_дней"), CalendarFormat.view(CalendarView.Days(5)))
        assertEquals(s("7_дней"), CalendarFormat.view(CalendarView.Days(7)))
        assertEquals("Месяц", CalendarFormat.view(CalendarView.Month))
        assertEquals("Список", CalendarFormat.view(CalendarView.Agenda))
    }

    @Test
    fun monthAndListLines() {
        // Макет «Месяц»: заголовок дня и число справа, строки тренировок и дежурства.
        assertEquals(s("10_тренировок"), CalendarFormat.trainings(10))
        assertEquals(s("1_тренировка"), CalendarFormat.trainings(1))
        assertEquals(s("3_тренировки"), CalendarFormat.trainings(3))
        assertEquals("Сплит · ББ", CalendarFormat.listLine("Сплит", "ББ", 50, charged = false))
        assertEquals(s("Грудничковое · ДБ · 30_мин"), CalendarFormat.listLine("Грудничковое", "ДБ", 30, charged = false))
        assertEquals("ПТ · ББ · со списанием", CalendarFormat.listLine("ПТ", "ББ", 50, charged = true))
        assertEquals(s("Группа · ББ · 60_мин · со списанием"), CalendarFormat.listLine("Группа", "ББ", 60, charged = true))
        assertEquals(s("ББ · до_15:00"), CalendarFormat.dutyLine("ББ", 15 * 60))
        assertEquals(s("ДБ · до_24:00"), CalendarFormat.dutyLine("ДБ", 24 * 60))
        assertEquals(s("ББ · до_15:00 · за_Александра"), CalendarFormat.dutyLine("ББ", 15 * 60, "Александра"))
        // TalkBack на клетке месяца: день и число тренировок; пустой день — только день.
        assertEquals(s("вт_20.10, 10_тренировок"), CalendarFormat.monthDay(LocalDate(2026, 10, 20), 10))
        assertEquals(s("вс_25.10"), CalendarFormat.monthDay(LocalDate(2026, 10, 25), 0))
        // Значки строки словами — для TalkBack.
        assertEquals("Отмена со списанием", CalendarFormat.badge(CalendarBadge.CHARGED))
        assertEquals("Поверх дежурства", CalendarFormat.badge(CalendarBadge.OVER_DUTY))
    }
}
