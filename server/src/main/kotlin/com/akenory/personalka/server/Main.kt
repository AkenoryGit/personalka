package com.akenory.personalka.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours

/** Версия сборки (0.1.N) — из манифеста файла сервера; при запуске из Gradle — «разработка». */
val serverVersion: String = Database::class.java.`package`?.implementationVersion ?: "разработка"

/** Всё приложение сервера: подключается и в службе, и в тестах (`testApplication`). */
fun Application.trener(
    database: Database,
    version: String = serverVersion,
    auth: Auth = Auth(NoMailer, emptySet()),
    sync: Sync = Sync(),
) {
    install(CallLogging)
    apiErrors()
    monitor.subscribe(ApplicationStopped) { database.close() }
    routing {
        health(version, database)
        auth(database, auth)
        sync(database, auth, sync)
    }
}

/** Удалённые больше 90 дней назад записи синхронизации стираются при запуске и потом раз в сутки (7.2, п. 6). */
fun Application.purgeDaily(database: Database, sync: Sync) {
    launch {
        while (true) {
            try {
                val purged = database.tx { sync.purge(it) }
                if (purged > 0) log.info("Стёрто удалённых записей синхронизации: $purged")
            } catch (e: Exception) {
                log.error("Не удалось стереть старые удалённые записи: ${e.message}", e)
            }
            delay(24.hours)
        }
    }
}

/**
 * Запуск службы `trener.service` (ТЗ 7.4): настройки из окружения, миграции, затем порт.
 * Ошибка настроек или базы — выход с кодом 1: systemd перезапустит через 5 секунд, а выкладка увидит,
 * что /health не отвечает, и откатит версию.
 */
fun main() {
    val log = LoggerFactory.getLogger("trener")
    val config = try {
        ServerConfig.fromEnv()
    } catch (e: IllegalArgumentException) {
        log.error(e.message)
        exitProcess(1)
    }
    log.info("Сервер «Персоналки» $serverVersion: $config")
    val database = try {
        Database.connect(config)
    } catch (e: Exception) {
        log.error("База не открылась: ${e.message}", e)
        exitProcess(1)
    }
    log.info("Схема базы — версия ${database.schemaVersion}")
    val auth = Auth(config.mailer(), config.adminEmails)
    val sync = Sync()
    embeddedServer(Netty, port = config.port, host = config.host) {
        trener(database, auth = auth, sync = sync)
        purgeDaily(database, sync)
    }.start(wait = true)
}
