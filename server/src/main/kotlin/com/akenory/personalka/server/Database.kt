package com.akenory.personalka.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

/**
 * База сервера (ТЗ 7.4): PostgreSQL 16, база и роль `trener`. Миграции Flyway — при каждом запуске,
 * файлы `db/migration/V<N>__<что>.sql`; выложенную миграцию не правят — только новая следующим номером.
 */
class Database(
    val dataSource: HikariDataSource,
    /** Версия схемы после миграций при запуске — её показывает /health. */
    val schemaVersion: String?,
) : AutoCloseable {

    /** База отвечает — для /health: выкладка по нему решает, переключать версию или откатывать. */
    fun ping(): Boolean = runCatching {
        dataSource.connection.use { it.isValid(PING_SECONDS) }
    }.getOrDefault(false)

    override fun close() = dataSource.close()

    companion object {
        private const val PING_SECONDS = 2

        fun connect(config: ServerConfig): Database {
            val pool = HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.dbUser
                password = config.dbPassword
                // Один тренер и родители по ссылкам: малого пула хватает, а Java на VPS ограничена 384 МБ.
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 5_000
                poolName = "trener"
            }
            val dataSource = HikariDataSource(pool)
            try {
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                flyway.migrate()
                return Database(dataSource, flyway.info().current()?.version?.version)
            } catch (e: Exception) {
                dataSource.close()
                throw e
            }
        }
    }
}
