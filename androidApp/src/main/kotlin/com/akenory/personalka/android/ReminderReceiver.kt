package com.akenory.personalka.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Будильник напоминаний (шаг 2.5) и события телефона, после которых будильник надо поставить заново: перезагрузка,
 * обновление приложения, смена времени или пояса. Проверка идёт в фоне ([goAsync]) — приложение может быть закрыто.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext as PersonalkaApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val check = app.reminders.check()
                AndroidReminders.show(app, check.notices)
                AndroidReminders.schedule(app, check.next)
            } catch (_: Exception) {
                // Не вышло (база занята восстановлением копии) — будильник поставит следующий запуск приложения.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            AndroidReminders.ACTION_CHECK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
