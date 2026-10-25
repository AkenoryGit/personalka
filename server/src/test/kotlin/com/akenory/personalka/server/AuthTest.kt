package com.akenory.personalka.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Письма в тестах — в список: код берётся из темы, как его увидел бы тренер в уведомлении почты. */
class RecordingMailer : Mailer {
    val sent = mutableListOf<Triple<String, String, String>>()
    var failing = false

    override suspend fun send(to: String, subject: String, text: String) {
        if (failing) throw MailException("ящик недоступен")
        sent += Triple(to, subject, text)
    }

    fun lastCode(): String = sent.last().second.takeLast(6)
}

/** Вход (ТЗ 7.3, 7.1): регистрация с кодом, вход, сброс пароля кодом, сессии, возможности. */
class AuthTest {
    private val mailer = RecordingMailer()
    private val password = "плавание-2026"

    private fun withServer(admins: Set<String> = emptySet(), block: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val database = Database.connect(TestDb.fresh())
        // Слабый Argon2 — только для скорости тестов; формат строки тот же.
        application { trener(database, auth = Auth(mailer, admins, Passwords(memoryKb = 1024, iterations = 1))) }
        block(database)
    }

    private suspend fun HttpClient.json(path: String, vararg fields: Pair<String, String>, ip: String = "10.0.0.1"): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        header("X-Forwarded-For", ip)
        setBody(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String = obj()["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.register(email: String = "trener@example.ru", name: String = "Иван Петров", ip: String = "10.0.0.1") =
        json("/api/v1/auth/register", "email" to email, "password" to password, "display_name" to name, ip = ip)

    /** Регистрация и подтверждение — токен. */
    private suspend fun HttpClient.signUp(email: String = "trener@example.ru", device: String = "Galaxy Z Fold"): String {
        assertEquals(HttpStatusCode.OK, register(email).status)
        val response = json("/api/v1/auth/confirm", "email" to email, "password" to password, "code" to mailer.lastCode(), "device_name" to device)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.obj()["token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.me(token: String) = get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun registerSendsCodeAndConfirmGivesToken() = withServer {
        val response = client.register(email = "  Trener@Example.RU ")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("code_sent", response.obj()["status"]!!.jsonPrimitive.content)
        val (to, subject, text) = mailer.sent.single()
        assertEquals("trener@example.ru", to)
        assertTrue(subject.matches(Regex("Код подтверждения: \\d{6}")))
        assertTrue("Здравствуйте, Иван Петров!" in text)
        // До подтверждения войти нельзя: новый код и просьба подтвердить.
        val early = client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to password)
        assertEquals(HttpStatusCode.Forbidden, early.status)
        assertEquals("email_not_confirmed", early.code())
        assertEquals(1, mailer.sent.size, "второе письмо — не раньше чем через минуту")
    }

    @Test
    fun confirmedAccountSignsIn() = withServer {
        val token = client.signUp()
        val me = client.me(token)
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.obj()
        assertEquals("Иван Петров", body["display_name"]!!.jsonPrimitive.content)
        assertEquals("COACH", body["role"]!!.jsonPrimitive.content)
        assertEquals(0, body["features"]!!.jsonArray.size, "у нового аккаунта всё закрыто")
        val session = body["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("Galaxy Z Fold", session["device_name"]!!.jsonPrimitive.content)
        assertEquals("true", session["current"]!!.jsonPrimitive.content)
        // Вход с другого устройства — вторая сессия.
        val login = client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to password, "device_name" to "Планшет")
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals(2, client.me(token).obj()["sessions"]!!.jsonArray.size)
    }

    @Test
    fun wrongPasswordAndUnknownEmailLookTheSame() = withServer {
        client.signUp()
        val wrong = client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to "чужой-пароль-123")
        val unknown = client.json("/api/v1/auth/login", "email" to "nobody@example.ru", "password" to "чужой-пароль-123", ip = "10.0.0.2")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
        assertEquals("wrong_password", wrong.code())
    }

    @Test
    fun fiveLoginAttemptsPerMinute() = withServer {
        client.signUp()
        repeat(5) { client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to "не-тот-пароль-$it", ip = "10.0.0.${it + 10}") }
        // Шестая — даже с верным паролем и с другого адреса: предел на почту.
        val sixth = client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to password, ip = "10.0.0.99")
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("too_many", sixth.code())
        assertTrue(sixth.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
        // И на IP: пять разных адресов почты с одного IP — шестой ждёт.
        repeat(5) { client.json("/api/v1/auth/login", "email" to "x$it@example.ru", "password" to password, ip = "10.9.9.9") }
        assertEquals(HttpStatusCode.TooManyRequests, client.json("/api/v1/auth/login", "email" to "y@example.ru", "password" to password, ip = "10.9.9.9").status)
    }

