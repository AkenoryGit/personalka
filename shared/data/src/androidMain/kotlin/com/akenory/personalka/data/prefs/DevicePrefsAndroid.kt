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

        override fun string(key: String): String? = prefs.getString(key, null)

        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun boolean(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
}
