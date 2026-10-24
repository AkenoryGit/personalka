package com.akenory.personalka.server

import com.akenory.personalka.domain.account.AccountRules

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
    /** Ящик для писем с кодами (2.8); нет — регистрация и сброс пароля отвечают «письма не отправляются». */
    val smtp: SmtpSettings? = null,
    /** Почта администратора (через запятую можно несколько): его аккаунт — тоже через регистрацию, но с отметкой. */
    val adminEmails: Set<String> = emptySet(),
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
            val smtpPort = value(SMTP_PORT)?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { problems += "$SMTP_PORT — не номер порта: $text"; null } }
            val smtpTls = value(SMTP_TLS)
            if (smtpTls != null && smtpTls.lowercase() !in setOf("ssl", "tls", "starttls", "none")) problems += "$SMTP_TLS — ssl, starttls или none, а не «$smtpTls»"
            val smtpHost = value(SMTP_HOST)
            val smtpUser = value(SMTP_USER)
            val smtpPassword = value(SMTP_PASSWORD)
            if (smtpHost != null && (smtpUser == null || smtpPassword == null)) problems += "для $SMTP_HOST нужны $SMTP_USER и $SMTP_PASSWORD"
            if (problems.isNotEmpty()) throw IllegalArgumentException("Настройки сервера: ${problems.joinToString("; ")}")
            return ServerConfig(
                dbUrl = dbUrl!!,
                dbUser = value(DB_USER)!!,
                dbPassword = value(DB_PASSWORD)!!,
                domain = value(DOMAIN)!!,
                host = value(HOST) ?: DEFAULT_HOST,
                port = port ?: DEFAULT_PORT,
                backupPublicKey = value(BACKUP_PUBLIC_KEY),
                smtp = if (smtpHost != null && smtpUser != null && smtpPassword != null) {
                    val port = smtpPort ?: 465
                    SmtpSettings(smtpHost, port, smtpTls, smtpUser, smtpPassword, value(MAIL_FROM) ?: "Персоналка <$smtpUser>")
                } else {
                    null
                },
                adminEmails = value(ADMIN_EMAIL)?.split(',', ' ')?.mapNotNull { AccountRules.email(it) }?.toSet().orEmpty(),
            )
        }

        const val DB_URL = "TRENER_DB_URL"
        const val DB_USER = "TRENER_DB_USER"
        const val DB_PASSWORD = "TRENER_DB_PASSWORD"
        const val DOMAIN = "TRENER_DOMAIN"
        const val HOST = "TRENER_HOST"
        const val PORT = "TRENER_PORT"
        const val BACKUP_PUBLIC_KEY = "TRENER_BACKUP_PUBLIC_KEY"
        const val SMTP_HOST = "TRENER_SMTP_HOST"
        const val SMTP_PORT = "TRENER_SMTP_PORT"
        const val SMTP_TLS = "TRENER_SMTP_TLS"
        const val SMTP_USER = "TRENER_SMTP_USER"
        const val SMTP_PASSWORD = "TRENER_SMTP_PASSWORD"
        const val MAIL_FROM = "TRENER_MAIL_FROM"
        const val ADMIN_EMAIL = "TRENER_ADMIN_EMAIL"
    }

    override fun toString() =
        "ServerConfig(db=$dbUrl, user=$dbUser, domain=$domain, $host:$port, почта=${smtp?.let { "${it.host}:${it.port}" } ?: "нет"}, администраторов=${adminEmails.size})"
}

/** Ящик SMTP: [tls] — ssl, starttls или none (не задано — по порту); [from] — «Имя <адрес ящика>». */
data class SmtpSettings(val host: String, val port: Int, val tls: String?, val user: String, val password: String, val from: String) {
    override fun toString() = "SmtpSettings($host:$port, $user)"
}
