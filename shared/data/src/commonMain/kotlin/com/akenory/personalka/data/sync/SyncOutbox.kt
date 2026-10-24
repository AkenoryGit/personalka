package com.akenory.personalka.data.sync

import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.akenory.personalka.data.backup.bind
import com.akenory.personalka.data.backup.value
import com.akenory.personalka.domain.sync.SyncRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Очередь изменений на уровне SQLite (ТЗ 7.2, п. 1): у каждой синхронизируемой таблицы — три триггера, которые
 * в той же транзакции, что правка, кладут запись в `sync_outbox`. Так в очередь попадает любая правка любого
 * репозитория — и `upsert`, и мягкое удаление, и «Удалить полностью» (`DELETE`), — и ни одну не надо помнить в коде.
 *
 * Триггеры ставятся при каждом открытии базы (`IF NOT EXISTS`): Room их не хранит в схеме, а так они есть и у новой
 * базы, и у перешедшей со старой версии.
 *
 * Все функции — на соединении Room внутри транзакции того, кто зовёт (у голого соединения SQLite — [pooled]).
 */
internal object SyncOutbox {
    /** Триггеры всех таблиц [SyncRules.ENTITIES]. */
    suspend fun install(connection: PooledConnection) {
        for (table in SyncRules.ENTITIES) {
            connection.run(trigger(table, "INSERT", "NEW.id", "NEW.updatedAt"))
            connection.run(trigger(table, "UPDATE", "NEW.id", "NEW.updatedAt"))
            connection.run(trigger(table, "DELETE", "OLD.id", "OLD.updatedAt + 1"))
        }
    }

    /**
     * Повторная правка склеивается с прежней: старая строка очереди убирается, новая кладётся. Не `INSERT OR REPLACE`:
     * у правки с `OR IGNORE` снаружи SQLite заменил бы и правило внутри триггера, и момент в очереди остался бы старым.
     */
    private fun trigger(table: String, event: String, id: String, updatedAt: String) =
        "CREATE TRIGGER IF NOT EXISTS `sync_${table}_${event.lowercase()}` AFTER $event ON `$table` BEGIN " +
            "DELETE FROM sync_outbox WHERE entity = '$table' AND rowId = $id; " +
            "INSERT INTO sync_outbox (entity, rowId, updatedAt) VALUES ('$table', $id, $updatedAt); END"

    /**
     * Вся база — в очередь заново: первая выгрузка при подключении сервера (7.2, п. 5) и после восстановления из копии,
     * когда очередь уже ничего не значит. Сервер сам оставит у себя то, что новее.
     */
    suspend fun requeueAll(connection: PooledConnection) {
        connection.run("DELETE FROM sync_outbox")
        for (table in SyncRules.ENTITIES) {
            connection.run("INSERT INTO sync_outbox (entity, rowId, updatedAt) SELECT '$table', id, updatedAt FROM `$table`")
        }
    }

    /** Запись таблицы целиком — столбец за столбцом, как в копии (1.15); `null` — её уже нет. */
    suspend fun read(connection: PooledConnection, table: String, id: String): JsonObject? =
        connection.single("SELECT * FROM `$table` WHERE id = ?", id) { s ->
            JsonObject(s.getColumnNames().withIndex().associate { (i, name) -> name to s.value(i) })
        }

    /** Момент последней правки своей записи; `null` — её нет. */
    suspend fun updatedAt(connection: PooledConnection, table: String, id: String): Long? =
        connection.single("SELECT updatedAt FROM `$table` WHERE id = ?", id) { it.getLong(0) }

    /** Ждёт ли своя правка записи отправки. */
    suspend fun pending(connection: PooledConnection, table: String, id: String): Boolean =
        connection.single("SELECT 1 FROM sync_outbox WHERE entity = ? AND rowId = ?", table, id) { true } ?: false

    /**
     * Записать пришедшую с сервера запись: только столбцы, которые есть в таблице сейчас, остальные — по умолчанию
     * (как при восстановлении копии). Триггер кладёт её в очередь — её тут же убирает [forget]: своё не отправляется назад.
     */
    suspend fun write(connection: PooledConnection, table: String, data: JsonObject, columns: Set<String>) {
        val used = data.entries.filter { it.key in columns }
        val sql = "INSERT OR REPLACE INTO `$table` (${used.joinToString { "`${it.key}`" }}) VALUES (${used.joinToString { "?" }})"
        connection.usePrepared(sql) { s ->
            used.forEachIndexed { i, (_, value) -> s.bind(i + 1, value) }
            s.step()
        }
    }

    suspend fun delete(connection: PooledConnection, table: String, id: String) {
        connection.run("DELETE FROM `$table` WHERE id = ?", id)
    }

    /** Убрать запись из очереди: пришедшее с сервера новее своей правки или само пришло с сервера. */
    suspend fun forget(connection: PooledConnection, table: String, id: String) {
        connection.run("DELETE FROM sync_outbox WHERE entity = ? AND rowId = ?", table, id)
    }

    /** Столбцы таблицы в базе этого телефона. */
    suspend fun columns(connection: PooledConnection, table: String): Set<String> =
        connection.usePrepared("PRAGMA table_info(`$table`)") { s -> buildSet { while (s.step()) add(s.getText(1)) } }

    /** Стереть все записи синхронизируемых таблиц и очередь — перед тем как взять данные с сервера. */
    suspend fun clearAll(connection: PooledConnection) {
        for (table in SyncRules.ENTITIES) connection.run("DELETE FROM `$table`")
        connection.run("DELETE FROM sync_outbox")
    }

    private suspend fun PooledConnection.run(sql: String, vararg args: String) {
        usePrepared(sql) { s ->
            args.forEachIndexed { i, value -> s.bindText(i + 1, value) }
            while (s.step()) Unit
        }
    }

    private suspend fun <T> PooledConnection.single(sql: String, vararg args: String, read: (SQLiteStatement) -> T): T? =
        usePrepared(sql) { s ->
            args.forEachIndexed { i, value -> s.bindText(i + 1, value) }
            if (s.step()) read(s) else null
        }
}

/** Голое соединение SQLite (открытие базы, переход версии) — как соединение Room. */
internal fun SQLiteConnection.pooled(): PooledConnection = object : PooledConnection {
    override suspend fun <R> usePrepared(sql: String, block: suspend (SQLiteStatement) -> R): R {
        val statement = prepare(sql)
        try {
            return block(statement)
        } finally {
            statement.close()
        }
    }
}

/** Пусто ли значение `data` изменения: так приходит запись, стёртая совсем. */
internal fun JsonElement?.isEmptyRow(): Boolean = this !is JsonObject || isEmpty()
