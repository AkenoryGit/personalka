package com.akenory.personalka.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Переходы базы со старых версий: файл версии 1 (до шага 1.13в) и версии 2 (до «За кого» у дежурства) — как на телефоне
 * до новой сборки — открываются нынешней версией без потерь. Таблицы старой версии строятся по её схеме
 * из `shared/data/schemas`, которую когда-то записал Room.
 */
class MigrationTest {

    @Test
    fun version1GetsPlaceColorsAndTypeShades(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v1", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 1)
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
                assertEquals(PersonalkaDatabase.VERSION.toLong(), raw.long("PRAGMA user_version"))
                assertEquals(0L, raw.long("SELECT COUNT(*) FROM pragma_table_info('training_type') WHERE name = 'color'"), "Цвет типа удалён")
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    @Test
    fun version2KeepsDutiesWithoutCoverFor(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v2", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 2)
                raw.exec(
                    "INSERT INTO training (id, typeId, locationId, start, durationMin, seriesId, seriesDetached, groupId, status, plan, fact, " +
                        "owner, ownerColleagueId, conductedBy, colleagueId, splitHalf, cancelReason, overRestriction, planCarriedFromId, " +
                        "planCarriedText, doneAt, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('d1', 't1', 'p1', '2026-10-20T11:00', 240, NULL, 0, NULL, 'PLANNED', NULL, NULL, " +
                        "'SELF', NULL, 'SELF', NULL, 0, NULL, 0, NULL, NULL, NULL, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val duty = db.trainingDao().training("d1")!!
                assertEquals(240, duty.durationMin, "Дежурство не тронуто")
                assertNull(duty.coverFor, "Прежние дежурства — свои")
                db.trainingDao().upsertTraining(duty.copy(coverFor = "Александра"))
                assertEquals("Александра", db.trainingDao().training("d1")!!.coverFor)
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals(3L, raw.long("PRAGMA user_version"))
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    @Test
    fun newDatabaseIsVersion3() {
        assertEquals(3, PersonalkaDatabase.VERSION)
        assertTrue(schemaFile(1).exists() && schemaFile(2).exists(), "Схемы версий 1 и 2 хранятся: по ним строятся переходы")
    }

    /** Таблицы, индексы и служебная таблица Room версии [version] — из её JSON-схемы. */
    private fun createVersion(raw: SQLiteConnection, version: Int) {
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
