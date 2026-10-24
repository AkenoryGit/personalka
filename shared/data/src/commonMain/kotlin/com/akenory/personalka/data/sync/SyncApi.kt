package com.akenory.personalka.data.sync

import com.akenory.personalka.data.account.AccountException
import com.akenory.personalka.data.account.serverCall
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Адреса синхронизации на сервере (ТЗ 7.2, шаг 2.9): `POST /sync/push`, `GET /sync/pull`, `DELETE /account/data`.
 * [baseUrl] — тот же адрес, что у входа (`coach.server_url`). Сбой — [AccountException]: его код решает, повторять ли
 * (`offline`, `server_error`, `server_outdated`) или остановиться (`update_required`, `feature_closed`, `suspended`,
 * `unauthorized`); `full_sync` — получать всё с начала.
 */
class SyncApi(private val client: HttpClient, private val baseUrl: suspend () -> String) {
    /** Пачка изменений `{entity, id, version, updated_at, deleted_at, data}`; ответ — `results` по каждому, по порядку. */
    suspend fun push(token: String, schemaVersion: Int, device: String, changes: List<JsonObject>): JsonObject {
        val body = JsonObject(
            mapOf(
                "schema_version" to JsonPrimitive(schemaVersion),
                "device_id" to JsonPrimitive(device),
                "changes" to JsonArray(changes),
            ),
        )
        return serverCall {
            client.post(url("/sync/push")) {
                bearerAuth(token)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
    }

    /** Изменения после номера [since]: `changes`, `last_seq`, `has_more`. */
    suspend fun pull(token: String, schemaVersion: Int, since: Long, limit: Int): JsonObject = serverCall {
        client.get(url("/sync/pull")) {
            bearerAuth(token)
            parameter("since", since)
            parameter("limit", limit)
            parameter("schema_version", schemaVersion)
        }
    }

    /** «Отключиться от сервера»: записи тренера на сервере стираются (1.5). */
    suspend fun deleteData(token: String) {
        serverCall { client.delete(url("/account/data")) { bearerAuth(token) } }
    }

    private suspend fun url(path: String) = baseUrl().trimEnd('/') + "/api/v1" + path

    companion object {
        const val UPDATE_REQUIRED = "update_required"
        const val SERVER_OUTDATED = "server_outdated"
        const val FULL_SYNC = "full_sync"
        const val FEATURE_CLOSED = "feature_closed"
        const val SUSPENDED = "suspended"
    }
}
