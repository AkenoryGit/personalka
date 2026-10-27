package com.akenory.personalka.server

import com.akenory.personalka.domain.sync.SyncRules
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Синхронизация (ТЗ 7.2, 7.1): приём и выдача, повтор отправки, конфликт правок, удаления, версии схемы, доступ. */
class SyncTest {
    private val mailer = RecordingMailer()
    private val password = "плавание-2026"
    private val admin = "admin@example.ru"

    /** 24.10.2026 12:00 UTC — часы сервера в тестах. */
    private var clock = 1_792_843_200_000L
    private val day = 24 * 60 * 60 * 1000L

    private val phone = "0193a1b2-0000-7000-8000-00000000000a"
    private val tablet = "0193a1b2-0000-7000-8000-00000000000b"
    private val trainingId = "0193a1b2-1111-7000-8000-000000000001"

    private fun withServer(block: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val database = Database.connect(TestDb.fresh())
        application {
            trener(database, auth = Auth(mailer, setOf(admin), Passwords(memoryKb = 1024, iterations = 1)), sync = Sync { clock })
        }
        block(database)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String = obj()["code"]!!.jsonPrimitive.content

    /** Регистрация и подтверждение — токен; [copy] — открыть «Копию на сервере» до [until] (null — без срока). */
    private suspend fun ApplicationTestBuilder.signUp(
        database: Database,
        email: String = "trener@example.ru",
        copy: Boolean = true,
        until: String? = null,
    ): String {
        suspend fun send(path: String, vararg fields: Pair<String, String>) = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())
        }
        send("/api/v1/auth/register", "email" to email, "password" to password, "display_name" to "Иван Петров")
        val response = send("/api/v1/auth/confirm", "email" to email, "password" to password, "code" to mailer.lastCode(), "device_name" to "Телефон")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val token = response.obj()["token"]!!.jsonPrimitive.content
        if (email != admin) {
            // Копия открыта новым тренерам по умолчанию (шаг 5.2в) — здесь её срок и наличие задаёт тест.
            database.tx { c ->
                val id = c.queryOne("SELECT id FROM coach WHERE email = ?", email) { it.getString(1) }
                c.update("DELETE FROM coach_feature WHERE coach_id = ?", id)
                if (copy) c.update("INSERT INTO coach_feature (coach_id, feature, until) VALUES (?, 'server_copy', ?::date)", id, until)
            }
        }
        return token
    }

    private fun change(
        id: String = trainingId,
        updatedAt: Long = clock - 60_000,
        entity: String = "training",
        deletedAt: Long? = null,
        version: Long = 1,
        data: JsonObject = buildJsonObject { put("id", id); put("comment", "Кроль на спине") },
    ) = buildJsonObject {
        put("entity", entity)
        put("id", id)
        put("version", version)
        put("updated_at", updatedAt)
        put("deleted_at", deletedAt)
        put("data", data)
    }

    private suspend fun HttpClient.push(token: String?, vararg changes: JsonObject, device: String = phone, schema: Int? = SyncRules.SCHEMA_VERSION) =
        post("/api/v1/sync/push") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    schema?.let { put("schema_version", it) }
                    put("device_id", device)
                    put("changes", buildJsonArray { changes.forEach { add(it) } })
                }.toString(),
            )
        }

    private suspend fun HttpClient.pull(token: String?, since: Long = 0, limit: Int? = null, schema: Int? = SyncRules.SCHEMA_VERSION) =
        get("/api/v1/sync/pull?since=$since" + (limit?.let { "&limit=$it" } ?: "") + (schema?.let { "&schema_version=$it" } ?: "")) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun HttpResponse.statuses(): List<String> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return obj()["results"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }
    }

    private suspend fun HttpResponse.rows(): List<JsonObject> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return obj()["changes"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.comment() = this["data"]!!.jsonObject["comment"]!!.jsonPrimitive.content

    @Test
    fun pushedChangesComeBackInOrder() = withServer { db ->
        val token = signUp(db)
        val other = "0193a1b2-1111-7000-8000-000000000002"
        assertEquals(listOf("accepted", "accepted"), client.push(token, change(), change(id = other)).statuses())

        val response = client.pull(token)
        val rows = response.rows()
        assertEquals(listOf(trainingId, other), rows.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(phone, rows[0]["device_id"]!!.jsonPrimitive.content)
        assertEquals("Кроль на спине", rows[0].comment())
        assertEquals(JsonNull, rows[0]["deleted_at"])
        val last = response.obj()["last_seq"]!!.jsonPrimitive.long
        assertEquals(rows[1]["seq"]!!.jsonPrimitive.long, last)
        assertFalse(response.obj()["has_more"]!!.jsonPrimitive.content.toBoolean())

        // После последнего номера — пусто, номер тот же.
        val next = client.pull(token, since = last)
        assertEquals(emptyList(), next.rows())
        assertEquals(last, next.obj()["last_seq"]!!.jsonPrimitive.long)
    }

    @Test
    fun repeatedPushDoesNotDuplicate() = withServer { db ->
        val token = signUp(db)
        val first = client.push(token, change()).obj()["results"]!!.jsonArray[0].jsonObject
        // Ответ потерялся — телефон отправляет то же самое ещё раз: принято, номер прежний, запись одна.
        val again = client.push(token, change()).obj()["results"]!!.jsonArray[0].jsonObject
        assertEquals("accepted", again["status"]!!.jsonPrimitive.content)
        assertEquals(first["seq"], again["seq"])
        assertEquals(1, client.pull(token).rows().size)
    }

    @Test
    fun laterEditWinsWhateverArrivesFirst() = withServer { db ->
        val token = signUp(db)
        val early = change(updatedAt = clock - 120_000, data = buildJsonObject { put("comment", "С телефона") })
        val late = change(updatedAt = clock - 60_000, data = buildJsonObject { put("comment", "С планшета") })

        // Позднее изменение пришло первым — раннее отклоняется и придёт телефону уже чужим.
        assertEquals(listOf("accepted"), client.push(token, late, device = tablet).statuses())
        assertEquals(listOf("stale"), client.push(token, early, device = phone).statuses())
        assertEquals("С планшета", client.pull(token).rows().single().comment())

        // Ещё более позднее — принимается с новым номером.
        val seq = client.pull(token).rows().single()["seq"]!!.jsonPrimitive.long
        val latest = change(updatedAt = clock, version = 3, data = buildJsonObject { put("comment", "Снова с телефона") })
        assertEquals(listOf("accepted"), client.push(token, latest, device = phone).statuses())
        val row = client.pull(token, since = seq).rows().single()
        assertEquals("Снова с телефона", row.comment())
        assertEquals(3L, row["version"]!!.jsonPrimitive.long)
    }

    @Test
    fun sameMomentIsDecidedByDevice() = withServer { db ->
        val token = signUp(db)
        val at = clock - 60_000
        assertEquals(listOf("accepted"), client.push(token, change(updatedAt = at, data = buildJsonObject { put("comment", "b") }), device = tablet).statuses())
        // Тот же момент с устройства «меньше» — проигрывает, «больше» — побеждает.
        assertEquals(listOf("stale"), client.push(token, change(updatedAt = at, data = buildJsonObject { put("comment", "a") }), device = phone).statuses())
        assertEquals(
            listOf("accepted"),
            client.push(token, change(updatedAt = at, data = buildJsonObject { put("comment", "c") }), device = "$tablet-c").statuses(),
        )
        assertEquals("c", client.pull(token).rows().single().comment())
    }

    @Test
    fun futureMomentIsReplacedByServerTime() = withServer { db ->
        val token = signUp(db)
        client.push(token, change(updatedAt = clock + day))
        assertEquals(clock, client.pull(token).rows().single()["updated_at"]!!.jsonPrimitive.long)
    }

    @Test
    fun deletionsLiveNinetyDaysThenFullSyncIsNeeded() = withServer { db ->
        val token = signUp(db)
        client.push(token, change())
        val seq = client.pull(token).rows().single()["seq"]!!.jsonPrimitive.long
        client.push(token, change(updatedAt = clock, deletedAt = clock, version = 2))
        val deleted = client.pull(token, since = seq).rows().single()
        assertEquals(clock, deleted["deleted_at"]!!.jsonPrimitive.long)

        // Через 89 дней удалённая ещё хранится, через 91 — стирается.
        clock += 89 * day
        assertEquals(0, db.tx { Sync { clock }.purge(it) })
        clock += 2 * day
        assertEquals(1, db.tx { Sync { clock }.purge(it) })

        // Кто не синхронизировался с тех пор — «нужна полная синхронизация»; полная выдача с нуля работает.
        val gone = client.pull(token, since = seq)
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals("full_sync", gone.code())
        assertEquals(emptyList(), client.pull(token).rows())
    }

    @Test
    fun coachesSeeOnlyTheirOwnRecords() = withServer { db ->
        val ivan = signUp(db)
        val anna = signUp(db, email = "anna@example.ru")
        client.push(ivan, change(data = buildJsonObject { put("comment", "Иван") }))
        // Тот же id у другого тренера — его отдельная запись, Ивана она не трогает.
        assertEquals(listOf("accepted"), client.push(anna, change(updatedAt = clock, data = buildJsonObject { put("comment", "Анна") })).statuses())
        assertEquals(listOf("Иван"), client.pull(ivan).rows().map { it.comment() })
        assertEquals(listOf("Анна"), client.pull(anna).rows().map { it.comment() })
    }

    @Test
    fun clubDataComesFromAdminOnly() = withServer { db ->
        val boss = signUp(db, email = admin)
        val coach = signUp(db)
        val pool = buildJsonObject { put("scope", "CLUB"); put("comment", "ББ") }
        val grid = buildJsonObject { put("comment", "Сетка") }
        assertEquals(
            listOf("club_only", "club_only"),
            client.push(coach, change(entity = "location", data = pool), change(entity = "club_rate_grid", data = grid)).statuses(),
        )
        assertEquals(emptyList(), client.pull(coach).rows())

        assertEquals(listOf("accepted", "accepted"), client.push(boss, change(entity = "location", data = pool), change(entity = "club_rate_grid", data = grid)).statuses())
        // Данные клуба приходят каждому тренеру с копией на сервере.
        assertEquals(listOf("ББ", "Сетка"), client.pull(coach).rows().map { it.comment() })
        // Своё место тренера (scope COACH) — только у него.
        client.push(coach, change(id = "own", entity = "location", data = buildJsonObject { put("scope", "COACH"); put("comment", "Зал") }))
        assertEquals(listOf("ББ", "Сетка"), client.pull(boss).rows().map { it.comment() })
    }

    @Test
    fun pagesFollowLimit() = withServer { db ->
        val token = signUp(db)
        client.push(token, *Array(5) { change(id = "id-$it") })
        val first = client.pull(token, limit = 2)
        assertEquals(listOf("id-0", "id-1"), first.rows().map { it["id"]!!.jsonPrimitive.content })
        assertTrue(first.obj()["has_more"]!!.jsonPrimitive.content.toBoolean())
        val rest = client.pull(token, since = first.obj()["last_seq"]!!.jsonPrimitive.long, limit = 10)
        assertEquals(listOf("id-2", "id-3", "id-4"), rest.rows().map { it["id"]!!.jsonPrimitive.content })
        assertFalse(rest.obj()["has_more"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun oldAppIsAskedToUpdate() = withServer { db ->
        val token = signUp(db)
        val previous = client.push(token, change(), schema = SyncRules.SCHEMA_VERSION - 1)
        assertEquals(listOf("accepted"), previous.statuses())
        for (old in listOf(SyncRules.SCHEMA_VERSION - 2, null)) {
            val response = client.push(token, change(), schema = old)
            assertEquals(HttpStatusCode.UpgradeRequired, response.status)
            assertEquals("update_required", response.code())
        }
        assertEquals(HttpStatusCode.UpgradeRequired, client.pull(token, schema = 1).status)
        val newer = client.pull(token, schema = SyncRules.SCHEMA_VERSION + 1)
        assertEquals(HttpStatusCode.ServiceUnavailable, newer.status)
        assertEquals("server_outdated", newer.code())
    }

    @Test
    fun syncNeedsSignInAndServerCopy() = withServer { db ->
        assertEquals(HttpStatusCode.Unauthorized, client.push(null, change()).status)
        assertEquals(HttpStatusCode.Unauthorized, client.pull("чужой").status)

        val closed = signUp(db, copy = false)
        assertEquals("feature_closed", client.push(closed, change()).code())
        assertEquals(HttpStatusCode.Forbidden, client.pull(closed).status)

        // Срок вышел вчера — копия закрыта, данные не стираются.
        val expired = signUp(db, email = "anna@example.ru", until = "2026-10-23")
        assertEquals("feature_closed", client.pull(expired).code())
        val today = signUp(db, email = "olga@example.ru", until = "2026-10-24")
        assertEquals(listOf("accepted"), client.push(today, change()).statuses())
    }

    @Test
    fun badPushIsRefusedWhole() = withServer { db ->
        val token = signUp(db)
        assertEquals(HttpStatusCode.BadRequest, client.push(token, *Array(SyncRules.PUSH_MAX + 1) { change(id = "id-$it") }).status)
        assertEquals(HttpStatusCode.OK, client.push(token, *Array(SyncRules.PUSH_MAX) { change(id = "id-$it") }).status)
        val unknown = client.push(token, change(id = "x"), change(entity = "outbox"))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("Изменение 2: неизвестная таблица «outbox»", unknown.obj()["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.push(token, change(), device = "").status)
        val noTime = client.push(token, buildJsonObject { put("entity", "training"); put("id", "x"); put("version", 1); put("data", buildJsonObject { }) })
        assertEquals(HttpStatusCode.BadRequest, noTime.status)
        // Ни одно изменение плохих пачек не записалось.
        assertNull(client.pull(token, limit = 1000).rows().firstOrNull { it["id"]!!.jsonPrimitive.content == "x" })
    }

    @Test
    fun disconnectErasesCoachDataAndSessions() = withServer { db ->
        val boss = signUp(db, email = admin)
        val token = signUp(db)
        client.push(boss, change(id = "pool", entity = "location", data = buildJsonObject { put("scope", "CLUB"); put("comment", "ББ") }))
        client.push(token, change())

        val response = client.delete("/api/v1/account/data") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(HttpStatusCode.Unauthorized, client.pull(token).status)
        assertEquals(0, db.tx { c -> c.queryOne("SELECT count(*) FROM sync_row WHERE id = ?", trainingId) { it.getInt(1) } })
        // Данные клуба остаются.
        assertEquals(listOf("ББ"), client.pull(boss).rows().map { it.comment() })
    }
}
