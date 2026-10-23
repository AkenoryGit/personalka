package com.akenory.personalka.data.backup

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.backup.BackupFrequency
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.data.training.SaveResult
import com.akenory.personalka.domain.training.TrainingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Резервная копия (шаг 1.15, ТЗ 7.6). «Сейчас» — пт 23.10.2026 15:40. Имена придуманы. */
class BackupRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private lateinit var other: PersonalkaDatabase
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 10, 23, 15, 40))
    private val folder = MemoryFolder()
    private lateinit var backup: BackupRepository

    /** Папка в памяти: файлы по именам; [lost] — доступ к папке пропал. */
    private class MemoryFolder : BackupFolder {
        val files = mutableMapOf<String, ByteArray>()
        var lost = false

        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean {
            if (lost) return false
            files[name] = bytes
            return true
        }

        override suspend fun list(folder: String): List<String>? = if (lost) null else files.keys.toList()

        override suspend fun delete(folder: String, name: String) {
            files.remove(name)
        }
    }

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        other = inMemoryPersonalkaDatabase()
        backup = BackupRepository(db, MemoryPrefs(), folder) { millis }
    }

    @AfterTest
    fun close() {
        db.close()
        other.close()
    }

    /** Тренер, два клиента, группа с составом и тренировки группы. */
    private suspend fun fill() {
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        val clients = ClientRepository(db) { millis++ }
        val groups = GroupRepository(db) { millis++ }
        val group = groups.create(GroupDraft("Сб 9:00 ББ", settings.types.first().single { it.shortName == "Группа" }.id, settings.places.first().single { it.shortName == "ББ" }.id))
        listOf(clients.create(ClientDraft("Белова", "Аня")), clients.create(ClientDraft("Гусев", "Борис"))).forEach {
            groups.addMember(group, it, LocalDate(2026, 9, 1))
        }
        val draft = TrainingDraft(
            LocalDateTime(2026, 10, 24, 9, 0),
            settings.types.first().single { it.shortName == "Группа" }.id,
            emptyList(),
            group,
            settings.places.first().single { it.shortName == "ББ" }.id,
            60,
        )
        assertIs<SaveResult.Saved>(CalendarRepository(db) { millis }.forms.create(draft))
    }

    @Test
    fun blake2bAndArgon2idMatchStandardVectors() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.hash(64, "abc".encodeToByteArray())),
        )
        // RFC 9106, 5.3: Argon2id, 32 КиБ, 3 прохода, 4 полосы.
        val tag = Argon2id.hash(
            password = ByteArray(32) { 1 },
            salt = ByteArray(16) { 2 },
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            length = 32,
            secret = ByteArray(8) { 3 },
            extra = ByteArray(12) { 4 },
        )
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", hex(tag))
    }

    @Test
    fun copyRestoresOnAnotherPhoneWithoutLosses(): Unit = runBlocking {
        // Проверка шага: копия с паролем восстанавливается на другом телефоне без потерь.
        fill()
        assertNull(backup.create(), "Шифрование по умолчанию включено — без пароля копии нет")
        backup.setPassword("секрет-123")
        val made = assertNotNull(backup.create())
        assertEquals("trener-backup-20261023-1540.zip", made.name)
        assertFalse(made.bytes.decodeToString().contains("Белова"), "Имена в файле зашифрованы")

        val phone2 = BackupRepository(other, MemoryPrefs(), MemoryFolder()) { millis }
        assertEquals(BackupRead.NeedPassword, phone2.read(made.bytes))
        assertEquals(BackupRead.WrongPassword, phone2.read(made.bytes, "секрет-124"))
        val ready = assertIs<BackupRead.Ready>(phone2.read(made.bytes, "секрет-123"))
        assertEquals(LocalDateTime(2026, 10, 23, 15, 40), ready.createdAt)
        assertEquals(2, ready.clients)
        assertTrue(ready.trainings > 0)

        assertTrue(phone2.restore(ready))
        assertEquals(dump(db), dump(other))
        assertEquals(listOf("Белова", "Гусев"), other.clientDao().observeAllClients().first().map { it.lastName }.sorted())
        // Пароль копии стал паролем этого телефона: следующая копия шифруется сразу.
        assertTrue(phone2.state.value.hasPassword)
        assertNotNull(phone2.create())
    }

    @Test
    fun restoreReplacesCurrentData(): Unit = runBlocking {
        fill()
        backup.setEncrypt(false)
        val made = assertNotNull(backup.create())
        assertTrue(made.bytes.isNotEmpty())
        val ready = assertIs<BackupRead.Ready>(backup.read(made.bytes))

        // После копии завели ещё клиента — восстановление его убирает.
        ClientRepository(db) { millis++ }.create(ClientDraft("Зуева", "Вера"))
        assertEquals(3, db.clientDao().observeAllClients().first().size)
        assertTrue(backup.restore(ready))
        assertEquals(2, db.clientDao().observeAllClients().first().size)

        assertEquals(BackupRead.NotBackup, backup.read("не копия".encodeToByteArray()))
    }

    @Test
    fun ownCopyOpensWithoutPassword(): Unit = runBlocking {
        fill()
        backup.setPassword("секрет-123")
        val made = assertNotNull(backup.create())
        assertIs<BackupRead.Ready>(backup.read(made.bytes))
    }

    @Test
    fun autoBackupKeepsLastTenInFolder(): Unit = runBlocking {
        fill()
        backup.setPassword("секрет-123")
        assertFalse(backup.autoBackup(), "Папка не выбрана")
        backup.chooseFolder("content://folder", "Копии")
        folder.files["заметки.txt"] = ByteArray(1)

        assertTrue(backup.autoBackup())
        assertFalse(backup.autoBackup(), "Сегодня копия уже есть")
        repeat(11) {
            millis += 24 * 60 * 60 * 1000L
            assertTrue(backup.autoBackup())
        }
        assertEquals(10, folder.files.keys.count { it.startsWith("trener-backup-") })
        assertTrue("заметки.txt" in folder.files, "Чужие файлы не трогаются")
        assertEquals("trener-backup-20261103-1540.zip", folder.files.keys.filter { it.startsWith("trener-") }.max())

        backup.setFrequency(BackupFrequency.WEEKLY)
        millis += 24 * 60 * 60 * 1000L
        assertFalse(backup.autoBackup(), "Раз в неделю — следующая через 7 дней")
        folder.lost = true
        assertFalse(backup.saveNow())
        assertEquals(LocalDateTime(2026, 11, 3, 15, 40), backup.state.value.last)
    }

    @Test
    fun calendarExportHasTrainings(): Unit = runBlocking {
        fill()
        val (name, text) = backup.calendar()
        assertEquals("trener-calendar-20261023.ics", name)
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\n"))
        // Сб 24.10 09:00 по Тюмени (UTC+5) — 04:00 UTC.
        assertTrue("DTSTART:20261024T040000Z" in text)
        assertTrue("SUMMARY:Группа · Сб 9:00 ББ (2)" in text)
        assertTrue("LOCATION:Большой бассейн" in text)
    }

    private suspend fun dump(database: PersonalkaDatabase): String {
        val copy = BackupRepository(database, MemoryPrefs().apply { putBoolean("backup.encrypt", false) }, MemoryFolder()) { millis }
        val bytes = assertNotNull(copy.create()).bytes
        val data = assertIs<Unpacked.Opened>(BackupFile.unpack(bytes, null)).data
        return data.tables.entries.sortedBy { it.key }.joinToString("\n") { (name, t) -> name + t.columns + t.rows.sortedBy { it.toString() } }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