    @Test
    fun weakPasswordBadEmailAndNoName() = withServer {
        val short = client.json("/api/v1/auth/register", "email" to "a@example.ru", "password" to "1234567", "display_name" to "Иван")
        assertEquals("weak_password", short.code())
        assertEquals("Пароль — не короче 8 знаков", short.obj()["message"]!!.jsonPrimitive.content)
        assertEquals("invalid_email", client.json("/api/v1/auth/register", "email" to "a@b", "password" to password, "display_name" to "Иван").code())
        assertEquals("no_name", client.json("/api/v1/auth/register", "email" to "a@example.ru", "password" to password, "display_name" to " ").code())
        assertEquals("bad_request", client.post("/api/v1/auth/register") { setBody("не json") }.code())
        assertTrue(mailer.sent.isEmpty())
    }

    @Test
    fun confirmedEmailCannotBeTakenAgain() = withServer {
        client.signUp()
        val again = client.register(name = "Чужой", ip = "10.0.0.5")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("email_taken", again.code())
    }

    @Test
    fun wrongCodeCountsAttempts() = withServer { database ->
        client.register()
        val right = mailer.lastCode()
        val wrong = if (right == "000000") "111111" else "000000"
        repeat(4) { i ->
            val r = client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to password, "code" to wrong)
            assertEquals("wrong_code", r.code())
            if (i == 0) assertEquals("Неверный код. Осталось 4\u00A0попытки", r.obj()["message"]!!.jsonPrimitive.content)
        }
        // Пятая неверная — код сгорает, верный уже не подходит.
        assertEquals("code_expired", client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to password, "code" to wrong, ip = "10.0.0.7").code())
        // Подтверждение проверяет и пароль — у него тот же предел 5 попыток в минуту, что у входа.
        assertEquals("too_many", client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to password, "code" to right, ip = "10.0.0.8").code())
        database.dataSource.connection.use { it.createStatement().execute("UPDATE login_attempt SET at = at - interval '61 seconds'") }
        assertEquals("code_expired", client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to password, "code" to right, ip = "10.0.0.8").code())
    }

    @Test
    fun confirmNeedsThePasswordOfRegistration() = withServer {
        client.register()
        val code = mailer.lastCode()
        val other = client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to "другой-пароль-1", "code" to code)
        assertEquals("wrong_password", other.code())
    }

    @Test
    fun expiredCodeIsRefused() = withServer { database ->
        client.register()
        database.dataSource.connection.use { it.createStatement().execute("UPDATE mail_code SET expires_at = now() - interval '1 second'") }
        assertEquals("code_expired", client.json("/api/v1/auth/confirm", "email" to "trener@example.ru", "password" to password, "code" to mailer.lastCode()).code())
    }

    @Test
    fun codeIsResentNotOftenerThanOncePerMinute() = withServer { database ->
        client.register()
        val again = client.register(ip = "10.0.0.2")
        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        assertEquals(1, mailer.sent.size)
        database.dataSource.connection.use { it.createStatement().execute("UPDATE mail_send SET sent_at = now() - interval '61 seconds'") }
        assertEquals(HttpStatusCode.OK, client.register(name = "Иван", ip = "10.0.0.3").status)
        assertEquals(2, mailer.sent.size)
        // Код из второго письма; имя — из повторной регистрации.
        assertTrue("Здравствуйте, Иван!" in mailer.sent.last().third)
    }

    @Test
    fun failedMailDoesNotBlockRetry() = withServer {
        mailer.failing = true
        val failed = client.register()
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertEquals("mail_failed", failed.code())
        mailer.failing = false
        assertEquals(HttpStatusCode.OK, client.register().status, "письмо не ушло — повтор сразу, без минуты ожидания")
    }

    @Test
    fun resetByCodeChangesPasswordAndSignsOutOthers() = withServer { database ->
        val old = client.signUp()
        database.ageMail()
        val request = client.json("/api/v1/auth/reset/request", "email" to "trener@example.ru")
        assertEquals(HttpStatusCode.OK, request.status)
        assertTrue(mailer.sent.last().second.startsWith("Код для нового пароля: "))
        val newPassword = "новый-пароль-2026"
        val reset = client.json(
            "/api/v1/auth/reset", "email" to "trener@example.ru", "code" to mailer.lastCode(), "password" to newPassword, "device_name" to "Новый телефон",
        )
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        val token = reset.obj()["token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.me(old).status, "прежнее устройство вышло")
        assertEquals(1, client.me(token).obj()["sessions"]!!.jsonArray.size)
        assertEquals("wrong_password", client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to password).code())
        assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to newPassword).status)
    }

    @Test
    fun resetForUnknownEmailAnswersTheSameWithoutLetter() = withServer {
        val response = client.json("/api/v1/auth/reset/request", "email" to "nobody@example.ru")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(mailer.sent.isEmpty())
        assertEquals("code_expired", client.json("/api/v1/auth/reset", "email" to "nobody@example.ru", "code" to "123456", "password" to password).code())
    }

    @Test
    fun resetConfirmsEmailToo() = withServer { database ->
        client.register()
        // Тот же адрес — не чаще раза в минуту, даже для другого письма.
        assertEquals(HttpStatusCode.TooManyRequests, client.json("/api/v1/auth/reset/request", "email" to "trener@example.ru").status)
        database.ageMail()
        assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/reset/request", "email" to "trener@example.ru").status)
        val reset = client.json("/api/v1/auth/reset", "email" to "trener@example.ru", "code" to mailer.lastCode(), "password" to "новый-пароль-2026")
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        // Код пришёл на почту — она подтверждена, вход паролем работает.
        assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to "новый-пароль-2026").status)
    }

    /** Письма «отправлены» больше минуты назад — следующее можно. */
    private fun Database.ageMail() = dataSource.connection.use { it.createStatement().execute("UPDATE mail_send SET sent_at = sent_at - interval '61 seconds'") }

    @Test
    fun logoutAndSignOutOtherDevice() = withServer {
        val phone = client.signUp()
        val tablet = client.json("/api/v1/auth/login", "email" to "trener@example.ru", "password" to password, "device_name" to "Планшет")
            .obj()["token"]!!.jsonPrimitive.content
        val sessions = client.me(phone).obj()["sessions"]!!.jsonArray.map { it.jsonObject }
        val tabletId = sessions.single { it["device_name"]!!.jsonPrimitive.content == "Планшет" }["id"]!!.jsonPrimitive.content
        val out = client.delete("/api/v1/me/sessions/$tabletId") { header(HttpHeaders.Authorization, "Bearer $phone") }
        assertEquals(HttpStatusCode.OK, out.status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(tablet).status)
        val logout = client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $phone") }
        assertEquals(HttpStatusCode.OK, logout.status)
        val after = client.me(phone)
        assertEquals(HttpStatusCode.Unauthorized, after.status)
        assertEquals("Вход устарел — войдите заново", after.obj()["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun foreignSessionCannotBeClosed() = withServer {
        val mine = client.signUp()
        val other = client.signUp(email = "other@example.ru")
        val otherId = client.me(other).obj()["sessions"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        val r = client.delete("/api/v1/me/sessions/$otherId") { header(HttpHeaders.Authorization, "Bearer $mine") }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals(HttpStatusCode.OK, client.me(other).status)
    }

    @Test
    fun sessionIsProlongedOnUseAndExpires() = withServer { database ->
        val token = client.signUp()
        val sql = { q: String -> database.dataSource.connection.use { it.createStatement().execute(q) } }
        sql("UPDATE coach_session SET expires_at = now() + interval '1 day'")
        assertEquals(HttpStatusCode.OK, client.me(token).status)
        val days = database.dataSource.connection.use { c ->
            c.createStatement().executeQuery("SELECT EXTRACT(DAY FROM expires_at - now())::int FROM coach_session").use { it.next(); it.getInt(1) }
        }
        assertTrue(days in 179..180, "продлено на 180 дней: $days")
        sql("UPDATE coach_session SET expires_at = now() - interval '1 second'")
        assertEquals(HttpStatusCode.Unauthorized, client.me(token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.me("выдуманный").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me").status)
    }

    @Test
    fun tokenIsStoredOnlyAsHash() = withServer { database ->
        val token = client.signUp()
        val stored = database.dataSource.connection.use { c ->
            c.createStatement().executeQuery("SELECT token_hash, (SELECT password_hash FROM coach) FROM coach_session").use { it.next(); it.getBytes(1) to it.getString(2) }
        }
        assertEquals(32, stored.first.size)
        assertFalse(stored.first.contentEquals(token.toByteArray()))
        assertTrue(stored.second.startsWith("\$argon2id\$v=19\$"))
        assertFalse(password in stored.second)
    }

    @Test
    fun adminHasEverythingOpen() = withServer(admins = setOf("dmitry@example.ru")) {
        val token = client.signUp(email = "Dmitry@example.ru")
        val me = client.me(token).obj()
        assertEquals("ADMIN", me["role"]!!.jsonPrimitive.content)
        val features = me["features"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("half_official", "cabinets", "server_copy"), features.map { it["code"]!!.jsonPrimitive.content })
        assertTrue(features.all { it["until"].toString() == "null" })
    }

    @Test
    fun featuresWithDatesComeFromTable() = withServer { database ->
        val token = client.signUp()
        database.dataSource.connection.use {
            it.createStatement().execute("INSERT INTO coach_feature (coach_id, feature, until) SELECT id, 'cabinets', DATE '2026-12-31' FROM coach")
        }
        val features = client.me(token).obj()["features"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("cabinets" to "2026-12-31", "server_copy" to "2026-12-31"),
            features.map { it["code"]!!.jsonPrimitive.content to it["until"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun phoneCoachIdIsKeptWhenFree() = withServer {
        val id = "3f2c1b4e-8a7d-4c6b-9e5f-1a2b3c4d5e6f"
        client.json("/api/v1/auth/register", "email" to "a@example.ru", "password" to password, "display_name" to "Иван", "coach_id" to id)
        val token = client.json("/api/v1/auth/confirm", "email" to "a@example.ru", "password" to password, "code" to mailer.lastCode())
            .obj()["token"]!!.jsonPrimitive.content
        assertEquals(id, client.me(token).obj()["id"]!!.jsonPrimitive.content)
        // Тот же id у другого — сервер даёт свой.
        client.json("/api/v1/auth/register", "email" to "b@example.ru", "password" to password, "display_name" to "Пётр", "coach_id" to id, ip = "10.0.0.2")
        val other = client.json("/api/v1/auth/confirm", "email" to "b@example.ru", "password" to password, "code" to mailer.lastCode(), ip = "10.0.0.2")
            .obj()["token"]!!.jsonPrimitive.content
        assertNotEquals(id, client.me(other).obj()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutMailRegistrationSaysSo() = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database) }
        val r = client.json("/api/v1/auth/register", "email" to "a@example.ru", "password" to password, "display_name" to "Иван")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("mail_disabled", r.code())
    }
}
