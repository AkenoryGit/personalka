package com.akenory.personalka.android

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Сообщения с часов (шаг 5.3т). «Проведено» / «Все пришли» на плитке — та же отметка, что в карточке
 * (`TrainingCards.markDone`): правила те же, нельзя — ничего не меняется. «Пришлите данные» — часы только что
 * установлены или потеряли расписание. Android будит процесс сам, даже если приложение закрыто; колбэк — не в главном
 * потоке, поэтому ждать базу здесь можно.
 */
class WatchActionService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val app = application as PersonalkaApplication
        when (event.path) {
            WatchBridge.MARK -> {
                val id = String(event.data, Charsets.UTF_8)
                runCatching { runBlocking { app.calendar.cards.markDone(id) } }
            }
            WatchBridge.HELLO -> app.watch.resend()
        }
    }
}
