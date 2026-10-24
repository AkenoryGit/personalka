package com.akenory.personalka.server

import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.account.FeatureGrant
import kotlinx.datetime.LocalDate
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Тренер на сервере. [admin] — почта из TRENER_ADMIN_EMAIL: администратор не отдельный путь, а отметка. */
data class Coach(
    val id: String,
    val email: String,
    val displayName: String,
    val confirmed: Boolean,
    val suspended: Boolean,
    val admin: Boolean,
)

/** Сессия устройства. */
data class SessionRow(val id: Long, val deviceName: String, val createdAt: OffsetDateTime, val lastUsedAt: OffsetDateTime)

/**
 * Аккаунты и сессии (ТЗ 7.3): пароль — Argon2id, токен сессии — 32 случайных байта, на сервере только SHA-256,
 * срок 180 дней от последнего использования. Всё здесь — внутри транзакции вызывающего.
 */
class Accounts(
    private val adminEmails: Set<String>,
    private val passwords: Passwords = Passwords(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val columns = "id, email, display_name, email_confirmed_at IS NOT NULL, status = 'SUSPENDED'"

    private fun coach(rs: ResultSet) = Coach(
        id = rs.getString(1),
        email = rs.getString(2),
        displayName = rs.getString(3),
        confirmed = rs.getBoolean(4),
        suspended = rs.getBoolean(5),
        admin = rs.getString(2) in adminEmails,
    )

    fun byEmail(c: Connection, email: String, lock: Boolean = false): Coach? =
        c.queryOne("SELECT $columns FROM coach WHERE email = ?${if (lock) " FOR UPDATE" else ""}", email, row = ::coach)

    fun byId(c: Connection, id: String): Coach? = c.queryOne("SELECT $columns FROM coach WHERE id = ?", id, row = ::coach)

    /**
     * Новый аккаунт или повтор регистрации, пока почта не подтверждена (имя и пароль — новые). Подтверждённая
     * почта — [Errors.emailTaken]. [wantedId] — идентификатор тренера из телефона, если он свободен.
     */
    fun register(c: Connection, email: String, password: String, name: String, wantedId: String?): Coach {
        val existing = byEmail(c, email, lock = true)
        val hash = passwords.hash(password)
        if (existing != null) {
            if (existing.confirmed) throw Errors.emailTaken()
            c.update("UPDATE coach SET display_name = ?, password_hash = ? WHERE id = ?", name, hash, existing.id)
            return existing.copy(displayName = name)
        }
        val free = wantedId?.takeIf { isUuid(it) && c.queryOne("SELECT 1 FROM coach WHERE id = ?", it) { true } == null }
        val id = free ?: UUID.randomUUID().toString()
        c.update("INSERT INTO coach (id, email, display_name, password_hash) VALUES (?, ?, ?, ?)", id, email, name, hash)
        return byId(c, id)!!
    }

    /** Пароль подходит. Нет такой почты — тоже проверка Argon2 (по времени ответа не узнать, есть ли аккаунт). */
    fun passwordMatches(c: Connection, email: String, password: String): Coach? {
        val stored = c.queryOne("SELECT password_hash FROM coach WHERE email = ?", email) { it.getString(1) }
        val ok = passwords.verify(password, stored ?: passwords.dummy)
        return if (ok && stored != null) byEmail(c, email) else null
    }

    fun confirmEmail(c: Connection, id: String) {
        c.update("UPDATE coach SET email_confirmed_at = COALESCE(email_confirmed_at, now()) WHERE id = ?", id)
    }

    /** Новый пароль: все сессии закрываются — потерянный телефон больше не войдёт. */
    fun setPassword(c: Connection, id: String, password: String) {
        c.update("UPDATE coach SET password_hash = ? WHERE id = ?", passwords.hash(password), id)
        c.update("DELETE FROM coach_session WHERE coach_id = ?", id)
    }

    /**
     * Попытка входа паролем: не больше 5 в минуту на адрес и на IP (7.3). Сверх предела — [Errors.tooMany];
     * попытка записывается до проверки пароля, поэтому перебор упирается в предел.
     */
    fun countLoginAttempt(c: Connection, email: String, ip: String?) {
        c.queryOne("SELECT pg_advisory_xact_lock(hashtext(?))", "login:$email") { }
        fun wait(column: String, value: String): Int? = c.queryOne(
            "SELECT count(*), COALESCE(EXTRACT(EPOCH FROM min(at) + interval '1 minute' - now())::int, 0) FROM login_attempt WHERE $column = ? AND at > now() - interval '1 minute'",
            value,
        ) { if (it.getInt(1) >= LOGIN_PER_MINUTE) it.getInt(2).coerceAtLeast(1) else null }
        val seconds = wait("email", email) ?: ip?.let { wait("ip", it) }
        if (seconds != null) throw Errors.tooMany(seconds)
        c.update("DELETE FROM login_attempt WHERE at < now() - interval '1 day'")
        c.update("INSERT INTO login_attempt (email, ip) VALUES (?, ?)", email, ip)
    }

    /** Новая сессия; ответ — токен для телефона (на сервере остаётся только его хэш). */
    fun openSession(c: Connection, coachId: String, deviceName: String): String {
        val token = ByteArray(32).also(random::nextBytes)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        c.update(
            "INSERT INTO coach_session (coach_id, token_hash, device_name, expires_at) VALUES (?, ?, ?, now() + make_interval(days => ?))",
            coachId, tokenHash(text), deviceName, SESSION_DAYS,
        )
        return text
    }

    /** Сессия по токену: действующая — продлевается на 180 дней от этого запроса; иначе null. */
    fun session(c: Connection, token: String): Pair<Coach, Long>? {
        val row = c.queryOne(
            "SELECT s.id, s.coach_id FROM coach_session s WHERE s.token_hash = ? AND s.expires_at > now()",
            tokenHash(token),
        ) { it.getLong(1) to it.getString(2) } ?: return null
        c.update(
            "UPDATE coach_session SET last_used_at = now(), expires_at = now() + make_interval(days => ?) WHERE id = ?",
            SESSION_DAYS, row.first,
        )
        val coach = byId(c, row.second) ?: return null
        return coach to row.first
    }

    fun sessions(c: Connection, coachId: String): List<SessionRow> = c.query(
        "SELECT id, device_name, created_at, last_used_at FROM coach_session WHERE coach_id = ? AND expires_at > now() ORDER BY last_used_at DESC",
        coachId,
    ) { SessionRow(it.getLong(1), it.getString(2), it.getObject(3, OffsetDateTime::class.java), it.getObject(4, OffsetDateTime::class.java)) }

    /** Закрыть сессию тренера; чужую — нельзя (0 строк). */
    fun closeSession(c: Connection, coachId: String, sessionId: Long): Boolean =
        c.update("DELETE FROM coach_session WHERE id = ? AND coach_id = ?", sessionId, coachId) > 0

    /** Открытые возможности: у администратора — всё без срока, у остальных — строки «тренер — возможность — до». */
    fun grants(c: Connection, coach: Coach): List<FeatureGrant> = if (coach.admin) {
        AccountRules.admin()
    } else {
        c.query("SELECT feature, until FROM coach_feature WHERE coach_id = ?", coach.id) { rs ->
            Feature.of(rs.getString(1))?.let { FeatureGrant(it, rs.getString(2)?.let(LocalDate::parse)) }
        }.filterNotNull()
    }

    companion object {
        const val SESSION_DAYS = 180
        const val LOGIN_PER_MINUTE = 5

        fun tokenHash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

        private fun isUuid(text: String) = runCatching { UUID.fromString(text).toString() == text.lowercase() }.getOrDefault(false)
    }
}
