package com.akenory.personalka.data.account

import com.akenory.personalka.domain.format.AccountFormat
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Ошибка сервера или связи: [code] — из ответа (`wrong_code`, `email_not_confirmed`, ТЗ 7.1) или свой
 * ([OFFLINE], [SERVER]); [message] — текст для тренера, по-русски.
 */
class AccountException(val code: String, override val message: String) : Exception(message) {
    companion object {
        const val OFFLINE = "offline"
        const val SERVER = "server_error"
        const val UNAUTHORIZED = "unauthorized"
        const val NOT_CONFIRMED = "email_not_confirmed"
    }
}

/**
 * Адреса входа на сервере (ТЗ 7.1, шаг 2.8): JSON деревом, как на сервере, без плагина сериализации.
 * [baseUrl] — адрес сервера тренера (`coach.server_url`, 1.5), читается перед каждым запросом: переезд сервера
 * не требует перезапуска. Любой сбой — [AccountException] с текстом для показа.
 */
class AccountApi(private val client: HttpClient, private val baseUrl: suspend () -> String) {
    suspend fun register(email: String, password: String, name: String, coachId: String?): JsonObject =
        post("/auth/register", "email" to email, "password" to password, "display_name" to name, "coach_id" to coachId)

    suspend fun confirm(email: String, password: String, code: String, device: String): JsonObject =
        post("/auth/confirm", "email" to email, "password" to password, "code" to code, "device_name" to device)

    suspend fun login(email: String, password: String, device: String): JsonObject =
        post("/auth/login", "email" to email, "password" to password, "device_name" to device)

    suspend fun requestReset(email: String): JsonObject = post("/auth/reset/request", "email" to email)

    suspend fun reset(email: String, code: String, password: String, device: String): JsonObject =
        post("/auth/reset", "email" to email, "code" to code, "password" to password, "device_name" to device)

    suspend fun me(token: String): JsonObject = call { client.get(url("/me")) { bearerAuth(token) } }

    suspend fun logout(token: String) {
        call { client.post(url("/auth/logout")) { bearerAuth(token) } }
    }

    suspend fun closeSession(token: String, id: Long) {
        call { client.delete(url("/me/sessions/$id")) { bearerAuth(token) } }
    }

    private suspend fun url(path: String) = baseUrl().trimEnd('/') + "/api/v1" + path

    private suspend fun post(path: String, vararg fields: Pair<String, String?>): JsonObject {
        val body = JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })
        return call { client.post(url(path)) { setBody(TextContent(body.toString(), ContentType.Application.Json)) } }
    }

    private suspend fun call(request: suspend () -> HttpResponse): JsonObject = serverCall(request)

    companion object {
        /** Сервер по умолчанию — домен Дмитрия (1.5). */
        const val DEFAULT_URL = "https://trener.planovo-app.ru"
    }
}

/**
 * Запрос к серверу «Персоналки»: ответ — JSON-объект; ошибка — [AccountException] с кодом и текстом сервера, без связи —
 * [AccountException.OFFLINE], чужой ответ — [AccountException.SERVER]. Общий для входа и синхронизации (2.10).
 */
internal suspend fun serverCall(request: suspend () -> HttpResponse): JsonObject {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AccountException(AccountException.OFFLINE, AccountFormat.OFFLINE)
    }
    val text = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AccountException(AccountException.OFFLINE, AccountFormat.OFFLINE)
    }
    val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    if (response.status.isSuccess()) return json ?: throw AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)
    // Ответ не от «Персоналки» (Caddy, страница ошибки) — без кода: общий текст.
    val code = json?.string("code")
    val message = json?.string("message")
    throw if (code != null && message != null) AccountException(code, message) else AccountException(AccountException.SERVER, AccountFormat.SERVER_ERROR)
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
