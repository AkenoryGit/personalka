package com.akenory.personalka.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `/health` (ТЗ 7.4): по нему выкладка решает, оставить новую версию или откатить. 200 — сервер запущен
 * и база отвечает; 503 — база не отвечает. В ответе — версия сборки и схемы, без данных тренеров.
 */
fun Route.health(version: String, database: Database) {
    get("/health") {
        val ok = database.ping()
        val text = buildString {
            appendLine(if (ok) "ok" else "база не отвечает")
            appendLine("версия $version")
            if (ok) appendLine("схема ${database.schemaVersion ?: "—"}")
        }
        call.respondText(text, ContentType.Text.Plain, if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
    }
}
