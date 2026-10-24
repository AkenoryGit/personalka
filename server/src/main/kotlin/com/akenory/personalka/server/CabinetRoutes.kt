package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.cabinet.CabinetRules
import com.akenory.personalka.domain.cabinet.ConsentForm
import com.akenory.personalka.domain.cabinet.ConsentRules
import com.akenory.personalka.domain.cabinet.ConsentScreen
import com.akenory.personalka.domain.cabinet.ConsentSubmit
import com.akenory.personalka.domain.cabinet.RevokeScreen
import com.akenory.personalka.domain.cabinet.RevokedScreen
import com.akenory.personalka.domain.format.CabinetFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.html.HTML
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Кабинет родителя (ТЗ 6.1–6.3).
 *
 * Страница: `GET /c/{токен}` — код (если включён), затем кабинет; `POST /c/{токен}/pin` — проверка кода. Любая причина,
 * по которой кабинет закрыт, — одинаковый 404 «Ссылка больше не действует». Заголовки защиты и предел 30 запросов в
 * минуту с одного IP (6.2); `robots.txt` запрещает `/c/`. Перед кабинетом — согласие (6.1): `POST /c/{токен}/consent`;
 * отзыв — `GET /c/{токен}/revoke` (подтверждение) и `POST` (отозвать). Политика обработки — `GET /policy/{id тренера}`.
 *
 * Для приложения (шаг 2.13), с токеном тренера и открытыми «Кабинетами»:
 * - `PUT /api/v1/cabinets/{client_id}` `{token_hash, pin?}` — новая ссылка (прежняя отзывается); `token_hash` —
 *   SHA-256 токена в base64url (сам токен сервер не видит), `pin` — 4 цифры;
 * - `DELETE /api/v1/cabinets/{client_id}` — отозвать ссылку;
 * - `GET /api/v1/cabinets` — действующие ссылки: `client_id`, `created_at`, `last_opened_at`; и последнее согласие
 *   каждого клиента (`consents`): `client_id`, `accepted_at`, `photo`, `given_by_name`, `revoked_at`.
 */
