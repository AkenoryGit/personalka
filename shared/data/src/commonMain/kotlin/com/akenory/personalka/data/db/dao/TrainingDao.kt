package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

/** Ближайшая тренировка клиента — для строки списка (5.8). */
data class NextTraining(val clientId: String, val start: LocalDateTime)

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

    /** Тренировка для карточки (5.4): карточка обновляется сама после каждого действия. */
    @Query("SELECT * FROM training WHERE id = :id")
    fun observeTraining(id: String): Flow<TrainingEntity?>

    /**
     * Запланированные тренировки, начатые раньше [before]: закрытие в конце (4.1). Статус передаётся параметром:
     * Room хранит перечисления именами.
     */
    @Query("SELECT * FROM training WHERE status = :planned AND start < :before AND deletedAt IS NULL ORDER BY start")
    suspend fun plannedBefore(before: LocalDateTime, planned: TrainingStatus): List<TrainingEntity>

    /** То же для «Главной»: прошедшие без отметки — пункт «Нужно сделать» (5.2). */
    @Query("SELECT * FROM training WHERE status = :planned AND start < :before AND deletedAt IS NULL ORDER BY start")
    fun observePlannedBefore(before: LocalDateTime, planned: TrainingStatus): Flow<List<TrainingEntity>>

    /** Участие клиентов в них: отмечен ли в группе хоть один (4.1). */
    @Query(
        """
        SELECT p.* FROM participation AS p
        JOIN training AS t ON t.id = p.trainingId
        WHERE t.status = :planned AND t.start < :before AND p.deletedAt IS NULL AND t.deletedAt IS NULL
        """
    )
    fun observeParticipationsOfPlannedBefore(before: LocalDateTime, planned: TrainingStatus): Flow<List<ParticipationEntity>>

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

    /** Тренировки, которые начнутся позже [from], отменённые тоже: пересчёт флага «поверх запрета» (4.5). */
    @Query("SELECT * FROM training WHERE start > :from AND deletedAt IS NULL ORDER BY start")
    suspend fun trainingsAfter(from: LocalDateTime): List<TrainingEntity>

    /** Участие клиентов в тренировках, которые начинаются в промежутке [from, to): имена в блоках календаря (5.3). */
    @Query(
        """
        SELECT p.* FROM participation AS p
        JOIN training AS t ON t.id = p.trainingId
        WHERE t.start >= :from AND t.start < :to AND p.deletedAt IS NULL AND t.deletedAt IS NULL
        """
    )
    fun observeParticipationsBetween(from: LocalDateTime, to: LocalDateTime): Flow<List<ParticipationEntity>>

    @Query("SELECT * FROM training WHERE seriesId = :seriesId AND deletedAt IS NULL ORDER BY start")
    suspend fun trainingsOfSeries(seriesId: String): List<TrainingEntity>

    /** Тренировки серий с [from]: продление серий не ставит вторую на дату, где уже есть своя (4.4). */
    @Query("SELECT * FROM training WHERE seriesId IS NOT NULL AND start >= :from AND deletedAt IS NULL ORDER BY start")
    suspend fun seriesTrainingsFrom(from: LocalDateTime): List<TrainingEntity>

    /**
     * Последние созданные тренировки, новые первыми: в форме (5.5) последний тип — первым, место — последнее
     * для типа, недавние клиенты и группы.
     */
    @Query("SELECT * FROM training WHERE deletedAt IS NULL ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeLastCreated(limit: Int): Flow<List<TrainingEntity>>

    /** Участия в последних созданных тренировках: недавние клиенты над полем поиска (5.5). */
    @Query(
        """
        SELECT p.* FROM participation AS p
        JOIN training AS t ON t.id = p.trainingId
        WHERE p.deletedAt IS NULL AND t.deletedAt IS NULL
        ORDER BY t.createdAt DESC, t.id DESC, p.createdAt, p.id
        LIMIT :limit
        """
    )
    fun observeRecentParticipations(limit: Int): Flow<List<ParticipationEntity>>

    /** Тренировки группы по времени, отменённые тоже (5.10). */
    @Query("SELECT * FROM training WHERE groupId = :groupId AND deletedAt IS NULL ORDER BY start")
    suspend fun trainingsOfGroup(groupId: String): List<TrainingEntity>

    @Query("SELECT * FROM training WHERE groupId = :groupId AND deletedAt IS NULL ORDER BY start")
    fun observeTrainingsOfGroup(groupId: String): Flow<List<TrainingEntity>>

    /** Участие детей в тренировках группы: посещаемость в карточке группы. */
    @Query(
        """
        SELECT p.* FROM participation AS p
        JOIN training AS t ON t.id = p.trainingId
        WHERE t.groupId = :groupId AND p.deletedAt IS NULL AND t.deletedAt IS NULL
        """
    )
    fun observeParticipationsOfGroup(groupId: String): Flow<List<ParticipationEntity>>

    @Upsert
    suspend fun upsertParticipation(item: ParticipationEntity)

    @Upsert
    suspend fun upsertParticipations(items: List<ParticipationEntity>)

    @Query("SELECT * FROM participation WHERE id = :id")
    suspend fun participation(id: String): ParticipationEntity?

    @Query("SELECT * FROM participation WHERE trainingId = :trainingId AND deletedAt IS NULL")
    suspend fun participationsOf(trainingId: String): List<ParticipationEntity>

    @Query("SELECT * FROM participation WHERE trainingId = :trainingId AND deletedAt IS NULL")
    fun observeParticipationsOf(trainingId: String): Flow<List<ParticipationEntity>>

    /** Тренировки клиента по его участию, отменённые тоже, по времени: карточка клиента (5.9). */
    @Query(
        """
        SELECT t.* FROM training AS t
        JOIN participation AS p ON p.trainingId = t.id
        WHERE p.clientId = :clientId AND p.deletedAt IS NULL AND t.deletedAt IS NULL
        ORDER BY t.start
        """
    )
    fun observeTrainingsOfClient(clientId: String): Flow<List<TrainingEntity>>

    @Query("SELECT * FROM participation WHERE clientId = :clientId AND deletedAt IS NULL")
    fun observeParticipationsOfClient(clientId: String): Flow<List<ParticipationEntity>>

    /** Участия клиента, и удалённые: пара «тренировка — клиент» уникальна и с ними (объединение, удаление). */
    @Query("SELECT * FROM participation WHERE clientId = :clientId")
    suspend fun participationRowsOf(clientId: String): List<ParticipationEntity>

    @Query("SELECT * FROM participation WHERE trainingId = :trainingId AND clientId = :clientId")
    suspend fun participationRow(trainingId: String, clientId: String): ParticipationEntity?

    /** Участия тренировки, и удалённые: «Отменить» возвращает состав как был (5.20). */
    @Query("SELECT * FROM participation WHERE trainingId = :trainingId")
    suspend fun participationRowsOfTraining(trainingId: String): List<ParticipationEntity>

    @Query("DELETE FROM participation WHERE id = :id")
    suspend fun eraseParticipation(id: String)

    @Query("DELETE FROM training WHERE id = :id")
    suspend fun eraseTraining(id: String)

    /**
     * Ближайшая тренировка каждого клиента: начинается не раньше [from], не отменена, клиента ждут.
     * Статусы передаются параметрами: Room хранит перечисления именами.
     */
    @Query(
        """
        SELECT p.clientId AS clientId, MIN(t.start) AS start FROM participation AS p
        JOIN training AS t ON t.id = p.trainingId
        WHERE t.start >= :from AND t.status = :planned AND p.status = :expected
          AND p.deletedAt IS NULL AND t.deletedAt IS NULL
        GROUP BY p.clientId
        """
    )
    fun observeNextTrainings(from: LocalDateTime, planned: TrainingStatus, expected: ParticipationStatus): Flow<List<NextTraining>>

    @Upsert
    suspend fun upsertResult(item: ResultEntity)

    @Query("SELECT * FROM result WHERE clientId = :clientId AND deletedAt IS NULL ORDER BY date")
    suspend fun resultsOf(clientId: String): List<ResultEntity>

    @Query("DELETE FROM result WHERE clientId = :clientId")
    suspend fun eraseResultsOf(clientId: String)

    @Upsert
    suspend fun upsertSptCase(item: SptCaseEntity)

    @Query("SELECT * FROM spt_case WHERE clientId = :clientId AND deletedAt IS NULL ORDER BY sptDate")
    suspend fun sptCasesOf(clientId: String): List<SptCaseEntity>

    @Query("DELETE FROM spt_case WHERE clientId = :clientId")
    suspend fun eraseSptCasesOf(clientId: String)
}
