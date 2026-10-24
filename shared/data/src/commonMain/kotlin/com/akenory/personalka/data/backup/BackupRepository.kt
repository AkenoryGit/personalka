package com.akenory.personalka.data.backup

import androidx.room3.deferredTransaction
import androidx.room3.executeSQL
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.sync.SyncOutbox
import com.akenory.personalka.data.sync.pullFromStart
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.backup.BackupFrequency
import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.backup.IcsCalendar
import com.akenory.personalka.domain.backup.IcsEvent
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Папка для копий, выбранная тренером системным диалогом (на Android — папка Storage Access Framework, в том
 * числе облачная). [folder] — её адрес от системы.
 */
interface BackupFolder {
    /** Записать файл (тот же — перезаписать); `false` — папка недоступна: её удалили или забрали доступ. */
    suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean

    /** Имена файлов в папке; `null` — папка недоступна. */
    suspend fun list(folder: String): List<String>?

    suspend fun delete(folder: String, name: String)

    /** Содержимое файла; `null` — его нет или папка недоступна. Нужно только папке приложения ([UpdateCopies]). */
    suspend fun read(folder: String, name: String): ByteArray? = null
}

/**
 * Своя папка приложения на телефоне ([path] у [files]) — для копий перед обновлением базы (ТЗ 10.2): она есть всегда,
 * даже если тренер папку для копий не выбрал, и доступ к ней не отзовёшь.
 */
class UpdateCopies(val files: BackupFolder, val path: String)

/** Что показывает раздел «Данные» (5.16): папка, частота, пароль и последняя копия. */
data class BackupState(
    val folder: String? = null,
    val folderName: String? = null,
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val encrypt: Boolean = true,
    val hasPassword: Boolean = false,
    /** Последняя копия любым способом: в папку, «Поделиться» — по часам клуба. */
    val last: LocalDateTime? = null,
    /** Последняя копия перед обновлением базы — в папке приложения (2.7б). */
    val updateCopy: LocalDateTime? = null,
) {
    /** Копию можно делать сразу: шифрование выключено или пароль уже задан. */
    val ready: Boolean get() = !encrypt || hasPassword
}

/** Готовый файл копии: имя `trener-backup-…zip` и содержимое. */
class BackupMade(val name: String, val bytes: ByteArray)

/** Файл, выбранный для восстановления (7.6): открыт, нужен пароль или не годится. */
sealed interface BackupRead {
    /** Копия открыта: предпросмотр перед заменой данных. */
    class Ready internal constructor(
        val createdAt: LocalDateTime,
        val clients: Int,
        val trainings: Int,
        internal val data: BackupData,
        internal val key: BackupKey?,
    ) : BackupRead

    data object NeedPassword : BackupRead

    data object WrongPassword : BackupRead

    data object NotBackup : BackupRead

    /** Копия снята более новой версией приложения: её таблиц эта версия не знает. */
    data object Newer : BackupRead
}

