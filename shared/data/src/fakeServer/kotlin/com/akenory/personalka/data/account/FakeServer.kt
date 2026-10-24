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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.IOException

/**
 * Сервер входа в памяти — те же адреса, ответы и коды ошибок, что `server/.../AuthRoutes.kt` (ТЗ 7.1).
 * Код в письме всегда [CODE]; [offline] — нет связи.
 */
class FakeServer {
    class Coach(val id: String, val email: String, var password: String, val name: String, var confirmed: Boolean)

    data class Session(val id: Long, val coach: Coach, val device: String, val token: String)

    val coaches = mutableMapOf<String, Coach>()
    val sessions = mutableListOf<Session>()
    val requests = mutableListOf<Pair<String, JsonObject?>>()
    val codesSent = mutableListOf<String>()
    var offline = false
    var admin: String? = null
    private var next = 1L

    val client = HttpClient(MockEngine { request -> handle(request) })

    fun api(url: String = "https://trener.example.ru") = AccountApi(client) { url }

    /** Аккаунт с подтверждённой почтой и одним устройством «Второй телефон». */
    fun signedUp(email: String, password: String, name: String = "Тренер"): Coach {
        val coach = Coach("c-$email", email, password, name, true)
        coaches[email] = coach
        sessions += Session(next++, coach, "Второй телефон", "t-other")
        return coach
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        if (offline) throw IOException("нет сети")
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
            else -> respond("Not found", HttpStatusCode.NotFound)
        }
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
