package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.report1c.Status1c
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Услуга 1С → тип и место (ServiceMapping1C, ТЗ 3.7; шаг 4.2а, версия 11 базы): спрашивается один раз. Две записи
 * одного названия (два телефона) — читается последняя изменённая.
 */
@Entity(tableName = "service_mapping_1c", indices = [Index("name1c")])
data class ServiceMapping1cEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    /** «Наименование услуги», как его пишет 1С. */
    val name1c: String,
    val typeId: String,
    val locationId: String,
)

/** Имя из 1С → клиент (ClientAlias1C, ТЗ 3.7): подтверждённая тренером пара. [nameKey] — `Names1c.key`. */
@Entity(tableName = "client_alias_1c", indices = [Index("nameKey")])
data class ClientAlias1cEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val nameKey: String,
    val clientId: String,
)

/**
 * Сверка с отчётом 1С (Reconciliation, ТЗ 3.7) — одна на период отчёта: повторная загрузка того же периода обновляет
 * её и её пункты (8.4). [checkedUntil] — до какого времени сверялись тренировки приложения, [matched] — сколько сошлось.
 */
@Entity(tableName = "reconciliation", indices = [Index("periodFrom", "periodTo")])
data class ReconciliationEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val fileName: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    /** Время выгрузки из имени файла. */
    val generatedAt: LocalDateTime? = null,
    val coachName1c: String? = null,
    val rowsTotal: Int,
    val matched: Int,
    val checkedUntil: LocalDateTime,
)

/**
 * Пункт сверки (ReconciliationItem, ТЗ 3.7). [itemKey] — `Diff1c.key`: по нему повторная сверка узнаёт тот же пункт.
 * Строка 1С — колонками `row*` (у «Только в приложении» их нет), участие — [trainingId] и [clientId].
 */
@Entity(tableName = "reconciliation_item", indices = [Index("reconciliationId"), Index("resolution")])
data class ReconciliationItemEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val reconciliationId: String,
    val kind: ReconcileItemKind,
    val itemKey: String,
    /** Время строки 1С, а у «Только в приложении» — начало тренировки: порядок в списках. */
    val at: LocalDateTime,
    val trainingId: String? = null,
    val clientId: String? = null,
    val statusDiffers: Boolean = false,
    val serviceDiffers: Boolean = false,
    val rowAt: LocalDateTime? = null,
    val rowServiceName: String? = null,
    val rowClient: String? = null,
    val rowStatus: Status1c? = null,
    val resolution: ReconcileResolution = ReconcileResolution.OPEN,
    /** Когда решили; у «Написал администратору» — от него считаются 3 дня до напоминания (8.4). */
    val resolvedAt: Long? = null,
)
