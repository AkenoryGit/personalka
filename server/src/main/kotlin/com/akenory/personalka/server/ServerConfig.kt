package com.akenory.personalka.server

/**
 * Настройки сервера — из переменных окружения (ТЗ 2.4, 7.4). На VPS их задаёт файл окружения службы
 * `/etc/trener/trener.env` с правами только для неё; его пишет `deploy/setup.sh`.
 */
data class ServerConfig(
    /** Адрес базы, например `jdbc:postgresql://127.0.0.1:5432/trener`. */
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    /** Поддомен сервера и кабинетов, например `trener.example.ru`: из него строятся ссылки кабинетов. */
    val domain: String,
    /** Сервер слушает только сам VPS: снаружи к нему ходит Caddy. */
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** Открытый ключ шифрования копий (ТЗ 7.6); до шага копий на сервере может быть пустым. */
    val backupPublicKey: String? = null,
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8090

        /** Все ошибки сразу, а не по одной: на сервере каждый запуск — это перезапуск службы. */
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val problems = mutableListOf<String>()
            val required = listOf(DB_URL, DB_USER, DB_PASSWORD, DOMAIN).filter { value(it) == null }
            if (required.isNotEmpty()) problems += "не заданы ${required.joinToString(", ")}"
            val dbUrl = value(DB_URL)
            if (dbUrl != null && !dbUrl.startsWith("jdbc:postgresql://")) problems += "$DB_URL должен начинаться с jdbc:postgresql://"
            val port = value(PORT)?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { problems += "$PORT — не номер порта: $text"; null } }
            if (problems.isNotEmpty()) throw IllegalArgumentException("Настройки сервера: ${problems.joinToString("; ")}")
            return ServerConfig(
                dbUrl = dbUrl!!,
                dbUser = value(DB_USER)!!,
                dbPassword = value(DB_PASSWORD)!!,
                domain = value(DOMAIN)!!,
                host = value(HOST) ?: DEFAULT_HOST,
                port = port ?: DEFAULT_PORT,
                backupPublicKey = value(BACKUP_PUBLIC_KEY),
            )
        }

        const val DB_URL = "TRENER_DB_URL"
        const val DB_USER = "TRENER_DB_USER"
        const val DB_PASSWORD = "TRENER_DB_PASSWORD"
        const val DOMAIN = "TRENER_DOMAIN"
        const val HOST = "TRENER_HOST"
        const val PORT = "TRENER_PORT"
        const val BACKUP_PUBLIC_KEY = "TRENER_BACKUP_PUBLIC_KEY"
    }

    override fun toString() = "ServerConfig(db=$dbUrl, user=$dbUser, domain=$domain, $host:$port)"
}
