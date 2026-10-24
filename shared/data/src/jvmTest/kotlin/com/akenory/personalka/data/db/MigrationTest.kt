package com.akenory.personalka.data.db

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
 * из `shared/data/schemas`, которую когда-то записал Room ([createVersion]).
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
}
