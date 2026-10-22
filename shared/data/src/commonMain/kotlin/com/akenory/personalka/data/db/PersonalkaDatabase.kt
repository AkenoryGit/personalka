package com.akenory.personalka.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.ColumnTypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.data.db.dao.ClientDao
import com.akenory.personalka.data.db.dao.CoachDao
import com.akenory.personalka.data.db.dao.ReferenceDao
import com.akenory.personalka.data.db.dao.ScheduleDao
import com.akenory.personalka.data.db.dao.TrainingDao
import com.akenory.personalka.data.db.entity.CategoryVersionEntity
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ClientRelationEntity
import com.akenory.personalka.data.db.entity.ClubRateGridEntity
import com.akenory.personalka.data.db.entity.CoachEntity
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.PaySchemeVersionEntity
import com.akenory.personalka.data.db.entity.RateVersionEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.SeriesClientEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TierSchemeVersionEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import kotlin.coroutines.CoroutineContext

/**
 * База телефона (ТЗ 3.1–3.5): полная копия данных тренера в обоих режимах работы.
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
    ],
    // Число, а не PersonalkaDatabase.VERSION: KSP не разбирает ссылку класса на свою же константу.
    // VERSION ниже должна совпадать — это проверяет тест.
    version = 1,
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

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "personalka.db"
    }
}

/** Код создания базы для каждой платформы пишет Room. */
@Suppress("KotlinNoActualForExpect")
expect object PersonalkaDatabaseConstructor : RoomDatabaseConstructor<PersonalkaDatabase> {
    override fun initialize(): PersonalkaDatabase
}

/**
 * Общие настройки базы на всех платформах: SQLite, собранный вместе с приложением (одна и та же версия
 * на любом телефоне и на компьютере), запросы — в [queryContext].
 */
fun RoomDatabase.Builder<PersonalkaDatabase>.buildPersonalka(queryContext: CoroutineContext): PersonalkaDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .build()
