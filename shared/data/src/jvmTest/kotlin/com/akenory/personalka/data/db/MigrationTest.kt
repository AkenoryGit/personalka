package com.akenory.personalka.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Переход базы со старой версии (шаг 1.13в): файл версии 1 — как на телефоне до этой сборки — открывается версией 2
 * без потерь. Таблицы версии 1 строятся по её схеме из `shared/data/schemas`, которую когда-то записал Room.
 */
class MigrationTest {

    @Test
    fun version1GetsPlaceColorsAndTypeShades(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v1", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion1(raw)
                // Места — в порядке тренера: ДБ первым, своё место «Ул» — третьим.
                raw.exec(place("p1", "ДБ", "Детский бассейн", sort = 0))
                raw.exec(place("p2", "ББ", "Большой бассейн", sort = 1))
                raw.exec(place("p3", "Ул", "Улица", sort = 2))
                raw.exec(type("t1", "ПТ", "PERSONAL", "blue"))
                raw.exec(type("t2", "Мини-группа", "GROUP", "lime"))
                raw.exec(type("t3", "Группа", "GROUP", "green"))
                raw.exec(type("t4", "Аквааэробика", "GROUP", "sky"))
                raw.exec(type("t5", "Тройка", "OTHER", "brown"))
            }

            val db = personalkaDatabase(path)
            try {
                val places = db.referenceDao().locations()
                assertEquals(listOf("ДБ" to "pink", "ББ" to "blue", "Ул" to "red"), places.map { it.shortName to it.color })
                assertEquals("Детский бассейн", places.first().name, "Остальное в месте не тронуто")

                val shades = db.referenceDao().trainingTypes().associate { it.name to it.shade }
                assertEquals(mapOf("ПТ" to 1, "Мини-группа" to 3, "Группа" to 4, "Аквааэробика" to 4, "Тройка" to 2), shades)
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals(2L, raw.long("PRAGMA user_version"))
                assertEquals(0L, raw.long("SELECT COUNT(*) FROM pragma_table_info('training_type') WHERE name = 'color'"), "Цвет типа удалён")
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    @Test
    fun newDatabaseIsVersion2() {
        assertEquals(2, PersonalkaDatabase.VERSION)
        assertTrue(schemaFile(1).exists(), "Схема версии 1 хранится: по ней строится переход")
    }

    /** Таблицы, индексы и служебная таблица Room версии 1 — из её JSON-схемы. */
    private fun createVersion1(raw: SQLiteConnection) {
        var table = ""
        for (line in schemaFile(1).readLines()) {
            val key = line.trim()
            when {
                key.startsWith("\"tableName\"") -> table = key.substringAfter(": \"").substringBeforeLast("\"")
                key.startsWith("\"createSql\"") -> raw.exec(unescape(key.substringAfter(": \"").substringBeforeLast("\"")).replace("\${TABLE_NAME}", table))
            }
        }
        val schema = schemaFile(1).readText()
        Regex("\"(CREATE TABLE IF NOT EXISTS room_master_table[^\"]*|INSERT OR REPLACE INTO room_master_table[^\"]*)\"")
            .findAll(schema.replace("\\\"", "\u0001"))
            .forEach { raw.exec(it.groupValues[1].replace("\u0001", "\"")) }
        raw.exec("PRAGMA user_version = 1")
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.akenory.personalka.data.db.PersonalkaDatabase/$version.json"
        return listOf(File(relative), File("shared/data/$relative")).firstOrNull { it.exists() } ?: File(relative)
    }

    private fun unescape(json: String) = json.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun place(id: String, short: String, name: String, sort: Int) =
        "INSERT INTO location (id, scope, name, shortName, sort, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
            "VALUES ('$id', 'COACH', '$name', '$short', $sort, 0, 'c1', 1, 1, 1, NULL)"

    private fun type(id: String, name: String, kind: String, color: String) =
        "INSERT INTO training_type (id, name, shortName, kind, color, defaultDurationMin, maxClients, payUnit, countsTowardPt, " +
            "tierDependent, diaryPlan, diaryFact, diaryBehavior, splitFallbackTypeId, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
            "VALUES ('$id', '$name', '${name.take(8)}', '$kind', '$color', 50, NULL, 'PER_SESSION', 0, 0, 1, 1, 1, NULL, 0, 'c1', 1, 1, 1, NULL)"

    private inline fun <T> SQLiteConnection.useConnection(block: (SQLiteConnection) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun SQLiteConnection.exec(sql: String) {
        val statement = prepare(sql)
        try {
            while (statement.step()) Unit
        } finally {
            statement.close()
        }
    }

    private fun SQLiteConnection.long(sql: String): Long {
        val statement = prepare(sql)
        try {
            check(statement.step()) { "Пусто: $sql" }
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    }
}
