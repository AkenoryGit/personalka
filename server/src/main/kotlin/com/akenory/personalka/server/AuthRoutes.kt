package com.akenory.personalka.server

import com.akenory.personalka.domain.account.AccountRules
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.sql.Connection

/** Что нужно входу: почта и аккаунты. В тестах почта подменяется. */
class Auth(
    val mailer: Mailer,
    adminEmails: Set<String>,
    passwords: Passwords = Passwords(),
) {
    val accounts = Accounts(adminEmails, passwords)
    val codes = MailCodes()
}

/**
 * Вход (ТЗ 7.3, 7.1; решения Дмитрия 24.10.2026): открытая регистрация по почте и паролю с подтверждением почты
 * кодом, вход паролем, сброс пароля кодом на почту, сессии устройств. Все возможности сервера у нового аккаунта
 * закрыты — посторонний получает только аккаунт.
 *
 * - `POST /auth/register` `{email, password, display_name, coach_id?}` → письмо с кодом;
 * - `POST /auth/confirm` `{email, password, code, device_name}` → токен;
 * - `POST /auth/login` `{email, password, device_name}` → токен; почта не подтверждена — новое письмо и 403;
 * - `POST /auth/reset/request` `{email}` → письмо с кодом, если такой аккаунт есть (ответ одинаковый);
 * - `POST /auth/reset` `{email, code, password, device_name}` → новый пароль, остальные устройства выходят, токен;
 * - `POST /auth/logout`, `GET /me`, `DELETE /me/sessions/{id}` — с токеном.
 */
