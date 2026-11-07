package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus

/**
 * Запреты, дежурства и наложения (ТЗ 4.5). Правила ничего не сохраняют и не знают о Room: слой данных
 * передаёт запреты (свои и клуба) и тренировки, а записывает то, что вернулось. «Сейчас» — время клуба
 * (`ClubTime.now`).
 *
 * Главные правила:
 * - **Только то же место.** Тренировка в ДБ сверяется только с запретами ДБ и «Все места»; в ББ в то же время —
 *   только с запретами ББ и «Все места».
 * - **Пересечение — по интервалам \[начало, конец).** Тренировка, которая кончается в 17:00, и запрет
 *   с 17:00 не пересекаются.
 * - **Окно — только когда тренировку ставят на это время:** создание, перенос, смена места или длительности.
 *   Правка плана, клиентов или типа окна не открывает, флаг просто пересчитывается.
 * - **Дежурство с запретами не сверяется:** оно не занимает дорожку, `over_restriction` у него всегда `false`.
 * - **Изменились запреты — флаг пересчитывается без окон**, только у тренировок, которые ещё не начались.
 * - **Накладка с дежурством разрешена:** правило лишь находит дежурства под тренировкой для значка
 *   и строки «Накладка с дежурством 15:00–19:00» — только в другом месте: тренировка ББ во время дежурства ББ
 *   накладкой не считается.
 */
object RestrictionRules {
    // --- Даты и время запрета ---

    /**
     * Действует ли запрет в этот день. Разовый — в свою дату; повторяющийся — в свои дни недели
     * с `validFrom` по `validTo` включительно. Пропущенные даты не действуют. Запрет с ошибкой
     * ([problem] не пусто) не действует нигде.
     */
    fun occursOn(restriction: Restriction, date: LocalDate): Boolean {
        if (problem(restriction) != null) return false
        if (date in restriction.skipDates) return false
        return if (restriction.isOneOff) {
            restriction.date == date
        } else {
            date >= restriction.validFrom &&
                (restriction.validTo == null || date <= restriction.validTo) &&
                date.dayOfWeek in restriction.weekdays
        }
    }

    /**
     * «Удалить → Этот и все следующие» (5.3, шаг 5.3р): запрет действует до дня перед [date] — последний день
     * повторения `validTo` = накануне, пропуски после него отбрасываются. До [date] запрет не действовал ни разу
     * или он разовый — `null`: удаляется целиком.
     */
    fun endBefore(restriction: Restriction, date: LocalDate): Restriction? {
        if (restriction.isOneOff) return null
        val last = date.minus(1, DateTimeUnit.DAY)
        val cut = restriction.copy(
            validTo = restriction.validTo?.let { minOf(it, last) } ?: last,
            skipDates = restriction.skipDates.filter { it <= last }.toSet(),
        )
        var day: LocalDate = cut.validTo ?: return null
        while (day >= cut.validFrom) {
            if (occursOn(cut, day)) return cut
            day = day.minus(1, DateTimeUnit.DAY)
        }
        return null
    }

    /**
     * Запреты дня — для штриховки и полосок календаря (5.3) и поиска свободного времени (4.13).
     * [locationId] — только запреты, которые действуют в этом месте (свои и «Все места»); пусто — все запреты.
     * По началу, затем по концу, месту и id.
     */
    fun on(restrictions: List<Restriction>, date: LocalDate, locationId: String? = null): List<RestrictionHit> =
        restrictions
            .filter { (locationId == null || it.covers(locationId)) && occursOn(it, date) }
            .sortedWith(
                compareBy<Restriction>({ it.startMinute }, { it.endMinute }, { it.locationId }, { it.id }),
            )
            .map { hitOn(it, date) }

    /** Что не так с запретом из формы (5.5), или `null`. */
    fun problem(restriction: Restriction): RestrictionProblem? = when {
        restriction.isOneOff && restriction.date == null -> RestrictionProblem.NO_DATE
        restriction.endMinute <= restriction.startMinute -> RestrictionProblem.TIME_REVERSED
        !restriction.isOneOff && restriction.validTo != null && restriction.validTo < restriction.validFrom ->
            RestrictionProblem.PERIOD_REVERSED
        else -> null
    }

    // --- Пересечение с тренировкой ---

    /**
     * Запреты того же места, которые пересекаются с тренировкой \[начало, конец), по началу.
     * У дежурства — всегда пусто.
     */
    fun hits(placement: Placement, restrictions: List<Restriction>): List<RestrictionHit> {
        if (placement.isDuty) return emptyList()
        val from = ClubTime.minuteOfDay(placement.start)
        val to = from + placement.durationMin
        return on(restrictions, placement.date, placement.locationId)
            .filter { from < it.restriction.endMinute && it.restriction.startMinute < to }
    }

    /** `over_restriction` без окна: под запретом ли тренировка сейчас. */
    fun flag(placement: Placement, restrictions: List<Restriction>): Boolean =
        hits(placement, restrictions).isNotEmpty()

    /**
     * Тренировку ставят на новое время или место: новая ([before] пусто) или изменились начало,
     * длительность, место или вид (дежурство / тренировка).
     */
    fun moved(before: Placement?, after: Placement): Boolean = before != after

