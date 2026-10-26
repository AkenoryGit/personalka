package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.SeriesClientEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.VacationNoticeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/** Запреты и серии (ТЗ 3.4). Какие даты попадают под запрет или серию, решает shared/domain (1.3, 1.4). */
@Dao
interface ScheduleDao {
    @Upsert
    suspend fun upsertBlock(item: ScheduleBlockEntity)

    @Query("SELECT * FROM schedule_block WHERE id = :id")
    suspend fun block(id: String): ScheduleBlockEntity?

    @Query("SELECT * FROM schedule_block WHERE locationId = :locationId AND deletedAt IS NULL")
    suspend fun blocksAt(locationId: String): List<ScheduleBlockEntity>

    /** Действующие запреты, свои и клуба: штриховка календаря (5.3). */
    @Query("SELECT * FROM schedule_block WHERE deletedAt IS NULL")
    fun observeBlocks(): Flow<List<ScheduleBlockEntity>>

    /** То же разово: пересчёт флага «поверх запрета» (4.5). */
    @Query("SELECT * FROM schedule_block WHERE deletedAt IS NULL")
    suspend fun blocks(): List<ScheduleBlockEntity>

    @Upsert
    suspend fun upsertSeries(item: SeriesEntity)

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun series(id: String): SeriesEntity?

    @Upsert
    suspend fun upsertSeriesClient(item: SeriesClientEntity)

    @Query("SELECT * FROM series_client WHERE seriesId = :seriesId AND deletedAt IS NULL")
    suspend fun seriesClients(seriesId: String): List<SeriesClientEntity>

    /** Серии, в которых есть клиент. */
    @Query(
        """
        SELECT s.* FROM series AS s
        JOIN series_client AS sc ON sc.seriesId = s.id
        WHERE sc.clientId = :clientId AND sc.deletedAt IS NULL AND s.deletedAt IS NULL
        ORDER BY s.validFrom
        """
    )
    suspend fun seriesOfClient(clientId: String): List<SeriesEntity>

    /** То же с обновлениями: вкладка «Расписание» карточки клиента (5.9). */
    @Query(
        """
        SELECT s.* FROM series AS s
        JOIN series_client AS sc ON sc.seriesId = s.id
        WHERE sc.clientId = :clientId AND sc.deletedAt IS NULL AND s.deletedAt IS NULL
        ORDER BY s.validFrom
        """
    )
    fun observeSeriesOfClient(clientId: String): Flow<List<SeriesEntity>>

    /** Серии, которые идут на [date] или начнутся позже: постоянное расписание в списке клиентов (5.8). */
    @Query("SELECT * FROM series WHERE deletedAt IS NULL AND (validTo IS NULL OR validTo >= :date)")
    fun observeSeriesFrom(date: LocalDate): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series_client WHERE deletedAt IS NULL")
    fun observeSeriesClients(): Flow<List<SeriesClientEntity>>

    /** То же разово: продление серий при открытии приложения (4.4). */
    @Query("SELECT * FROM series WHERE deletedAt IS NULL AND (validTo IS NULL OR validTo >= :date)")
    suspend fun seriesFrom(date: LocalDate): List<SeriesEntity>

    @Query("SELECT * FROM series_client WHERE deletedAt IS NULL")
    suspend fun allSeriesClients(): List<SeriesClientEntity>

    /** Клиенты серии, и удалённые: пара «серия — клиент» уникальна и с ними; «Отменить» возвращает состав. */
    @Query("SELECT * FROM series_client WHERE seriesId = :seriesId")
    suspend fun seriesClientRowsOfSeries(seriesId: String): List<SeriesClientEntity>

    /** Записи «серия — клиент» клиента, и удалённые: пара уникальна и с ними (объединение дублей). */
    @Query("SELECT * FROM series_client WHERE clientId = :clientId")
    suspend fun seriesClientRowsOf(clientId: String): List<SeriesClientEntity>

    @Query("SELECT * FROM series_client WHERE seriesId = :seriesId AND clientId = :clientId")
    suspend fun seriesClientRow(seriesId: String, clientId: String): SeriesClientEntity?

    @Query("SELECT COUNT(*) FROM series WHERE groupId = :groupId AND deletedAt IS NULL")
    suspend fun seriesCountOfGroup(groupId: String): Int

    @Query("DELETE FROM series_client WHERE clientId = :clientId")
    suspend fun eraseSeriesClientsOf(clientId: String)

    @Upsert
    suspend fun upsertVacationNotice(item: VacationNoticeEntity)

    @Query("SELECT * FROM vacation_notice WHERE id = :id")
    suspend fun vacationNotice(id: String): VacationNoticeEntity?

    /** Строки «Предупредить об отпуске», которые ещё нужны: не предупреждён и отпуск не кончился до [today]. */
    @Query(
        "SELECT * FROM vacation_notice WHERE deletedAt IS NULL AND notifiedAt IS NULL AND vacationTo >= :today " +
            "ORDER BY vacationFrom, createdAt, id",
    )
    fun observeOpenVacationNotices(today: LocalDate): Flow<List<VacationNoticeEntity>>
}
