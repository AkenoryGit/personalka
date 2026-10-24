package com.akenory.personalka.server

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.sync.SyncRules
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Кабинет родителя (ТЗ 6.1–6.3): страница по ссылке, код доступа, защита. Главная проверка шага 2.11 — в кабинете
 * ребёнка из группы в 12 человек нет чужих имён, названия группы и служебного.
 */
class CabinetTest {
    private val mailer = RecordingMailer()
    private val password = "плавание-2026"
    private val admin = "admin@example.ru"

    /** 20.10.2026 10:41 UTC = 15:41 в Тюмени — «сейчас» макетов 0.7. */
    private var clock = 1_792_492_860_000L

    private val group = "Сб 9:00 ББ (12)"
    private val children = listOf(
        "Данилова" to "Ева", "Беляев" to "Тимофей", "Ершова" to "Алиса", "Жуков" to "Марк", "Зайцева" to "Ульяна",
        "Исаев" to "Роман", "Карпова" to "Злата", "Ларин" to "Матвей", "Миронова" to "Софья", "Никитин" to "Глеб",
        "Осипова" to "Варвара", "Панов" to "Святослав",
    )
    private fun childId(i: Int) = "0193a1b2-c000-7000-8000-%012d".format(i)

    /** Число и слово — через неразрывный пробел, как в форматах (10.6). */
    private fun nb(text: String) = text.replace(Regex("(\\d) "), "$1${RuText.NBSP}")

