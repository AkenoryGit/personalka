package com.akenory.personalka.data.account

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.akenory.personalka.data.prefs.DevicePrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Токен сессии под ключом Android Keystore (ТЗ 7.3): ключ AES-256 не покидает защищённое хранилище телефона,
 * в настройках приложения лежит только шифр (AES-GCM, IV — в начале). Не расшифровался (ключ стёрт вместе
 * с блокировкой экрана, телефон сменился) — входа нет, тренер входит заново.
 */
class KeystoreTokens(context: Context) : TokenStore {
    private val box = KeystoreBox(context, "personalka-account")

    override fun read(): String? = box.read(TOKEN)

    override fun write(token: String?) = box.write(TOKEN, token)

    private companion object {
        const val TOKEN = "token"
    }
}

/**
 * Строки под ключом Android Keystore в своём файле настроек [file]: AES-256-GCM, IV — в начале шифра. Ключ один
 * на приложение. Не расшифровалась — `null`, как будто её нет.
 */
class KeystoreBox(context: Context, file: String) {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV))
            String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "personalka-session"
        const val AES = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** Имя телефона для списка устройств: «Samsung SM-F976B». */
fun deviceName(): String {
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}

/**
 * Аккаунт на Android: клиент Ktor на HttpURLConnection, токен — в Keystore. [serverUrl] — адрес сервера тренера
 * (`coach.server_url`), пусто — сервер по умолчанию.
 */
fun androidAccount(
    context: Context,
    prefs: DevicePrefs,
    coachId: suspend () -> String?,
    serverUrl: suspend () -> String?,
): AccountRepository {
    val api = AccountApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return AccountRepository(api, KeystoreTokens(context), prefs, deviceName(), coachId)
}

/** Клиент Ktor для сервера: движок `ktor-client-android` (HttpURLConnection), таймауты 15/30 с — вход и синхронизация. */
internal fun serverClient(): HttpClient = HttpClient(Android) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
    }
}
