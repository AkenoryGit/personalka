package com.akenory.personalka.data.backup

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.PersonalkaMigrations
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * Таблицы данных: все, кроме служебных таблиц SQLite и Room и служебных таблиц синхронизации (`sync_…`, 2.10) — они
 * только для этого телефона: очередь изменений в копии и на другом телефоне ничего не значит.
 */
internal const val TABLES =
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' " +
        "AND name NOT LIKE 'room\\_%' ESCAPE '\\' AND name NOT LIKE 'sync\\_%' ESCAPE '\\' " +
        "AND name <> 'android_metadata' ORDER BY name"

private const val BLOB = "base64"

/** Значение столбца [i] для JSON копии — по типу SQLite; двоичное — base64. */
internal fun SQLiteStatement.value(i: Int): JsonElement = when (getColumnType(i)) {
    SQLITE_DATA_NULL -> JsonNull
    SQLITE_DATA_INTEGER -> JsonPrimitive(getLong(i))
    SQLITE_DATA_FLOAT -> JsonPrimitive(getDouble(i))
    SQLITE_DATA_BLOB -> JsonObject(mapOf(BLOB to JsonPrimitive(b64(getBlob(i)))))
    else -> JsonPrimitive(getText(i))
}

internal fun SQLiteStatement.bind(i: Int, value: JsonElement) {
    when {
        value is JsonNull -> bindNull(i)
        value is JsonObject -> bindBlob(i, Base64.decode(value.getValue(BLOB).jsonPrimitive.content))
        value is JsonPrimitive && value.isString -> bindText(i, value.content)
        value is JsonPrimitive && value.longOrNull != null -> bindLong(i, value.longOrNull!!)
        value is JsonPrimitive && value.doubleOrNull != null -> bindDouble(i, value.doubleOrNull!!)
        else -> bindText(i, value.jsonPrimitive.content)
    }
}

/**
 * Все таблицы данных соединения — то же, что копия 1.15 берёт у Room, но с голого соединения SQLite: им пользуются
 * копия перед переходом базы (переход идёт на соединении, которое Room ещё не отдал) и подъём старой копии.
 */
internal fun SQLiteConnection.dumpTables(): Map<String, BackupTable> {
    val names = select(TABLES) { buildList { while (it.step()) add(it.getText(0)) } }
    return names.associateWith { table ->
        select("SELECT * FROM `$table`") { s ->
            val columns = s.getColumnNames()
            BackupTable(columns, buildList { while (s.step()) add(columns.indices.map { s.value(it) }) })
        }
    }
}

/**
 * Копия старой версии — в нынешнюю (ТЗ 10.2, шаг 2.7б): таблицы копии собираются в базе в памяти и проходят те же
 * переходы, что база телефона ([PersonalkaMigrations]). Так переход, который пересчитывает данные (цвета мест
 * в версии 2), пересчитает и их, а не оставит значения по умолчанию.
 *
 * Таблицы в памяти — без типов столбцов: значения лежат как в копии, а переходы делают с ними то же, что с базой.
 */
internal suspend fun BackupData.upgraded(): BackupData {
    if (schemaVersion >= PersonalkaDatabase.VERSION) return this
    val connection = BundledSQLiteDriver().open(":memory:")
    try {
        tables.forEach { (table, rows) ->
            connection.select("CREATE TABLE `$table` (${rows.columns.joinToString { "`$it`" }})") { it.step() }
            if (rows.rows.isEmpty()) return@forEach
            val sql = "INSERT INTO `$table` VALUES (${rows.columns.joinToString { "?" }})"
            connection.select(sql) { s ->
                rows.rows.forEach { row ->
                    s.reset()
                    s.clearBindings()
                    row.forEachIndexed { i, value -> s.bind(i + 1, value) }
                    s.step()
                }
            }
        }
        PersonalkaMigrations.filter { it.startVersion >= schemaVersion }.sortedBy { it.startVersion }.forEach { it.migrate(connection) }
        return BackupData(createdAt, PersonalkaDatabase.VERSION, connection.dumpTables())
    } finally {
        connection.close()
    }
}

private inline fun <T> SQLiteConnection.select(sql: String, block: (SQLiteStatement) -> T): T {
    val statement = prepare(sql)
    try {
        return block(statement)
    } finally {
        statement.close()
    }
}
