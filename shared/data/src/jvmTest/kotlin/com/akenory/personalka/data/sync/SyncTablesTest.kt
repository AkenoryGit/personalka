package com.akenory.personalka.data.sync

import androidx.room3.deferredTransaction
import androidx.room3.useReaderConnection
import com.akenory.personalka.data.backup.TABLES
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.domain.sync.SyncRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Сервер (2.9) знает таблицы и версию схемы телефона только из [SyncRules] — здесь они сверяются с базой. */
class SyncTablesTest {

    @Test
    fun syncRulesKnowEveryTableAndTheSchemaVersion(): Unit = runBlocking {
        assertEquals(PersonalkaDatabase.VERSION, SyncRules.SCHEMA_VERSION, "Новая версия базы — поднять SyncRules.SCHEMA_VERSION")
        val db = inMemoryPersonalkaDatabase()
        try {
            val tables = db.useReaderConnection { connection ->
                connection.deferredTransaction { usePrepared(TABLES) { s -> buildSet { while (s.step()) add(s.getText(0)) } } }
            }
            assertEquals(tables, SyncRules.ENTITIES, "Новая таблица — добавить в SyncRules.ENTITIES (или решить, что она только для телефона)")
        } finally {
            db.close()
        }
    }
}
