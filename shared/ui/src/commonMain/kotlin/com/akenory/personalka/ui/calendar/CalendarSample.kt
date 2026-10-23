package com.akenory.personalka.ui.calendar

import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.calendar.CalendarZone
import com.akenory.personalka.domain.calendar.BadgeFacts
import com.akenory.personalka.domain.calendar.CalendarBadges
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.ZoneFormat
import com.akenory.personalka.domain.restriction.PlacedTraining
import com.akenory.personalka.domain.restriction.Placement
import com.akenory.personalka.domain.restriction.Restriction
import com.akenory.personalka.domain.restriction.RestrictionCheck
import com.akenory.personalka.domain.restriction.RestrictionHit
import com.akenory.personalka.domain.restriction.RestrictionRules
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Пример дня по макету «День» (шаг 0.3): проверить сетку, блоки, значки, дежурство, запрет и перенос на телефоне,
 * пока тренировку нельзя создать — форма появится в шаге 1.13, и вместе с ней пример уберётся. Живёт только
 * в памяти, в базу не пишется. Имена придуманы.
 */
internal object CalendarSample {
    private const val PREFIX = "sample-"
    private const val SMALL_POOL = "Детский бассейн"

    /** Полные названия типов примера — для строк «Месяца» и «Списка». */
    private val TYPE_NAMES = mapOf("Груд" to "Грудничковое", "Мини" to "Мини-группа", "Гидро" to "Гидрореабилитация")

    /** Id запрета примера: «Пропустить этот день» убирает его из [day] и [zones]. */
    const val RESTRICTION_ID = "${PREFIX}restriction"

    /**
     * Тренировки примера на [date]. [moves] — куда их перенесли (в памяти, шаг 1.11); значки «поверх запрета»
     * и «поверх дежурства» считаются по новому месту, как у настоящих: запрет и дежурство есть только в [date].
     */
    fun day(date: LocalDate, moves: Map<String, LocalDateTime> = emptyMap(), restrictionSkipped: Boolean = false): List<CalendarTraining> {
        val restrictions = if (restrictionSkipped) emptyList() else listOf(oneDay(date))
        val duty = PlacedTraining("${PREFIX}duty", Placement(LocalDateTime(date, LocalTime(11, 0)), 240, "${PREFIX}ББ", isDuty = true))
        var n = 0
        fun t(
            time: String,
            minutes: Int,
            title: String,
            type: String,
            shade: Int,
            place: String,
            facts: BadgeFacts = BadgeFacts(),
        ): CalendarTraining {
            val (h, m) = time.split(':').map { it.toInt() }
            val id = "$PREFIX${++n}"
            val start = moves[id] ?: LocalDateTime(date, LocalTime(h, m))
            val placement = Placement(start, minutes, "$PREFIX$place")
            val placed = facts.copy(
                overRestriction = RestrictionRules.flag(placement, restrictions),
                overDuty = RestrictionRules.dutyOverlaps(placement, listOf(duty)).isNotEmpty(),
            )
            return CalendarTraining(
                id = id,
                start = start,
                durationMin = minutes,
                title = title,
                typeShort = type,
                placeId = "$PREFIX$place",
                placeShort = place,
                // Цвет — у места, как у настоящих: ББ синий, ДБ розовый; оттенок — у типа (шаг 1.13в).
                color = if (place == "ДБ") "pink" else "blue",
                cancelled = facts.cancelled,
                badges = CalendarBadges.of(placed),
                marked = facts.done,
                shade = shade,
                typeName = TYPE_NAMES[type] ?: type,
            )
        }
        fun one(last: String, first: String) = CalendarFormat.client(ClientName(last, first))
        val done = BadgeFacts(done = true)
        return listOf(
            // Раньше часов работы: сетка дня начинается с 06:00.
            t("06:30", 45, one("Гусев", "Борис"), "ПТ", 1, "ББ", done),
            t("08:00", 50, one("Морозов", "Даниил"), "ПТ", 1, "ББ", done),
            // Проведена без дневника: на блоке 53 dp виден один значок — точка важнее галочки.
            t("09:00", 50, "Ковалёв / Ковалёва", "Сплит", 2, "ББ", done.copy(noDiary = true)),
            // Отмена со списанием и грудничковое на одно время: длинная — левее.
            t("10:00", 50, one("Зайцева", "Мария"), "ПТ", 1, "ББ", BadgeFacts(cancelled = true, charged = true)),
            t("10:00", 30, one("Белова", "Ева"), "Груд", 2, "ББ", done),
            // Поверх дежурства ББ 11:00–15:00: значок «слои» считается по времени.
            t("12:00", 50, one("Никитин", "Артём"), "ПТ", 1, "ББ", done),
            t("13:30", 50, one("Орлова", "Ксения"), "ПТ", 1, "ББ", done.copy(noDiary = true)),
            t("15:00", 50, one("Соколова", "Вера"), "ПТ", 1, "ДБ"),
            t("16:00", 50, "Ильин / Лаптев / Носова", "Мини", 3, "ДБ"),
            // Поверх запрета ДБ 17:00–18:00: штриховка видна по краям блока и на полоске, треугольник — по времени.
            t("17:00", 50, one("Орлов", "Максим"), "ПТ", 1, "ДБ"),
            // Три на одно время делят ширину.
            t("18:00", 60, CalendarFormat.title(emptyList(), "Вт, Чт 18:00 ДБ", 10, "Группа"), "Группа", 4, "ДБ"),
            t("18:00", 50, one("Ильин", "Глеб"), "ПТ", 1, "ББ"),
            t("18:15", 50, one("Новикова", "Полина"), "СПТ", 3, "ББ"),
            // Пять на одно время: видны три и «+2».
            t("20:00", 50, one("Ткаченко", "Роман"), "ПТ", 1, "ББ"),
            t("20:00", 50, one("Яковлева", "Софья"), "Гидро", 3, "ББ"),
            t("20:00", 50, one("Захаров", "Никита"), "ПТ", 1, "ДБ"),
            t("20:00", 50, one("Лаптева", "Алина"), "Сплит", 2, "ДБ"),
            t("20:00", 50, one("Егорова", "Лиза"), "ПТ", 1, "ББ"),
            t("21:30", 30, one("Носова", "Ева"), "ПТ", 1, "ББ"),
        )
    }

