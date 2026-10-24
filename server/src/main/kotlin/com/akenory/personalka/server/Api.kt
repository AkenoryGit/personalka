package com.akenory.personalka.server

import com.akenory.personalka.domain.format.RuText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// API (ТЗ 7.1): REST + JSON под /api/v1. Ошибка — {"code": "…", "message": "…"}, текст по-русски для показа тренеру.
// JSON — деревом kotlinx.serialization, без плагина сериализации: полей немного, так видно, что уходит наружу.

/** Ошибка для ответа: HTTP-код, код ошибки и текст; [retryAfter] — через сколько секунд можно снова (429). */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val retryAfter: Int? = null,
) : RuntimeException(message)

object Errors {
    fun badRequest(message: String) = ApiException(HttpStatusCode.BadRequest, "bad_request", message)
    fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Вход устарел — войдите заново")
    fun invalidEmail() = ApiException(HttpStatusCode.BadRequest, "invalid_email", "Проверьте адрес почты")
    fun weakPassword(message: String) = ApiException(HttpStatusCode.BadRequest, "weak_password", message)
    fun noName() = ApiException(HttpStatusCode.BadRequest, "no_name", "Напишите имя — его увидят родители в кабинете")
    fun emailTaken() = ApiException(HttpStatusCode.Conflict, "email_taken", "Эта почта уже зарегистрирована — войдите или сбросьте пароль")
    fun wrongPassword() = ApiException(HttpStatusCode.Unauthorized, "wrong_password", "Неверная почта или пароль")
    fun notConfirmed() = ApiException(HttpStatusCode.Forbidden, "email_not_confirmed", "Подтвердите почту: мы отправили код в письме")
    fun suspended() = ApiException(HttpStatusCode.Forbidden, "suspended", "Доступ к серверу приостановлен — напишите администратору")
    fun sessionNotFound() = ApiException(HttpStatusCode.NotFound, "session_not_found", "Это устройство уже вышло")
    fun tooMany(seconds: Int) = ApiException(
        HttpStatusCode.TooManyRequests, "too_many", "Слишком часто — попробуйте через ${waitText(seconds)}", retryAfter = seconds,
    )
    fun mailDisabled() = ApiException(HttpStatusCode.ServiceUnavailable, "mail_disabled", "Письма с сервера пока не отправляются — попробуйте позже")
    fun mailFailed() = ApiException(HttpStatusCode.ServiceUnavailable, "mail_failed", "Не удалось отправить письмо — попробуйте позже")
    fun wrongCode(left: Int) = ApiException(
        HttpStatusCode.BadRequest, "wrong_code", "Неверный код. Осталось ${RuText.count(left, "попытка", "попытки", "попыток")}",
    )
    fun codeExpired() = ApiException(HttpStatusCode.BadRequest, "code_expired", "Код устарел — запросите новый")
    fun featureClosed() = ApiException(HttpStatusCode.Forbidden, "feature_closed", "Копия на сервере не открыта — напишите администратору")
    fun updateRequired() = ApiException(
        HttpStatusCode.UpgradeRequired, "update_required", "Обновите приложение: эта версия больше не синхронизируется с сервером",
    )
    fun serverOutdated() = ApiException(HttpStatusCode.ServiceUnavailable, "server_outdated", "Сервер обновляется — данные отправятся позже")
    fun fullSync() = ApiException(HttpStatusCode.Gone, "full_sync", "Нужна полная синхронизация")

    /** «40 секунд», «5 минут». */
    private fun waitText(seconds: Int): String = if (seconds < 90) {
        RuText.count(seconds, "секунду", "секунды", "секунд")
    } else {
        RuText.count((seconds + 59) / 60, "минуту", "минуты", "минут")
    }
}

/** Ошибки — одним видом JSON; непредвиденная — 500 без подробностей наружу и с записью в журнал. */
fun Application.apiErrors() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            e.retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respondJson(error(e.code, e.message), e.status)
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Ошибка запроса", e)
            call.respondJson(error("server_error", "Ошибка сервера — попробуйте позже"), HttpStatusCode.InternalServerError)
        }
    }
}

private fun error(code: String, message: String) = buildJsonObject {
    put("code", code)
    put("message", message)
}

suspend fun ApplicationCall.respondJson(body: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)

/** Тело запроса — объект JSON; иначе 400. */
suspend fun ApplicationCall.receiveObject(): JsonObject {
    val text = receiveText()
    return runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: throw Errors.badRequest("Запрос не похож на JSON")
}

/** Строковое поле; нет его — пустая строка (что не так с пустым, решают правила). */
fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

/**
 * Адрес того, кто спрашивает, — для пределов. Сервер слушает только 127.0.0.1, снаружи — Caddy: он дописывает
 * адрес в конец X-Forwarded-For, поэтому берётся последний (первые мог прислать сам клиент).
 */
fun ApplicationCall.clientIp(): String? =
    request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress.takeIf { it.isNotEmpty() }
