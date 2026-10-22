package com.akenory.personalka.data.prefs

/**
 * Настройки этого телефона, а не тренера: масштаб календаря (5.3), позже — последний вид отдельно для сложенного
 * и разложенного Fold (шаг 1.12). В базу, копию и синхронизацию не попадают: на другом телефоне — свой экран.
 */
interface DevicePrefs {
    fun float(key: String): Float?

    fun putFloat(key: String, value: Float)

    companion object {
        /** Высота часа в календаре, dp. */
        const val CALENDAR_HOUR = "calendar.hourDp"
    }
}

/** Настройки в памяти: для тестов и платформ, где своего хранилища ещё нет. */
class MemoryPrefs : DevicePrefs {
    private val values = mutableMapOf<String, Float>()

    override fun float(key: String): Float? = values[key]

    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }
}
