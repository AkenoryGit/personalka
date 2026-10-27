package com.akenory.personalka.ui.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.clients.CabinetTools
import com.akenory.personalka.ui.components.ProvideAvatarPhotos
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.SyncTools
import kotlinx.coroutines.flow.first
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

// Входы для Swift (iosApp): Kotlin-имена файла видны там как MainViewControllerKt.*.

/** Нажатое уведомление или открытый файл: приложение открывает нужный экран и сбрасывает ссылку. */
private var link by mutableStateOf<AppLink?>(null)

/** Запуск до первого экрана: база, обмен с сервером, уведомления наперёд. */
fun startPersonalka() = IosApp.start()

/** Приложение снова на экране (Swift: scenePhase .active) — обмен сразу и уведомления заново. */
fun onAppActive() {
    IosApp.syncRunner.now()
    IosNotifications.onActive()
}

/** Ссылка из уведомления (`userInfo["link"]`, у кнопки «Дневник» — `userInfo["diary"]`). */
fun openLink(code: String?) {
    AppLink.parse(code)?.let { link = it }
}

/** Файл, открытый в «Персоналке» из почты или «Файлов»: PDF — отчёт 1С в «Сверках». */
fun openUrl(url: NSURL) {
    if (IosFiles.receive(url)) link = AppLink.Report1c
}

/** Всё приложение — общие экраны Compose (модуль shared/ui) на весь экран. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val app = IosApp
    LaunchedEffect(Unit) {
        // Разрешение на уведомления — один раз, когда первый запуск пройден: сводка включена сразу.
        app.settings.coach.first { it != null }
        IosNotifications.askOnce()
    }
    val backup = remember { BackupTools(app.backup, IosFiles) }
    val sync = remember { SyncTools(app.sync, app.syncRunner::now) }
    // Кабинет родителя открывается в Safari: своего окна браузера в приложении на iPhone пока нет.
    val cabinets = remember {
        CabinetTools(app.cabinets, IosFiles::shareText, IosFiles::copy, app.sync) { url, onClose, _ ->
            LaunchedEffect(url) {
                UIApplication.sharedApplication.openURL(NSURL(string = url), emptyMap<Any?, Any>(), null)
                onClose()
            }
        }
    }
    ProvideAvatarPhotos(IosPhotos, app.clients.ids, app.cabinets.consents) {
        App(
            versionName = app.versionName,
            settings = app.settings,
            clients = app.clients,
            groups = app.groups,
            calendar = app.calendar,
            prefs = app.prefs,
            backup = backup,
            account = app.account,
            sync = sync,
            cabinets = cabinets,
            shareText = IosFiles::shareText,
            reminders = app.reminders,
            notifications = IosNotifications,
            link = link,
            onLink = { link = null },
        )
    }
}
