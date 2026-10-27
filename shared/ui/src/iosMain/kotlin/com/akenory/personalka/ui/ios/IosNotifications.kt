package com.akenory.personalka.ui.ios

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.ui.reminders.Notice
import com.akenory.personalka.ui.reminders.NotificationAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Уведомления на iPhone (шаг 5.1, ТЗ 5.19). Будильника, который разбудит приложение в нужную минуту, у iPhone нет,
 * поэтому система получает готовые уведомления наперёд ([com.akenory.personalka.ui.reminders.Reminders.upcoming]):
 * при запуске, при каждом возврате в приложение и при каждой правке тренировок и настроек они ставятся заново.
 * Нажатие ведёт по ссылке [LINK] (её разбирает Swift в `AppDelegate` и отдаёт [openLink]); у сводки — кнопка «Дневник».
 */
object IosNotifications : NotificationAccess {
    const val LINK = "link"
    const val DIARY_LINK = "diary"
    const val DIARY_ACTION = "diary"
    private const val DIGEST_CATEGORY = "digest"
    private const val ASKED = "notifications.asked"

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Последний известный ответ системы: разрешение читается асинхронно, экран спрашивает синхронно. */
    private var allowed = false
    private var scope: CoroutineScope? = null
    private var planning: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        val diary = UNNotificationAction.actionWithIdentifier(DIARY_ACTION, ReminderFormat.DIARY_ACTION, UNNotificationActionOptionForeground)
        center.setNotificationCategories(
            setOf(UNNotificationCategory.categoryWithIdentifier(DIGEST_CATEGORY, listOf(diary), emptyList<String>(), UNNotificationCategoryOptionNone)),
        )
        refresh()
        // Тренировки и настройки меняются — уведомления наперёд ставятся заново (как будильник на Android).
        scope.launch { IosApp.reminders.nextCheck().collectLatest { plan() } }
    }

    /** Приложение снова на экране: закрыть прошедшие и продлить серии (то, что на Android делает будильник), пересчитать. */
    fun onActive() {
        val scope = scope ?: return
        refresh()
        scope.launch {
            runCatching { IosApp.reminders.check() }
            plan()
        }
    }

    /** Разрешение спрашивается один раз, когда первый запуск пройден (как на Android 13+). */
    fun askOnce() {
        if (IosApp.prefs.boolean(ASKED) == true) return
        IosApp.prefs.putBoolean(ASKED, true)
        request()
    }

    override fun enabled(): Boolean = allowed

    override fun enable() {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (settings?.authorizationStatus == UNAuthorizationStatusNotDetermined) {
                request()
            } else {
                onMain { UIApplication.sharedApplication.openURL(NSURL(string = UIApplicationOpenSettingsURLString), emptyMap<Any?, Any>(), null) }
            }
        }
    }

    override fun changed() {
        scope?.launch { plan() }
    }

    private fun request() {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { granted, _ ->
            allowed = granted
            changed()
        }
    }

    private fun refresh() {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            allowed = status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional
        }
    }

    /** Снять поставленные и поставить заново на ближайшие проверки. */
    private suspend fun plan() {
        val upcoming = runCatching { IosApp.reminders.upcoming() }.getOrDefault(emptyList())
        center.removeAllPendingNotificationRequests()
        upcoming.forEach { (at, notice) -> center.addNotificationRequest(request(at, notice), null) }
    }

    private fun request(at: kotlinx.datetime.LocalDateTime, notice: Notice): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setTitle(notice.title)
            setBody(notice.text)
            setSound(UNNotificationSound.defaultSound)
            setThreadIdentifier(notice.channel.name)
            val info = mutableMapOf<Any?, Any?>(LINK to notice.open.code)
            notice.diary?.let {
                info[DIARY_LINK] = it.code
                setCategoryIdentifier(DIGEST_CATEGORY)
            }
            setUserInfo(info)
        }
        val date = NSDate.dateWithTimeIntervalSince1970(ClubTime.toMillis(at) / 1000.0)
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute
        val components = NSCalendar.currentCalendar.components(units, date)
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        // Одно и то же уведомление в одну минуту — одна заявка; повтор того же ключа позже заменяет прежнее в центре.
        return UNNotificationRequest.requestWithIdentifier("${notice.key}@$at", content, trigger)
    }
}
