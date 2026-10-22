package com.akenory.personalka.ui.calendar

import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Пример дня по макету «День» (шаг 0.3): проверить сетку, блоки и наложения на телефоне, пока тренировку нельзя
 * создать — форма появится в шаге 1.13, и вместе с ней пример уберётся. Живёт только в памяти, в базу не пишется.
 * Имена придуманы.
 */
internal object CalendarSample {
    fun day(date: LocalDate): List<CalendarTraining> {
        var n = 0
        fun t(time: String, minutes: Int, title: String, type: String, color: String, place: String, cancelled: Boolean = false): CalendarTraining {
            val (h, m) = time.split(':').map { it.toInt() }
            return CalendarTraining(
                id = "sample-${++n}",
                start = LocalDateTime(date, LocalTime(h, m)),
                durationMin = minutes,
                title = title,
                typeShort = type,
                placeId = "sample-$place",
                placeShort = place,
                color = color,
                cancelled = cancelled,
            )
        }
        fun one(last: String, first: String) = CalendarFormat.client(ClientName(last, first))
        return listOf(
            // Раньше часов работы: сетка дня начинается с 06:00.
            t("06:30", 45, one("Гусев", "Борис"), "ПТ", "blue", "ББ"),
            t("08:00", 50, one("Морозов", "Даниил"), "ПТ", "blue", "ББ"),
            t("09:00", 50, "Ковалёв / Ковалёва", "Сплит", "indigo", "ББ"),
            // Отмена и грудничковое на одно время: длинная — левее.
            t("10:00", 50, one("Зайцева", "Мария"), "ПТ", "blue", "ББ", cancelled = true),
            t("10:00", 30, one("Белова", "Ева"), "Груд", "teal", "ББ"),
            t("12:00", 50, one("Никитин", "Артём"), "ПТ", "blue", "ББ"),
            t("13:30", 50, one("Орлова", "Ксения"), "ПТ", "blue", "ББ"),
            t("15:00", 50, one("Соколова", "Вера"), "ПТ", "blue", "ДБ"),
            t("16:00", 50, "Ильин / Лаптев / Носова", "Мини", "lime", "ДБ"),
            t("17:00", 50, one("Орлов", "Максим"), "ПТ", "blue", "ДБ"),
            // Три на одно время делят ширину.
            t("18:00", 60, CalendarFormat.title(emptyList(), "Вт, Чт 18:00 ДБ", 10, "Группа"), "Группа", "green", "ДБ"),
            t("18:00", 50, one("Ильин", "Глеб"), "ПТ", "blue", "ББ"),
            t("18:15", 50, one("Новикова", "Полина"), "СПТ", "pink", "ББ"),
            // Пять на одно время: видны три и «+2».
            t("20:00", 50, one("Ткаченко", "Роман"), "ПТ", "blue", "ББ"),
            t("20:00", 50, one("Яковлева", "Софья"), "Гидро", "magenta", "ББ"),
            t("20:00", 50, one("Захаров", "Никита"), "ПТ", "blue", "ДБ"),
            t("20:00", 50, one("Лаптева", "Алина"), "Сплит", "indigo", "ДБ"),
            t("20:00", 50, one("Егорова", "Лиза"), "ПТ", "blue", "ББ"),
            t("21:30", 30, one("Носова", "Ева"), "ПТ", "blue", "ББ"),
        )
    }
}