fun Route.auth(database: Database, auth: Auth) {
    val accounts = auth.accounts
    val codes = auth.codes

    /** Отправить код: письмо не ушло — код отменяется, чтобы не мешать повтору. */
    suspend fun ApplicationCall.sendCode(email: String, purpose: CodePurpose, letter: (String) -> Letter, prepare: (Connection) -> String?) {
        if (!auth.mailer.enabled) throw Errors.mailDisabled()
        val code = database.tx(prepare) ?: return
        val mail = letter(code)
        try {
            auth.mailer.send(email, mail.subject, mail.text)
        } catch (e: MailException) {
            application.log.error("Письмо ${purpose.code} не ушло: ${e.message}")
            database.tx { codes.cancel(it, email, purpose) }
            throw Errors.mailFailed()
        }
    }

    route("/api/v1") {
        post("/auth/register") {
            val body = call.receiveObject()
            val email = AccountRules.email(body.text("email")) ?: throw Errors.invalidEmail()
            val password = body.text("password")
            AccountRules.passwordProblem(password)?.let { throw Errors.weakPassword(it) }
            val name = AccountRules.name(body.text("display_name")) ?: throw Errors.noName()
            val wantedId = body.text("coach_id").takeIf { it.isNotEmpty() }
            var coachName = name
            call.sendCode(email, CodePurpose.CONFIRM, { Letters.confirm(coachName, it, MailCodes.MINUTES) }) { c ->
                coachName = accounts.register(c, email, password, name, wantedId).displayName
                codes.prepare(c, email, CodePurpose.CONFIRM, call.clientIp())
            }
            call.respondJson(codeSent(email))
        }

        post("/auth/confirm") {
            val body = call.receiveObject()
            val email = AccountRules.email(body.text("email")) ?: throw Errors.invalidEmail()
            val password = body.text("password")
            val device = deviceName(body)
            // Неверный код — попытка засчитывается: транзакция завершается, ошибка — после неё.
            val result = database.tx { c ->
                accounts.countLoginAttempt(c, email, call.clientIp())
                val coach = accounts.passwordMatches(c, email, password) ?: return@tx Result.failure(Errors.wrongPassword())
                when (val check = codes.check(c, email, CodePurpose.CONFIRM, body.text("code"))) {
                    is CodeCheck.Wrong -> Result.failure(Errors.wrongCode(check.attemptsLeft))
                    CodeCheck.Expired -> Result.failure(Errors.codeExpired())
                    CodeCheck.Ok -> {
                        accounts.confirmEmail(c, coach.id)
                        Result.success(signedIn(c, accounts, coach.copy(confirmed = true), device))
                    }
                }
            }
            call.respondJson(result.getOrThrow())
        }

        post("/auth/login") {
            val body = call.receiveObject()
            val email = AccountRules.email(body.text("email")) ?: throw Errors.wrongPassword()
            val password = body.text("password")
            val device = deviceName(body)
            val result = database.tx { c ->
                accounts.countLoginAttempt(c, email, call.clientIp())
                val coach = accounts.passwordMatches(c, email, password) ?: return@tx Result.failure(Errors.wrongPassword())
                when {
                    coach.suspended -> Result.failure(Errors.suspended())
                    !coach.confirmed -> Result.success(coach to null)
                    else -> Result.success(coach to signedIn(c, accounts, coach, device))
                }
            }
            val (coach, answer) = result.getOrThrow()
            if (answer == null) {
                // Почта не подтверждена: новый код, а телефон покажет поле для него. Письмо ушло меньше минуты
                // назад — нового не будет, годится прежний код.
                try {
                    call.sendCode(email, CodePurpose.CONFIRM, { Letters.confirm(coach.displayName, it, MailCodes.MINUTES) }) { c ->
                        codes.prepare(c, email, CodePurpose.CONFIRM, call.clientIp())
                    }
                } catch (e: ApiException) {
                    if (e.status != HttpStatusCode.TooManyRequests) throw e
                }
                throw Errors.notConfirmed()
            }
            call.respondJson(answer)
        }

        post("/auth/reset/request") {
            val body = call.receiveObject()
            val email = AccountRules.email(body.text("email")) ?: throw Errors.invalidEmail()
            var name = ""
            call.sendCode(email, CodePurpose.RESET, { Letters.reset(name, it, MailCodes.MINUTES) }) { c ->
                // Нет аккаунта — письма нет, а ответ тот же: по нему не узнать, чья почта зарегистрирована.
                val coach = accounts.byEmail(c, email) ?: return@sendCode null
                name = coach.displayName
                codes.prepare(c, email, CodePurpose.RESET, call.clientIp())
            }
            call.respondJson(codeSent(email))
        }

        post("/auth/reset") {
            val body = call.receiveObject()
            val email = AccountRules.email(body.text("email")) ?: throw Errors.invalidEmail()
            val password = body.text("password")
            AccountRules.passwordProblem(password)?.let { throw Errors.weakPassword(it) }
            val device = deviceName(body)
            val result = database.tx { c ->
                val coach = accounts.byEmail(c, email, lock = true) ?: return@tx Result.failure(Errors.codeExpired())
                when (val check = codes.check(c, email, CodePurpose.RESET, body.text("code"))) {
                    is CodeCheck.Wrong -> Result.failure(Errors.wrongCode(check.attemptsLeft))
                    CodeCheck.Expired -> Result.failure(Errors.codeExpired())
                    CodeCheck.Ok -> {
                        // Код пришёл на эту почту — значит, она и подтверждена.
                        accounts.setPassword(c, coach.id, password)
                        accounts.confirmEmail(c, coach.id)
                        if (coach.suspended) Result.failure(Errors.suspended()) else Result.success(signedIn(c, accounts, coach.copy(confirmed = true), device))
                    }
                }
            }
            call.respondJson(result.getOrThrow())
        }

        post("/auth/logout") {
            val token = call.bearer() ?: throw Errors.unauthorized()
            database.tx { c -> c.update("DELETE FROM coach_session WHERE token_hash = ?", Accounts.tokenHash(token)) }
            call.respondJson(buildJsonObject { put("status", "ok") })
        }

        get("/me") {
            val token = call.bearer() ?: throw Errors.unauthorized()
            val answer = database.tx { c ->
                val (coach, sessionId) = accounts.session(c, token) ?: throw Errors.unauthorized()
                buildJsonObject {
                    putCoach(coach, c, accounts)
                    putJsonArray("sessions") {
                        for (s in accounts.sessions(c, coach.id)) {
                            addJsonObject {
                                put("id", s.id)
                                put("device_name", s.deviceName)
                                put("created_at", s.createdAt.toInstant().toString())
                                put("last_used_at", s.lastUsedAt.toInstant().toString())
                                put("current", s.id == sessionId)
                            }
                        }
                    }
                }
            }
            call.respondJson(answer)
        }

        delete("/me/sessions/{id}") {
            val token = call.bearer() ?: throw Errors.unauthorized()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw Errors.sessionNotFound()
            database.tx { c ->
                val (coach, _) = accounts.session(c, token) ?: throw Errors.unauthorized()
                if (!accounts.closeSession(c, coach.id, id)) throw Errors.sessionNotFound()
            }
            call.respondJson(buildJsonObject { put("status", "ok") })
        }
    }
}

/** Токен из `Authorization: Bearer …`. */
fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

/** Имя устройства для списка «Устройства»; не прислали — «Телефон». */
private fun deviceName(body: JsonObject): String =
    body.text("device_name").trim().replace(Regex("""\s+"""), " ").take(60).ifEmpty { "Телефон" }

private fun codeSent(email: String) = buildJsonObject {
    put("status", "code_sent")
    put("email", email)
    put("minutes", MailCodes.MINUTES)
}

/** Ответ на вход: токен и тренер с возможностями. */
private fun signedIn(c: Connection, accounts: Accounts, coach: Coach, device: String): JsonObject {
    val token = accounts.openSession(c, coach.id, device)
    return buildJsonObject {
        put("token", token)
        putJsonObject("coach") { putCoach(coach, c, accounts) }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putCoach(coach: Coach, c: Connection, accounts: Accounts) {
    put("id", coach.id)
    put("email", coach.email)
    put("display_name", coach.displayName)
    put("role", if (coach.admin) "ADMIN" else "COACH")
    put("status", if (coach.suspended) "SUSPENDED" else "ACTIVE")
    // Срок каждой возможности с учётом зависимостей; открыта ли она сегодня, телефон решает теми же правилами.
    putJsonArray("features") {
        for ((feature, until) in AccountRules.effective(accounts.grants(c, coach))) {
            addJsonObject {
                put("code", feature.code)
                put("until", until?.toString())
            }
        }
    }
}
