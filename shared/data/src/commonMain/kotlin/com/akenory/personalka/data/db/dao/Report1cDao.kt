package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ClientAlias1cEntity
import com.akenory.personalka.data.db.entity.ReconciliationEntity
import com.akenory.personalka.data.db.entity.ReconciliationItemEntity
import com.akenory.personalka.data.db.entity.ServiceMapping1cEntity
import com.akenory.personalka.domain.model.ReconcileResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/** Сверка с 1С (ТЗ 3.7, 8): услуги, имена, сверки и их пункты. */
@Dao
interface Report1cDao {
    @Upsert
    suspend fun upsertService(item: ServiceMapping1cEntity)

    /** Все услуги; одно название дважды — последняя изменённая идёт последней и перекрывает. */
    @Query("SELECT * FROM service_mapping_1c WHERE deletedAt IS NULL ORDER BY updatedAt, id")
    suspend fun services(): List<ServiceMapping1cEntity>

    @Upsert
    suspend fun upsertAlias(item: ClientAlias1cEntity)

    @Query("SELECT * FROM client_alias_1c WHERE deletedAt IS NULL ORDER BY updatedAt, id")
    suspend fun aliases(): List<ClientAlias1cEntity>

    @Upsert
    suspend fun upsertReconciliation(item: ReconciliationEntity)

    @Query("SELECT * FROM reconciliation WHERE id = :id")
    suspend fun reconciliation(id: String): ReconciliationEntity?

    @Query("SELECT * FROM reconciliation WHERE id = :id")
    fun observeReconciliation(id: String): Flow<ReconciliationEntity?>

    /** Сверка периода — последняя изменённая, если с двух телефонов пришли две. */
    @Query(
        "SELECT * FROM reconciliation WHERE periodFrom = :from AND periodTo = :to AND deletedAt IS NULL " +
            "ORDER BY updatedAt DESC, id DESC LIMIT 1",
    )
    suspend fun reconciliationOf(from: LocalDate, to: LocalDate): ReconciliationEntity?

    /** История для карточки «Отчёт 1С»: новые периоды первыми. */
    @Query("SELECT * FROM reconciliation WHERE deletedAt IS NULL ORDER BY periodTo DESC, periodFrom DESC, updatedAt DESC")
    fun observeReconciliations(): Flow<List<ReconciliationEntity>>

    @Upsert
    suspend fun upsertItems(items: List<ReconciliationItemEntity>)

    @Query("SELECT * FROM reconciliation_item WHERE id = :id")
    suspend fun item(id: String): ReconciliationItemEntity?

    @Query("SELECT * FROM reconciliation_item WHERE reconciliationId = :id AND deletedAt IS NULL")
    suspend fun items(id: String): List<ReconciliationItemEntity>

    @Query("SELECT * FROM reconciliation_item WHERE reconciliationId = :id AND deletedAt IS NULL ORDER BY at, itemKey")
    fun observeItems(id: String): Flow<List<ReconciliationItemEntity>>

    /** Пункты с решением [open] и [asked] всех сверок — «Нужно сделать» (5.2) и напоминание через 3 дня (8.4). */
    @Query("SELECT * FROM reconciliation_item WHERE resolution IN (:open, :asked) AND deletedAt IS NULL")
    fun observeUnresolved(open: ReconcileResolution, asked: ReconcileResolution): Flow<List<ReconciliationItemEntity>>
}
