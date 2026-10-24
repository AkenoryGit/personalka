package com.akenory.personalka.android

import android.app.Application
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.backup.UpdateCopies
import com.akenory.personalka.data.backup.fileBackupFolder
import com.akenory.personalka.data.backup.safBackupFolder
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.personalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.devicePrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.ui.reminders.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
    }

    /** «План на день» и разрешение не видны в базе — будильник переставляется по просьбе экрана. */
    fun remindersChanged() {
        scope.launch { AndroidReminders.schedule(this@PersonalkaApplication, reminders.nextCheck().first()) }
    }
}
