package com.akenory.personalka.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.akenory.personalka.ui.clients.MaxMessenger

/**
 * Макс на телефоне (шаг 3.9ж): номер — в буфер обмена, затем Макс открывается своим значком.
 * Пакет Макса — `ru.oneme.app`; с Android 11 приложение видит его, только если он назван в `<queries>` манифеста.
 */
class AndroidMax(private val context: Context) : MaxMessenger {
    override fun installed(): Boolean = launch() != null

    override fun open(phone: String): Boolean {
        val intent = launch() ?: return false
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Телефон", phone))
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun launch(): Intent? = try {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val PACKAGE = "ru.oneme.app"
    }
}