    /** Дежурство ББ 11:00–15:00 и запрет ДБ 17:00–18:00 «групповая тренировка» по вт и чт — как в макете. */
    fun zones(date: LocalDate): List<CalendarZone> {
        val group = group(date)
        return listOf(
            CalendarZone.duty("${PREFIX}duty", LocalDateTime(date, LocalTime(11, 0)), 240, "Дежурство", "${PREFIX}ББ", "ББ", "blue"),
            CalendarZone.restriction(RestrictionHit(group, LocalDateTime(date, group.startTime), LocalDateTime(date, group.endTime)), "ДБ"),
        )
    }

    /**
     * Запрет под тренировкой, которую перенесли на [start] (4.5): строки окна «Поставить всё равно?», как
     * у настоящих; пусто — окна не нужно. Запрет примера действует только в [date] и только в ДБ.
     */
    fun conflicts(training: CalendarTraining, start: LocalDateTime, date: LocalDate, restrictionSkipped: Boolean): List<String> {
        if (restrictionSkipped) return emptyList()
        val before = Placement(training.start, training.durationMin, training.placeId)
        val check = RestrictionRules.check(before, Placement(start, training.durationMin, training.placeId), listOf(oneDay(date)))
        if (check !is RestrictionCheck.Confirm) return emptyList()
        return check.hits.map { ZoneFormat.conflict(SMALL_POOL, it.restriction.startMinute, it.restriction.endMinute, it.reason) }
    }

    private fun group(date: LocalDate) = Restriction(
        id = RESTRICTION_ID,
        locationId = "${PREFIX}ДБ",
        reason = "Групповая тренировка",
        startTime = LocalTime(17, 0),
        endTime = LocalTime(18, 0),
        weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
        validFrom = date,
    )

    /** Тот же запрет разово в [date]: в календаре пример показывает его в любой день недели. */
    private fun oneDay(date: LocalDate) = group(date).copy(weekdays = emptySet(), date = date)

    /** Зона примера: «Пропустить этот день» убирает её только из памяти. */
    fun isSample(zone: CalendarZone): Boolean = zone.id.startsWith(PREFIX)

    /** Тренировка примера: перенос и «Копировать» — только в памяти. */
    fun isSample(training: CalendarTraining): Boolean = training.id.startsWith(PREFIX)

    /** Место примера — «sample-ББ»: фильтр мест сверяет у примера короткое имя, а не id. */
    fun isSamplePlace(placeId: String): Boolean = placeId.startsWith(PREFIX)
}
