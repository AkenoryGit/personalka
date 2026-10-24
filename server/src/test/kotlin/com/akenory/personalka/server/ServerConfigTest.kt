package com.akenory.personalka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Настройки сервера из окружения (ТЗ 2.4): обязательные, значения по умолчанию, все ошибки одним сообщением. */
class ServerConfigTest {

    private val full = mapOf(
        "TRENER_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/trener",
        "TRENER_DB_USER" to "trener",
        "TRENER_DB_PASSWORD" to "секрет",
        "TRENER_DOMAIN" to "trener.example.ru",
    )

    @Test
    fun requiredOnlyGivesDefaults() {
        val config = ServerConfig.fromEnv(full)
        assertEquals("jdbc:postgresql://127.0.0.1:5432/trener", config.dbUrl)
        assertEquals("trener.example.ru", config.domain)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8090, config.port)
        assertNull(config.backupPublicKey)
    }

    @Test
    fun optionalValuesAreRead() {
        val config = ServerConfig.fromEnv(full + mapOf("TRENER_PORT" to "8123", "TRENER_HOST" to "0.0.0.0", "TRENER_BACKUP_PUBLIC_KEY" to "ключ"))
        assertEquals(8123, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals("ключ", config.backupPublicKey)
    }

    @Test
    fun allMissingAreNamedAtOnce() {
        val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(mapOf("TRENER_DB_USER" to "trener", "TRENER_DOMAIN" to " ")) }
        assertEquals("Настройки сервера: не заданы TRENER_DB_URL, TRENER_DB_PASSWORD, TRENER_DOMAIN", error.message)
    }

    @Test
    fun wrongUrlAndPortAreBothReported() {
        val error = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(full + mapOf("TRENER_DB_URL" to "postgres://localhost/trener", "TRENER_PORT" to "80000"))
        }
        assertTrue("jdbc:postgresql://" in error.message!!)
        assertTrue("TRENER_PORT — не номер порта: 80000" in error.message!!)
    }

    @Test
    fun passwordIsNotPrintedToLog() {
        assertFalse("секрет" in ServerConfig.fromEnv(full).toString())
    }

    @Test
    fun mailAndAdminAreOptional() {
        val config = ServerConfig.fromEnv(
            full + mapOf(
                "TRENER_SMTP_HOST" to "smtp.mail.ru",
                "TRENER_SMTP_USER" to "box@mail.ru",
                "TRENER_SMTP_PASSWORD" to "пароль-ящика",
                "TRENER_ADMIN_EMAIL" to " Dmitry@Example.ru, второй@неверный",
            ),
        )
        assertEquals(SmtpSettings("smtp.mail.ru", 465, null, "box@mail.ru", "пароль-ящика", "Персоналка <box@mail.ru>"), config.smtp)
        assertEquals(setOf("dmitry@example.ru"), config.adminEmails)
        assertFalse("пароль-ящика" in config.toString())
        assertNull(ServerConfig.fromEnv(full).smtp)
    }

    @Test
    fun mailHostWithoutPasswordIsAnError() {
        val error = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(full + mapOf("TRENER_SMTP_HOST" to "smtp.mail.ru", "TRENER_SMTP_TLS" to "maybe"))
        }
        assertTrue("TRENER_SMTP_TLS — ssl, starttls или none" in error.message!!, error.message)
        assertTrue("нужны TRENER_SMTP_USER и TRENER_SMTP_PASSWORD" in error.message!!, error.message)
    }
}
