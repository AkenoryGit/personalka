package com.akenory.personalka.domain.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Полоски зон и нажатие на зону (ТЗ 5.3): дежурство ББ 11:00–15:00 и запрет ДБ 17:00–18:00 из макета «День». */
class DayZonesTest {
    private fun zone(id: String, kind: ZoneKind, from: String, to: String): ZoneSpan {
        fun minute(text: String) = text.split(':').map { it.toInt() }.let { (h, m) -> h * 60 + m }
        return ZoneSpan(id, kind, minute(from), minute(to))
    }

    private val duty = zone("duty", ZoneKind.DUTY, "11:00", "15:00")
    private val group = zone("group", ZoneKind.RESTRICTION, "17:00", "18:00")

    @Test
    fun separateZonesAtTheEdge() {
        val places = DayZoneRules.lanes(listOf(group, duty))
        // Дежурства рисуются первыми — под запретами; полоски у самого края.
        assertEquals(listOf(ZonePlace(duty, 0), ZonePlace(group, 0)), places)
    }

    @Test
    fun overlappingZonesStandSideBySide() {
        val cleaning = zone("cleaning", ZoneKind.RESTRICTION, "14:00", "16:00")
        val aqua = zone("aqua", ZoneKind.RESTRICTION, "14:30", "15:30")
        val places = DayZoneRules.lanes(listOf(aqua, cleaning, duty))
        assertEquals(listOf(ZonePlace(duty, 0), ZonePlace(cleaning, 1), ZonePlace(aqua, 1)), places)
        // Запрет, который начинается ровно в конце дежурства, с ним не пересекается.
        val after = zone("after", ZoneKind.RESTRICTION, "15:00", "16:00")
        assertEquals(ZonePlace(after, 0), DayZoneRules.lanes(listOf(duty, after)).last())
    }

    @Test
    fun tapOnStripOrVisiblePart() {
        val cleaning = zone("cleaning", ZoneKind.RESTRICTION, "14:00", "16:00")
        val places = DayZoneRules.lanes(listOf(duty, cleaning, group))
        // На полоске дежурства в 14:30 — дежурство, хоть запрет нарисован выше.
        assertEquals(duty, DayZoneRules.at(places, 14 * 60 + 30f, lane = 0)?.zone)
        // На второй полоске и правее полосок — запрет: он верхний слой.
        assertEquals(cleaning, DayZoneRules.at(places, 14 * 60 + 30f, lane = 1)?.zone)
        assertEquals(cleaning, DayZoneRules.at(places, 14 * 60 + 30f, lane = null)?.zone)
        // Полоска, на которой в это время ничего нет, — зона под пальцем.
        assertEquals(duty, DayZoneRules.at(places, 12 * 60f, lane = 1)?.zone)
        assertEquals(group, DayZoneRules.at(places, 17 * 60f, lane = null)?.zone)
        // Конец не входит, вне зон — ничего.
        assertNull(DayZoneRules.at(places, 18 * 60f, lane = null))
        assertNull(DayZoneRules.at(places, 9 * 60f, lane = 0))
    }
}
