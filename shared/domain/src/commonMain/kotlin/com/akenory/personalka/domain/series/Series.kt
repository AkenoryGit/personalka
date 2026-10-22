package com.akenory.personalka.domain.series

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ParticipantState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Что, где и когда повторяет серия (ТЗ 3.4). Дежурство — серия без клиентов и группы.
 */
data class SeriesPattern(
    val typeId: String,
    val locationId: String,
    val weekdays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val durationMin: Int,
    /** Клиенты ПТ, сплита и СПТ (`SeriesClient`); у группы и дежурства пусто. */
    val clientIds: List<String> = emptyList(),
    /** Группа — у групповой серии. */
    val groupId: String? = null,
)

/**
 * Серия для правил (ТЗ 3.4, `Series`). Слой данных собирает её из `SeriesEntity` и `SeriesClientEntity`
 * и записывает обратно поля, которые вернули правила.
 */
data class Series(
    val id: String,
    val pattern: SeriesPattern,
    val validFrom: LocalDate,
    /** Пусто — бессрочно. */
    val validTo: LocalDate? = null,
    /** До какой даты включительно тренировки уже созданы. */
    val generatedUntil: LocalDate,
    /**
     * Даты, которые серия не создаёт: пропущенные («Пропустить дату», «Отпуск») и даты тренировок,
     * изменённых «Только эту», — их место уже занято своей тренировкой.
     */
    val skipDates: Set<LocalDate> = emptySet(),
)

/**
 * Тренировка серии — то, что видят правила серий (ТЗ 3.5). Слой данных собирает её из `TrainingEntity`
 * и участий, а изменённую записывает обратно: время, длительность, тип, место, серию, `seriesDetached`,
 * план и состав участников. Статусы и причины отмены правила серий не меняют.
 */
data class SeriesTraining(
    val id: String,
    val seriesId: String?,
    val start: LocalDateTime,
    val durationMin: Int,
    val typeId: String,
    val locationId: String,
    val groupId: String? = null,
    /** `series_detached`: изменена «Только эту», правка серии её не перезаписывает. */
    val detached: Boolean = false,
    val status: TrainingStatus = TrainingStatus.PLANNED,
    val plan: String? = null,
    /** Записан факт или поведение: о занятии уже есть запись — серия такую тренировку не трогает. */
    val diaryWritten: Boolean = false,
    val participants: List<ParticipantState> = emptyList(),
) {
    val date: LocalDate get() = start.date

    val hasPlan: Boolean get() = !plan.isNullOrBlank()

    /**
     * Можно ли серии менять или удалять эту тренировку: она ещё не началась, не проведена,
     * без записи о занятии и без списания. Остальное — прошлое или деньги (4.4: прошлое серия не меняет).
     */
    fun changeableAt(now: LocalDateTime): Boolean =
        start > now &&
            status != TrainingStatus.DONE &&
            !diaryWritten &&
            participants.none { it.status == ParticipationStatus.CANCELLED_CHARGED }
}

/** Новая тренировка серии. Id даёт слой данных (`Ids.newId`); ожидаемые участники — со статусом EXPECTED. */
data class NewTraining(
    val seriesId: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val typeId: String,
    val locationId: String,
    val groupId: String? = null,
    val clientIds: List<String> = emptyList(),
    /** План, перенесённый с удалённой тренировки серии. */
    val plan: String? = null,
) {
    val date: LocalDate get() = start.date
}

/** Новые значения тренировки из формы (5.5): время с датой, длительность, тип, место, клиенты или группа. */
data class TrainingEdit(
    val start: LocalDateTime,
    val durationMin: Int,
    val typeId: String,
    val locationId: String,
    val clientIds: List<String> = emptyList(),
    val groupId: String? = null,
)

/**
 * Что записать в базу одной транзакцией. Правила ничего не сохраняют сами.
 *
 * - [series] — новые и изменённые серии целиком (с составом клиентов);
 * - [created] — новые тренировки, [updated] — изменённые целиком, [deleted] — удалённые;
 * - [pendingPlans] — планы удалённых тренировок, которым не нашлось следующей тренировки серии:
 *   их место — `pending_plan` клиента или группы (4.3).
 */
data class SeriesChanges(
    val series: List<Series> = emptyList(),
    val created: List<NewTraining> = emptyList(),
    val updated: List<SeriesTraining> = emptyList(),
    val deleted: List<SeriesTraining> = emptyList(),
    val pendingPlans: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = series.isEmpty() && created.isEmpty() && updated.isEmpty() && deleted.isEmpty() && pendingPlans.isEmpty()

    operator fun plus(other: SeriesChanges): SeriesChanges = SeriesChanges(
        series = series + other.series,
        created = created + other.created,
        updated = updated + other.updated,
        deleted = deleted + other.deleted,
        pendingPlans = pendingPlans + other.pendingPlans,
    )

    companion object {
        val NONE = SeriesChanges()
    }
}
