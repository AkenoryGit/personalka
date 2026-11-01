package com.akenory.personalka.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.domain.format.WatchFormat
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import kotlinx.coroutines.flow.StateFlow

internal const val WATCH_NAMES_TAG = "settings-watch-names"

/**
 * Часы Galaxy Watch (шаг 5.3т). Есть только на Android: расписание и деньги уходят на часы сами, приложению остаются
 * счётчик дел (его считает только оно) и переключатель имён.
 */
interface WatchTools {
    /** К телефону подключены часы с Персоналкой — раздел «Часы» в настройках виден. */
    val connected: StateFlow<Boolean>

    /** «Имена клиентов на часах», по умолчанию включено. */
    val names: StateFlow<Boolean>

    fun setNames(on: Boolean)

    /** Счётчик дел «Главной» — для ячейки «дела». */
    fun todoCount(count: Int)
}

/** «Дневник и напоминания» → «Часы»: имена на часах можно выключить — экран часов видят все рядом. */
@Composable
internal fun WatchSettings(watch: WatchTools) {
    val connected by watch.connected.collectAsState()
    val names by watch.names.collectAsState()
    if (!connected) return
    SectionLabel(WatchFormat.SETTINGS_TITLE)
    SwitchRow(WatchFormat.NAMES_SWITCH, names, watch::setNames, Modifier.testTag(WATCH_NAMES_TAG), WatchFormat.NAMES_NOTE)
}
