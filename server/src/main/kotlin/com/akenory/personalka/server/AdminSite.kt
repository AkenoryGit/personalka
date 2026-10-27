package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.account.FeatureGrant
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.AdminFormat
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.ContentType
import kotlinx.datetime.toKotlinLocalDateTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64

/** Администратор, вошедший в админку: [csrf] — скрытое поле каждой формы. */
data class AdminSession(val coach: Coach, val csrf: String)

/** Строка журнала админки. */
data class AdminLogRow(val at: OffsetDateTime, val action: String, val actor: String?, val target: String?, val ip: String?)

/**
 * Сессии и журнал админки (шаг 5.2б). Сессия — 32 случайных байта в cookie, на сервере SHA-256; живёт [HOURS] часов
 * от входа и не продлевается. Всё — внутри транзакции вызывающего.
 */
class AdminSessions(private val accounts: Accounts, private val random: SecureRandom = SecureRandom()) {
    private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** Новая сессия администратора; ответ — токен для cookie. */
    fun open(c: Connection, coachId: String): String {
        val token = token()
        c.update("DELETE FROM admin_session WHERE expires_at < now()")
        c.update(
            "INSERT INTO admin_session (token_hash, coach_id, csrf, expires_at) VALUES (?, ?, ?, now() + make_interval(hours => ?))",
            Accounts.tokenHash(token), coachId, token(), HOURS,
        )
        return token
    }

    /** Действующая сессия; почта больше не в `TRENER_ADMIN_EMAIL` — сессии нет. */
    fun find(c: Connection, token: String?): AdminSession? {
        if (token.isNullOrEmpty()) return null
        val (coachId, csrf) = c.queryOne(
            "SELECT coach_id, csrf FROM admin_session WHERE token_hash = ? AND expires_at > now()",
            Accounts.tokenHash(token),
        ) { it.getString(1) to it.getString(2) } ?: return null
        val coach = accounts.byId(c, coachId)?.takeIf { it.admin } ?: return null
        return AdminSession(coach, csrf)
    }

    fun close(c: Connection, token: String) {
        c.update("DELETE FROM admin_session WHERE token_hash = ?", Accounts.tokenHash(token))
    }

    fun log(c: Connection, actor: Coach?, action: String, target: String?, ip: String?) {
        c.update("INSERT INTO admin_log (actor_id, action, target, ip) VALUES (?, ?, ?, ?)", actor?.id, action, target, ip)
    }

