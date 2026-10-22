package com.akenory.personalka.domain.calendar

// ТЗ 5.3, «Слои снизу вверх»: дежурства и запреты лежат под тренировками, у левого края колонки дня — их полоски.

/** Зона дня: дежурство — сплошная заливка, запрет — штриховка. */
enum class ZoneKind { DUTY, RESTRICTION }

/** Зона в дне: минуты от полуночи, [start] включительно, [end] — нет. */
data class ZoneSpan(val id: String, val kind: ZoneKind, val start: Int, val end: Int)

/** Зона и её полоска: дорожка [lane] 0 — у самого края колонки, 1 — следующие 4 dp. */
data class ZonePlace(val zone: ZoneSpan, val lane: Int)

/**
 * Полоски зон и нажатие на зону (ТЗ 5.3). У каждой зоны своя полоска 4 dp у левого края колонки; тренировки
 * начинаются в 8 dp от края, поэтому рядом помещаются две полоски — дежурство и запрет в одно время.
 */
object DayZoneRules {
    const val LANES = 2

    /**
     * Порядок рисования и дорожки полосок. Сначала дежурства, над ними запреты (слои 5.3), внутри — по началу.
     * Зона встаёт на первую дорожку, где не пересекается с уже поставленными; если заняты обе — на последнюю:
     * три зоны в одно время редки, полоски тогда ложатся одна на другую.
     */
    fun lanes(zones: List<ZoneSpan>): List<ZonePlace> {
        val sorted = zones.sortedWith(compareBy<ZoneSpan>({ it.kind.ordinal }, { it.start }, { it.end }, { it.id }))
        val placed = mutableListOf<ZonePlace>()
        sorted.forEach { zone ->
            val busy = placed.filter { it.zone.start < zone.end && zone.start < it.zone.end }.map { it.lane }.toSet()
            val lane = (0 until LANES).firstOrNull { it !in busy } ?: (LANES - 1)
            placed += ZonePlace(zone, lane)
        }
        return placed
    }

    /**
     * Зона под пальцем в минуту [minute]; [places] — в порядке рисования ([lanes]). [lane] — полоска под пальцем
     * или `null`, если нажали правее полосок. На полоске — зона этой полоски; в остальной колонке — та, что
     * нарисована выше: запрет над дежурством, из равных — позже начавшаяся.
     */
    fun at(places: List<ZonePlace>, minute: Float, lane: Int?): ZonePlace? {
        val under = places.filter { minute >= it.zone.start && minute < it.zone.end }
        return under.lastOrNull { lane != null && it.lane == lane } ?: under.lastOrNull()
    }
}
