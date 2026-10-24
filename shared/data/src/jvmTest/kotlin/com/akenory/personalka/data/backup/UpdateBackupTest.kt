package com.akenory.personalka.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.createVersion
import com.akenory.personalka.data.db.deleteDatabase
import com.akenory.personalka.data.db.exec
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.db.long
import com.akenory.personalka.data.db.personalkaDatabase
import com.akenory.personalka.data.db.place
import com.akenory.personalka.data.db.type
import com.akenory.personalka.data.db.useConnection
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.reference.ReferenceRules
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Копия перед обновлением базы (шаг 2.7б, ТЗ 10.2): база прежней версии открывается новой сборкой, перед переходом
 * рядом появляется копия, и она восстанавливается без потерь. «Сейчас» — сб 24.10.2026 18:30. Имена придуманы.
 */
class UpdateBackupTest {
    private val millis = ClubTime.toMillis(LocalDateTime(2026, 10, 24, 18, 30))
    private val path = File.createTempFile("personalka-update", ".db").also { it.delete() }.absolutePath
    private val prefs = MemoryPrefs()
    private val local = MemoryFolder()
    private val chosen = MemoryFolder()
    private val opened = mutableListOf<PersonalkaDatabase>()

    /** Папка в памяти; [broken] — запись не удаётся (нет места). */
    private class MemoryFolder : BackupFolder {
        val files = mutableMapOf<String, ByteArray>()
        var broken = false

        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean {
            if (broken) return false
            files[name] = bytes
            return true
        }

        override suspend fun list(folder: String): List<String> = files.keys.toList()

        override suspend fun delete(folder: String, name: String) {
            files.remove(name)
        }

        override suspend fun read(folder: String, name: String): ByteArray? = files[name]
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        deleteDatabase(path)
    }

    /** База приложения, как в `PersonalkaApplication`: перед переходом Room зовёт копию этого же [BackupRepository]. */
    private fun openApp(): Pair<PersonalkaDatabase, BackupRepository> {
        lateinit var backup: BackupRepository
        val db = personalkaDatabase(path) { connection, from -> backup.beforeMigration(connection, from) }
        opened += db
        backup = BackupRepository(db, prefs, chosen, UpdateCopies(local, "update-backups")) { millis }
        return db to backup
    }

    /** Телефон до обновления: база версии 2 — место, тип, клиент и дежурство. */
    private fun version2() = BundledSQLiteDriver().open(path).useConnection { raw ->
        createVersion(raw, 2)
        raw.exec(
            "INSERT INTO location (id, scope, name, shortName, sort, archived, color, coachId, createdAt, updatedAt, version, deletedAt) " +
                "VALUES ('p1', 'COACH', 'Большой бассейн', 'ББ', 0, 0, 'blue', 'c1', 1, 1, 1, NULL)",
        )
        raw.exec(
            "INSERT INTO client (id, lastName, firstName, middleName, birthDate, isAdult, sex, contactName, contactPhone, mediaUrl, " +
                "privateNote, name1c, cabinetEnabled, consentAt, consentSource, followupContactedAt, followupPausedUntil, followupPauseNote, " +
                "pendingPlan, status, source, coachId, createdAt, updatedAt, version, deletedAt) " +
                "VALUES ('k1', 'Белова', 'Аня', NULL, '2017-03-05', 0, NULL, NULL, NULL, NULL, 'боится глубины', NULL, 0, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, 'ACTIVE', 'MANUAL', 'c1', 1, 1, 1, NULL)",
        )
        raw.exec(
            "INSERT INTO training (id, typeId, locationId, start, durationMin, seriesId, seriesDetached, groupId, status, plan, fact, " +
                "owner, ownerColleagueId, conductedBy, colleagueId, splitHalf, cancelReason, overRestriction, planCarriedFromId, " +
                "planCarriedText, doneAt, coachId, createdAt, updatedAt, version, deletedAt) " +
                "VALUES ('d1', 't1', 'p1', '2026-10-20T11:00', 240, NULL, 0, NULL, 'PLANNED', NULL, NULL, " +
                "'SELF', NULL, 'SELF', NULL, 0, NULL, 0, NULL, NULL, NULL, 'c1', 1, 1, 1, NULL)",
        )
    }

