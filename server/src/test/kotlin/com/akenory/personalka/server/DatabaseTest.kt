package com.akenory.personalka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Миграции Flyway при запуске (ТЗ 7.4) на настоящем PostgreSQL. */
class DatabaseTest {

    @Test
    fun firstStartMigratesAndSecondChangesNothing() {
        val config = TestDb.fresh()
        val created = Database.connect(config).use { database ->
            assertEquals("3", database.schemaVersion)
            database.value("created_at")
        }
        Database.connect(config).use { database ->
            assertEquals("3", database.schemaVersion)
            assertEquals(created, database.value("created_at"))
            assertEquals(3, database.count("flyway_schema_history"))
        }
    }

    @Test
    fun pingFailsAfterClose() {
        val database = Database.connect(TestDb.fresh())
        assertTrue(database.ping())
        database.close()
        assertFalse(database.ping())
    }

    private fun Database.value(key: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT value FROM server_info WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { row -> row.next(); row.getString(1) }
        }
    }

    private fun Database.count(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { row -> row.next(); row.getInt(1) } }
    }
}
