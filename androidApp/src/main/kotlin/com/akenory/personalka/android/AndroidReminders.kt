package com.akenory.personalka.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.akenory.personalka.R
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.reminders.Notice
import com.akenory.personalka.ui.reminders.NoticeChannel
import com.akenory.personalka.ui.reminders.NotificationAccess
import kotlinx.datetime.LocalDateTime

/**
 * Уведомления на Android (шаг 2.5, ТЗ 5.19): два канала, будильник следующей проверки и показ того, что решили
 * [com.akenory.personalka.ui.reminders.Reminders]. Все уведомления локальные — сервер для них не нужен.
 *
 * Будильник — неточный `setAndAllowWhileIdle`: точный требует отдельного разрешения у тренера, а сводке в 21:03
 * вместо 21:00 это не мешает. Телефон в глубоком сне всё равно будится.
 */
object AndroidReminders {
    private const val CHANNEL_DIGEST = "digest"
    private const val CHANNEL_INSTANT = "instant"
    const val ACTION_CHECK = "com.akenory.personalka.REMINDERS"
    const val EXTRA_LINK = "link"

    /** Каналы создаются при каждом запуске: повторное создание ничего не меняет, выбор тренера в настройках остаётся. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DIGEST, ReminderFormat.CHANNEL_DIGEST, NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_INSTANT, ReminderFormat.CHANNEL_INSTANT, NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Будильник следующей проверки на время клуба [at]; `null` — напоминания выключены, будильник снимается. */
    fun schedule(context: Context, at: LocalDateTime?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarms.cancel(pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ClubTime.toMillis(at), pending)
        }
    }

    /** Показать уведомления; без разрешения ничего не показывается (и не падает). */
    fun show(context: Context, notices: List<Notice>) {
        if (!allowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        for (notice in notices) {
            val id = notice.key.hashCode()
            val builder = NotificationCompat.Builder(context, if (notice.channel == NoticeChannel.DIGEST) CHANNEL_DIGEST else CHANNEL_INSTANT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setContentIntent(open(context, id * 2, notice.open))
                .setAutoCancel(true)
            notice.diary?.let { builder.addAction(0, ReminderFormat.DIARY_ACTION, open(context, id * 2 + 1, it)) }
            try {
                manager.notify(id, builder.build())
            } catch (_: SecurityException) {
                return
            }
        }
    }

    /** Открыть приложение со ссылкой: окно уже открыто — оно получает её в `onNewIntent`, второе не создаётся. */
    private fun open(context: Context, code: Int, link: AppLink): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_LINK, link.code)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Разрешение Android 13+ есть и уведомления приложения не выключены в настройках телефона. */
    fun allowed(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/**
 * Уведомления для экрана настроек и первого запуска: на Android 13+ разрешение спрашивается один раз, когда тренер
 * уже прошёл первый запуск; дальше «Включить» открывает настройки уведомлений приложения. Создаётся в `onCreate`
 * окна: окно разрешения регистрируется до его показа.
 */
class AndroidNotifications(
    private val activity: ComponentActivity,
    private val prefs: DevicePrefs,
    private val onChanged: () -> Unit,
) : NotificationAccess {
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    override fun enabled(): Boolean = AndroidReminders.allowed(activity)

    override fun enable() {
        if (askOnce()) return
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        activity.startActivity(intent)
    }

    override fun changed() = onChanged()

    /** Спросить разрешение Android 13+, если ещё не спрашивали; `true` — окно разрешения показано. */
    fun askOnce(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || enabled()) return false
        if (prefs.boolean(ASKED) == true) return false
        prefs.putBoolean(ASKED, true)
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    private companion object {
        const val ASKED = "notifications.asked"
    }
}