    private fun withServer(block: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val database = Database.connect(TestDb.fresh())
        application {
            trener(database, auth = Auth(mailer, setOf(admin), Passwords(memoryKb = 1024, iterations = 1)), sync = Sync { clock })
        }
        block(database)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Регистрация — токен и id тренера. Администратору открыто всё; остальным — [features]. */
    private suspend fun ApplicationTestBuilder.signUp(database: Database, email: String = admin, features: List<String> = emptyList()): Pair<String, String> {
        suspend fun send(path: String, vararg fields: Pair<String, String>) = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())
        }
        send("/api/v1/auth/register", "email" to email, "password" to password, "display_name" to "Дмитрий")
        val response = send("/api/v1/auth/confirm", "email" to email, "password" to password, "code" to mailer.lastCode(), "device_name" to "Телефон")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val id = database.tx { c -> c.queryOne("SELECT id FROM coach WHERE email = ?", email) { it.getString(1) }!! }
        database.tx { c -> features.forEach { c.update("INSERT INTO coach_feature (coach_id, feature) VALUES (?, ?)", id, it) } }
        return response.obj()["token"]!!.jsonPrimitive.content to id
    }

    private fun row(entity: String, id: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("entity", entity)
        put("id", id)
        put("version", 1)
        put("updated_at", clock - 60_000)
        put("data", buildJsonObject { put("id", id); fields() })
    }

    /**
     * Данные тренера как с телефона: профиль и настройки, тип и место, группа из 12 детей и её тренировки,
     * внутренняя заметка и телефон у Евы, своя ПТ у другого ребёнка.
     */
    private fun data(coachId: String, cabinetsOn: Boolean = true): List<JsonObject> = buildList {
        add(row("coach", coachId) { put("coachId", coachId); put("lastName", "Дудник"); put("firstName", "Дмитрий"); put("middleName", "Викторович"); put("timezone", "Asia/Yekaterinburg") })
        add(row("coach_settings", "settings-1") { put("coachId", coachId); put("cancelNoticeMinutes", 60); put("upcomingInCard", 4); put("cabinetsEnabled", if (cabinetsOn) 1 else 0) })
        add(row("training_type", "type-group") { put("name", "Групповая"); put("shortName", "Группа"); put("kind", "GROUP"); put("diaryPlan", 1); put("diaryFact", 1); put("diaryBehavior", 0); put("scope", "COACH") })
        add(row("training_type", "type-pt") { put("name", "Персональная"); put("shortName", "ПТ"); put("kind", "PERSONAL"); put("diaryPlan", 1); put("diaryFact", 1); put("diaryBehavior", 1); put("scope", "COACH") })
        add(row("location", "place-bb") { put("name", "Большой бассейн"); put("shortName", "ББ"); put("scope", "CLUB") })
        add(row("client_group", "group-1") { put("name", group); put("typeId", "type-group"); put("locationId", "place-bb") })
        children.forEachIndexed { i, (last, first) ->
            add(
                row("client", childId(i)) {
                    put("lastName", last); put("firstName", first); put("cabinetEnabled", 1); put("status", "ACTIVE")
                    put("privateNote", "Мама — Светлана, просила не ставить на утро")
                    put("contactPhone", "+79120000${10 + i}"); put("contactName", "Светлана Петровна")
                },
            )
            add(row("group_member", "member-$i") { put("groupId", "group-1"); put("clientId", childId(i)) })
        }
        val trainings = listOf(
            Triple("t-17", "2026-10-17T09:00", "DONE"),
            Triple("t-10", "2026-10-10T09:00", "CANCELLED"),
            Triple("t-24", "2026-10-24T09:00", "PLANNED"),
            Triple("t-31", "2026-10-31T09:00", "CANCELLED"),
            Triple("t-19", "2026-09-19T09:00", "DONE"),
        )
        trainings.forEach { (id, start, status) ->
            add(
                row("training", id) {
                    put("typeId", "type-group"); put("locationId", "place-bb"); put("groupId", "group-1"); put("start", start)
                    put("durationMin", 45); put("status", status); put("owner", "SELF"); put("conductedBy", "SELF")
                    if (id == "t-17") { put("plan", "Дыхание в воду"); put("fact", "Выдох в воду на 5 счётов") }
                    if (id == "t-24") put("plan", "Ныряние за предметами")
                    if (id == "t-31" || id == "t-10") put("cancelReason", "По инициативе клуба: санитарный день")
                },
            )
            children.indices.forEach { i ->
                val p = when {
                    status == "DONE" && i == 3 -> "CANCELLED"
                    status == "DONE" -> "ATTENDED"
                    else -> "EXPECTED"
                }
                add(row("participation", "p-$id-$i") { put("trainingId", id); put("clientId", childId(i)); put("status", p); if (status == "DONE") put("behavior", "Поведение ${children[i].second}") })
            }
        }
        // ПТ другого ребёнка с коллегой — в кабинет Евы не попадает.
        add(row("training", "t-pt") { put("typeId", "type-pt"); put("locationId", "place-bb"); put("start", "2026-10-19T13:30"); put("durationMin", 30); put("status", "DONE"); put("plan", "Старт с тумбочки Тимофея") })
        add(row("participation", "p-pt") { put("trainingId", "t-pt"); put("clientId", childId(1)); put("status", "ATTENDED") })
    }

    private suspend fun HttpClient.push(token: String, changes: List<JsonObject>) {
        val response = post("/api/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("schema_version", SyncRules.SCHEMA_VERSION)
                    put("device_id", "phone-1")
                    put("changes", buildJsonArray { changes.forEach { add(it) } })
                }.toString(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private var tokenCount = 0

    /** Новая ссылка клиента [clientId] — токен для адреса `/c/<токен>`. */
    private suspend fun HttpClient.issue(token: String, clientId: String, pin: String? = null): String {
        val link = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 7 * ++tokenCount).toByte() })
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(link.toByteArray()))
        val response = put("/api/v1/cabinets/$clientId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("token_hash", hash); pin?.let { put("pin", it) } }.toString())
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return link
    }

    private suspend fun ApplicationTestBuilder.setUp(db: Database, pin: String? = null): Pair<String, String> {
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId))
        return token to client.issue(token, childId(0), pin)
    }

    private suspend fun HttpResponse.html(expected: HttpStatusCode = HttpStatusCode.OK): String {
        val text = bodyAsText()
        assertEquals(expected, status, text)
        return text
    }

    @Test
    fun groupChildSeesNoOtherNames() = withServer { db ->
        val (_, link) = setUp(db)
        val page = client.get("/c/$link").html()

        assertTrue("Данилова Ева" in page)
        assertTrue("Тренер: Дмитрий Дудник" in page)
        assertTrue("Данные на 20.10.2026, 15:41" in page)
        assertTrue("Групповая · Большой бассейн" in page)
        assertTrue("Ныряние за предметами" in page, "план ближайшей")
        assertTrue("Выдох в воду на 5 счётов" in page, "общий факт группы")
        assertTrue("По инициативе клуба: санитарный день" in page, "отменённая клубом будущая — в «Ближайших»")
        // Чужих имён, группы и служебного нет (6.2).
        children.drop(1).forEach { (last, first) ->
            assertFalse(last in page, "чужая фамилия $last")
            assertFalse(first in page, "чужое имя $first")
        }
        listOf(group, "Сб 9:00", "Светлана", "+7912", "Викторович", "Поведение", "Старт с тумбочки", "Персональная").forEach {
            assertFalse(it in page, "в кабинете не должно быть «$it»")
        }
        assertTrue(page.length < 100 * 1024, "страница до 100 КБ")
    }

    @Test
    fun pageSectionsFollowMockup() = withServer { db ->
        val (_, link) = setUp(db)
        val page = client.get("/c/$link").html()
        val order = listOf("Ближайшие тренировки", "Посещения", "Если вы предупреждаете об отмене", "История", "Октябрь 2026", "Ранее", "Сентябрь 2026", "По всем вопросам")
        val positions = order.map { page.indexOf(it.replace(" 2026", "${RuText.NBSP}2026")) }
        assertTrue(positions.none { it < 0 } && positions == positions.sorted(), "разделы по порядку: $positions")
        assertTrue("<details class=\"mo\" open" in page, "текущий месяц раскрыт")
        assertFalse("<script" in page)
        assertFalse("style=" in page, "встроенные стили запрещены CSP")
    }

    @Test
    fun securityHeadersAndRobots() = withServer { db ->
        val (_, link) = setUp(db)
        val response = client.get("/c/$link")
        assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("User-agent: *\nDisallow: /c/\n", client.get("/robots.txt").bodyAsText())
        val css = client.get("/cabinet.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue("prefers-color-scheme:dark" in css.bodyAsText())
    }

    @Test
    fun invalidLinksAnswerTheSame() = withServer { db ->
        val (token, link) = setUp(db)
        val notFound = client.get("/c/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").html(HttpStatusCode.NotFound)
        assertTrue("Ссылка больше не действует" in notFound)

        // Новая ссылка отзывает прежнюю.
        val fresh = client.issue(token, childId(0))
        assertEquals(notFound, client.get("/c/$link").html(HttpStatusCode.NotFound))
        client.get("/c/$fresh").html()

        // Кабинет клиента выключен.
        client.push(token, listOf(row("client", childId(0)) { put("lastName", "Данилова"); put("firstName", "Ева"); put("cabinetEnabled", 0) }.withTime(clock)))
        assertEquals(notFound, client.get("/c/$fresh").html(HttpStatusCode.NotFound))

        // Отозвана из приложения.
        val second = client.issue(token, childId(2))
        client.get("/c/$second").html()
        client.delete("/api/v1/cabinets/${childId(2)}") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(notFound, client.get("/c/$second").html(HttpStatusCode.NotFound))
    }

    @Test
    fun cabinetsOffOrClosedHideThePage() = withServer { db ->
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId, cabinetsOn = false))
        val link = client.issue(token, childId(0))
        client.get("/c/$link").html(HttpStatusCode.NotFound)

        // Без открытых «Кабинетов» ссылку не выпустить.
        val (other, _) = signUp(db, "trener@example.ru", features = listOf("server_copy"))
        val response = client.put("/api/v1/cabinets/${childId(0)}") {
            header(HttpHeaders.Authorization, "Bearer $other")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("token_hash", "x") }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun codeGuardsThePage() = withServer { db ->
        val (_, link) = setUp(db, pin = "4721")
        val client = createClient { followRedirects = false }
        val form = client.get("/c/$link").html()
        assertTrue("Введите код" in form)
        assertFalse("Данилова" in form || "Дудник" in form, "до кода — ни имени ребёнка, ни тренера")

        suspend fun enter(code: String) = client.submitForm("/c/$link/pin", parameters { append("code", code) })
        assertTrue(nb("Осталось 4 попытки") in enter("1111").html())
        assertTrue("Введите 4 цифры" in enter("12").html())
        val right = enter("4721")
        assertEquals(HttpStatusCode.SeeOther, right.status)
        val cookie = right.headers[HttpHeaders.SetCookie]!!
        assertTrue("HttpOnly" in cookie && "Secure" in cookie && "SameSite=Lax" in cookie && "Max-Age=2592000" in cookie && "Path=/c/$link" in cookie, cookie)
        val pass = cookie.substringBefore(';')

        val page = client.get("/c/$link") { header(HttpHeaders.Cookie, pass) }.html()
        assertTrue("Данилова Ева" in page)
        // Чужая cookie не подходит.
        assertTrue("Введите код" in client.get("/c/$link") { header(HttpHeaders.Cookie, "kb=чужая") }.html())
    }

    @Test
    fun fiveWrongCodesBlockFor15Minutes() = withServer { db ->
        val (_, link) = setUp(db, pin = "4721")
        val client = createClient { followRedirects = false }
        suspend fun enter(code: String) = client.submitForm("/c/$link/pin", parameters { append("code", code) }).html()
        listOf(nb("Осталось 4 попытки"), nb("Осталось 3 попытки"), nb("Осталось 2 попытки"), nb("Осталось 1 попытка")).forEach {
            assertTrue(it in enter("0000"), it)
        }
        assertTrue("Слишком много попыток. Попробуйте через 15 минут." in enter("0000"))
        assertTrue("Слишком много попыток" in enter("4721"), "под блокировкой и верный код не проходит")
        assertTrue("disabled" in client.get("/c/$link").html())
        clock += 15 * 60_000L + 1
        assertEquals(HttpStatusCode.SeeOther, client.submitForm("/c/$link/pin", parameters { append("code", "4721") }).status)
    }

    @Test
    fun thirtyRequestsPerMinuteFromOneAddress() = withServer { db ->
        val (_, link) = setUp(db)
        repeat(30) { client.get("/c/$link") { header("X-Forwarded-For", "203.0.113.5") }.html() }
        val blocked = client.get("/c/$link") { header("X-Forwarded-For", "203.0.113.5") }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertFalse("Данилова" in blocked.bodyAsText())
        client.get("/c/$link") { header("X-Forwarded-For", "203.0.113.6") }.html()
        clock += 60_001
        client.get("/c/$link") { header("X-Forwarded-For", "203.0.113.5") }.html()
    }

    @Test
    fun openingIsVisibleToCoach() = withServer { db ->
        val (token, link) = setUp(db)
        suspend fun links() = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()["cabinets"]!!.jsonArray
        assertEquals("null", links().single().jsonObject["last_opened_at"].toString())
        client.get("/c/$link").html()
        val opened = links().single().jsonObject
        assertEquals(childId(0), opened["client_id"]!!.jsonPrimitive.content)
        assertEquals(clock.toString(), opened["last_opened_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun tokenIsMaskedInLogs() {
        assertEquals("/c/AbCd…", maskCabinet("/c/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"))
        assertEquals("/c/AbCd…", maskCabinet("/c/AbCdEfGh?x=1").substringBefore('?'))
        assertEquals("/api/v1/sync/pull", maskCabinet("/api/v1/sync/pull"))
    }

    private fun JsonObject.withTime(at: Long) = JsonObject(this + ("updated_at" to kotlinx.serialization.json.JsonPrimitive(at)))
}
