package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.CategoryVersionEntity
import com.akenory.personalka.data.db.entity.CoachEntity
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.PaySchemeVersionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Тренер, настройки, категория и схема выплат (ТЗ 3.2).
 *
 * Общее для всех DAO: в телефоне лежат данные одного тренера и общие данные клуба, поэтому запросы
 * не отбирают по `coachId`. Чтение по id возвращает и удалённые записи (они нужны синхронизации),
 * списки — только неудалённые. Удаление — это `upsert` записи с [com.akenory.personalka.data.db.SyncFields.deleted].
 */
@Dao
interface CoachDao {
    @Upsert
    suspend fun upsertCoach(item: CoachEntity)

    @Query("SELECT * FROM coach WHERE id = :id")
    suspend fun coach(id: String): CoachEntity?

    /** Тренер этого телефона; пусто до первого запуска (5.18). */
    @Query("SELECT * FROM coach WHERE deletedAt IS NULL ORDER BY createdAt LIMIT 1")
    suspend fun currentCoach(): CoachEntity?

    /** То же наблюдением: приложение показывает первый запуск, пока тренера нет (5.18). */
    @Query("SELECT * FROM coach WHERE deletedAt IS NULL ORDER BY createdAt LIMIT 1")
    fun observeCurrentCoach(): Flow<CoachEntity?>

    @Upsert
    suspend fun upsertSettings(item: CoachSettingsEntity)

    @Query("SELECT * FROM coach_settings WHERE coachId = :coachId AND deletedAt IS NULL")
    suspend fun settings(coachId: String): CoachSettingsEntity?

    /** Настройки тренера телефона наблюдением: часы сетки календаря (5.3). */
    @Query("SELECT * FROM coach_settings WHERE deletedAt IS NULL ORDER BY createdAt LIMIT 1")
    fun observeSettings(): Flow<CoachSettingsEntity?>

    @Upsert
    suspend fun upsertCategoryVersion(item: CategoryVersionEntity)

    /** Версии категории по дате начала. */
    @Query("SELECT * FROM category_version WHERE deletedAt IS NULL ORDER BY validFrom")
    suspend fun categoryVersions(): List<CategoryVersionEntity>

    /** То же наблюдением: мастер «Настроить зарплату» и «Профиль» (5.18, 5.16). */
    @Query("SELECT * FROM category_version WHERE deletedAt IS NULL ORDER BY validFrom")
    fun observeCategoryVersions(): Flow<List<CategoryVersionEntity>>

    @Upsert
    suspend fun upsertPaySchemeVersion(item: PaySchemeVersionEntity)

    /** Версии схемы выплат по дате начала. */
    @Query("SELECT * FROM pay_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    suspend fun paySchemeVersions(): List<PaySchemeVersionEntity>

    @Query("SELECT * FROM pay_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    fun observePaySchemeVersions(): Flow<List<PaySchemeVersionEntity>>
}