fun Route.cabinets(database: Database, auth: Auth, cabinets: Cabinets, limit: RateLimit) {
    val accounts = auth.accounts

    get("/robots.txt") {
        call.respondText("User-agent: *\nDisallow: /c/\n", ContentType.Text.Plain)
    }

    get(CabinetHtml.CSS_PATH) {
        val css = CSS ?: return@get call.respondText("", ContentType.Text.CSS, HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(css, ContentType.Text.CSS)
    }

    // Политика обработки (10.3): опубликована для всех, без токена; своя у каждого тренера.
    get("/policy/{coach}") {
        if (!call.withinLimit(limit)) return@get
        val doc = database.tx { c -> cabinets.policy(c, accounts, call.parameters["coach"].orEmpty()) }
            ?: return@get call.cabinetHtml(HttpStatusCode.NotFound) { CabinetHtml.message(this, CabinetFormat.POLICY_MISSING, CabinetFormat.POLICY_MISSING_TEXT) }
        call.cabinetHtml { CabinetHtml.policy(this, doc) }
    }

    route("/c/{token}") {
        get {
            if (!call.withinLimit(limit)) return@get
            val token = call.parameters["token"].orEmpty()
            val pass = call.request.cookies[PASS_COOKIE]
            // Дистанция графика — чипами-ссылками `?d=50-freestyle-25` (6.3): без JavaScript.
            val chartKey = call.request.queryParameters["d"]?.takeIf { it.length <= 32 }
            val page = database.tx { c ->
                val access = cabinets.open(c, accounts, token) ?: return@tx null
                if (access.pinHash != null && !cabinets.passValid(c, access, pass)) {
                    return@tx CabinetView.Code(if (cabinets.blocked(access)) CabinetFormat.CODE_BLOCKED else null, cabinets.blocked(access))
                }
                // Согласие — при первом открытии, до данных кабинета (6.1).
                val consent = cabinets.consent(c, access) ?: return@tx CabinetView.Consent(cabinets.consentScreen(c, access), access.coachId)
                cabinets.opened(c, access)
                CabinetView.Page(CabinetRules.page(cabinets.input(c, access, consent), chartKey), access.coachId)
            }
            when (page) {
                null -> call.invalid()
                is CabinetView.Code -> call.cabinetHtml { CabinetHtml.code(this, "/c/$token/pin", page.error, page.blocked) }
                is CabinetView.Consent -> call.cabinetHtml { CabinetHtml.consent(this, page.screen, "/c/$token/consent", policyPath(page.coachId)) }
                is CabinetView.Page -> call.cabinetHtml {
                    CabinetHtml.page(this, page.page, CabinetLinks(policy = policyPath(page.coachId), revoke = "/c/$token/revoke"))
                }
            }
        }

        // Согласие (6.1): без JavaScript — кнопка всегда активна, без галочки тот же экран с подсказкой.
        post("/consent") {
            if (!call.withinLimit(limit)) return@post
            val token = call.parameters["token"].orEmpty()
            val pass = call.request.cookies[PASS_COOKIE]
            val fields = runCatching { call.receiveParameters() }.getOrNull()
            val form = ConsentForm(
                name = fields?.get("name").orEmpty().take(ConsentRules.NAME_MAX * 2),
                agree = fields?.get("agree") != null,
                photo = fields?.get("photo") != null,
            )
            val ip = call.clientIp()
            val userAgent = call.request.headers[HttpHeaders.UserAgent]
            val answer = database.tx { c ->
                val access = cabinets.open(c, accounts, token) ?: return@tx null
                // Без кода и при уже данном согласии — просто на страницу: там код или кабинет.
                if (access.pinHash != null && !cabinets.passValid(c, access, pass)) return@tx ConsentAnswer.Open
                if (cabinets.consent(c, access) != null) return@tx ConsentAnswer.Open
                when (val submit = ConsentRules.submit(cabinets.consentScreen(c, access), form)) {
                    is ConsentSubmit.Retry -> ConsentAnswer.Retry(submit.screen, access.coachId)
                    is ConsentSubmit.Accepted -> {
                        cabinets.accept(c, access, submit, ip, userAgent)
                        ConsentAnswer.Open
                    }
                }
            }
            when (answer) {
                null -> call.invalid()
                ConsentAnswer.Open -> call.seeOther(token)
                is ConsentAnswer.Retry -> call.cabinetHtml { CabinetHtml.consent(this, answer.screen, "/c/$token/consent", policyPath(answer.coachId)) }
            }
        }

        // Отзыв (6.1): сначала подтверждение, потом «Согласие отозвано» — дальше ссылка не действует.
        route("/revoke") {
            get {
                if (!call.withinLimit(limit)) return@get
                val token = call.parameters["token"].orEmpty()
                val pass = call.request.cookies[PASS_COOKIE]
                val screen = database.tx { c ->
                    val access = cabinets.open(c, accounts, token) ?: return@tx null
                    if (access.pinHash != null && !cabinets.passValid(c, access, pass)) return@tx RevokeAnswer.Open
                    if (cabinets.consent(c, access) == null) return@tx RevokeAnswer.Open
                    RevokeAnswer.Ask(cabinets.revokeScreen(c, access))
                }
                when (screen) {
                    null -> call.invalid()
                    RevokeAnswer.Open -> call.seeOther(token)
                    is RevokeAnswer.Ask -> call.cabinetHtml { CabinetHtml.revoke(this, screen.screen, "/c/$token/revoke", "/c/$token") }
                    is RevokeAnswer.Done -> Unit
                }
            }

            post {
                if (!call.withinLimit(limit)) return@post
                val token = call.parameters["token"].orEmpty()
                val pass = call.request.cookies[PASS_COOKIE]
                val answer = database.tx { c ->
                    val access = cabinets.open(c, accounts, token) ?: return@tx null
                    if (access.pinHash != null && !cabinets.passValid(c, access, pass)) return@tx RevokeAnswer.Open
                    val (who, day) = cabinets.withdraw(c, access)
                    RevokeAnswer.Done(ConsentRules.revoked(who.child, day))
                }
                when (answer) {
                    null -> call.invalid()
                    RevokeAnswer.Open -> call.seeOther(token)
                    is RevokeAnswer.Done -> call.cabinetHtml { CabinetHtml.revoked(this, answer.screen) }
                    is RevokeAnswer.Ask -> Unit
                }
            }
        }

        post("/pin") {
            if (!call.withinLimit(limit)) return@post
            val token = call.parameters["token"].orEmpty()
            val code = runCatching { call.receiveParameters()["code"] }.getOrNull().orEmpty().trim()
            val answer = database.tx { c ->
                val access = cabinets.open(c, accounts, token) ?: return@tx null
                when {
                    access.pinHash == null -> PinAnswer.Open
                    cabinets.blocked(access) -> PinAnswer.Check(PinCheck.Blocked)
                    !PIN.matches(code) -> PinAnswer.BadFormat
                    else -> PinAnswer.Check(cabinets.checkPin(c, access, code))
                }
            }
            when (answer) {
                null -> call.invalid()
                PinAnswer.Open -> call.seeOther(token)
                PinAnswer.BadFormat -> call.cabinetHtml { CabinetHtml.code(this, "/c/$token/pin", CabinetFormat.CODE_FORMAT, blocked = false) }
                is PinAnswer.Check -> when (val check = answer.check) {
                    is PinCheck.Passed -> {
                        call.response.header(
                            HttpHeaders.SetCookie,
                            "$PASS_COOKIE=${check.pass}; Max-Age=${Cabinets.PASS_DAYS * 24 * 60 * 60}; Path=/c/$token; HttpOnly; Secure; SameSite=Lax",
                        )
                        call.seeOther(token)
                    }
                    is PinCheck.Wrong -> call.cabinetHtml { CabinetHtml.code(this, "/c/$token/pin", CabinetFormat.wrongCode(check.left), blocked = false) }
                    PinCheck.Blocked -> call.cabinetHtml { CabinetHtml.code(this, "/c/$token/pin", CabinetFormat.CODE_BLOCKED, blocked = true) }
                }
            }
        }
    }

    route("/api/v1/cabinets") {
        /** Тренер, которому открыты кабинеты. */
        fun cabinetCoach(c: Connection, token: String?): Coach {
            val (coach, _) = token?.let { accounts.session(c, it) } ?: throw Errors.unauthorized()
            if (coach.suspended) throw Errors.suspended()
            if (Feature.CABINETS !in AccountRules.open(accounts.grants(c, coach), ClubTime.today(cabinets.now()))) {
                throw Errors.cabinetsClosed()
            }
            return coach
        }

        fun clientId(call: ApplicationCall): String =
            call.parameters["client"]?.takeIf { it.isNotBlank() && it.length <= 64 } ?: throw Errors.badRequest("Нет id клиента")

        get {
            val token = call.bearer()
            val (links, consents) = database.tx { c ->
                val coachId = cabinetCoach(c, token).id
                cabinets.links(c, coachId) to cabinets.consents(c, coachId)
            }
            call.respondJson(
                buildJsonObject {
                    putJsonArray("cabinets") {
                        links.forEach { link ->
                            addJsonObject {
                                put("client_id", link.clientId)
                                put("created_at", link.createdAt.toInstant().toEpochMilli())
                                put("last_opened_at", link.lastOpenedAt?.toInstant()?.toEpochMilli())
                            }
                        }
                    }
                    putJsonArray("consents") {
                        consents.forEach { consent ->
                            addJsonObject {
                                put("client_id", consent.clientId)
                                put("accepted_at", consent.acceptedAt)
                                put("photo", consent.photo)
                                put("given_by_name", consent.givenBy)
                                put("revoked_at", consent.revokedAt)
                            }
                        }
                    }
                },
            )
        }

        put("/{client}") {
            val token = call.bearer()
            val clientId = clientId(call)
            val body = call.receiveObject()
            val hash = runCatching { Base64.getUrlDecoder().decode(body.text("token_hash")) }.getOrNull()?.takeIf { it.size == 32 }
            val pin = body.text("pin").takeIf { it.isNotEmpty() }
            database.tx { c ->
                val coach = cabinetCoach(c, token)
                if (hash == null) throw Errors.badRequest("token_hash — SHA-256 токена в base64url")
                if (pin != null && !PIN.matches(pin)) throw Errors.badRequest("Код — 4 цифры")
                cabinets.issue(c, coach.id, clientId, hash, pin)
            }
            call.respondJson(buildJsonObject { put("status", "ok") })
        }

        delete("/{client}") {
            val token = call.bearer()
            val clientId = clientId(call)
            // Отозвать ссылку можно и без открытых «Кабинетов»: закрыть доступ — всегда можно.
            database.tx { c ->
                val (coach, _) = token?.let { accounts.session(c, it) } ?: throw Errors.unauthorized()
                cabinets.revoke(c, coach.id, clientId)
            }
            call.respondJson(buildJsonObject { put("status", "ok") })
        }
    }
}

/**
 * Предел запросов с одного адреса (6.2): не больше [perMinute] за последние 60 секунд. Память сервера — его хватает:
 * сервер один, а после перезапуска счёт начинается заново.
 */
class RateLimit(private val perMinute: Int = 30, private val now: () -> Long = System::currentTimeMillis) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Учесть запрос; false — предел исчерпан. */
    fun allow(key: String): Boolean {
        val now = now()
        if (hits.size > CLEAN_AT) hits.entries.removeIf { (_, q) -> synchronized(q) { q.lastOrNull()?.let { it < now - MINUTE } ?: true } }
        val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.first() <= now - MINUTE) queue.removeFirst()
            if (queue.size >= perMinute) return false
            queue.addLast(now)
            return true
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val CLEAN_AT = 10_000
    }
}

