package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

/** Тренировки и дежурства, участие клиентов, результаты, стартовые тренировки (ТЗ 3.5). */
@Dao
interface TrainingDao {
    @Upsert
    suspend fun upsertTraining(item: TrainingEntity)

    /** Пачкой — для серий: тренировки на 8 недель вперёд (4.4). */
    @Upsert
    suspend fun upsertTrainings(items: List<TrainingEntity>)

    @Query("SELECT * FROM training WHERE id = :id")
    suspend fun training(id: String): TrainingEntity?

    /** Тренировки, которые начинаются в промежутке [from, to), по времени начала. Отменённые тоже. */
    @Query(
        """
        SELECT * FROM training
        WHERE start >= :from AND start < :to AND deletedAt IS NULL
        ORDER BY start
        """
    )
    suspend fun trainingsBetween(from: LocalDateTime, to: LocalDateTime): List<TrainingEntity>

    /** То же для экранов: список обновляется сам при каждом изменении. */
    @Query(
        """
        SELECT * FROM training
        WHERE start >= :from AND start < :to AND deletedAt IS NULL
        ORDER BY start
        """
    )
    fun observeTrainingsBetween(from: LocalDateTime, to: LocalDateTime): Flow<List<TrainingEntity>>

    @Query("SELECT * FROM training WHERE seriesId = :seriesId AND deletedAt IS NULL ORDER BY start")
    suspend fun trainingsOfSeries(seriesId: String): List<TrainingEntity>

    @Upsert
    suspend fun upsertParticipation(item: ParticipationEntity)

    @Upsert
    suspend fun upsertParticipations(items: List<ParticipationEntity>)

    @Query("SELECT * FROM participation WHERE id = :id")
    suspend fun participation(id: String): ParticipationEntity?

    @Query("SELECT * FROM participation WHERE trainingId = :trainingId AND deletedAt IS NULL")
    suspend fun participationsOf(trainingId: String): List<ParticipationEntity>

    @Upsert
    suspend fun upsertResult(item: ResultEntity)

    @Query("SELECT * FROM result WHERE clientId = :clientId AND deletedAt IS NULL ORDER BY date")
    suspend fun resultsOf(clientId: String): List<ResultEntity>

    @Upsert
    suspend fun upsertSptCase(item: SptCaseEntity)

    @Query("SELECT * FROM spt_case WHERE clientId = :clientId AND deletedAt IS NULL ORDER BY sptDate")
    suspend fun sptCasesOf(clientId: String): List<SptCaseEntity>
}
