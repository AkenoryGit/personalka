package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.sync.SchemaProblem
import com.akenory.personalka.domain.sync.SyncRules
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.sql.Connection

/**
 * Синхронизация (ТЗ 7.2, 7.1): только для тренера с открытой «Копией на сервере».
 *
 * - `POST /sync/push` `{schema_version, device_id, changes: [{entity, id, version, updated_at, deleted_at, data}]}` —
 *   до 500 изменений; ответ — `results` по каждому: `accepted` (с `seq`), `stale`, `club_only`;
 * - `GET /sync/pull?since=&limit=&schema_version=` — изменения тренера и клуба после номера: `changes` (с `device_id`
 *   и `seq`), `last_seq`, `has_more`; удалённые после `since` уже стёрты — 410 `full_sync`;
 * - `DELETE /account/data` — «Отключиться от сервера»: записи и сессии тренера стираются.
 *
 * Версия схемы: старше предыдущей — 426 `update_required`, новее сервера — 503 `server_outdated`.
 */
fun Route.sync(database: Database, auth: Auth, sync: Sync) {
    val accounts = auth.accounts

    /** Тренер, которому открыта синхронизация. */
    fun syncCoach(c: Connection, token: String?, schemaVersion: Int?): Coach {
        val (coach, _) = token?.let { accounts.session(c, it) } ?: throw Errors.unauthorized()
        when (SyncRules.schemaProblem(schemaVersion)) {
            SchemaProblem.TOO_OLD -> throw Errors.updateRequired()
            SchemaProblem.TOO_NEW -> throw Errors.serverOutdated()
            null -> Unit
        }
        if (coach.suspended) throw Errors.suspended()
        if (Feature.SERVER_COPY !in AccountRules.open(accounts.grants(c, coach), ClubTime.today(sync.now()))) throw Errors.featureClosed()
        return coach
    }

    route("/api/v1") {
        post("/sync/push") {
            val token = call.bearer()
            val body = call.receiveObject()
            val schemaVersion = (body["schema_version"] as? JsonPrimitive)?.intOrNull
            val device = body.text("device_id").trim()
            val (changes, results) = database.tx { c ->
                val coach = syncCoach(c, token, schemaVersion)
                if (device.isEmpty() || device.length > ID_MAX) throw Errors.badRequest("Нет идентификатора устройства")
                val changes = changes(body)
                changes to sync.push(c, coach, device, changes)
            }
            call.respondJson(
                buildJsonObject {
                    putJsonArray("results") {
                        changes.zip(results).forEach { (change, result) ->
                            addJsonObject {
                                put("entity", change.entity)
                                put("id", change.id)
                                put("version", change.version)
                                put("status", result.first.code)
                                result.second?.let { put("seq", it) }
                            }
                        }
                    }
                },
            )
        }

        get("/sync/pull") {
            val token = call.bearer()
            val params = call.request.queryParameters
            val since = params["since"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw Errors.badRequest("Неверный номер since") } ?: 0
            val limit = params["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw Errors.badRequest("Неверный limit") }
                ?.coerceAtMost(SyncRules.PULL_MAX) ?: SyncRules.PULL_DEFAULT
            val schemaVersion = params["schema_version"]?.toIntOrNull()
            val rows = database.tx { c ->
                val coach = syncCoach(c, token, schemaVersion)
                sync.pull(c, coach.id, since, limit + 1) ?: throw Errors.fullSync()
            }
            val page = rows.take(limit)
            call.respondJson(
                buildJsonObject {
                    putJsonArray("changes") {
                        for (row in page) {
                            addJsonObject {
                                put("entity", row.entity)
                                put("id", row.id)
                                put("version", row.version)
                                put("updated_at", row.updatedAt)
                                put("deleted_at", row.deletedAt)
                                put("device_id", row.device)
                                put("data", row.data)
                                put("seq", row.seq)
                            }
                        }
                    }
                    put("last_seq", page.lastOrNull()?.seq ?: since)
                    put("has_more", rows.size > limit)
                },
            )
        }

        delete("/account/data") {
            val token = call.bearer() ?: throw Errors.unauthorized()
            // Стереть свои данные можно всегда — и без открытой копии, и приостановленному (право на удаление, 152-ФЗ).
            database.tx { c ->
                val (coach, _) = accounts.session(c, token) ?: throw Errors.unauthorized()
                sync.deleteCoachData(c, coach.id)
            }
            call.respondJson(buildJsonObject { put("status", "ok") })
        }
    }
}

/** Изменения из тела отправки; ошибка в любом — 400 на всю пачку (телефон отправляет только то, что записал сам). */
private fun changes(body: JsonObject): List<Change> {
    val list = body["changes"] as? JsonArray ?: throw Errors.badRequest("Нет списка изменений")
    if (list.size > SyncRules.PUSH_MAX) throw Errors.badRequest("Не больше ${SyncRules.PUSH_MAX} изменений за раз")
    return list.mapIndexed { i, item ->
        fun bad(what: String): Nothing = throw Errors.badRequest("Изменение ${i + 1}: $what")
        val obj = item as? JsonObject ?: bad("не объект")
        fun long(name: String): Long? = when (val v = obj[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.takeIf { !it.isString }?.longOrNull ?: bad("$name — не число")
            else -> bad("$name — не число")
        }
        val entity = obj.text("entity")
        if (entity !in SyncRules.ENTITIES) bad("неизвестная таблица «${entity.take(ID_MAX)}»")
        val id = obj.text("id")
        if (id.isBlank() || id.length > ID_MAX) bad("нет id")
        val version = long("version")?.takeIf { it >= 0 } ?: bad("нет version")
        val updatedAt = long("updated_at")?.takeIf { it > 0 } ?: bad("нет updated_at")
        val data = obj["data"] as? JsonObject ?: bad("нет data")
        if (data.toString().length > DATA_MAX) bad("запись больше ${DATA_MAX / 1024} КБ")
        Change(entity, id, version, updatedAt, long("deleted_at"), data)
    }
}

private const val ID_MAX = 64
private const val DATA_MAX = 64 * 1024
