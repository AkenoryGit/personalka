package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.akenory.personalka.data.db.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

/** Очередь изменений (ТЗ 7.2). Пишут её триггеры (`SyncOutbox`), здесь — только чтение и уборка после отправки. */
@Dao
interface SyncDao {
    /** Сколько записей ждёт отправки — для индикатора и для обмена через 5 секунд после правки. */
    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePending(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pending(): Int

    /** Самая давняя правка в очереди — для пункта «Изменения не отправлены больше суток» (5.2). */
    @Query("SELECT MIN(updatedAt) FROM sync_outbox")
    fun observeOldest(): Flow<Long?>

    /** Следующая пачка: сначала давние правки. */
    @Query("SELECT * FROM sync_outbox ORDER BY updatedAt, entity, rowId LIMIT :limit")
    suspend fun next(limit: Int): List<SyncOutboxEntity>

    /** Отправленное убирается, только если запись не менялась после чтения: новая правка уйдёт следующей пачкой. */
    @Query("DELETE FROM sync_outbox WHERE entity = :entity AND rowId = :rowId AND updatedAt = :updatedAt")
    suspend fun sent(entity: String, rowId: String, updatedAt: Long)
}
