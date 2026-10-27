package com.akenory.personalka.data.account

import com.akenory.personalka.data.backup.toByteArray
import com.akenory.personalka.data.backup.toNSData
import com.akenory.personalka.data.prefs.DevicePrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIDevice

/** Токен сессии в Связке ключей iPhone (ТЗ 7.3): только на этом телефоне, доступен после первой разблокировки. */
class KeychainTokens : TokenStore {
    private val box = KeychainBox("personalka-account")

    override fun read(): String? = box.read(TOKEN)

    override fun write(token: String?) = box.write(TOKEN, token)

    private companion object {
        const val TOKEN = "token"
    }
}

/** Строки в Связке ключей: служба [service], имя — ключ записи. Не прочиталась — `null`, как будто её нет. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainBox(private val service: String) {
    fun read(name: String): String? = memScoped {
        val query = query(name)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        data.toByteArray().decodeToString()
    }

    fun write(name: String, value: String?) {
        val old = query(name)
        SecItemDelete(old)
        CFRelease(old)
        if (value == null) return
        val add = query(name)
        val data = CFBridgingRetain(value.encodeToByteArray().toNSData())
        CFDictionaryAddValue(add, kSecValueData, data)
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        SecItemAdd(add, null)
        CFRelease(data)
        CFRelease(add)
    }

    private fun query(name: String): CFMutableDictionaryRef {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        val s = CFBridgingRetain(NSString.create(string = service))
        val a = CFBridgingRetain(NSString.create(string = name))
        CFDictionaryAddValue(dict, kSecAttrService, s)
        CFDictionaryAddValue(dict, kSecAttrAccount, a)
        CFRelease(s)
        CFRelease(a)
        return dict
    }
}

/** Имя телефона для списка устройств: «iPhone · iOS 26.5» (имя, данное хозяином, iOS 16+ не отдаёт). */
fun deviceName(): String {
    val device = UIDevice.currentDevice
    return "${device.model} · iOS ${device.systemVersion}"
}

/** Аккаунт на iPhone: клиент Ktor на NSURLSession, токен — в Связке ключей. */
fun iosAccount(
    prefs: DevicePrefs,
    coachId: suspend () -> String?,
    serverUrl: suspend () -> String?,
): AccountRepository {
    val api = AccountApi(serverClient()) { serverUrl()?.takeIf { it.isNotBlank() } ?: AccountApi.DEFAULT_URL }
    return AccountRepository(api, KeychainTokens(), prefs, deviceName(), coachId)
}

/** Клиент Ktor для сервера: движок Darwin (NSURLSession), таймауты 15/30 с — как на Android. */
internal fun serverClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
    }
}