    /** Последние [limit] строк журнала, новые сверху. */
    fun recent(c: Connection, limit: Int = LOG_ROWS): List<AdminLogRow> = c.query(
        """
        SELECT l.at, l.action, a.display_name, l.target, l.ip
        FROM admin_log l LEFT JOIN coach a ON a.id = l.actor_id
        ORDER BY l.at DESC, l.id DESC LIMIT ?
        """.trimIndent(),
        limit,
    ) { AdminLogRow(it.getObject(1, OffsetDateTime::class.java), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }

    companion object {
        const val HOURS = 12
        const val LOG_ROWS = 30
        const val COOKIE = "adm"

        /** Действия журнала. */
        const val SIGN_IN = "sign_in"
        const val SIGN_OUT = "sign_out"
        const val SUSPEND = "suspend"
        const val RESUME = "resume"
        const val FEATURES = "features"
        const val DEFAULTS = "defaults"
    }
}

/**
 * Админка на сайте сервера (решение Дмитрия 27.10.2026, шаг 5.2б; ТЗ 5.17, 1.5, 7.3) — HTML без JavaScript:
 *
 * - `GET /admin` — вход или, с действующей сессией, тренеры и журнал;
 * - `POST /admin/login` `email, password` — пароль администратора верен → код на почту и форма кода. Любая другая
 *   почта, неверный пароль или неподтверждённая почта — одинаковое «Неверная почта или пароль»;
 * - `POST /admin/code` `email, code` — код верен → сессия на 12 часов в cookie `adm` (`HttpOnly; Secure;
 *   SameSite=Strict; Path=/admin`) и переход на `/admin`;
 * - `POST /admin/logout`, `POST /admin/coaches/{id}/suspend` и `/resume` — с полем `csrf` из страницы;
 * - `GET /admin/coaches/{id}` — возможности тренера; `POST /admin/coaches/{id}/features` `csrf, f=код…, until_код` —
 *   сохранить (шаг 5.2в); `POST /admin/defaults` `csrf, f=код…` — что открыто новым тренерам.
 *
 * Администратор — только почта из `TRENER_ADMIN_EMAIL` (отметка в базе её не заменит). Пароль и код считаются
 * попытками входа (5 в минуту на почту и на IP), у кода — 5 попыток. Каждый вход и действие пишутся в журнал.
 */
fun Route.adminSite(database: Database, auth: Auth, limit: RateLimit) {
    val accounts = auth.accounts
    val codes = auth.codes
    val sessions = AdminSessions(accounts)

    get(AdminHtml.CSS_PATH) {
        val css = ADMIN_CSS ?: return@get call.respondText("", ContentType.Text.CSS, HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(css, ContentType.Text.CSS)
    }

    get("/admin") {
        if (!call.adminLimit(limit)) return@get
        val page = database.tx { c ->
            val session = sessions.find(c, call.request.cookies[AdminSessions.COOKIE]) ?: return@tx null
            val coaches = accounts.summaries(c)
            AdminPage(session, coaches, sessions.recent(c), coaches.associate { it.coach.id to accounts.grants(c, it.coach) }, accounts.defaults(c))
        }
        if (page == null) call.adminHtml { AdminHtml.login(this, null) } else call.adminHtml { AdminHtml.coaches(this, page, ClubTime.today(System.currentTimeMillis())) }
    }

    post("/admin/login") {
        if (!call.adminLimit(limit)) return@post
        val form = call.form()
        val email = AccountRules.email(form["email"].orEmpty())
        val password = form["password"].orEmpty()
        if (email == null) return@post call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, AdminFormat.WRONG) }
        if (!auth.mailer.enabled) return@post call.adminHtml(HttpStatusCode.ServiceUnavailable) { AdminHtml.login(this, AdminFormat.NO_MAIL) }
        val ip = call.clientIp()
        val prepared = runCatching {
            database.tx { c ->
                accounts.countLoginAttempt(c, email, ip)
                val coach = accounts.passwordMatches(c, email, password)?.takeIf { it.admin && it.confirmed } ?: return@tx null
                coach to codes.prepare(c, email, CodePurpose.ADMIN, ip)
            }
        }
        val (coach, code) = prepared.getOrElse { e ->
            val message = (e as? ApiException)?.message ?: throw e
            return@post call.adminHtml(HttpStatusCode.TooManyRequests) { AdminHtml.login(this, message) }
        } ?: return@post call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, AdminFormat.WRONG) }
        val letter = Letters.admin(coach.displayName, code, MailCodes.MINUTES)
        try {
            auth.mailer.send(email, letter.subject, letter.text)
        } catch (e: MailException) {
            call.application.log.error("Письмо admin не ушло: ${e.message}")
            database.tx { codes.cancel(it, email, CodePurpose.ADMIN) }
            return@post call.adminHtml(HttpStatusCode.ServiceUnavailable) { AdminHtml.login(this, AdminFormat.MAIL_FAILED) }
        }
        call.adminHtml { AdminHtml.code(this, email, AdminFormat.codeSent(email, MailCodes.MINUTES), null) }
    }

    post("/admin/code") {
        if (!call.adminLimit(limit)) return@post
        val form = call.form()
        val email = AccountRules.email(form["email"].orEmpty()) ?: return@post call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, AdminFormat.CODE_EXPIRED) }
        val ip = call.clientIp()
        // Неверный код — попытка засчитывается: транзакция завершается, страница — после неё.
        val result = runCatching {
            database.tx { c ->
                accounts.countLoginAttempt(c, email, ip)
                when (val check = codes.check(c, email, CodePurpose.ADMIN, form["code"].orEmpty())) {
                    is CodeCheck.Wrong -> AdminCode.Wrong(Errors.wrongCode(check.attemptsLeft).message)
                    CodeCheck.Expired -> AdminCode.Expired
                    CodeCheck.Ok -> {
                        val coach = accounts.byEmail(c, email)?.takeIf { it.admin } ?: return@tx AdminCode.Expired
                        sessions.log(c, coach, AdminSessions.SIGN_IN, null, ip)
                        AdminCode.Ok(sessions.open(c, coach.id))
                    }
                }
            }
        }.getOrElse { e -> AdminCode.Wrong((e as? ApiException)?.message ?: throw e) }
        when (result) {
            is AdminCode.Ok -> {
                call.response.header(
                    HttpHeaders.SetCookie,
                    "${AdminSessions.COOKIE}=${result.token}; Max-Age=${AdminSessions.HOURS * 3600}; Path=/admin; HttpOnly; Secure; SameSite=Strict",
                )
                call.adminRedirect()
            }
            is AdminCode.Wrong -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.code(this, email, null, result.message) }
            AdminCode.Expired -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, AdminFormat.CODE_EXPIRED) }
        }
    }

    post("/admin/logout") {
        val form = call.form()
        val token = call.request.cookies[AdminSessions.COOKIE]
        database.tx { c ->
            val session = sessions.find(c, token)
            if (session != null && sameText(session.csrf, form["csrf"])) {
                sessions.log(c, session.coach, AdminSessions.SIGN_OUT, null, call.clientIp())
                sessions.close(c, token!!)
            }
        }
        call.response.header(HttpHeaders.SetCookie, "${AdminSessions.COOKIE}=; Max-Age=0; Path=/admin; HttpOnly; Secure; SameSite=Strict")
        call.adminRedirect()
    }

    for ((path, suspended) in listOf("suspend" to true, "resume" to false)) {
        post("/admin/coaches/{id}/$path") {
            if (!call.adminLimit(limit)) return@post
            val form = call.form()
            val id = call.parameters["id"].orEmpty()
            val ok = database.tx { c ->
                val session = sessions.find(c, call.request.cookies[AdminSessions.COOKIE]) ?: return@tx null
                if (!sameText(session.csrf, form["csrf"])) return@tx false
                val coach = accounts.byId(c, id)
                // Администратора не приостановить — кнопки у него нет; нет такого тренера — просто вернуться к списку.
                if (coach != null && !coach.admin && coach.suspended != suspended) {
                    accounts.setSuspended(c, coach.id, suspended)
                    sessions.log(c, session.coach, if (suspended) AdminSessions.SUSPEND else AdminSessions.RESUME, coach.displayName, call.clientIp())
                }
                true
            }
            when (ok) {
                null -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, null) }
                false -> call.adminHtml(HttpStatusCode.Forbidden) { AdminHtml.login(this, AdminFormat.FORM_EXPIRED) }
                true -> call.adminRedirect()
            }
        }
    }

    get("/admin/coaches/{id}") {
        if (!call.adminLimit(limit)) return@get
        val id = call.parameters["id"].orEmpty()
        val page = database.tx { c ->
            val session = sessions.find(c, call.request.cookies[AdminSessions.COOKIE]) ?: return@tx null
            val summary = accounts.summaries(c).firstOrNull { it.coach.id == id }
            summary?.let { AdminCoachPage(session, it, accounts.grants(c, it.coach)) } ?: false
        }
        when (page) {
            null -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, null) }
            is AdminCoachPage -> call.adminHtml { AdminHtml.coach(this, page) }
            else -> call.adminRedirect()
        }
    }

    post("/admin/coaches/{id}/features") {
        if (!call.adminLimit(limit)) return@post
        val form = call.form()
        val id = call.parameters["id"].orEmpty()
        val picked = form.getAll("f").orEmpty().mapNotNull(Feature::of).toSet()
        val dates = Feature.entries.associateWith { f -> form["until_${f.code}"].orEmpty().trim() }
        val parsed = dates.mapValues { (_, text) -> if (text.isEmpty()) null else parseDate(text) }
        val badDate = picked.any { dates[it]!!.isNotEmpty() && parsed[it] == null }
        val grants = picked.map { FeatureGrant(it, parsed[it]) }
        val answer = database.tx { c ->
            val session = sessions.find(c, call.request.cookies[AdminSessions.COOKIE]) ?: return@tx null
            if (!sameText(session.csrf, form["csrf"])) return@tx false
            val summary = accounts.summaries(c).firstOrNull { it.coach.id == id } ?: return@tx true
            if (badDate) return@tx AdminCoachPage(session, summary, grants, AdminFormat.BAD_DATE)
            accounts.setGrants(c, id, grants)
            val open = Feature.entries.filter { it in picked }.associateWith { parsed[it] }
            sessions.log(c, session.coach, AdminSessions.FEATURES, summary.coach.displayName + " · " + AdminFormat.features(open), call.clientIp())
            true
        }
        when (answer) {
            null -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, null) }
            false -> call.adminHtml(HttpStatusCode.Forbidden) { AdminHtml.login(this, AdminFormat.FORM_EXPIRED) }
            is AdminCoachPage -> call.adminHtml(HttpStatusCode.BadRequest) { AdminHtml.coach(this, answer) }
            else -> call.adminRedirect()
        }
    }

    post("/admin/defaults") {
        if (!call.adminLimit(limit)) return@post
        val form = call.form()
        val picked = form.getAll("f").orEmpty().mapNotNull(Feature::of).toSet()
        val ok = database.tx { c ->
            val session = sessions.find(c, call.request.cookies[AdminSessions.COOKIE]) ?: return@tx null
            if (!sameText(session.csrf, form["csrf"])) return@tx false
            accounts.setDefaults(c, picked)
            sessions.log(c, session.coach, AdminSessions.DEFAULTS, AdminFormat.defaults(Feature.entries.filter { it in picked }.toSet()), call.clientIp())
            true
        }
        when (ok) {
            null -> call.adminHtml(HttpStatusCode.Unauthorized) { AdminHtml.login(this, null) }
            false -> call.adminHtml(HttpStatusCode.Forbidden) { AdminHtml.login(this, AdminFormat.FORM_EXPIRED) }
            true -> call.adminRedirect()
        }
    }
}

