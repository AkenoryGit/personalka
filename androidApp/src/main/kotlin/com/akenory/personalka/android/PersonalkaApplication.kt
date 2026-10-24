package com.akenory.personalka.android

import android.app.Application
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.androidAccount
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.backup.UpdateCopies
import com.akenory.personalka.data.backup.fileBackupFolder
import com.akenory.personalka.data.backup.safBackupFolder
import com.akenory.personalka.data.cabinet.CabinetRepository
import com.akenory.personalka.data.cabinet.androidCabinets
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.personalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.devicePrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.data.sync.SyncRunner
import com.akenory.personalka.data.sync.androidSync
import com.akenory.personalka.ui.reminders.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Приложение: одна база на всё время жизни процесса. Окно пересоздаётся при смене темы, база — нет.
 * Открывается при первом обращении, сама база создаётся Room в фоне при первом запросе. Если база в телефоне
 * прежней версии, перед переходом Room зовёт копию (ТЗ 10.2): к тому времени база уже собрана, и копия берёт её.
 */
class PersonalkaApplication : Application() {
    val database: PersonalkaDatabase by lazy {
        personalkaDatabase(this) { connection, from -> backup.beforeMigration(connection, from) }
    }
    val settings: SettingsRepository by lazy { SettingsRepository(database, System::currentTimeMillis) }
    val clients: ClientRepository by lazy { ClientRepository(database, System::currentTimeMillis) }
    val groups: GroupRepository by lazy { GroupRepository(database, System::currentTimeMillis) }
    val calendar: CalendarRepository by lazy { CalendarRepository(database, System::currentTimeMillis) }

    /** Настройки этого телефона: масштаб календаря, папка и ключ копий. */
    val prefs: DevicePrefs by lazy { devicePrefs(this) }

    /**
     * Резервная копия (шаг 1.15): папка — выбранная тренером через системный диалог; копии перед обновлением базы
     * (2.7б) — ещё и в личной папке приложения.
     */
    val backup: BackupRepository by lazy {
        val updates = UpdateCopies(fileBackupFolder(), File(filesDir, "update-backups").path)
        BackupRepository(database, prefs, safBackupFolder(this), updates, System::currentTimeMillis)
    }

    /** Аккаунт на сервере (шаг 2.8а): id тренера и адрес сервера — из базы, токен — в Android Keystore. */
    val account: AccountRepository by lazy {
        androidAccount(
            this,
            prefs,
            coachId = { settings.coach.first()?.id },
            serverUrl = { settings.coach.first()?.serverUrl },
        )
    }

    /**
     * Синхронизация (шаг 2.10а): адрес сервера — из базы, токен — тот же, что у [account]. Обмен, пока процесс жив, —
     * [syncRunner]; при закрытом приложении — [SyncJob].
     */
    val sync: SyncRepository by lazy { androidSync(this, database, prefs) { settings.coach.first()?.serverUrl } }
    val syncRunner: SyncRunner by lazy { SyncRunner(sync) }

    /** Ссылки кабинетов (шаг 2.13): тот же сервер и токен, что у [account]; токены ссылок — в Android Keystore. */
    val cabinets: CabinetRepository by lazy { androidCabinets(this, database) { settings.coach.first()?.serverUrl } }

    /** Напоминания (шаг 2.5): проверка по будильнику ([ReminderReceiver]) и время следующей. */
    val reminders: Reminders by lazy { Reminders(calendar, clients, backup, prefs, System::currentTimeMillis) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AndroidReminders.createChannels(this)
        // Пока процесс жив, будильник следует за настройками и тренировками: новая тренировка сразу получает
        // своё «Дневник сразу», выключенная сводка снимает будильник.
        scope.launch {
            reminders.nextCheck().collect { AndroidReminders.schedule(this@PersonalkaApplication, it) }
        }
        // Обмен с сервером (7.2, п. 4): сразу при запуске, через 5 секунд после правки, раз в 15 минут; фоновая задача
        // стоит, пока сервер подключён.
        syncRunner.start(scope)
        scope.launch {
            sync.state.map { it.connected }.distinctUntilChanged().collect { SyncJob.schedule(this@PersonalkaApplication, it) }
        }
    }

    /** «План на день» и разрешение не видны в базе — будильник переставляется по просьбе экрана. */
    fun remindersChanged() {
        scope.launch { AndroidReminders.schedule(this@PersonalkaApplication, reminders.nextCheck().first()) }
    }
}
