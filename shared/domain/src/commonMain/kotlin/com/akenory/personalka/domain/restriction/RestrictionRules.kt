package com.akenory.personalka.domain.restriction

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Запреты, дежурства и наложения (ТЗ 4.5). Правила ничего не сохраняют и не знают о Room: слой данных
 * передаёт запреты (свои и клуба) и тренировки, а записывает то, что вернулось. «Сейчас» — время клуба
 * (`ClubTime.now`).
 *
 * Главные правила:
 * - **Только то же место.** Тренировка в ДБ сверяется только с запретами ДБ; в ББ в то же время — ничего.
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
     * Запреты дня — для штриховки и полосок календаря (5.3) и поиска свободного времени (4.13).
     * [locationId] — только одно место; пусто — все места. По началу, затем по концу, месту и id.
     */
    fun on(restrictions: List<Restriction>, date: LocalDate, locationId: String? = null): List<RestrictionHit> =
        restrictions
            .filter { (locationId == null || it.locationId == locationId) && occursOn(it, date) }
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
}
