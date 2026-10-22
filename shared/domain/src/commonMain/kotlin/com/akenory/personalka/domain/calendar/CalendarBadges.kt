package com.akenory.personalka.domain.calendar

/** Значок в правом верхнем углу блока тренировки (ТЗ 5.3) — в порядке важности. */
enum class CalendarBadge {
    /** «₽»: отмена со списанием — хотя бы у одного клиента. */
    CHARGED,

    /** Фиолетовый треугольник: поставлена поверх запрета. */
    OVER_RESTRICTION,

    /** Оранжевая точка: проведена, а дневник не заполнен. */
    NO_DIARY,

    /** Серые «слои»: поверх дежурства. */
    OVER_DUTY,

    /** Галочка: проведена. */
    DONE,
}

/** Что известно о тренировке для значков; считает слой данных по базе и правилам 4.1–4.5. */
data class BadgeFacts(
    /** Отменена целиком. */
    val cancelled: Boolean = false,
    /** Хотя бы у одного клиента — отмена со списанием. */
    val charged: Boolean = false,
    /** `over_restriction`. */
    val overRestriction: Boolean = false,
    /** Проведена без дневника (`DiaryRules.missing`). */
    val noDiary: Boolean = false,
    /** Под ней есть неотменённое дежурство (`RestrictionRules.dutyOverlaps`). */
    val overDuty: Boolean = false,
    val done: Boolean = false,
)

/**
 * Значки блока (ТЗ 5.3): «₽» → поверх запрета → нет дневника → поверх дежурства → проведено. На блоке ниже 56 dp
 * виден один, самый важный; от 56 dp — до двух. Все — с пояснениями в карточке тренировки (5.4).
 */
object CalendarBadges {
    /**
     * Все значки тренировки по важности. У отменённой — только «₽»: дорожку она не занимает, дневника и галочки
     * у неё нет, а серый зачёркнутый блок уже говорит «отменена».
     */
    fun of(facts: BadgeFacts): List<CalendarBadge> = buildList {
        if (facts.charged) add(CalendarBadge.CHARGED)
        if (facts.cancelled) return@buildList
        if (facts.overRestriction) add(CalendarBadge.OVER_RESTRICTION)
        if (facts.noDiary) add(CalendarBadge.NO_DIARY)
        if (facts.overDuty) add(CalendarBadge.OVER_DUTY)
        if (facts.done) add(CalendarBadge.DONE)
    }

    /** Сколько значков помещается на блок: от 56 dp (три строки) — два, ниже — один. */
    fun visible(badges: List<CalendarBadge>, rows: BlockRows): List<CalendarBadge> =
        badges.take(if (rows == BlockRows.THREE) 2 else 1)
}
