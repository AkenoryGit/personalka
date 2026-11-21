package com.akenory.personalka.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.akenory.personalka.BuildConfig
import com.akenory.personalka.domain.about.UpdateRules
import com.akenory.personalka.ui.about.AboutTools
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «О приложении» на Android (шаг 5.33): последняя сборка — из релиза «Последняя сборка» на GitHub (ТЗ 7.7, без своего
 * сервера), APK скачивает браузер; журнал ошибок — файл в папке приложения ([PersonalkaApplication.crashLogFile]).
 * В запросе к GitHub нет ничего о тренере: только адрес релиза.
 */
class AndroidAbout(private val context: Context, private val crashLog: File) : AboutTools {
    override val build: Int = BuildConfig.VERSION_CODE

    override suspend fun release(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(UpdateRules.RELEASE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Personalka")
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override fun download() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRules.APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            // Браузера нет — скачать нечем; кнопка просто ничего не делает.
        }
    }

    override suspend fun crashLog(): String? = withContext(Dispatchers.IO) {
        runCatching { crashLog.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    override suspend fun clearCrashLog() {
        withContext(Dispatchers.IO) { runCatching { crashLog.delete() } }
    }

    private companion object {
        const val TIMEOUT = 10_000
    }
}
