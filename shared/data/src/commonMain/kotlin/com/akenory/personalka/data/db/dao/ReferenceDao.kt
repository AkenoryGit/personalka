package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ClubRateGridEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.RateVersionEntity
import com.akenory.personalka.data.db.entity.TierSchemeVersionEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import kotlinx.coroutines.flow.Flow

/** Места, типы, ставки, сетка клуба и ступени (ТЗ 3.3). Правила выбора версии — в shared/domain. */
@Dao
interface ReferenceDao {
    @Upsert
    suspend fun upsertLocation(item: LocationEntity)

    @Query("SELECT * FROM location WHERE id = :id")
    suspend fun location(id: String): LocationEntity?

    /** Места, включая архивные (они нужны истории), в порядке тренера. */
    @Query("SELECT * FROM location WHERE deletedAt IS NULL ORDER BY sort, name")
    fun observeLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location WHERE deletedAt IS NULL ORDER BY sort, name")
    suspend fun locations(): List<LocationEntity>

    @Upsert
    suspend fun upsertTrainingType(item: TrainingTypeEntity)

    @Query("SELECT * FROM training_type WHERE id = :id")
    suspend fun trainingType(id: String): TrainingTypeEntity?

    /** Типы, включая архивные. */
    @Query("SELECT * FROM training_type WHERE deletedAt IS NULL")
    suspend fun trainingTypes(): List<TrainingTypeEntity>

    /** Типы, включая архивные, в порядке создания: первыми — типы первого запуска в порядке ТЗ 3.3. */
    @Query("SELECT * FROM training_type WHERE deletedAt IS NULL ORDER BY createdAt, id")
    fun observeTrainingTypes(): Flow<List<TrainingTypeEntity>>

    @Upsert
    suspend fun upsertRateVersion(item: RateVersionEntity)

    /** Все версии ставок типа (с местом и без) по дате начала. */
    @Query("SELECT * FROM rate_version WHERE typeId = :typeId AND deletedAt IS NULL ORDER BY validFrom")
    suspend fun rateVersions(typeId: String): List<RateVersionEntity>

    /** Все ставки по дате начала: ставка тренировки выбирается по типу и месту (`Versions.rateFor`, ТЗ 3.3). */
    @Query("SELECT * FROM rate_version WHERE deletedAt IS NULL ORDER BY validFrom")
    suspend fun allRateVersions(): List<RateVersionEntity>

    /** То же наблюдением: «Ставки и ступени» в настройках (5.16). */
    @Query("SELECT * FROM rate_version WHERE deletedAt IS NULL ORDER BY validFrom")
    fun observeRateVersions(): Flow<List<RateVersionEntity>>

    @Upsert
    suspend fun upsertClubRate(item: ClubRateGridEntity)

    @Query("SELECT * FROM club_rate_grid WHERE deletedAt IS NULL ORDER BY validFrom, category, service")
    suspend fun clubRates(): List<ClubRateGridEntity>

    @Upsert
    suspend fun upsertTierSchemeVersion(item: TierSchemeVersionEntity)

    @Query("SELECT * FROM tier_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    suspend fun tierSchemeVersions(): List<TierSchemeVersionEntity>

    @Query("SELECT * FROM tier_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    fun observeTierSchemeVersions(): Flow<List<TierSchemeVersionEntity>>
}
