package com.akenory.personalka.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Телефон прислал новое расписание (шаг 5.3т): запомнить и перерисовать ячейки и плитку. */
class DayListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchStore.PATH) {
                DataMapItem.fromDataItem(event.dataItem).dataMap.getString("day")?.let { WatchStore.save(this, it) }
            }
        }
    }
}
