package com.akenory.personalka.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.akenory.personalka.BuildConfig
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.clients.CabinetTools
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.settings.BackupTools
import com.akenory.personalka.ui.settings.SyncTools
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    /** Нажатое уведомление (шаг 2.5): приложение открывает нужный экран и сбрасывает ссылку. */
    private var link by mutableStateOf<AppLink?>(null)

    /** Первый показ окна — обмен уже идёт с запуска приложения; дальше каждый возврат — обмен сразу (7.2, п. 4). */
    private var shown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Экран во весь размер, значки строки состояния — по теме телефона, как и тема приложения.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Сложение и разложение Fold окно не пересоздаёт (configChanges в манифесте): Compose просто
        // перекладывает экран под новую ширину, открытый раздел и прокрутка остаются на месте.
        val app = application as PersonalkaApplication
        val backup = BackupTools(app.backup, AndroidFiles(this))
        val sync = SyncTools(app.sync, app.syncRunner::now)
        val cabinets = CabinetTools(app.cabinets, ::share, ::copy)
        val voice = if (AndroidVoice.availableOn(this)) AndroidVoice(this) else null
        val notifications = AndroidNotifications(this, app.prefs, app::remindersChanged)
        // Пересоздание окна при смене темы не открывает уведомление второй раз.
        if (savedInstanceState == null) link = linkOf(intent)
        setContent {
            // Разрешение на уведомления (Android 13+) — один раз, когда первый запуск пройден: сводка включена сразу.
            LaunchedEffect(Unit) {
                app.settings.coach.first { it != null }
                notifications.askOnce()
            }
            App(
                versionName = BuildConfig.VERSION_NAME,
                settings = app.settings,
                clients = app.clients,
                groups = app.groups,
                calendar = app.calendar,
                prefs = app.prefs,
                backup = backup,
                account = app.account,
                sync = sync,
                cabinets = cabinets,
                voice = voice,
                reminders = app.reminders,
                notifications = notifications,
                link = link,
                onLink = { link = null },
                backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (shown) (application as PersonalkaApplication).syncRunner.now()
        shown = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link = linkOf(intent)
    }

    /** «Отправить» ссылку кабинета (5.9): системное «Поделиться» — мессенджер выбирает тренер. */
    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    /** «Скопировать» — тот же текст, что у «Отправить»: ссылка и код. */
    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(CabinetLinkFormat.BUTTON, text))
    }

    private fun linkOf(intent: Intent?): AppLink? = AppLink.parse(intent?.getStringExtra(AndroidReminders.EXTRA_LINK))
}
