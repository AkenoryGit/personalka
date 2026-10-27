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

    /** Согласие с экрана перед кабинетом (6.1): ответ — без перехода по 303, чтобы не открыть кабинет заодно. */
    private suspend fun ApplicationTestBuilder.consent(
        link: String,
        name: String? = "Данилова Светлана",
        agree: Boolean = true,
        photo: Boolean = false,
        cookie: String? = null,
    ): HttpResponse = createClient { followRedirects = false }.submitForm(
        "/c/$link/consent",
        parameters {
            name?.let { append("name", it) }
            if (agree) append("agree", "1")
            if (photo) append("photo", "1")
        },
    ) {
        header(HttpHeaders.UserAgent, "Браузер родителя")
        cookie?.let { header(HttpHeaders.Cookie, it) }
    }

    /** Тренер с данными и ссылкой для Евы; без кода — согласие уже дано ([consented]). */
    private suspend fun ApplicationTestBuilder.setUp(db: Database, pin: String? = null, consented: Boolean = pin == null): Pair<String, String> {
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId))
        val link = client.issue(token, childId(0), pin)
        if (consented) assertEquals(HttpStatusCode.SeeOther, consent(link).status)
        return token to link
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
        assertFalse("Результаты" in page, "нет результатов — нет раздела")
    }

    private fun result(id: String, clientId: String, date: String, distance: Int, style: String, cs: Int, kind: String = "TRAINING", pool: Int = 25) =
        row("result", id) {
            put("clientId", clientId); put("date", date); put("style", style); put("distanceM", distance); put("poolM", pool)
            put("timeCs", cs); put("kind", kind); put("note", "Заметка тренера о результате")
        }

    /** Результаты Евы: 50 м в/с — три в 25-метровом, 25 м на спине — два, один в 50-метровом; и чужой рекорд. */
    private fun results(): List<JsonObject> = listOf(
        result("res-1", childId(0), "2026-09-05", 50, "FREESTYLE", 5810),
        result("res-2", childId(0), "2026-09-26", 50, "FREESTYLE", 5520, kind = "COMPETITION"),
        result("res-3", childId(0), "2026-10-17", 50, "FREESTYLE", 5390),
        result("res-4", childId(0), "2026-10-03", 25, "BACKSTROKE", 3120),
        result("res-5", childId(0), "2026-10-17", 25, "BACKSTROKE", 2990),
        result("res-6", childId(0), "2026-10-18", 50, "FREESTYLE", 5600, pool = 50),
        result("res-other", childId(1), "2026-10-17", 50, "FREESTYLE", 4277),
    )

    @Test
    fun resultsWithChartChosenByChips() = withServer { db ->
        val (token, link) = setUp(db)
        client.push(token, results())
        val page = client.get("/c/$link").html()

        val order = listOf("История", "Результаты", "Личные рекорды · бассейн 25 м", "Личные рекорды · бассейн 50 м", "Время по дистанции", "Все результаты", "По всем вопросам")
        val positions = order.map { page.indexOf(nb(it)) }
        assertTrue(positions.none { it < 0 } && positions == positions.sorted(), "результаты между историей и подвалом: $positions")
        assertTrue("53,90" in page && "29,90" in page && "56,00" in page, "рекорды")
        assertFalse("42,77" in page, "чужой результат")
        assertFalse("Заметка тренера" in page, "заметка к результату не показывается")
        // Чипы-ссылки: по умолчанию — дистанция, где результатов больше всего.
        assertTrue("<a href=\"?d=50-freestyle-25#res\" class=\"chip on\" aria-current=\"true\">" in page, page)
        assertTrue("href=\"?d=25-backstroke-25#res\"" in page)
        assertFalse("d=50-freestyle-50" in page, "в 50-метровом один результат — графика нет")
        assertEquals(3, Regex("<circle class=\"p[fo]\" cx=\"[0-9.]+%\"").findAll(page).count(), "три точки")
        assertEquals(1, Regex("<circle class=\"pf\" cx=\"[0-9.]+%\"").findAll(page).count(), "соревнования — залитая")
        assertTrue("role=\"img\"" in page && nb("50 м вольный стиль: 3 результата") in page)
        assertTrue("Чем ниже точка, тем быстрее." in page)
        assertTrue("<summary><span class=\"mt\">Все результаты</span><span class=\"mut\">6</span>" in page)
        assertFalse("<script" in page)
        assertFalse("style=" in page, "встроенные стили запрещены CSP")

        val picked = client.get("/c/$link?d=25-backstroke-25").html()
        assertTrue("<a href=\"?d=25-backstroke-25#res\" class=\"chip on\"" in picked)
        assertEquals(2, Regex("<circle class=\"p[fo]\" cx=\"[0-9.]+%\"").findAll(picked).count())
        // Неизвестная дистанция — по умолчанию, текст из адреса на страницу не попадает.
        val odd = client.get("/c/$link?d=%3Cb%3Ex").html()
        assertTrue("<a href=\"?d=50-freestyle-25#res\" class=\"chip on\"" in odd)
        assertFalse("<b>x" in odd)
    }

    @Test
    fun pageSectionsFollowMockup() = withServer { db ->
        val (_, link) = setUp(db)
        val page = client.get("/c/$link").html()
        val order = listOf("Ближайшие тренировки", "Посещения", "Если вы предупреждаете об отмене", "История", "Октябрь 2026", "Ранее", "Сентябрь 2026", "По всем вопросам")
        val positions = order.map { page.indexOf(it.replace(" 2026", "${RuText.NBSP}2026")) }
        assertTrue(positions.none { it < 0 } && positions == positions.sorted(), "разделы по порядку: $positions")
        assertTrue("<details class=\"mo\" open" in page, "текущий месяц раскрыт")
        assertTrue("<details class=\"tr\">" in page, "тренировка в истории свёрнута до даты")
        assertFalse("<details class=\"tr\" open" in page, "ни одна тренировка не раскрыта сразу")
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
        assertEquals("User-agent: *\nDisallow: /c/\nDisallow: /p/\nDisallow: /admin\n", client.get("/robots.txt").bodyAsText())
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
    fun newLinkClosesTheOldOne() = withServer { db ->
        val (token, old) = setUp(db)
        client.get("/c/$old").html()
        val fresh = client.issue(token, childId(0))
        assertTrue("Ссылка больше не действует" in client.get("/c/$old").html(HttpStatusCode.NotFound))
        assertTrue("Данилова Ева" in client.get("/c/$fresh").html(), "согласие у клиента — новая ссылка его не спрашивает")
    }

    @Test
    fun codeChangesWithoutNewLink() = withServer { db ->
        val (token, link) = setUp(db, pin = "4721", consented = false)
        val browser = createClient { followRedirects = false }
        suspend fun pin(code: String?, client: String = childId(0)) = this.client.put("/api/v1/cabinets/$client/pin") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { code?.let { put("pin", it) } }.toString())
        }
        suspend fun links() = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()["cabinets"]!!.jsonArray
        assertEquals("true", links().single().jsonObject["pin"].toString())
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(link.toByteArray()))
        assertEquals(hash, links().single().jsonObject["token_hash"]!!.jsonPrimitive.content, "телефон узнаёт свою ссылку")
        val pass = browser.submitForm("/c/$link/pin", parameters { append("code", "4721") }).headers[HttpHeaders.SetCookie]!!.substringBefore(';')

        // Новый код: ссылка та же, прежний пропуск и прежний код больше не подходят.
        assertEquals(HttpStatusCode.OK, pin("1358").status)
        assertTrue("Введите код" in browser.get("/c/$link") { header(HttpHeaders.Cookie, pass) }.html())
        assertTrue(nb("Осталось 4 попытки") in browser.submitForm("/c/$link/pin", parameters { append("code", "4721") }).html())
        assertEquals(HttpStatusCode.SeeOther, browser.submitForm("/c/$link/pin", parameters { append("code", "1358") }).status)

        // Без кода — сразу дальше (здесь — согласие).
        assertEquals(HttpStatusCode.OK, pin(null).status)
        assertEquals("false", links().single().jsonObject["pin"].toString())
        assertFalse("Введите код" in browser.get("/c/$link").html())

        assertEquals(HttpStatusCode.BadRequest, pin("12").status)
        assertEquals(HttpStatusCode.NotFound, pin("1234", childId(5)).status)
    }

    @Test
    fun tokenIsMaskedInLogs() {
        assertEquals("/c/AbCd…", maskCabinet("/c/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"))
        assertEquals("/c/AbCd…", maskCabinet("/c/AbCdEfGh?x=1").substringBefore('?'))
        assertEquals("/p/AbCd…", maskCabinet("/p/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"))
        assertEquals("/api/v1/sync/pull", maskCabinet("/api/v1/sync/pull"))
    }

    /** Токен предпросмотра «Как видит родитель» (6.4) клиента [clientId]; ответ сервера — целиком. */
    private suspend fun HttpClient.preview(token: String?, clientId: String = childId(0)): HttpResponse = post("/api/v1/cabinets/$clientId/preview") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpClient.previewToken(token: String, clientId: String = childId(0)): String {
        val response = preview(token, clientId)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("900", response.obj()["expires_in"].toString())
        return response.obj()["token"]!!.jsonPrimitive.content
    }

    @Test
    fun previewDoesNotChangeLastOpened() = withServer { db ->
        val (token, _) = setUp(db, pin = "4721", consented = false)
        suspend fun links() = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()["cabinets"]!!.jsonArray
        val preview = client.previewToken(token)
        // Тот же кабинет без кода и без согласия: согласия нет — как после согласия без фото.
        val page = client.get("/p/$preview").html()
        listOf("Данилова Ева", "Тренер: Дмитрий Дудник", "Ближайшие", "Отозвать согласие").forEach { assertTrue(it in page, it) }
        assertFalse("Введите код" in page || "Ваши фамилия и имя" in page)
        assertFalse("/c/" in page, "ни ссылки родителя, ни отзыва согласия")
        assertEquals("default-src 'self'", client.get("/p/$preview").headers["Content-Security-Policy"])
        // Чипы графика работают и в предпросмотре.
        client.get("/p/$preview?d=50-freestyle-25").html()
        assertEquals("null", links().single().jsonObject["last_opened_at"].toString(), "предпросмотр — не открытие родителем")
        assertEquals(0, db.tx { c -> c.queryOne("SELECT count(*) FROM consent_record") { it.getInt(1) } })
    }

    @Test
    fun previewTokenIsForCoachOnly() = withServer { db ->
        val (token, link) = setUp(db)
        assertEquals(HttpStatusCode.Unauthorized, client.preview(null).status)
        // Ссылка родителя на месте токена предпросмотра и выдуманный токен — «Ссылка больше не действует».
        assertTrue("Ссылка больше не действует" in client.get("/p/$link").html(HttpStatusCode.NotFound))
        assertTrue("Ссылка больше не действует" in client.get("/p/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").html(HttpStatusCode.NotFound))

        // Через 15 минут токен не действует.
        val preview = client.previewToken(token)
        client.get("/p/$preview").html()
        clock += 15 * 60_000L
        client.get("/p/$preview").html(HttpStatusCode.NotFound)

        // Без ссылки или с закрытым кабинетом предпросмотра нет: родитель не видит ничего.
        assertEquals(HttpStatusCode.NotFound, client.preview(token, childId(5)).status)
        val fresh = client.previewToken(token)
        client.push(token, listOf(row("client", childId(0)) { put("lastName", "Данилова"); put("firstName", "Ева"); put("cabinetEnabled", 0) }.withTime(clock)))
        client.get("/p/$fresh").html(HttpStatusCode.NotFound)
        val closed = client.preview(token)
        assertEquals(HttpStatusCode.NotFound, closed.status)
        assertEquals("cabinet_closed", closed.obj()["code"]!!.jsonPrimitive.content)

        // Чужой тренер не получит предпросмотр чужого клиента.
        val (other, _) = signUp(db, "trener@example.ru", features = listOf("cabinets"))
        assertEquals(HttpStatusCode.NotFound, client.preview(other, childId(1)).status)
    }

    @Test
    fun consentComesBeforeTheCabinet() = withServer { db ->
        val (token, link) = setUp(db, consented = false)
        val screen = client.get("/c/$link").html()
        listOf("Данилова Ева", "Тренер: Дмитрий Дудник", "Согласие на обработку персональных данных", "Редакция 1 от 24.10.2026",
            "Тренер Дудник Дмитрий Викторович", "Ваши фамилия и имя", "Я родитель (законный представитель)", "Разрешаю хранить фото",
            "Продолжить", "href=\"/policy/").forEach { assertTrue(it in screen, "на экране согласия — «$it»") }
        // До согласия данных кабинета нет (6.1).
        listOf("Ближайшие тренировки", "Ныряние за предметами", "История", "/revoke").forEach { assertFalse(it in screen, "до согласия нет «$it»") }
        assertFalse("<script" in screen)
        assertFalse("style=" in screen)

        // Без галочки — тот же экран с подсказкой, введённое имя на месте, ничего не записано.
        val noAgree = consent(link, agree = false).html()
        assertTrue("Чтобы открыть кабинет, отметьте согласие." in noAgree)
        assertTrue("value=\"Данилова Светлана\"" in noAgree)
        assertFalse("Ныряние за предметами" in noAgree)
        // Без имени у ребёнка — подсказка про имя.
        assertTrue("Впишите ваши фамилию и имя." in consent(link, name = "  ").html())
        assertEquals(0, db.tx { c -> c.queryOne("SELECT count(*) FROM consent_record") { it.getInt(1) } })
        assertTrue("Согласие на обработку персональных данных" in client.get("/c/$link").html(), "без согласия — снова экран согласия")

        val accepted = consent(link)
        assertEquals(HttpStatusCode.SeeOther, accepted.status)
        assertEquals("/c/$link", accepted.headers[HttpHeaders.Location])
        val page = client.get("/c/$link").html()
        assertTrue("Ныряние за предметами" in page)
        val footer = page.substringAfter("<footer")
        listOf("По всем вопросам", "Согласие на обработку данных дано 20.10.2026", "Политика обработки персональных данных", "href=\"/c/$link/revoke\"")
            .forEach { assertTrue(it in footer, "в подвале — «$it»") }
        assertFalse("Фото и видео" in page, "ссылки на фото нет — раздела нет")

        // Запись согласия (3.7).
        val record = db.tx { c ->
            c.queryOne("SELECT client_id, text_version, array_to_string(items, ','), given_by_name, user_agent, ip IS NOT NULL FROM consent_record") {
                listOf(it.getString(1), it.getInt(2).toString(), it.getString(3), it.getString(4), it.getString(5), it.getBoolean(6).toString())
            }
        }
        assertEquals(listOf(childId(0), "1", "personal_data", "Данилова Светлана", "Браузер родителя", "true"), record)

        // Приложению видно: согласие получено, без фото, кем.
        val consents = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()["consents"]!!.jsonArray
        val given = consents.single().jsonObject
        assertEquals(childId(0), given["client_id"]!!.jsonPrimitive.content)
        assertEquals(clock.toString(), given["accepted_at"]!!.jsonPrimitive.content)
        assertEquals("false", given["photo"]!!.jsonPrimitive.content)
        assertEquals("Данилова Светлана", given["given_by_name"]!!.jsonPrimitive.content)
        assertEquals("null", given["revoked_at"].toString())

        // Новая ссылка без отзыва согласие заново не спрашивает: оно у клиента, а не у ссылки.
        val fresh = client.issue(token, childId(0))
        assertTrue("Ныряние за предметами" in client.get("/c/$fresh").html())
        // Повторная отправка формы второй записи не делает.
        consent(fresh)
        assertEquals(1, db.tx { c -> c.queryOne("SELECT count(*) FROM consent_record") { it.getInt(1) } })
    }

    @Test
    fun photosOnlyWithPhotoConsent() = withServer { db ->
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId))
        val withMedia = listOf(0, 1).map { i ->
            row("client", childId(i)) {
                put("lastName", children[i].first); put("firstName", children[i].second); put("cabinetEnabled", 1); put("mediaUrl", "https://disk.yandex.ru/d/kQ7x")
            }.withTime(clock)
        }
        client.push(token, withMedia)

        val noPhoto = client.issue(token, childId(0))
        consent(noPhoto, photo = false)
        val page = client.get("/c/$noPhoto").html()
        assertFalse("Фото и видео" in page, "без галочки про фото нет ссылки на фото")
        assertFalse("disk.yandex.ru" in page)

        val photo = client.issue(token, childId(1))
        consent(photo, photo = true)
        val shown = client.get("/c/$photo").html()
        assertTrue("Фото и видео" in shown && "Откроется Яндекс Диск" in shown)
        assertTrue("href=\"https://disk.yandex.ru/d/kQ7x\"" in shown && "rel=\"noopener noreferrer\"" in shown)
        assertTrue(shown.indexOf("Фото и видео") < shown.indexOf("<footer"), "«Фото и видео» — перед подвалом")
        val consents = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()["consents"]!!.jsonArray
        assertEquals(mapOf(childId(0) to "false", childId(1) to "true"), consents.associate { it.jsonObject["client_id"]!!.jsonPrimitive.content to it.jsonObject["photo"]!!.jsonPrimitive.content })
    }

    @Test
    fun adultGivesConsentWithoutName() = withServer { db ->
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId))
        client.push(token, listOf(row("client", "adult-1") { put("lastName", "Яковлева"); put("firstName", "Татьяна"); put("birthDate", "1992-04-03"); put("cabinetEnabled", 1) }.withTime(clock)))
        val link = client.issue(token, "adult-1")
        val screen = client.get("/c/$link").html()
        assertTrue("Яковлева Татьяна" in screen && "Даю согласие на обработку моих персональных данных" in screen)
        assertFalse("Ваши фамилия и имя" in screen || "name=\"name\"" in screen, "у взрослого поля имени нет")
        assertFalse("ребён" in screen)
        assertEquals(HttpStatusCode.SeeOther, consent(link, name = null).status)
        assertEquals(null, db.tx { c -> c.queryOne("SELECT given_by_name FROM consent_record") { it.getString(1) } })
        assertTrue("Яковлева Татьяна" in client.get("/c/$link").html())
    }

    @Test
    fun revokeClosesTheCabinetAndAsksAgain() = withServer { db ->
        val (token, link) = setUp(db)
        val ask = client.get("/c/$link/revoke").html()
        listOf("Отозвать согласие?", "Данилова Ева", "Кабинет закроется по этой ссылке", nb("в течение 30 дней"), "Вернуться в кабинет", "href=\"/c/$link\"")
            .forEach { assertTrue(it in ask, "на подтверждении — «$it»") }
        assertTrue("Ныряние за предметами" in client.get("/c/$link").html(), "подтверждение само ничего не отзывает")

        val done = client.post("/c/$link/revoke").html()
        assertTrue("Согласие отозвано" in done && "Кабинет закрыт. Тренер удалит данные ребёнка до 19.11.2026." in done, done)
        assertFalse("Данилова" in done)

        // Дальше ссылка отвечает как недействительная.
        assertTrue("Ссылка больше не действует" in client.get("/c/$link").html(HttpStatusCode.NotFound))
        client.post("/c/$link/revoke").html(HttpStatusCode.NotFound)
        val cabinets = client.get("/api/v1/cabinets") { header(HttpHeaders.Authorization, "Bearer $token") }.obj()
        assertTrue(cabinets["cabinets"]!!.jsonArray.isEmpty(), "ссылка отозвана")
        assertEquals(clock.toString(), cabinets["consents"]!!.jsonArray.single().jsonObject["revoked_at"]!!.jsonPrimitive.content, "тренеру видно: удалить данные")

        // Новая ссылка — согласие спрашивается снова.
        val fresh = client.issue(token, childId(0))
        val again = client.get("/c/$fresh").html()
        assertTrue("Ваши фамилия и имя" in again)
        assertFalse("Ныряние за предметами" in again)
    }

    @Test
    fun codeComesBeforeConsent() = withServer { db ->
        val (_, link) = setUp(db, pin = "4721")
        // Без кода согласие не принимается — ответ ведёт на страницу кода.
        val blind = consent(link)
        assertEquals(HttpStatusCode.SeeOther, blind.status)
        assertEquals(0, db.tx { c -> c.queryOne("SELECT count(*) FROM consent_record") { it.getInt(1) } })
        assertTrue("Введите код" in client.get("/c/$link/revoke").html(), "без кода отзыва нет — сначала код")

        val noRedirect = createClient { followRedirects = false }
        val pass = noRedirect.submitForm("/c/$link/pin", parameters { append("code", "4721") }).headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue("Ваши фамилия и имя" in client.get("/c/$link") { header(HttpHeaders.Cookie, pass) }.html(), "после кода — согласие")
        assertEquals(HttpStatusCode.SeeOther, consent(link, cookie = pass).status)
        assertTrue("Ныряние за предметами" in client.get("/c/$link") { header(HttpHeaders.Cookie, pass) }.html())
    }

    @Test
    fun policyIsPublicWithCoachName() = withServer { db ->
        val (token, coachId) = signUp(db)
        client.push(token, data(coachId))
        client.push(token, listOf(row("coach", coachId) {
            put("coachId", coachId); put("lastName", "Дудник"); put("firstName", "Дмитрий"); put("middleName", "Викторович")
            put("contactForParents", "+7 912 000-00-00"); put("timezone", "Asia/Yekaterinburg")
        }.withTime(clock)))
        val policy = client.get("/policy/$coachId").html()
        listOf("Политика обработки персональных данных", "Редакция 1 от 24.10.2026", "тренер Дудник Дмитрий Викторович", "+7 912 000-00-00", "152-ФЗ", "9. Изменения политики")
            .forEach { assertTrue(it in policy, "в политике — «$it»") }
        assertFalse("Данилова" in policy, "имён клиентов нет")
        assertEquals("default-src 'self'", client.get("/policy/$coachId").headers["Content-Security-Policy"])
        client.get("/policy/нет-такого").html(HttpStatusCode.NotFound)
    }

    private fun JsonObject.withTime(at: Long) = JsonObject(this + ("updated_at" to kotlinx.serialization.json.JsonPrimitive(at)))
}
