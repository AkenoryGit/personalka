package com.akenory.personalka.server

import java.sql.DriverManager

/**
 * Пустая база PostgreSQL для тестов. Адрес — TRENER_TEST_DB_URL (и _USER, _PASSWORD): в Actions его задаёт
 * служба postgres:16 сборки. Перед каждым тестом схема public пересоздаётся — тесты не видят следов друг друга.
 */
object TestDb {
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    val config: ServerConfig by lazy {
        val url = env("TRENER_TEST_DB_URL") ?: error(
            "Тестам сервера нужен PostgreSQL 16: задайте TRENER_TEST_DB_URL (jdbc:postgresql://…), TRENER_TEST_DB_USER, TRENER_TEST_DB_PASSWORD",
        )
        ServerConfig(
            dbUrl = url,
            dbUser = env("TRENER_TEST_DB_USER") ?: "trener",
            dbPassword = env("TRENER_TEST_DB_PASSWORD") ?: "",
            domain = "trener.example.ru",
        )
    }

    fun fresh(): ServerConfig {
        DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword).use { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS public CASCADE")
                it.execute("CREATE SCHEMA public")
            }
        }
        return config
    }
}
