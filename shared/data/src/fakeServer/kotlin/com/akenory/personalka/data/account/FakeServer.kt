package com.akenory.personalka.data.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import com.akenory.personalka.domain.sync.SchemaProblem
import com.akenory.personalka.domain.sync.SyncOrder
import com.akenory.personalka.domain.sync.SyncRules
import com.akenory.personalka.domain.sync.SyncStamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.IOException

/**
 * Сервер входа и синхронизации в памяти — те же адреса, ответы и коды ошибок, что `server/.../AuthRoutes.kt`
 * и `SyncRoutes.kt` (ТЗ 7.1, 7.2). Код в письме всегда [CODE]; [offline] — нет связи; [loseAnswers] — запрос
 * доходит и выполняется, а ответ теряется (обрыв сети посреди обмена).
 */
class FakeServer {
    class Coach(val id: String, val email: String, var password: String, val name: String, var confirmed: Boolean)

    data class Session(val id: Long, val coach: Coach, val device: String, val token: String)

    val coaches = mutableMapOf<String, Coach>()
    val sessions = mutableListOf<Session>()
    val requests = mutableListOf<Pair<String, JsonObject?>>()
    val codesSent = mutableListOf<String>()
    var offline = false
    var loseAnswers = false
    var admin: String? = null
    private var next = 1L

    /** Почты, у которых открыта «Копия на сервере» (у администратора открыто всё). */
    val serverCopy = mutableSetOf<String>()

    /** Версия схемы сервера: старше телефона — 503, телефон старше предыдущей — 426. */
    var schemaVersion = SyncRules.SCHEMA_VERSION

    /** Запись на сервере: как `sync_row` — последнее состояние строки и её номер. */
    data class Row(
        val owner: String, val entity: String, val id: String, val version: Long, val updatedAt: Long,
        val deletedAt: Long?, val device: String, val data: JsonObject, val seq: Long,
    )

    val rows = mutableMapOf<Triple<String, String, String>, Row>()
    private var seq = 0L

    /** Удалённые до этого номера стёрты (чистка 90 дней): выдача с меньшего `since` — 410. */
    var purgedSeq = 0L

    /** Принятые изменения по порядку — проверить, что повтор отправки не пишет дубль. */
    val accepted = mutableListOf<Row>()

    /** Что сделать, когда телефон пришёл за изменениями, — правка на телефоне посреди обмена. */
    var beforePull: (suspend () -> Unit)? = null

