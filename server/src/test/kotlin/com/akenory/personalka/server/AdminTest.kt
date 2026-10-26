package com.akenory.personalka.server

import com.akenory.personalka.domain.sync.SyncRules
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Клуб → Тренеры» (ТЗ 5.17, 1.5; шаг 5.2): список тренеров и приостановка — только у администратора. */
class AdminTest {
    private val mailer = RecordingMailer()
    private val password = "плавание-2026"
    private val admin = "admin@example.ru"
    private val coachEmail = "lena@example.ru"

    private fun withServer(block: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val database = Database.connect(TestDb.fresh())
        application { trener(database, auth = Auth(mailer, setOf(admin), Passwords(memoryKb = 1024, iterations = 1))) }
        block(database)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String = obj()["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.send(path: String, vararg fields: Pair<String, String>) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())
    }

    /** Регистрация и подтверждение — токен; у не администратора открыта «Копия на сервере». */
    private suspend fun ApplicationTestBuilder.signUp(database: Database, email: String, name: String): String {
        client.send("/api/v1/auth/register", "email" to email, "password" to password, "display_name" to name)
        val response = client.send("/api/v1/auth/confirm", "email" to email, "password" to password, "code" to mailer.lastCode(), "device_name" to "Телефон")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        if (email != admin) {
            database.tx { c -> c.update("INSERT INTO coach_feature (coach_id, feature) SELECT id, 'server_copy' FROM coach WHERE email = ?", email) }
        }
        return response.obj()["token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.coaches(token: String) = get("/api/v1/admin/coaches") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.action(token: String, id: String, what: String) =
        post("/api/v1/admin/coaches/$id/$what") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.pull(token: String) =
        get("/api/v1/sync/pull?since=0&schema_version=${SyncRules.SCHEMA_VERSION}") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.push(token: String) = post("/api/v1/sync/push") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("schema_version", SyncRules.SCHEMA_VERSION)
                put("device_id", "0193a1b2-0000-7000-8000-00000000000a")
                put(
                    "changes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("entity", "client")
                                put("id", "0193a1b2-1111-7000-8000-000000000001")
                                put("version", 1)
                                put("updated_at", 1_792_843_200_000L)
                                put("data", buildJsonObject { put("first_name", "Вероника") })
                            },
                        )
                    },
                )
            }.toString(),
        )
    }

    private fun coachId(database: Database, email: String): String = kotlinx.coroutines.runBlocking {
        database.tx { c -> c.queryOne("SELECT id FROM coach WHERE email = ?", email) { it.getString(1) }!! }
    }

    @Test
    fun onlyAdminSeesCoaches() = withServer { db ->
        signUp(db, admin, "Дмитрий")
        val lena = signUp(db, coachEmail, "Лена Смирнова")
        val response = client.coaches(lena)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("admin_only", response.code())
        assertEquals(HttpStatusCode.Forbidden, client.action(lena, coachId(db, admin), "suspend").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/coaches").status)
    }

    @Test
    fun listShowsAccountsWithoutTheirData() = withServer { db ->
        val boss = signUp(db, admin, "Дмитрий")
        val lena = signUp(db, coachEmail, "Лена Смирнова")
        assertEquals(HttpStatusCode.OK, client.push(lena).status)
        val lenaId = coachId(db, coachEmail)
        db.tx { c ->
            c.update("INSERT INTO cabinet_access (coach_id, client_id, token_hash) VALUES (?, 'k1', '\\x01'::bytea)", lenaId)
            c.update("INSERT INTO cabinet_access (coach_id, client_id, token_hash) VALUES (?, 'k2', '\\x02'::bytea)", lenaId)
            c.update("INSERT INTO cabinet_access (coach_id, client_id, token_hash, revoked_at) VALUES (?, 'k3', '\\x03'::bytea, now())", lenaId)
        }
        val response = client.coaches(boss)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val list = response.obj()["coaches"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Дмитрий", "Лена Смирнова"), list.map { it["display_name"]!!.jsonPrimitive.content }, "администратор первым")
        val row = list[1]
        assertEquals(coachEmail, row["email"]!!.jsonPrimitive.content)
        assertEquals("ACTIVE", row["status"]!!.jsonPrimitive.content)
        assertEquals(2, row["cabinets"]!!.jsonPrimitive.content.toInt(), "отозванная ссылка не считается")
        assertTrue(row["synced_at"]!!.jsonPrimitive.content.startsWith("20"), "обмен был")
        // Данных тренера в ответе нет — ни клиентов, ни тренировок.
        assertTrue("Вероника" !in response.bodyAsText())
        assertEquals(setOf("id", "display_name", "email", "role", "status", "confirmed", "synced_at", "cabinets", "created_at"), row.keys)
    }

    @Test
    fun suspendStopsSyncAndResumeRestores() = withServer { db ->
        val boss = signUp(db, admin, "Дмитрий")
        val lena = signUp(db, coachEmail, "Лена Смирнова")
        assertEquals(HttpStatusCode.OK, client.push(lena).status)
        val id = coachId(db, coachEmail)

        val suspended = client.action(boss, id, "suspend")
        assertEquals(HttpStatusCode.OK, suspended.status, suspended.bodyAsText())
        assertEquals("SUSPENDED", suspended.obj()["coach"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("suspended", client.pull(lena).code())
        assertEquals("suspended", client.push(lena).code())
        // Сессия жива: телефон узнаёт о приостановке из /me, а не выходит из аккаунта.
        val me = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $lena") }
        assertEquals("SUSPENDED", me.obj()["status"]!!.jsonPrimitive.content)
        val login = client.send("/api/v1/auth/login", "email" to coachEmail, "password" to password, "device_name" to "Планшет")
        assertEquals("suspended", login.code())
        // Записи на сервере не стёрты.
        assertEquals(1, db.tx { c -> c.queryOne("SELECT count(*) FROM sync_row WHERE owner = ?", id) { it.getInt(1) } })

        val resumed = client.action(boss, id, "resume")
        assertEquals("ACTIVE", resumed.obj()["coach"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        val pull = client.pull(lena)
        assertEquals(HttpStatusCode.OK, pull.status, pull.bodyAsText())
        assertEquals(1, pull.obj()["changes"]!!.jsonArray.size)
    }

    @Test
    fun adminCannotBeSuspended() = withServer { db ->
        val boss = signUp(db, admin, "Дмитрий")
        val response = client.action(boss, coachId(db, admin), "suspend")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(HttpStatusCode.NotFound, client.action(boss, "нет-такого", "suspend").status)
    }
}
