package com.akenory.personalka.data.prefs

import platform.Foundation.NSUserDefaults

/** Настройки телефона на iPhone — `NSUserDefaults` приложения, ключи с приставкой `personalka.`. */
fun devicePrefs(): DevicePrefs {
    val defaults = NSUserDefaults.standardUserDefaults
    fun k(key: String) = "personalka.$key"
    return object : DevicePrefs {
        override fun float(key: String): Float? = if (defaults.objectForKey(k(key)) != null) defaults.floatForKey(k(key)) else null

        override fun putFloat(key: String, value: Float) = defaults.setFloat(value, k(key))

        override fun string(key: String): String? = defaults.stringForKey(k(key))

        override fun putString(key: String, value: String) = defaults.setObject(value, k(key))

        override fun boolean(key: String): Boolean? = if (defaults.objectForKey(k(key)) != null) defaults.boolForKey(k(key)) else null

        override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
    }
}
