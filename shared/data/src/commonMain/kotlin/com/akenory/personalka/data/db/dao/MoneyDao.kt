package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ArchivedMonthEntity
import com.akenory.personalka.data.db.entity.ColleagueEntity
import com.akenory.personalka.data.db.entity.ColleagueSchemeVersionEntity
import com.akenory.personalka.data.db.entity.ColleagueSettlementEntity
import com.akenory.personalka.data.db.entity.MonthAdjustmentEntity
import com.akenory.personalka.data.db.entity.MonthRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.YearMonth

/** Деньги (ТЗ 3.6): коллеги и их схемы, данные месяцев, доплаты и удержания, итоги прошлых месяцев. */
@Dao
interface MoneyDao {
    @Upsert
    suspend fun upsertColleague(item: ColleagueEntity)

    @Query("SELECT * FROM colleague WHERE id = :id")
    suspend fun colleague(id: String): ColleagueEntity?

    /** Коллеги, в том числе в архиве: их имена нужны в расчётах прошлых месяцев. Порядок по алфавиту — в коде. */
    @Query("SELECT * FROM colleague WHERE deletedAt IS NULL")
    fun observeColleagues(): Flow<List<ColleagueEntity>>

    @Query("SELECT * FROM colleague WHERE deletedAt IS NULL")
    suspend fun colleagues(): List<ColleagueEntity>

    @Upsert
    suspend fun upsertColleagueScheme(item: ColleagueSchemeVersionEntity)

    /** Версии схем всех коллег по дате начала. */
    @Query("SELECT * FROM colleague_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    suspend fun colleagueSchemes(): List<ColleagueSchemeVersionEntity>

    @Query("SELECT * FROM colleague_scheme_version WHERE deletedAt IS NULL ORDER BY validFrom")
    fun observeColleagueSchemes(): Flow<List<ColleagueSchemeVersionEntity>>

    @Upsert
    suspend fun upsertMonthRecord(item: MonthRecordEntity)

    /** Данные месяца: при двух записях одного месяца (с двух телефонов) — последняя изменённая. */
    @Query("SELECT * FROM month_record WHERE month = :month AND deletedAt IS NULL ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun monthRecord(month: YearMonth): MonthRecordEntity?

    @Query("SELECT * FROM month_record WHERE month = :month AND deletedAt IS NULL ORDER BY updatedAt DESC, id DESC LIMIT 1")
    fun observeMonthRecord(month: YearMonth): Flow<MonthRecordEntity?>

    /** Все месяцы по порядку (год по месяцам, 5.12). */
    @Query("SELECT * FROM month_record WHERE deletedAt IS NULL ORDER BY month, updatedAt")
    suspend fun monthRecords(): List<MonthRecordEntity>

    @Query("SELECT * FROM month_record WHERE deletedAt IS NULL ORDER BY month, updatedAt")
    fun observeMonthRecords(): Flow<List<MonthRecordEntity>>

    @Query("SELECT * FROM month_adjustment WHERE id = :id")
    suspend fun adjustment(id: String): MonthAdjustmentEntity?

    @Upsert
    suspend fun upsertAdjustment(item: MonthAdjustmentEntity)

    /** Доплаты и удержания месяца в порядке внесения. */
    @Query("SELECT * FROM month_adjustment WHERE month = :month AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun adjustments(month: YearMonth): List<MonthAdjustmentEntity>

    @Query("SELECT * FROM month_adjustment WHERE month = :month AND deletedAt IS NULL ORDER BY createdAt, id")
    fun observeAdjustments(month: YearMonth): Flow<List<MonthAdjustmentEntity>>

    @Upsert
    suspend fun upsertSettlement(item: ColleagueSettlementEntity)

    /** Отметка «Рассчитались» с коллегой за месяц: при двух записях — последняя изменённая. */
    @Query(
        "SELECT * FROM colleague_settlement WHERE month = :month AND colleagueId = :colleagueId AND deletedAt IS NULL " +
            "ORDER BY updatedAt DESC, id DESC LIMIT 1",
    )
    suspend fun settlement(month: YearMonth, colleagueId: String): ColleagueSettlementEntity?

    /** Отметки «Рассчитались» месяца по возрастанию изменения: у коллеги с двумя записями решает последняя. */
    @Query("SELECT * FROM colleague_settlement WHERE month = :month AND deletedAt IS NULL ORDER BY updatedAt, id")
    fun observeSettlements(month: YearMonth): Flow<List<ColleagueSettlementEntity>>

    @Upsert
    suspend fun upsertArchivedMonth(item: ArchivedMonthEntity)

    /** Итоги месяцев до работы в приложении, по порядку. */
    @Query("SELECT * FROM archived_month WHERE deletedAt IS NULL ORDER BY month")
    suspend fun archivedMonths(): List<ArchivedMonthEntity>
}
