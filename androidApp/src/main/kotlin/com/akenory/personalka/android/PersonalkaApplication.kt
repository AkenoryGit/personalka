package com.akenory.personalka.android

import android.app.Application
import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.clients.ClientRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.personalkaDatabase
import com.akenory.personalka.data.groups.GroupRepository
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.devicePrefs
import com.akenory.personalka.data.settings.SettingsRepository

/**
 * Приложение: одна база на всё время жизни процесса. Окно пересоздаётся при смене темы, база — нет.
 * Открывается при первом обращении, сама база создаётся Room в фоне при первом запросе.
 */
class PersonalkaApplication : Application() {
    val database: PersonalkaDatabase by lazy { personalkaDatabase(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(database, System::currentTimeMillis) }
    val clients: ClientRepository by lazy { ClientRepository(database, System::currentTimeMillis) }
    val groups: GroupRepository by lazy { GroupRepository(database, System::currentTimeMillis) }
    val calendar: CalendarRepository by lazy { CalendarRepository(database) }

    /** Настройки этого телефона: масштаб календаря. */
    val prefs: DevicePrefs by lazy { devicePrefs(this) }
}
