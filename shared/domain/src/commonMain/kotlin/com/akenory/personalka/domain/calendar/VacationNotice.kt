package com.akenory.personalka.domain.calendar

import com.akenory.personalka.domain.series.Series
import com.akenory.personalka.domain.series.SeriesChanges
import kotlinx.datetime.LocalDate

/**
 * Тренировка серии, которую забирает отпуск: день и кого она касается. У ПТ и сплита — клиенты серии, у группы —
 * ожидаемые участники (у ещё не созданных дней — `null`: состав на дату подставляет слой данных), у дежурства —
 * никого.
 */
data class VacationDay(val date: LocalDate, val clientIds: List<String>?, val duty: Boolean)

/** Строка «Кого предупредить»: клиент ([clientId]) или дежурства (`null`) — и его дни по порядку. */
data class VacationTouch(val clientId: String?, val dates: List<LocalDate>)

/**
 * Защита «Отпуска» (ТЗ 5.16, шаг 3.12): до записи тренер видит, у кого пропадут тренировки, а после записи
 * на «Главной» висит «Предупредить об отпуске», пока каждого клиента не отметят «Предупредил».
 */
object VacationNoticeRules {
    /**
     * Дни серии [series], которые отпуск [changes] у неё забирает: удалённые тренировки (с их участниками) и новые
     * пропуски дней, до которых серия ещё не создана. По порядку, день — один раз.
     */
    fun days(series: Series, changes: SeriesChanges, duty: Boolean): List<VacationDay> {
        // Кого касается день: дежурство — никого; группа без участников (день ещё не создан) — состав на дату потом.
        fun people(ids: List<String>): List<String>? = when {
            duty -> emptyList()
            ids.isEmpty() && series.pattern.groupId != null -> null
            else -> ids
        }
        val deleted = changes.deleted.filter { it.seriesId == series.id }
        val built = deleted.map { t -> VacationDay(t.date, people(t.participants.map { it.clientId }.ifEmpty { series.pattern.clientIds }), duty) }
        val taken = deleted.map { it.date }.toSet()
        val skipped = changes.series.firstOrNull { it.id == series.id }?.skipDates.orEmpty() - series.skipDates - taken
        val unbuilt = skipped.map { VacationDay(it, people(series.pattern.clientIds), duty) }
        return (built + unbuilt).sortedBy { it.date }
    }

    /**
     * Строки «Кого предупредить»: клиент — все его дни из всех серий, дежурства — одной строкой в конце.
     * Клиенты — по первому дню, при равенстве — по [sortKey] (фамилия).
     */
    fun touches(days: List<VacationDay>, sortKey: (String) -> String = { it }): List<VacationTouch> {
        val byClient = mutableMapOf<String, MutableSet<LocalDate>>()
        days.forEach { day -> day.clientIds.orEmpty().forEach { byClient.getOrPut(it) { mutableSetOf() } += day.date } }
        val clients = byClient.map { (id, dates) -> VacationTouch(id, dates.sorted()) }
            .sortedWith(compareBy({ it.dates.first() }, { sortKey(it.clientId!!) }))
        val duties = days.filter { it.duty }.map { it.date }.distinct().sorted()
        return clients + listOfNotNull(duties.takeIf { it.isNotEmpty() }?.let { VacationTouch(null, it) })
    }

    /** Строки дела «Предупредить об отпуске», которые ещё нужны: не предупреждён и отпуск не кончился. */
    fun open(notifiedAt: Long?, to: LocalDate, today: LocalDate): Boolean = notifiedAt == null && to >= today
}