/** Дата из поля: календарь браузера шлёт 2026-12-31, руками пишут 31.12.2026. */
private fun parseDate(text: String): kotlinx.datetime.LocalDate? =
    runCatching { kotlinx.datetime.LocalDate.parse(text) }.getOrNull() ?: DateFormat.parseFull(text)

/** Что показать на `/admin` после входа: [grants] — возможности каждого тренера, [defaults] — новым тренерам. */
data class AdminPage(
    val session: AdminSession,
    val coaches: List<CoachSummary>,
    val log: List<AdminLogRow>,
    val grants: Map<String, List<FeatureGrant>>,
    val defaults: Set<Feature>,
)

/** Страница тренера: его возможности; [error] — что не так с формой. */
data class AdminCoachPage(val session: AdminSession, val coach: CoachSummary, val grants: List<FeatureGrant>, val error: String? = null)

private sealed interface AdminCode {
    data class Ok(val token: String) : AdminCode

    data class Wrong(val message: String) : AdminCode

    data object Expired : AdminCode
}

/** Время сервера — в часовом поясе клуба (ТЗ 3.1). */
fun OffsetDateTime.club(): kotlinx.datetime.LocalDateTime =
    atZoneSameInstant(ZoneId.of(ClubTime.DEFAULT_ZONE_ID)).toLocalDateTime().toKotlinLocalDateTime()