private sealed interface CabinetView {
    data class Code(val error: String?, val blocked: Boolean) : CabinetView

    data class Consent(val screen: ConsentScreen, val coachId: String) : CabinetView

    data class Page(val page: com.akenory.personalka.domain.cabinet.CabinetPage, val coachId: String) : CabinetView
}

private sealed interface ConsentAnswer {
    /** На страницу ссылки: там код, кабинет или снова согласие. */
    data object Open : ConsentAnswer

    data class Retry(val screen: ConsentScreen, val coachId: String) : ConsentAnswer
}

private sealed interface RevokeAnswer {
    data object Open : RevokeAnswer

    data class Ask(val screen: RevokeScreen) : RevokeAnswer

    data class Done(val screen: RevokedScreen) : RevokeAnswer
}

/** Адрес политики тренера [coachId]. */
private fun policyPath(coachId: String) = "/policy/$coachId"

/** Одинаковый ответ для любой причины, по которой кабинет закрыт (6.1). */
private suspend fun ApplicationCall.invalid() =
    cabinetHtml(HttpStatusCode.NotFound) { CabinetHtml.message(this, CabinetFormat.INVALID_TITLE, CabinetFormat.INVALID_TEXT) }

private sealed interface PinAnswer {
    data object Open : PinAnswer