    /**
     * Проверка при сохранении одной тренировки — из формы (5.5) или после перетаскивания (5.3).
     * [before] — как было (пусто у новой). Окно — если тренировку поставили под запрет; иначе флаг
     * пересчитывается молча: правка плана тренировки, которая уже стоит поверх запрета, окна не открывает.
     */
    fun check(before: Placement?, after: Placement, restrictions: List<Restriction>): RestrictionCheck {
        val hits = hits(after, restrictions)
        return if (hits.isNotEmpty() && moved(before, after)) {
            RestrictionCheck.Confirm(hits)
        } else {
            RestrictionCheck.Save(overRestriction = hits.isNotEmpty())
        }
    }

    /**
     * Проверка нескольких тренировок одним окном — новой серии или «Эту и следующие» (4.4, 5.5).
     * [placements] — только новые и перенесённые тренировки (см. [moved]); у остальных флаг
     * пересчитывается молча через [flag]. `null` — окна не нужно. «Поставить» — каждой тренировке
     * `over_restriction = flag(...)`, «Отмена» — не сохранять ничего.
     *
     * Продление серий (`SeriesRules.extendAll`) окна не открывает: новые тренировки получают [flag] молча.
     */
    fun checkAll(placements: List<Placement>, restrictions: List<Restriction>): BatchConflicts? {
        val perTraining = placements.map { hits(it, restrictions) }
        val conflicting = perTraining.count { it.isNotEmpty() }
        if (conflicting == 0) return null
        val groups = perTraining.flatten()
            .groupBy { it.restriction.id }
            .values
            .map { same -> ConflictGroup(same.first().restriction, same.map { it.date }.distinct().sorted()) }
            .sortedWith(
                compareBy<ConflictGroup>({ it.dates.first() }, { it.restriction.startMinute }, { it.restriction.id }),
            )
        return BatchConflicts(checked = placements.size, conflicting = conflicting, groups = groups)
    }

    // --- Изменились запреты ---

    /**
     * Запреты изменились — добавлены, изменены, удалены, пропущен день или пришли с сервера: новый
     * `over_restriction` для тренировок, которые ещё не начались (начало позже [now]), в том числе
     * отменённых — флаг верен, если их вернут. Прошлые сохраняют флаг, с которым прошли. Возвращаются
     * только изменения, в порядке [trainings]; окон нет.
     */
    fun recalc(trainings: List<PlacedTraining>, restrictions: List<Restriction>, now: LocalDateTime): List<FlagChange> =
        trainings
            .filter { it.placement.start > now }
            .mapNotNull { t ->
                val flag = flag(t.placement, restrictions)
                if (flag != t.overRestriction) FlagChange(t.id, flag) else null
            }

    // --- Накладка с дежурством ---

    /**
     * Дежурства под тренировкой — для нейтрального значка и строки «Накладка с дежурством 15:00–19:00»
     * в карточке (5.4). Только дежурства в другом месте (решение 24.10.2026): в ДБ во время дежурства в ББ тренеру
     * нужно отойти, а тренировка в том же ББ проходит рядом с дежурством. Отменённые дежурства не считаются;
     * у самого дежурства накладок с дежурствами не бывает. По началу.
     */
    fun dutyOverlaps(training: Placement, sameDay: List<PlacedTraining>): List<PlacedTraining> {
        if (training.isDuty) return emptyList()
        val from = training.start
        val to = training.end
        return sameDay
            .filter { it.placement.isDuty && !it.cancelled && it.placement.locationId != training.locationId }
            .filter { it.placement.start < to && from < it.placement.end }
            .sortedWith(compareBy<PlacedTraining>({ it.placement.start }, { it.placement.end }, { it.id }))
    }

    private fun hitOn(restriction: Restriction, date: LocalDate): RestrictionHit {
        val midnight = LocalDateTime(date, LocalTime(0, 0))
        return RestrictionHit(
            restriction = restriction,
            start = ClubTime.plusMinutes(midnight, restriction.startMinute),
            end = ClubTime.plusMinutes(midnight, restriction.endMinute),
        )
    }

    // --- «Все места» одной записью (шаг 5.3ю) ---

    /** Склейка старых запретов: [keep] становится «Все места», [drop] удаляются. */
    data class AllPlacesMerge(val keep: String, val drop: List<String>)

    /**
     * Запреты «на все места», записанные до шага 5.3ю по одному на место: одинаковые время, дни, дата, период,
     * причина и пропущенные дни, а места вместе покрывают все действующие [places] (их не меньше двух). Остаётся запрет
     * с наименьшим id — на двух телефонах склейка выходит одинаковой. Если в группе на одно место два одинаковых
     * запрета, лишний тоже удаляется. Всё, что совпадает не полностью, не трогается.
     */
    fun allPlacesMerges(restrictions: List<Restriction>, places: Set<String>): List<AllPlacesMerge> {
        if (places.size < 2) return emptyList()
        return restrictions
            .filter { !it.allPlaces }
            .groupBy { it.copy(id = "", locationId = "") }
            .values
            .filter { same -> same.map { it.locationId }.toSet().containsAll(places) }
            .map { same ->
                val ids = same.map { it.id }.sorted()
                AllPlacesMerge(keep = ids.first(), drop = ids.drop(1))
            }
            .sortedBy { it.keep }
    }
}
