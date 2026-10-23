package com.akenory.personalka.data.prefs

/**
 * Настройки этого телефона, а не тренера: масштаб календаря (5.3), последний вид отдельно для сложенного
 * и разложенного Fold и показ отменённых (шаг 1.12). В базу, копию и синхронизацию не попадают: на другом
 * телефоне — свой экран.
 */
interface DevicePrefs {
    fun float(key: String): Float?

    fun putFloat(key: String, value: Float)

    fun string(key: String): String?

    fun putString(key: String, value: String)

    fun boolean(key: String): Boolean?

    fun putBoolean(key: String, value: Boolean)

    companion object {
        /** Высота часа в календаре, dp. */
        const val CALENDAR_HOUR = "calendar.hourDp"

        /** Последний вид календаря (`CalendarView.code`) на сложенном и на разложенном экране. */
        const val CALENDAR_VIEW_FOLDED = "calendar.view.folded"
        const val CALENDAR_VIEW_WIDE = "calendar.view.wide"

        /** Показывать ли отменённые тренировки; по умолчанию — да. */
        const val CALENDAR_SHOW_CANCELLED = "calendar.showCancelled"
    }
}

/** Настройки в памяти: для тестов и платформ, где своего хранилища ещё нет. */
class MemoryPrefs : DevicePrefs {
    private val values = mutableMapOf<String, Any>()

    override fun float(key: String): Float? = values[key] as? Float

    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }

    override fun string(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun boolean(key: String): Boolean? = values[key] as? Boolean

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
}