    data object BadFormat : PinAnswer

    data class Check(val check: PinCheck) : PinAnswer
}

private val PIN = Regex("[0-9]{4}")
private const val PASS_COOKIE = "kb"

private val CSS: String? = CabinetHtml::class.java.getResource("/cabinet/cabinet.css")?.readText()

/** Предел для `/c/`: сверх него — 429 со страницей «Слишком много запросов». */
private suspend fun ApplicationCall.withinLimit(limit: RateLimit): Boolean {
    if (limit.allow(clientIp() ?: "?")) return true
    response.header(HttpHeaders.RetryAfter, "60")
    cabinetHtml(HttpStatusCode.TooManyRequests) {
        CabinetHtml.message(this, CabinetFormat.TOO_MANY_TITLE, CabinetFormat.TOO_MANY_TEXT, CabinetHtml.CLOCK_ICON)
    }
    return false
}

/** Ответ кабинета: HTML с заголовками защиты 6.2. */
suspend fun ApplicationCall.cabinetHtml(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
    cabinetHeaders()
    respondHtml(status, block)
}

fun ApplicationCall.cabinetHeaders() {
    response.header("Content-Security-Policy", "default-src 'self'")
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Referrer-Policy", "no-referrer")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
}

private suspend fun ApplicationCall.seeOther(token: String) {
    cabinetHeaders()
    response.header(HttpHeaders.Location, "/c/$token")
    respond(HttpStatusCode.SeeOther)
}

/** Адрес в журнале: токен кабинета замаскирован (6.2) — `/c/AbCd…`. */
fun maskCabinet(path: String): String = CABINET_PATH.replace(path) { "/c/" + it.groupValues[1].take(4) + "…" }

private val CABINET_PATH = Regex("/c/([^/?#]+)")
