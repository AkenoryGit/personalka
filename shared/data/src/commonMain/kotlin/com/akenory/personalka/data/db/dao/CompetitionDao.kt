package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.CompetitionEntity
import com.akenory.personalka.data.db.entity.CompetitionEntryEntity
import com.akenory.personalka.data.db.entity.CompetitionStartEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import kotlinx.coroutines.flow.Flow

/** Соревнования (ТЗ 5.21, шаг 5.38): соревнования, участники и старты. */
@Dao
interface CompetitionDao {
    @Query("SELECT * FROM competition WHERE deletedAt IS NULL ORDER BY date, name")
    fun observeCompetitions(): Flow<List<CompetitionEntity>>

    @Query("SELECT * FROM competition_entry WHERE deletedAt IS NULL")
    fun observeEntries(): Flow<List<CompetitionEntryEntity>>

    @Query("SELECT * FROM competition_start WHERE deletedAt IS NULL ORDER BY id")
    fun observeStarts(): Flow<List<CompetitionStartEntity>>

    /** Результаты всех клиентов — для заявочного времени и итогов. */
    @Query("SELECT * FROM result WHERE deletedAt IS NULL ORDER BY date, id")
    fun observeResults(): Flow<List<ResultEntity>>

    @Query("SELECT * FROM competition WHERE id = :id")
    suspend fun competition(id: String): CompetitionEntity?

    @Upsert
    suspend fun upsertCompetition(item: CompetitionEntity)

    @Query("SELECT * FROM competition_entry WHERE id = :id")
    suspend fun entry(id: String): CompetitionEntryEntity?

    /** Участники соревнования, и удалённые: повторное добавление клиента оживляет прежнюю запись. */
    @Query("SELECT * FROM competition_entry WHERE competitionId = :competitionId")
    suspend fun entryRowsOf(competitionId: String): List<CompetitionEntryEntity>

    @Query("SELECT * FROM competition_entry WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun entriesOfClient(clientId: String): List<CompetitionEntryEntity>

    @Upsert
    suspend fun upsertEntry(item: CompetitionEntryEntity)

    @Query("SELECT * FROM competition_start WHERE id = :id")
    suspend fun start(id: String): CompetitionStartEntity?

    @Query("SELECT * FROM competition_start WHERE entryId = :entryId")
    suspend fun startRowsOf(entryId: String): List<CompetitionStartEntity>

    /** Старты соревнования, и удалённые: «Отменить» после удаления соревнования возвращает их. */
    @Query("SELECT * FROM competition_start WHERE competitionId = :competitionId")
    suspend fun startRowsOfCompetition(competitionId: String): List<CompetitionStartEntity>

    @Upsert
    suspend fun upsertStart(item: CompetitionStartEntity)

    /** «Удалить клиента полностью» (3.1): участие и старты стираются сразу. */
    @Query("DELETE FROM competition_entry WHERE clientId = :clientId")
    suspend fun eraseEntriesOf(clientId: String)

    @Query("DELETE FROM competition_start WHERE clientId = :clientId")
    suspend fun eraseStartsOf(clientId: String)
}
