package com.akenory.personalka.data.cabinet

import com.akenory.personalka.data.account.serverCall
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Адреса ссылок кабинетов на сервере (ТЗ 7.1, шаги 2.11–2.13): список с «последний раз открывали» и согласиями,
 * новая ссылка, смена кода. [baseUrl] — тот же адрес, что у входа. Сбой — [com.akenory.personalka.data.account.AccountException].
 */
class CabinetApi(private val client: HttpClient, private val baseUrl: suspend () -> String) {
    /** `cabinets` (`client_id`, `created_at`, `last_opened_at`, `pin`) и `consents` (последнее согласие клиента). */
    suspend fun list(token: String): JsonObject = serverCall { client.get(url("")) { bearerAuth(token) } }

    /** Новая ссылка: прежняя отзывается. [tokenHash] — SHA-256 токена в base64url, [pin] — 4 цифры или нет. */
    suspend fun issue(token: String, clientId: String, tokenHash: String, pin: String?) {
        put(token, "/$clientId", "token_hash" to tokenHash, "pin" to pin)
    }

    /** Код действующей ссылки: новый или `null` — без кода. */
    suspend fun changePin(token: String, clientId: String, pin: String?) {
        put(token, "/$clientId/pin", "pin" to pin)
    }

    /** Адрес сервера — для ссылки, которую увидит родитель. */
    suspend fun server(): String = baseUrl().trimEnd('/')

    private suspend fun put(token: String, path: String, vararg fields: Pair<String, String?>) {
        val body = JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })
        serverCall {
            client.put(url(path)) {
                bearerAuth(token)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
    }

    private suspend fun url(path: String) = baseUrl().trimEnd('/') + "/api/v1/cabinets" + path
}