    @Test
    fun version2IsCopiedBeforeUpdateAndRestoredWithoutLoss(): Unit = runBlocking {
        version2()
        val (db, backup) = openApp()
        backup.chooseFolder("content://folder", "Копии")
        backup.setEncrypt(false)

        assertEquals("Белова", db.clientDao().client("k1")!!.lastName, "База открылась новой версией")
        val name = BackupRules.fileName(LocalDateTime(2026, 10, 24, 18, 30))
        assertEquals(setOf(name), local.files.keys, "Копия — в папке приложения")
        assertEquals(setOf(name), chosen.files.keys, "И в папке для копий — обычной копией")
        assertEquals(LocalDateTime(2026, 10, 24, 18, 30), backup.state.value.updateCopy)
        assertEquals(LocalDateTime(2026, 10, 24, 18, 30), backup.state.value.last, "Считается последней копией")
        val after = snapshot(db)

        // После обновления что-то пошло не так: данные испорчены.
        db.clientDao().upsertClient(db.clientDao().client("k1")!!.copy(lastName = "Испорчено", privateNote = null))
        db.trainingDao().upsertTraining(db.trainingDao().training("d1")!!.copy(coverFor = "Александра", durationMin = 60))

        val copy = assertIs<BackupRead.Ready>(backup.read(assertNotNull(backup.updateCopyBytes())))
        assertEquals(1, copy.clients)
        assertTrue(backup.restore(copy))
        assertEquals(after, snapshot(db), "Восстановлено всё, как сразу после перехода")
        assertNull(db.trainingDao().training("d1")!!.coverFor)
        assertEquals("боится глубины", db.clientDao().client("k1")!!.privateNote)
    }

    @Test
    fun copyIsEncryptedWithPhoneKeyAndOpensWithoutPassword(): Unit = runBlocking {
        version2()
        val (db, backup) = openApp()
        backup.setPassword("пароль-123")
        db.clientDao().client("k1")

        val bytes = assertNotNull(backup.updateCopyBytes())
        assertIs<Unpacked.NeedPassword>(BackupFile.unpack(bytes, null))
        assertIs<BackupRead.Ready>(backup.read(bytes), "Своя копия открывается ключом телефона")
        assertTrue(chosen.files.isEmpty(), "Папка для копий не выбрана — только папка приложения")
    }

    @Test
    fun noCopyNoUpdate(): Unit = runBlocking {
        version2()
        local.broken = true
        val (db, _) = openApp()
        assertFailsWith<IllegalStateException> { db.clientDao().client("k1") }
        db.close()
        opened.clear()

        BundledSQLiteDriver().open(path).useConnection { raw ->
            assertEquals(2L, raw.long("PRAGMA user_version"), "Переход не начат: база прежней версии")
            assertEquals(1L, raw.long("SELECT COUNT(*) FROM client"))
            assertEquals(0L, raw.long("SELECT COUNT(*) FROM pragma_table_info('training') WHERE name = 'coverFor'"))
        }

        local.broken = false
        val (again, backup) = openApp()
        assertEquals("Белова", again.clientDao().client("k1")!!.lastName, "Место появилось — обновление проходит")
        assertNotNull(backup.state.value.updateCopy)
    }

    @Test
    fun newDatabaseNeedsNoCopy(): Unit = runBlocking {
        val (db, backup) = openApp()
        assertNull(db.clientDao().client("k1"))
        assertTrue(local.files.isEmpty())
        assertNull(backup.state.value.updateCopy)
        assertNull(backup.updateCopyBytes())
    }

    /** Копия версии 1 проходит переход на версию 2 заново: цвета мест считаются, а не остаются цветом по умолчанию. */
    @Test
    fun version1CopyGetsRecalculatedColors(): Unit = runBlocking {
        BundledSQLiteDriver().open(path).useConnection { raw ->
            createVersion(raw, 1)
            raw.exec(place("p1", "ДБ", "Детский бассейн", sort = 0))
            raw.exec(place("p2", "ББ", "Большой бассейн", sort = 1))
            raw.exec(type("t1", "ПТ", "PERSONAL", "blue"))
        }
        val (db, backup) = openApp()
        db.referenceDao().locations()
        val copy = assertIs<BackupRead.Ready>(backup.read(assertNotNull(backup.updateCopyBytes())))

        val fresh = inMemoryPersonalkaDatabase()
        try {
            assertTrue(BackupRepository(fresh, MemoryPrefs(), chosen) { millis }.restore(copy))
            val places = fresh.referenceDao().locations()
            assertEquals(listOf("ДБ" to "pink", "ББ" to "blue"), places.map { it.shortName to it.color })
            assertTrue(places.none { it.color == ReferenceRules.FALLBACK_COLOR })
            assertEquals(1, fresh.referenceDao().trainingTypes().single().shade)
        } finally {
            fresh.close()
        }
    }

    /** Все таблицы данных текстом: строки по порядку ключей. */
    private suspend fun snapshot(db: PersonalkaDatabase): String {
        val dump = BackupRepository(db, MemoryPrefs(), chosen) { millis }.also { it.setEncrypt(false) }.create()!!
        val data = (BackupFile.unpack(dump.bytes, null) as Unpacked.Opened).data
        return data.tables.toSortedMap().entries.joinToString("\n") { (table, rows) ->
            "$table ${rows.columns}: " + rows.rows.map { it.toString() }.sorted()
        }
    }
}
