package com.akenory.personalka.ui.calendar

import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.domain.calendar.BadgeFacts
import com.akenory.personalka.domain.calendar.CalendarBadges
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionHit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Пример дня по макету «День» (шаг 0.3): проверить сетку, блоки, значки, дежурство и запрет на телефоне, пока
 * тренировку нельзя создать — форма появится в шаге 1.13, и вместе с ней пример уберётся. Живёт только в памяти,
 * в базу не пишется. Имена придуманы.
 */
internal object CalendarSample {
    private const val PREFIX = "sample-"

    fun day(date: LocalDate): List<CalendarTraining> {
        var n = 0
        fun t(
            time: String,
            minutes: Int,
            title: String,
            type: String,
            color: String,
            place: String,
            facts: BadgeFacts = BadgeFacts(),
        ): CalendarTraining {
            val (h, m) = time.split(':').map { it.toInt() }
            return CalendarTraining(
                id = "$PREFIX${++n}",
                start = LocalDateTime(date, LocalTime(h, m)),
                durationMin = minutes,
                title = title,
                typeShort = type,
                placeId = "$PREFIX$place",
                placeShort = place,
                color = color,
                cancelled = facts.cancelled,
                badges = CalendarBadges.of(facts),
            )
        }
        fun one(last: String, first: String) = CalendarFormat.client(ClientName(last, first))
        val done = BadgeFacts(done = true)
        return listOf(
            // Раньше часов работы: сетка дня начинается с 06:00.
            t("06:30", 45, one("Гусев", "Борис"), "ПТ", "blue", "ББ", done),
            t("08:00", 50, one("Морозов", "Даниил"), "ПТ", "blue", "ББ", done),
            // Проведена без дневника: на блоке 53 dp виден один значок — точка важнее галочки.
            t("09:00", 50, "Ковалёв / Ковалёва", "Сплит", "indigo", "ББ", done.copy(noDiary = true)),
            // Отмена со списанием и грудничковое на одно время: длинная — левее.
            t("10:00", 50, one("Зайцева", "Мария"), "ПТ", "blue", "ББ", BadgeFacts(cancelled = true, charged = true)),
            t("10:00", 30, one("Белова", "Ева"), "Груд", "teal", "ББ", done),
            // Поверх дежурства ББ 11:00–15:00.
            t("12:00", 50, one("Никитин", "Артём"), "ПТ", "blue", "ББ", done.copy(overDuty = true)),
            t("13:30", 50, one("Орлова", "Ксения"), "ПТ", "blue", "ББ", done.copy(overDuty = true, noDiary = true)),
            t("15:00", 50, one("Соколова", "Вера"), "ПТ", "blue", "ДБ"),
            t("16:00", 50, "Ильин / Лаптев / Носова", "Мини", "lime", "ДБ"),
            // Поверх запрета ДБ 17:00–18:00: штриховка видна по краям блока и на полоске.
            t("17:00", 50, one("Орлов", "Максим"), "ПТ", "blue", "ДБ", BadgeFacts(overRestriction = true)),
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

    /** Дежурство ББ 11:00–15:00 и запрет ДБ 17:00–18:00 «групповая тренировка» по вт и чт — как в макете. */
    fun zones(date: LocalDate): List<CalendarZone> {
        val group = Restriction(
            id = "${PREFIX}restriction",
            locationId = "${PREFIX}ДБ",
            reason = "Групповая тренировка",
            startTime = LocalTime(17, 0),
            endTime = LocalTime(18, 0),
            weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            validFrom = date,
        )
        return listOf(
            CalendarZone.duty("${PREFIX}duty", LocalDateTime(date, LocalTime(11, 0)), 240, "Дежурство", "ББ", "slate"),
            CalendarZone.restriction(RestrictionHit(group, LocalDateTime(date, group.startTime), LocalDateTime(date, group.endTime)), "ДБ"),
        )
    }

    /** Зона примера: «Пропустить этот день» убирает её только из памяти. */
    fun isSample(zone: CalendarZone): Boolean = zone.id.startsWith(PREFIX)
}
