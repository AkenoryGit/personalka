package com.akenory.personalka.ui.ios

import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.iosAccount
import com.akenory.personalka.data.account.payTypes
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.backup.UpdateCopies
import com.akenory.personalka.data.backup.bookmarkFolder
import com.akenory.personalka.data.backup.fileBackupFolder
import com.akenory.personalka.data.cabinet.CabinetRepository
import com.akenory.personalka.data.cabinet.iosCabinets
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
import com.akenory.personalka.data.sync.iosSync
import com.akenory.personalka.ui.reminders.Reminders
import com.akenory.personalka.ui.shell.systemMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Приложение на iPhone (шаг 5.1) — то же, что `PersonalkaApplication` на Android: одна база на всё время жизни процесса,
 * репозитории поверх неё. Файлы — в Application Support (не видны в «Файлах» и не теряются при нехватке места).
 */
object IosApp {
    /** Личная папка приложения: база, копии перед обновлением, фото аватарок. */
    val folder: String by lazy { supportFolder() }

    val database: PersonalkaDatabase by lazy {
        personalkaDatabase(folder) { connection, from -> backup.beforeMigration(connection, from) }
    }
    val settings: SettingsRepository by lazy { SettingsRepository(database, account.payTypes(::systemMillis), ::systemMillis) }
    val clients: ClientRepository by lazy { ClientRepository(database, ::systemMillis) }
    val groups: GroupRepository by lazy { GroupRepository(database, ::systemMillis) }
    val calendar: CalendarRepository by lazy { CalendarRepository(database, ::systemMillis, account.payTypes(::systemMillis)) }
    val prefs: DevicePrefs by lazy { devicePrefs() }

    /** Копии: папка — выбранная в «Файлах» (закладка); копии перед обновлением базы — в личной папке. */
    val backup: BackupRepository by lazy {
        val updates = UpdateCopies(fileBackupFolder(), "$folder/update-backups")
        BackupRepository(database, prefs, bookmarkFolder(), updates, account.payTypes(::systemMillis), ::systemMillis)
    }

    val account: AccountRepository by lazy {
        iosAccount(prefs, coachId = { settings.coach.first()?.id }, serverUrl = { settings.coach.first()?.serverUrl })
    }
    val sync: SyncRepository by lazy { iosSync(database, prefs, owner = { account.account.value?.email }) { settings.coach.first()?.serverUrl } }
    val syncRunner: SyncRunner by lazy { SyncRunner(sync) }
    val cabinets: CabinetRepository by lazy { iosCabinets(database) { settings.coach.first()?.serverUrl } }
    val reminders: Reminders by lazy { Reminders(calendar, clients, backup, prefs, ::systemMillis, settings.months) }

    /** Версия для «О приложении»: «0.1 (12)» — версия и номер сборки из пакета. */
    val versionName: String by lazy {
        val info = NSBundle.mainBundle.infoDictionary
        "${info?.get("CFBundleShortVersionString") ?: "0"} (${info?.get("CFBundleVersion") ?: "0"})"
    }

    /** Фоновая работа: сбой обмена не роняет приложение. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    private var started = false

    /** Запуск (из Swift до первого экрана): обмен с сервером и уведомления наперёд. */
    fun start() {
        if (started) return
        started = true
        syncRunner.start(scope)
        IosNotifications.start(scope)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun supportFolder(): String {
        val manager = NSFileManager.defaultManager
        val url = manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null) as NSURL
        val dir = url.URLByAppendingPathComponent("Personalka")!!
        manager.createDirectoryAtURL(dir, true, null, null)
        return dir.path!!
    }
}
