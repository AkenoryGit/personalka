package com.akenory.personalka.data.db

import androidx.sqlite.SQLiteConnection
import java.io.File

// Базы прежних версий для тестов переходов и копии перед ними (2.7б): как файл на телефоне до новой сборки.

/** Таблицы, индексы и служебная таблица Room версии [version] — из её JSON-схемы, которую когда-то записал Room. */
internal fun createVersion(raw: SQLiteConnection, version: Int) {
    var table = ""
    for (line in schemaFile(version).readLines()) {
        val key = line.trim()
        when {
            key.startsWith("\"tableName\"") -> table = key.substringAfter(": \"").substringBeforeLast("\"")
            key.startsWith("\"createSql\"") -> raw.exec(unescape(key.substringAfter(": \"").substringBeforeLast("\"")).replace("\${TABLE_NAME}", table))
        }
    }
    val schema = schemaFile(version).readText()
    Regex("\"(CREATE TABLE IF NOT EXISTS room_master_table[^\"]*|INSERT OR REPLACE INTO room_master_table[^\"]*)\"")
        .findAll(schema.replace("\\\"", "\u0001"))
        .forEach { raw.exec(it.groupValues[1].replace("\u0001", "\"")) }
    raw.exec("PRAGMA user_version = $version")
}

internal fun schemaFile(version: Int): File {
    val relative = "schemas/com.akenory.personalka.data.db.PersonalkaDatabase/$version.json"
    return listOf(File(relative), File("shared/data/$relative")).firstOrNull { it.exists() } ?: File(relative)
}

private fun unescape(json: String) = json.replace("\\\"", "\"").replace("\\\\", "\\")

/** Место версий 1–3: без цвета (его добавляет переход на версию 2). */
internal fun place(id: String, short: String, name: String, sort: Int) =
    "INSERT INTO location (id, scope, name, shortName, sort, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
        "VALUES ('$id', 'COACH', '$name', '$short', $sort, 0, 'c1', 1, 1, 1, NULL)"

/** Тип тренировки версии 1: с цветом, без оттенка. */
internal fun type(id: String, name: String, kind: String, color: String) =
    "INSERT INTO training_type (id, name, shortName, kind, color, defaultDurationMin, maxClients, payUnit, countsTowardPt, " +
        "tierDependent, diaryPlan, diaryFact, diaryBehavior, splitFallbackTypeId, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
        "VALUES ('$id', '$name', '${name.take(8)}', '$kind', '$color', 50, NULL, 'PER_SESSION', 0, 0, 1, 1, 1, NULL, 0, 'c1', 1, 1, 1, NULL)"

internal inline fun <T> SQLiteConnection.useConnection(block: (SQLiteConnection) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

internal fun SQLiteConnection.exec(sql: String) {
    val statement = prepare(sql)
    try {
        while (statement.step()) Unit
    } finally {
        statement.close()
    }
}

internal fun SQLiteConnection.long(sql: String): Long {
    val statement = prepare(sql)
    try {
        check(statement.step()) { "Пусто: $sql" }
        return statement.getLong(0)
    } finally {
        statement.close()
    }
}

/** Файл базы и его спутники SQLite — удалить после теста. */
internal fun deleteDatabase(path: String) = listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
