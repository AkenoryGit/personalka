package com.akenory.personalka.data.prefs

import android.content.Context

/** Настройки телефона в `SharedPreferences` приложения: файл маленький, запись — в фоне. */
fun devicePrefs(context: Context): DevicePrefs {
    val prefs = context.applicationContext.getSharedPreferences("personalka-device", Context.MODE_PRIVATE)
    return object : DevicePrefs {
        override fun float(key: String): Float? = if (prefs.contains(key)) prefs.getFloat(key, 0f) else null

        override fun putFloat(key: String, value: Float) {
            prefs.edit().putFloat(key, value).apply()
        }
    }
}
