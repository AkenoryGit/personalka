package com.akenory.personalka.server

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Админка на сайте (шаг 5.2б): вход паролем и кодом из письма, тренеры, приостановка, журнал. */
class AdminSiteTest {
    private val mailer = RecordingMailer()
    private val password = "плавание-2026"
    private val admin = "admin@example.ru"
    private val coachEmail = "lena@example.ru"

    private fun withServer(block: suspend ApplicationTestBuilder.(Database, HttpClient) -> Unit) = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database, auth = Auth(mailer, setOf(admin), Passwords(memoryKb = 1024, iterations = 1))) }
        block(database, createClient { followRedirects = false })
    }

    /** Регистрация в приложении — токен приложения. */
    private suspend fun HttpClient.signUp(database: Database, email: String, name: String): String {
        suspend fun send(path: String, vararg fields: Pair<String, String>) = post(path) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())
        }
        send("/api/v1/auth/register", "email" to email, "password" to password, "display_name" to name)
        val response = send("/api/v1/auth/confirm", "email" to email, "password" to password, "code" to mailer.lastCode(), "device_name" to "Телефон")
        // Код подтверждения ушёл только что, а второе письмо на адрес — не раньше чем через минуту: в тесте её не ждём.
        database.tx { c -> c.update("DELETE FROM mail_send"); c.update("DELETE FROM login_attempt") }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.login(email: String, pass: String) =
        submitForm("/admin/login", parameters { append("email", email); append("password", pass) })

    private suspend fun HttpClient.code(email: String, code: String) =
        submitForm("/admin/code", parameters { append("email", email); append("code", code) })

    /** Пароль и код — cookie админки. */
    private suspend fun HttpClient.signIn(database: Database): String {
        // Попытки входа с одного IP — 5 в минуту, а в тесте все запросы с одного адреса.
        database.tx { c -> c.update("DELETE FROM mail_send"); c.update("DELETE FROM login_attempt") }
        assertEquals(HttpStatusCode.OK, login(admin, password).status)
        val response = code(admin, mailer.lastCode())
        assertEquals(HttpStatusCode.SeeOther, response.status)
        val cookie = response.headers[HttpHeaders.SetCookie]!!
        assertTrue("HttpOnly" in cookie && "Secure" in cookie && "SameSite=Strict" in cookie && "Path=/admin" in cookie, cookie)
        return cookie.substringBefore(';')
    }

    private suspend fun HttpClient.page(cookie: String) = get("/admin") { header(HttpHeaders.Cookie, cookie) }

    private suspend fun HttpClient.act(cookie: String, path: String, csrf: String?) = submitForm(
        path,
        parameters { csrf?.let { append("csrf", it) } },
    ) { header(HttpHeaders.Cookie, cookie) }

    private fun csrf(html: String): String = Regex("""name="csrf" value="([^"]+)"""").find(html)!!.groupValues[1]

    private suspend fun suspended(database: Database, email: String): Boolean =
        database.tx { c -> c.queryOne("SELECT status FROM coach WHERE email = ?", email) { it.getString(1) } } == "SUSPENDED"

    private suspend fun HttpResponse.html(): String = bodyAsText()

    @Test
    fun withoutSessionOnlyLoginForm() = withServer { database, client ->
        val response = client.get("/admin")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.html()
        assertTrue("Вход в админку" in html)
        assertFalse("csrf" in html)
        assertEquals("default-src 'self'", response.headers.getAll("Content-Security-Policy")!!.first())
        assertEquals(HttpStatusCode.OK, client.get(AdminHtml.CSS_PATH).status)
    }

    @Test
    fun onlyAdminWithPasswordAndCodeGetsIn() = withServer { database, client ->
        client.signUp(database, admin, "Дмитрий")
        val appToken = client.signUp(database, coachEmail, "Лена")
        val letters = mailer.sent.size

        // Тренер с верным паролем — как неверный пароль, письма нет.
        val coach = client.login(coachEmail, password)
        assertEquals(HttpStatusCode.Unauthorized, coach.status)
        assertTrue("Неверная почта или пароль" in coach.html())
        assertEquals(HttpStatusCode.Unauthorized, client.login(admin, "не-тот-пароль").status)
        assertEquals(letters, mailer.sent.size)

        // Пароль администратора — письмо с кодом, но без кода сессии нет.
        val step = client.login(admin, password)
        assertEquals(HttpStatusCode.OK, step.status)
        assertTrue("a…@example.ru" in step.html())
        assertEquals(admin, mailer.sent.last().first)
        assertTrue(mailer.sent.last().second.startsWith("Код входа в админку"))
        val wrong = client.code(admin, "000000".takeIf { it != mailer.lastCode() } ?: "111111")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val wrongHtml = wrong.html()
        assertTrue("Неверный код" in wrongHtml, wrongHtml)
        assertEquals(null, wrong.headers[HttpHeaders.SetCookie])

        // Токен приложения вместо cookie админки не пускает.
        assertFalse("csrf" in client.page("adm=$appToken").html())

        val cookie = client.signIn(database)
        val html = client.page(cookie).html()
        assertTrue("Тренеры · 2" in html, html)
        assertTrue("Лена" in html && "Дмитрий · вы" in html)
        assertTrue("Вход в админку · Дмитрий" in html, "вход записан в журнал")
    }

    @Test
    fun suspendNeedsFormField() = withServer { database, client ->
        client.signUp(database, admin, "Дмитрий")
        val appToken = client.signUp(database, coachEmail, "Лена")
        val cookie = client.signIn(database)
        val html = client.page(cookie).html()
        val csrf = csrf(html)
        val id = database.tx { c -> c.queryOne("SELECT id FROM coach WHERE email = ?", coachEmail) { it.getString(1) } }!!
        val adminId = database.tx { c -> c.queryOne("SELECT id FROM coach WHERE email = ?", admin) { it.getString(1) } }!!
        assertTrue("/admin/coaches/$id/suspend" in html)
        assertFalse("/admin/coaches/$adminId/" in html, "у администратора кнопки нет")

        // Без поля формы или с чужим — ничего не меняется.
        assertEquals(HttpStatusCode.Forbidden, client.act(cookie, "/admin/coaches/$id/suspend", null).status)
        assertEquals(HttpStatusCode.Forbidden, client.act(cookie, "/admin/coaches/$id/suspend", "чужое").status)
        assertEquals(HttpStatusCode.Unauthorized, client.act("adm=$appToken", "/admin/coaches/$id/suspend", csrf).status)
        assertFalse(suspended(database, coachEmail))

        assertEquals(HttpStatusCode.SeeOther, client.act(cookie, "/admin/coaches/$id/suspend", csrf).status)
        assertTrue(suspended(database, coachEmail))
        val after = client.page(cookie).html()
        assertTrue("Лена · приостановлен" in after)
        assertTrue("/admin/coaches/$id/resume" in after)

        // Приостановку видит и приложение коллеги — как после «Клуб → Тренеры».
        val pull = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $appToken") }
        assertTrue("SUSPENDED" in pull.bodyAsText())

        // Администратора приостановить нельзя и через адрес.
        client.act(cookie, "/admin/coaches/$adminId/suspend", csrf)
        assertFalse(suspended(database, admin))

        assertEquals(HttpStatusCode.SeeOther, client.act(cookie, "/admin/coaches/$id/resume", csrf).status)
        assertFalse(suspended(database, coachEmail))
        assertTrue("Лена · возобновлён" in client.page(cookie).html())
    }

    @Test
    fun logoutEndsSession() = withServer { database, client ->
        client.signUp(database, admin, "Дмитрий")
        val cookie = client.signIn(database)
        val csrf = csrf(client.page(cookie).html())
        val out = client.act(cookie, "/admin/logout", csrf)
        assertEquals(HttpStatusCode.SeeOther, out.status)
        assertNotNull(out.headers[HttpHeaders.SetCookie]?.takeIf { "Max-Age=0" in it })
        assertFalse("csrf" in client.page(cookie).html())
    }

    @Test
    fun adminRemovedFromConfigLosesSession() = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database, auth = Auth(mailer, setOf(admin), Passwords(memoryKb = 1024, iterations = 1))) }
        val client = createClient { followRedirects = false }
        client.signUp(database, admin, "Дмитрий")
        val cookie = client.signIn(database)
        // Почту убрали из TRENER_ADMIN_EMAIL и перезапустили сервер — прежняя cookie больше не пускает.
        val stillAdmin = database.tx { c -> AdminSessions(Accounts(emptySet())).find(c, cookie.substringAfter('=')) }
        assertEquals(null, stillAdmin)
    }
}
