package com.akenory.personalka.data.db

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.data.db.dao.ClientDao
import com.akenory.personalka.data.db.dao.CoachDao
import com.akenory.personalka.data.db.dao.MoneyDao
import com.akenory.personalka.data.db.dao.ReferenceDao
import com.akenory.personalka.data.db.dao.Report1cDao
import com.akenory.personalka.data.db.dao.ScheduleDao
import com.akenory.personalka.data.db.dao.SyncDao
import com.akenory.personalka.data.db.dao.TrainingDao
import com.akenory.personalka.data.db.entity.ArchivedMonthEntity
import com.akenory.personalka.data.db.entity.CategoryVersionEntity
import com.akenory.personalka.data.db.entity.ClientAlias1cEntity
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ClientRelationEntity
import com.akenory.personalka.data.db.entity.ClubRateGridEntity
import com.akenory.personalka.data.db.entity.CoachEntity
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.ColleagueEntity
import com.akenory.personalka.data.db.entity.ColleagueSchemeVersionEntity
import com.akenory.personalka.data.db.entity.ColleagueSettlementEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.MonthAdjustmentEntity
import com.akenory.personalka.data.db.entity.MonthRecordEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.PaySchemeVersionEntity
import com.akenory.personalka.data.db.entity.RateVersionEntity
import com.akenory.personalka.data.db.entity.ReconciliationEntity
import com.akenory.personalka.data.db.entity.ReconciliationItemEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.SeriesClientEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.ServiceMapping1cEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.SyncOutboxEntity
import com.akenory.personalka.data.db.entity.TierSchemeVersionEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.db.entity.VacationNoticeEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.data.sync.SyncOutbox
import com.akenory.personalka.data.sync.pooled
import kotlin.coroutines.CoroutineContext

/**
 * База телефона (ТЗ 3.1–3.6): полная копия данных тренера в обоих режимах работы.
 *
 * Связи между таблицами — по id без внешних ключей SQLite: при синхронизации записи приходят
 * в любом порядке, а «Удалить клиента полностью» стирает данные сразу. Целостность держит код,
 * который пишет записи.
 *
 * Любое изменение таблиц после того, как приложение начнёт открывать базу на телефоне, —
 * только новой версией с миграцией; схемы версий лежат в `shared/data/schemas`.
 */
@Database(
    entities = [
        // 3.2
        CoachEntity::class,
        CoachSettingsEntity::class,
        CategoryVersionEntity::class,
        PaySchemeVersionEntity::class,
        // 3.3
        LocationEntity::class,
        TrainingTypeEntity::class,
        RateVersionEntity::class,
        ClubRateGridEntity::class,
        TierSchemeVersionEntity::class,
        // 3.4
        ScheduleBlockEntity::class,
        SeriesEntity::class,
        SeriesClientEntity::class,
        // 3.4, шаг 3.12
        VacationNoticeEntity::class,
        // 3.5
        ClientEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        TrainingEntity::class,
        ParticipationEntity::class,
        ResultEntity::class,
        SptCaseEntity::class,
        WaitEntryEntity::class,
        ClientRelationEntity::class,
        // 3.6
        ColleagueEntity::class,
        ColleagueSchemeVersionEntity::class,
        MonthRecordEntity::class,
        MonthAdjustmentEntity::class,
        ArchivedMonthEntity::class,
        ColleagueSettlementEntity::class,
        // 3.7, шаг 4.2а
        ServiceMapping1cEntity::class,
        ClientAlias1cEntity::class,
        ReconciliationEntity::class,
        ReconciliationItemEntity::class,
        // 3.8 — только для телефона
        SyncOutboxEntity::class,
    ],
    // Число, а не PersonalkaDatabase.VERSION: KSP не разбирает ссылку класса на свою же константу.
    // VERSION ниже должна совпадать — это проверяет тест.
    version = 13,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
@ConstructedBy(PersonalkaDatabaseConstructor::class)
abstract class PersonalkaDatabase : RoomDatabase() {
    abstract fun coachDao(): CoachDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun clientDao(): ClientDao
    abstract fun trainingDao(): TrainingDao
    abstract fun syncDao(): SyncDao
    abstract fun moneyDao(): MoneyDao
    abstract fun report1cDao(): Report1cDao

    companion object {
        /**
         * 1 — шаг 1.1; 2 — шаг 1.13в: цвет у места, оттенок у типа ([Migration1To2]); 3 — «За кого» у дежурства
         * ([Migration2To3]); 4 — очередь изменений для синхронизации ([Migration3To4], шаг 2.10); 5 — деньги: коллеги,
         * месяцы, доплаты, итоги прошлых месяцев и услуга сетки у ставки ([Migration4To5], шаг 3.1); 6 — отметки
         * «Рассчитались» с коллегами ([Migration5To6], шаг 3.8); 7 — «Предупредить об отпуске» ([Migration6To7], шаг 3.12); 8 — «Дежурства сверены» ([Migration7To8], шаг 3.9в);
         * 9 — свой цвет дежурства ([Migration8To9], шаг 3.9е); 10 — «Убрать из списка» «Без записи» ([Migration9To10], шаг 3.9н);
         * 11 — сверка с 1С: услуги, имена, сверки и пункты ([Migration10To11], шаг 4.2а); 12 — «Оставить» у места
         * в составе группы ([Migration11To12], шаг 3.9у); 13 — «Напомнить с» у листа ожидания ([Migration12To13], шаг 3.9х).
         * Перед переходом на новую версию — копия ([BeforeMigration], шаг 2.7б).
         */
        const val VERSION = 13
        const val FILE_NAME = "personalka.db"
    }
}

/** Код создания базы для каждой платформы пишет Room. */
@Suppress("KotlinNoActualForExpect")
expect object PersonalkaDatabaseConstructor : RoomDatabaseConstructor<PersonalkaDatabase> {
    override fun initialize(): PersonalkaDatabase
}

/**
 * Общие настройки базы на всех платформах: переходы со старых версий ([PersonalkaMigrations]) — перед ними
 * копия ([beforeMigration], 2.7б), SQLite, собранный вместе с приложением (одна и та же версия на любом телефоне
 * и на компьютере), запросы — в [queryContext]; при каждом открытии — триггеры очереди изменений ([SyncOutbox], 2.10).
 */
fun RoomDatabase.Builder<PersonalkaDatabase>.buildPersonalka(
    queryContext: CoroutineContext,
    beforeMigration: BeforeMigration? = null,
): PersonalkaDatabase =
    addMigrations(*migrationsWithCopy(beforeMigration))
        .addCallback(
            object : RoomDatabase.Callback() {
                override suspend fun onOpen(connection: SQLiteConnection) = SyncOutbox.install(connection.pooled())
            },
        )
        .setQueryCoroutineContext(queryContext)
        .setDriver(BundledSQLiteDriver())
        .build()
