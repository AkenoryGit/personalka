package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.Scope
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// ТЗ 3.4. Расписание. Дежурства — тренировки типа DUTY (таблица training), здесь только запреты.

/** Запрет: разовый (есть [date], [weekdays] пусто) или повторяющийся по дням недели в периоде. */
@Entity(tableName = "schedule_block", indices = [Index("locationId"), Index("coachId")])
data class ScheduleBlockEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val scope: Scope,
    val locationId: String,
    /** Текст предупреждения: «Групповая тренировка (Иванова)». */
    val reason: String,
    /** Дни недели повторяющегося; пусто — разовый. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** Дата разового. */
    val date: LocalDate? = null,
    val startTime: LocalTime,
    val endTime: LocalTime,
    /** Период действия повторения; у разового — его дата. */
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    /** Исключённые даты: «в эту субботу группы нет». */
    val skipDates: List<LocalDate> = emptyList(),
)

/** Постоянное расписание. Клиенты ПТ, сплита и СПТ — в [SeriesClientEntity], группа — в [groupId]. */
@Entity(tableName = "series", indices = [Index("coachId"), Index("groupId")])
data class SeriesEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val typeId: String,
    val locationId: String,
    val groupId: String? = null,
    val weekdays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val durationMin: Int,
    val validFrom: LocalDate,
    /** Пусто — бессрочно. */
    val validTo: LocalDate? = null,
    /** До какой даты уже созданы тренировки. */
    val generatedUntil: LocalDate,
    /** Даты, на которых серия не создаёт тренировку: пропущенные и занятые тренировкой «Только эту» (4.4). */
    val skipDates: List<LocalDate> = emptyList(),
)

/** Клиент серии. Пара «серия — клиент» одна. */
@Entity(
    tableName = "series_client",
    indices = [Index("seriesId", "clientId", unique = true), Index("clientId")],
)
data class SeriesClientEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val seriesId: String,
    val clientId: String,
)

/**
 * Кого предупредить об отпуске (ТЗ 5.16; шаг 3.12, версия 7 базы): строка на клиента, у которого отпуск забрал
 * тренировки серий, — его дни [dates]. «Предупредил» ставит [notifiedAt] (момент UTC); строка уходит из дела
 * «Предупредить об отпуске» и после последнего дня отпуска. «Отменить» после отпуска — мягкое удаление.
 */
@Entity(tableName = "vacation_notice", indices = [Index("vacationTo")])
data class VacationNoticeEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val vacationFrom: LocalDate,
    val vacationTo: LocalDate,
    val clientId: String,
    val dates: List<LocalDate>,
    val notifiedAt: Long? = null,
)