private suspend fun ApplicationCall.form(): Parameters = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)

private suspend fun ApplicationCall.adminRedirect() {
    cabinetHeaders()
    response.header(HttpHeaders.Location, "/admin")
    respond(HttpStatusCode.SeeOther)
}

/** Ответ админки: те же заголовки защиты, что у кабинета (6.2), и запрет отправлять формы на чужие адреса. */
private suspend fun ApplicationCall.adminHtml(status: HttpStatusCode = HttpStatusCode.OK, block: kotlinx.html.HTML.() -> Unit) {
    cabinetHeaders()
    response.headers.append("Content-Security-Policy", "form-action 'self'; frame-ancestors 'none'", safeOnly = false)
    respondHtml(status, block)
}

/** Тот же предел запросов с одного IP, что у кабинета: сверх него — страница входа с «Слишком часто». */
private suspend fun ApplicationCall.adminLimit(limit: RateLimit): Boolean {
    if (limit.allow("admin:" + (clientIp() ?: "?"))) return true
    response.header(HttpHeaders.RetryAfter, "60")
    adminHtml(HttpStatusCode.TooManyRequests) { AdminHtml.login(this, Errors.tooMany(60).message) }
    return false
}

private val ADMIN_CSS: String? = AdminHtml::class.java.getResource("/admin/admin.css")?.readText()

/** Поле csrf сравнивается без утечки по времени. */
internal fun sameText(a: String?, b: String?): Boolean =
    a != null && b != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