    val client = HttpClient(
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("/sync/pull")) beforePull?.invoke()
            handle(request)
        },
    )

    fun api(url: String = "https://trener.example.ru") = AccountApi(client) { url }

    /** Аккаунт с подтверждённой почтой и одним устройством «Второй телефон». */
    fun signedUp(email: String, password: String, name: String = "Тренер"): Coach {
        val coach = Coach("c-$email", email, password, name, true)
        coaches[email] = coach
        sessions += Session(next++, coach, "Второй телефон", "t-other")
        return coach
    }

    /** Записи тренера [email] без удалённых. */
    fun liveRows(email: String, entity: String): List<Row> =
        rows.values.filter { it.owner == coaches.getValue(email).id && it.entity == entity && it.deletedAt == null }

    /** Чистка старых удалений: всё удалённое стирается, выдача с номера до [seq] — 410. */
    fun purgeDeleted() {
        rows.values.removeAll { it.deletedAt != null }
        purgedSeq = seq
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        if (offline) throw IOException("нет сети")
        val answer = route(request)
        if (loseAnswers) throw IOException("ответ потерян")
        return answer
    }

    private fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath.removePrefix("/api/v1")
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
        requests += path to body
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        fun text(name: String) = (body?.get(name) as? JsonPrimitive)?.content.orEmpty()
        return when {
            request.method == HttpMethod.Post && path == "/auth/register" -> {
                val existing = coaches[text("email")]
                if (existing != null && existing.confirmed) return error(HttpStatusCode.Conflict, "email_taken", "Эта почта уже зарегистрирована")
                coaches[text("email")] = Coach(text("coach_id").ifEmpty { "c-${text("email")}" }, text("email"), text("password"), text("display_name"), false)
                codeSent(text("email"))
            }
            path == "/auth/confirm" -> {
                val coach = coaches[text("email")]?.takeIf { it.password == text("password") }
                    ?: return error(HttpStatusCode.Unauthorized, "wrong_password", "Неверная почта или пароль")
                if (text("code") != CODE) return error(HttpStatusCode.BadRequest, "wrong_code", "Неверный код. Осталось 4 попытки")
                coach.confirmed = true
                signedIn(coach, text("device_name"))
            }
            path == "/auth/login" -> {
                val coach = coaches[text("email")]?.takeIf { it.password == text("password") }
                    ?: return error(HttpStatusCode.Unauthorized, "wrong_password", "Неверная почта или пароль")
                if (!coach.confirmed) {
                    codesSent += coach.email
                    return error(HttpStatusCode.Forbidden, "email_not_confirmed", "Подтвердите почту: мы отправили код в письме")
                }
                signedIn(coach, text("device_name"))
            }
            path == "/auth/reset/request" -> {
                if (coaches.containsKey(text("email"))) codesSent += text("email")
                respondJson(buildJsonObject {
                    put("status", "code_sent")
                    put("email", text("email"))
                    put("minutes", 15)
                })
            }
            path == "/auth/reset" -> {
                val coach = coaches[text("email")] ?: return error(HttpStatusCode.BadRequest, "code_expired", "Код устарел — запросите новый")
                if (text("code") != CODE) return error(HttpStatusCode.BadRequest, "wrong_code", "Неверный код. Осталось 4 попытки")
                coach.password = text("password")
                coach.confirmed = true
                sessions.removeAll { it.coach == coach }
                signedIn(coach, text("device_name"))
            }
            path == "/auth/logout" -> {
                sessions.removeAll { it.token == token }
                respondJson(buildJsonObject { put("status", "ok") })
            }
            path == "/me" -> {
                val session = sessions.firstOrNull { it.token == token } ?: return unauthorized()
                respondJson(me(session))
            }
            request.method == HttpMethod.Delete && path.startsWith("/me/sessions/") -> {
                val session = sessions.firstOrNull { it.token == token } ?: return unauthorized()
                val id = path.substringAfterLast('/').toLong()
                if (!sessions.removeAll { it.id == id && it.coach == session.coach }) {
                    return error(HttpStatusCode.NotFound, "session_not_found", "Это устройство уже вышло")
                }
                respondJson(buildJsonObject { put("status", "ok") })
            }
            path.startsWith("/sync/") || path == "/account/data" -> {
                val session = sessions.firstOrNull { it.token == token } ?: return unauthorized()
                val coach = session.coach
                if (path == "/account/data") {
                    rows.keys.removeAll { it.first == coach.id }
                    sessions.removeAll { it.coach == coach }
                    return respondJson(buildJsonObject { put("status", "ok") })
                }
                val version = if (path == "/sync/push") (body?.get("schema_version") as? JsonPrimitive)?.intOrNull
                else request.url.parameters["schema_version"]?.toIntOrNull()
                when (SyncRules.schemaProblem(version, schemaVersion)) {
                    SchemaProblem.TOO_OLD -> return error(HttpStatusCode.UpgradeRequired, "update_required", "Обновите приложение")
                    SchemaProblem.TOO_NEW -> return error(HttpStatusCode.ServiceUnavailable, "server_outdated", "Сервер обновляется")
                    null -> Unit
                }
                if (coach.email != admin && coach.email !in serverCopy) {
                    return error(HttpStatusCode.Forbidden, "feature_closed", "Копия на сервере не открыта")
                }
                if (path == "/sync/push") push(coach, body!!) else pull(coach, request)
            }
            else -> respond("Not found", HttpStatusCode.NotFound)
        }
    }

    private fun owner(coach: Coach, entity: String, data: JsonObject): String =
        if (entity in SyncRules.CLUB_ENTITIES || (data["scope"] as? JsonPrimitive)?.content == "CLUB") "" else coach.id

    private fun MockRequestHandleScope.push(coach: Coach, body: JsonObject): HttpResponseData {
        val device = (body["device_id"] as JsonPrimitive).content
        val changes = (body["changes"] as JsonArray).map { it as JsonObject }
        if (changes.size > SyncRules.PUSH_MAX) return error(HttpStatusCode.BadRequest, "bad_request", "Не больше 500")
        return respondJson(buildJsonObject {
            putJsonArray("results") {
                for (c in changes) {
                    val entity = (c["entity"] as JsonPrimitive).content
                    val id = (c["id"] as JsonPrimitive).content
                    val data = c["data"] as JsonObject
                    val owner = owner(coach, entity, data)
                    val key = Triple(owner, entity, id)
                    val updatedAt = (c["updated_at"] as JsonPrimitive).long
                    val stored = rows[key]
                    val status: String
                    var number: Long? = null
                    if (owner == "" && coach.email != admin) {
                        status = "club_only"
                    } else when (SyncRules.order(SyncStamp(updatedAt, device), stored?.let { SyncStamp(it.updatedAt, it.device) })) {
                        SyncOrder.OLDER -> status = "stale"
                        SyncOrder.SAME -> {
                            status = "accepted"
                            number = stored!!.seq
                        }
                        SyncOrder.NEWER -> {
                            val row = Row(
                                owner, entity, id, (c["version"] as JsonPrimitive).long, updatedAt,
                                (c["deleted_at"] as? JsonPrimitive)?.longOrNull, device, data, ++seq,
                            )
                            rows[key] = row
                            accepted += row
                            status = "accepted"
                            number = row.seq
                        }
                    }
                    addJsonObject {
                        put("entity", entity)
                        put("id", id)
                        put("status", status)
                        number?.let { put("seq", it) }
                    }
                }
            }
        })
    }

    private fun MockRequestHandleScope.pull(coach: Coach, request: HttpRequestData): HttpResponseData {
        val since = request.url.parameters["since"]?.toLong() ?: 0
        val limit = request.url.parameters["limit"]?.toInt() ?: SyncRules.PULL_DEFAULT
        if (since in 1 until purgedSeq) return error(HttpStatusCode.Gone, "full_sync", "Нужна полная синхронизация")
        val all = rows.values.filter { (it.owner == coach.id || it.owner == "") && it.seq > since }.sortedBy { it.seq }
        val page = all.take(limit)
        return respondJson(buildJsonObject {
            putJsonArray("changes") {
                for (r in page) addJsonObject {
                    put("entity", r.entity)
                    put("id", r.id)
                    put("version", r.version)
                    put("updated_at", r.updatedAt)
                    put("deleted_at", r.deletedAt)
                    put("device_id", r.device)
                    put("data", r.data)
                    put("seq", r.seq)
                }
            }
            put("last_seq", page.lastOrNull()?.seq ?: since)
            put("has_more", all.size > limit)
        })
    }

    private fun MockRequestHandleScope.codeSent(email: String): HttpResponseData {
        codesSent += email
        return respondJson(buildJsonObject {
            put("status", "code_sent")
            put("email", email)
            put("minutes", 15)
        })
    }

    private fun MockRequestHandleScope.signedIn(coach: Coach, device: String): HttpResponseData {
        val session = Session(next++, coach, device, "t-${next}")
        sessions += session
        return respondJson(buildJsonObject {
            put("token", session.token)
            put("coach", coachJson(coach, session = null))
        })
    }

    private fun me(session: Session): JsonObject = coachJson(session.coach, session)

    private fun coachJson(coach: Coach, session: Session?): JsonObject = buildJsonObject {
        put("id", coach.id)
        put("email", coach.email)
        put("display_name", coach.name)
        put("role", if (coach.email == admin) "ADMIN" else "COACH")
        put("status", "ACTIVE")
        putJsonArray("features") {
            if (coach.email != admin && coach.email in serverCopy) addJsonObject {
                put("code", "server_copy")
                put("until", null as String?)
            }
            if (coach.email == admin) {
                for (code in listOf("half_official", "cabinets", "server_copy")) addJsonObject {
                    put("code", code)
                    put("until", null as String?)
                }
            }
        }
        if (session != null) {
            putJsonArray("sessions") {
                for (s in sessions.filter { it.coach == coach }) addJsonObject {
                    put("id", s.id)
                    put("device_name", s.device)
                    put("created_at", "2026-10-20T10:00:00Z")
                    put("last_used_at", "2026-10-22T10:00:00Z")
                    put("current", s.id == session.id)
                }
            }
        }
    }

    private fun MockRequestHandleScope.unauthorized() = error(HttpStatusCode.Unauthorized, "unauthorized", "Вход устарел — войдите заново")

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, message: String) =
        respondJson(buildJsonObject {
            put("code", code)
            put("message", message)
        }, status)

    private fun MockRequestHandleScope.respondJson(json: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(json.toString(), status, headersOf(HttpHeaders.ContentType, "application/json"))

    companion object {
        const val CODE = "123456"
    }
}
