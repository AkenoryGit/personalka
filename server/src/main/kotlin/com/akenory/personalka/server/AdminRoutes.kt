package com.akenory.personalka.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.sql.Connection
import java.time.OffsetDateTime

/** Строка экрана «Тренеры»: аккаунт, а не данные тренера — клиентов и тренировок здесь нет (5.17). */
data class CoachSummary(
    val coach: Coach,
    val syncedAt: OffsetDateTime?,
    val cabinets: Int,
    val createdAt: OffsetDateTime,
)

/**
 * «Клуб → Тренеры» (ТЗ 5.17, 1.5; шаг 5.2) — только администратору:
 *
 * - `GET /admin/coaches` — тренеры сервера: имя, почта, статус, последний обмен и число открытых кабинетов;
 * - `POST /admin/coaches/{id}/suspend` и `/resume` — приостановить и возобновить доступ. Приостановленному
 *   сервер не даёт синхронизироваться, входить и открывать кабинеты (SyncRoutes, AuthRoutes, Cabinets);
 *   его записи на сервере и данные в телефоне не трогаются. Администратора приостановить нельзя.
 *
 * Не администратору — 403 `admin_only`, как будто адресов нет: данных тренеров администратор и так не видит.
 */
fun Route.admin(database: Database, auth: Auth) {
    val accounts = auth.accounts

    fun adminOf(c: Connection, token: String?): Coach {
        val (coach, _) = token?.let { accounts.session(c, it) } ?: throw Errors.unauthorized()
        if (!coach.admin) throw Errors.adminOnly()
        return coach
    }

    route("/api/v1/admin") {
        get("/coaches") {
            val token = call.bearer()
            val list = database.tx { c ->
                adminOf(c, token)
                accounts.summaries(c)
            }
            call.respondJson(buildJsonObject { putJsonArray("coaches") { list.forEach { addJsonObject { putSummary(it) } } } })
        }

        for ((path, suspended) in listOf("suspend" to true, "resume" to false)) {
            post("/coaches/{id}/$path") {
                val token = call.bearer()
                val id = call.parameters["id"].orEmpty()
                val summary = database.tx { c ->
                    adminOf(c, token)
                    val coach = accounts.byId(c, id) ?: throw Errors.coachNotFound()
                    if (coach.admin) throw Errors.badRequest("Администратора приостановить нельзя")
                    accounts.setSuspended(c, coach.id, suspended)
                    accounts.summaries(c).first { it.coach.id == coach.id }
                }
                call.respondJson(buildJsonObject { putJsonObject("coach") { putSummary(summary) } }, HttpStatusCode.OK)
            }
        }
    }
}

private fun JsonObjectBuilder.putSummary(s: CoachSummary) {
    put("id", s.coach.id)
    put("display_name", s.coach.displayName)
    put("email", s.coach.email)
    put("role", if (s.coach.admin) "ADMIN" else "COACH")
    put("status", if (s.coach.suspended) "SUSPENDED" else "ACTIVE")
    put("confirmed", s.coach.confirmed)
    put("synced_at", s.syncedAt?.toInstant()?.toString())
    put("cabinets", s.cabinets)
    put("created_at", s.createdAt.toInstant().toString())
}
