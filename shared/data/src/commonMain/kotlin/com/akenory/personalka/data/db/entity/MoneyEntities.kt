package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// ТЗ 3.6. Деньги — копейки, длительности — минуты, месяц — «2026-10» (ТЗ 3.1). Таблицы — версия 5 базы (шаг 3.1).

/** Коллега: с кем тренер ведёт группы и кто его заменяет (ТЗ 4.8). */
@Entity(tableName = "colleague", indices = [Index("coachId")])
data class ColleagueEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    /** Как тренер зовёт коллегу в расчёте: «Лена». */
    val name: String,
    /** Если коллега тоже в приложении на том же сервере. */
    val linkedCoachId: String? = null,
    val archived: Boolean = false,
)

/**
 * Схема выплат коллеги, версия с 1-го числа месяца: только вид — для доли налога владельца группы (ТЗ 4.8).
 * Доля официальной части — `Versions.officialShareBp`.
 */
@Entity(tableName = "colleague_scheme_version", indices = [Index("colleagueId", "validFrom")])
data class ColleagueSchemeVersionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val colleagueId: String,
    val validFrom: LocalDate,
    val kind: PaySchemeKind,
)

/**
 * Данные месяца. Одна запись на месяц, но индекс не уникальный: два телефона без связи могут завести запись одного
 * месяца каждый, и синхронизация не должна на этом падать — читается последняя изменённая (`MoneyDao.monthRecord`).
 */
@Entity(tableName = "month_record", indices = [Index("month")])
data class MonthRecordEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val month: YearMonth,
    /** План на месяц, копейки. */
    val planAmount: Long? = null,
    /** Фактически полученный аванс, копейки. */
    val advanceActual: Long? = null,
    /** Месяц сверен и закрыт: правки его тренировок спрашивают подтверждение (ТЗ 4.1). */
    val closed: Boolean = false,
    /** «Дежурства сверены» (ТЗ 5.14): часы проведённых дежурств в минутах на момент отметки; `null` — не сверены. С версии 8. */
    val dutiesCheckedMin: Int? = null,
)

/** Доплата (больше нуля) или удержание (меньше нуля) за месяц. */
@Entity(tableName = "month_adjustment", indices = [Index("month")])
data class MonthAdjustmentEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val month: YearMonth,
    /** Копейки, может быть отрицательной. */
    val amount: Long,
    val comment: String,
    /** Входит ли в начисление клуба и облагается налогом. */
    val inClubAccrual: Boolean = true,
)

/**
 * Отметка «Рассчитались» с коллегой за месяц (ТЗ 4.11, 5.12; шаг 3.8, версия 6 базы): [amount] — итог взаиморасчёта,
 * на котором отметили (больше нуля — коллега отдал тренеру). Расчёт потом изменился — экран покажет разницу. Снять
 * отметку — мягкое удаление; две записи одного месяца и коллеги (два телефона) — читается последняя изменённая.
 */
@Entity(tableName = "colleague_settlement", indices = [Index("month")])
data class ColleagueSettlementEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val month: YearMonth,
    val colleagueId: String,
    /** Копейки. */
    val amount: Long,
)

/**
 * Итоги месяца до работы в приложении (только чтение): для годовой сводки. Импорт отменён 23.10.2026 (ТЗ 9) —
 * таблица остаётся пустой, пока итоги не внесёт сам тренер.
 */
@Entity(tableName = "archived_month", indices = [Index("month")])
data class ArchivedMonthEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val month: YearMonth,
    /** Копейки. */
    val earned: Long,
    val tax: Long,
    val received: Long,
    val plan: Long? = null,
    val ptCount: Int,
    val splitCount: Int,
    val groupChildren: Int,
    /** Часы дежурств — в минутах (ТЗ 3.1). */
    val dutyMinutes: Int,
)