/**
 * Резервная копия на телефоне (ТЗ 7.6, 1.5): все таблицы базы в JSON, шифрование паролем (AES-GCM, ключ —
 * Argon2id), автокопия в выбранную папку с последними 10 копиями, восстановление с заменой всех данных
 * и выгрузка тренировок в календарь (.ics).
 *
 * Настройки копии — этого телефона ([DevicePrefs]), а не тренера: на другом телефоне — своя папка. Пароль
 * не хранится: хранится ключ из него, чтобы автокопия шифровалась без вопроса.
 *
 * @param updates папка приложения для копий перед обновлением базы ([beforeMigration]).
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class BackupRepository(
    private val db: PersonalkaDatabase,
    private val prefs: DevicePrefs,
    private val folder: BackupFolder,
    private val updates: UpdateCopies? = null,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** Копии делаются по одной: автокопия при открытии и «Создать копию сейчас» не пишут один файл вдвоём. */
    private val busy = Mutex()

    private fun load() = BackupState(
        folder = prefs.string(FOLDER),
        folderName = prefs.string(FOLDER_NAME),
        frequency = BackupFrequency.of(prefs.string(FREQUENCY)),
        encrypt = prefs.boolean(ENCRYPT) ?: true,
        hasPassword = key() != null,
        last = prefs.string(LAST)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        updateCopy = prefs.string(UPDATE_AT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
    )

    private fun key(): BackupKey? = prefs.string(KEY)?.let { BackupKey.decode(it) }

    // --- Настройки ---

    /** Папка выбрана системным диалогом; [name] — её название для строки. */
    fun chooseFolder(uri: String, name: String) {
        prefs.putString(FOLDER, uri)
        prefs.putString(FOLDER_NAME, name)
        _state.update { it.copy(folder = uri, folderName = name) }
    }

    fun setFrequency(frequency: BackupFrequency) {
        prefs.putString(FREQUENCY, frequency.code)
        _state.update { it.copy(frequency = frequency) }
    }

    fun setEncrypt(on: Boolean) {
        prefs.putBoolean(ENCRYPT, on)
        _state.update { it.copy(encrypt = on) }
    }

    /** Новый пароль копий: ключ считается медленно (Argon2id), поэтому не на главном потоке. */
    suspend fun setPassword(password: String) {
        val key = withContext(Dispatchers.Default) { BackupKey.fromPassword(password) }
        prefs.putString(KEY, key.encode())
        prefs.putBoolean(ENCRYPT, true)
        _state.update { it.copy(hasPassword = true, encrypt = true) }
    }

    // --- Копия ---

    /** Файл копии всех данных; `null` — шифрование включено, а пароля ещё нет. */
    suspend fun create(): BackupMade? {
        val s = _state.value
        if (!s.ready) return null
        val now = ClubTime.now(clock())
        val data = BackupData(now, PersonalkaDatabase.VERSION, dump())
        val bytes = withContext(Dispatchers.Default) { BackupFile.pack(data, if (s.encrypt) key() else null) }
        return BackupMade(BackupRules.fileName(now), bytes)
    }

    /** Копия ушла через «Поделиться»: она тоже считается — напоминание о копии замолкает (1.5). */
    fun shared() = remember(ClubTime.now(clock()))

    /**
     * «Создать копию сейчас»: файл в папку и чистка до 10 последних. `false` — папка недоступна или
     * копию сделать нельзя (нет пароля, нет папки).
     */
    suspend fun saveNow(): Boolean = busy.withLock { saveToFolder() }

    /**
     * Автокопия (7.6): при открытии приложения и раз в час, если папка выбрана, копию можно сделать без вопросов
     * и последней в папке день (неделю) нет. `true` — копия сделана.
     */
    suspend fun autoBackup(): Boolean = busy.withLock {
        val s = _state.value
        val lastAuto = prefs.string(LAST_FOLDER)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (s.folder == null || !s.ready || !BackupRules.autoDue(lastAuto, ClubTime.now(clock()), s.frequency)) return@withLock false
        saveToFolder()
    }

    private suspend fun saveToFolder(): Boolean {
        val target = _state.value.folder ?: return false
        val made = create() ?: return false
        return putToFolder(target, made.name, made.bytes, ClubTime.now(clock()))
    }

    private suspend fun putToFolder(target: String, name: String, bytes: ByteArray, now: LocalDateTime): Boolean {
        if (!folder.write(target, name, bytes)) return false
        folder.list(target)?.let { names -> BackupRules.toDelete(names).forEach { folder.delete(target, it) } }
        prefs.putString(LAST_FOLDER, now.toString())
        remember(now)
        return true
    }

    // --- Копия перед обновлением базы ---

    /**
     * Копия перед переходом базы на новую версию (ТЗ 10.2, шаг 2.7б). Room зовёт её из первого перехода: [connection] —
     * база ещё версии [from], все таблицы берутся как в обычной копии. Копия ложится в папку приложения ([updates],
     * там остаются [BackupRules.KEEP_UPDATES] последних) и, если папка для копий выбрана и копию можно шифровать
     * без вопроса, — туда тоже, обычной копией: её видно в «Восстановить из копии».
     *
     * Шифруется ключом телефона, если шифрование включено; без пароля в папку приложения ложится открытой — там же,
     * где сама база. Не легла никуда — ошибка: Room откатывает открытие, и база остаётся прежней версии.
     *
     * Без [busy]: автокопия может сама ждать открытия базы, которое и зовёт этот переход.
     */
    suspend fun beforeMigration(connection: SQLiteConnection, from: Int) {
        val now = ClubTime.now(clock())
        val s = _state.value
        val bytes = BackupFile.pack(BackupData(now, from, connection.dumpTables()), if (s.encrypt) key() else null)
        val name = BackupRules.fileName(now)
        var saved = false
        updates?.let { u ->
            if (u.files.write(u.path, name, bytes)) {
                u.files.list(u.path)?.let { names -> BackupRules.toDelete(names, BackupRules.KEEP_UPDATES).forEach { u.files.delete(u.path, it) } }
                prefs.putString(UPDATE_NAME, name)
                prefs.putString(UPDATE_AT, now.toString())
                _state.update { it.copy(updateCopy = now) }
                saved = true
            }
        }
        val target = s.folder
        if (target != null && s.ready && putToFolder(target, name, bytes, now)) saved = true
        check(saved) { "Копия базы версии $from не сохранилась — переход на новую версию не начат" }
    }

    /** Показывать ли в «Данных» копию перед обновлением, снятую в [at]: первые 30 дней ([BackupRules.showUpdateCopy]). */
    fun showUpdateCopy(at: LocalDateTime): Boolean = BackupRules.showUpdateCopy(at, ClubTime.now(clock()))

    /** Файл последней копии перед обновлением — открыть его, как выбранный в «Восстановить из копии». */
    suspend fun updateCopyBytes(): ByteArray? {
        val u = updates ?: return null
        val name = prefs.string(UPDATE_NAME) ?: return null
        return u.files.read(u.path, name)
    }

    private fun remember(time: LocalDateTime) {
        prefs.putString(LAST, time.toString())
        _state.update { it.copy(last = time) }
    }

    // --- Восстановление ---

    /** Открыть выбранный файл: без [password] — своя копия этого телефона откроется ключом телефона. */
    suspend fun read(bytes: ByteArray, password: String? = null): BackupRead = withContext(Dispatchers.Default) {
        when (val u = BackupFile.unpack(bytes, password, key())) {
            Unpacked.NeedPassword -> BackupRead.NeedPassword
            Unpacked.WrongPassword -> BackupRead.WrongPassword
            Unpacked.NotBackup -> BackupRead.NotBackup
            is Unpacked.Opened -> when {
                u.data.schemaVersion > PersonalkaDatabase.VERSION -> BackupRead.Newer
                else -> BackupRead.Ready(
                    createdAt = u.data.createdAt,
                    clients = u.data.tables["client"]?.liveRows() ?: 0,
                    trainings = u.data.tables["training"]?.liveRows() ?: 0,
                    data = u.data,
                    key = u.key,
                )
            }
        }
    }

    /**
     * Заменить все данные телефона данными копии — одной транзакцией: не вышло — данные прежние, ответ `false`.
     * Копия старой версии сначала проходит те же переходы, что база ([upgraded]): пересчитанное ими не теряется.
     * Пароль открытой копии становится паролем копий этого телефона: автокопия на новом телефоне сразу шифруется им.
     *
     * С сервером (2.10) восстановленное сливается с данными сервера: вся база ложится в очередь заново, а получение
     * начинается с начала. Новее на сервере — останется серверное, новее в копии — уйдёт на сервер; стёртое
     * при замене серверу удалением не уходит.
     */
    suspend fun restore(copy: BackupRead.Ready): Boolean {
        val ok = runCatching { replaceAll(withContext(Dispatchers.Default) { copy.data.upgraded() }) }.isSuccess
        if (ok) prefs.pullFromStart()
        if (ok && copy.key != null) {
            prefs.putString(KEY, copy.key.encode())
            _state.update { it.copy(hasPassword = true) }
        }
        return ok
    }

    // --- Выгрузка в календарь ---

    /** Календарь .ics: прошлые 3 и будущие 3 месяца (7.6). Ответ — имя файла и текст. */
    suspend fun calendar(): Pair<String, String> {
        val millis = clock()
        val today = ClubTime.today(millis)
        val (from, to) = BackupRules.calendarRange(today)
        val trainings = db.trainingDao()
        val clients = db.clientDao()
        val reference = db.referenceDao()
        val types = reference.trainingTypes().associateBy { it.id }
        val places = reference.locations().associateBy { it.id }
        val groups = clients.groups().associateBy { it.id }
        val list = trainings.trainingsBetween(LocalDateTime(from, LocalTime(0, 0)), LocalDateTime(to, LocalTime(0, 0)))
        val events = list.map { t ->
            val people = trainings.participationsOf(t.id)
            val names = people.mapNotNull { p -> clients.client(p.clientId)?.let { ClientName(it.lastName, it.firstName, it.middleName) } }
            val type = types[t.typeId]
            val title = CalendarFormat.title(names, t.groupId?.let { groups[it]?.name }, people.size, type?.name.orEmpty())
            IcsEvent(
                id = t.id,
                start = t.start,
                durationMin = t.durationMin,
                title = listOfNotNull(type?.shortName, title.takeIf { it != type?.name }).joinToString(" · "),
                place = places[t.locationId]?.name.orEmpty(),
                cancelled = t.status == TrainingStatus.CANCELLED,
            )
        }
        return BackupRules.calendarFileName(today) to IcsCalendar.build(events, millis)
    }

    // --- Таблицы ---

    /** Все таблицы базы как есть, кроме служебных таблиц SQLite и Room: новые таблицы попадут в копию сами. */
    private suspend fun dump(): Map<String, BackupTable> = db.useReaderConnection { connection ->
        connection.deferredTransaction {
            val names = usePrepared(TABLES) { s -> buildList { while (s.step()) add(s.getText(0)) } }
            names.associateWith { table ->
                usePrepared("SELECT * FROM `$table`") { s ->
                    val columns = s.getColumnNames()
                    val rows = buildList { while (s.step()) add(columns.indices.map { s.value(it) }) }
                    BackupTable(columns, rows)
                }
            }
        }
    }

    private suspend fun replaceAll(data: BackupData) {
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val names = usePrepared(TABLES) { s -> buildList { while (s.step()) add(s.getText(0)) } }
                val columnsOf = names.associateWith { table ->
                    usePrepared("PRAGMA table_info(`$table`)") { s -> buildSet { while (s.step()) add(s.getText(1)) } }
                }
                names.forEach { executeSQL("DELETE FROM `$it`") }
                data.tables.forEach { (table, rows) ->
                    val known = columnsOf[table] ?: return@forEach
                    // Только столбцы, которые есть сейчас; остальные получат значения по умолчанию.
                    val used = rows.columns.withIndex().filter { it.value in known }
                    if (used.isEmpty() || rows.rows.isEmpty()) return@forEach
                    val sql = "INSERT INTO `$table` (${used.joinToString { "`${it.value}`" }}) VALUES (${used.joinToString { "?" }})"
                    usePrepared(sql) { s ->
                        rows.rows.forEach { row ->
                            s.reset()
                            s.clearBindings()
                            used.forEachIndexed { i, column -> s.bind(i + 1, row[column.index]) }
                            s.step()
                        }
                    }
                }
                // Триггеры записали стирание и вставку каждой записи — очередь собирается заново, без удалений.
                SyncOutbox.requeueAll(this)
            }
        }
    }

    private companion object {
        const val FOLDER = "backup.folder"
        const val FOLDER_NAME = "backup.folderName"
        const val FREQUENCY = "backup.frequency"
        const val ENCRYPT = "backup.encrypt"
        const val KEY = "backup.key"

        /** Последняя копия любым способом — для строки «Последняя копия» и напоминания (1.16). */
        const val LAST = "backup.last"

        /** Последняя копия в папку — для автокопии. */
        const val LAST_FOLDER = "backup.lastFolder"

        /** Последняя копия перед обновлением базы: имя файла в папке приложения и время. */
        const val UPDATE_NAME = "backup.updateName"
        const val UPDATE_AT = "backup.updateAt"
    }
}
