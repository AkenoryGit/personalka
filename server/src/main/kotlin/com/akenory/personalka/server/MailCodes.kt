package com.akenory.personalka.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection

/** Для чего код в письме. */
enum class CodePurpose(val code: String) {
    /** Подтверждение почты после регистрации (без него сброс пароля ушёл бы не туда). */
    CONFIRM("confirm"),

    /** Новый пароль. */
    RESET("reset"),
}

/** Итог проверки кода. */
sealed interface CodeCheck {
    data object Ok : CodeCheck

    data class Wrong(val attemptsLeft: Int) : CodeCheck

    data object Expired : CodeCheck
}

/**
 * Коды на почту (ТЗ 7.3, решение Дмитрия 24.10.2026) — как коды входа Planovo (`Logins.kt`): 6 цифр, действуют
 * 15 минут, 5 попыток, одноразовые; в базе — только SHA-256. Пределы отправки берегут ящик и защищают от перебора:
 * на адрес — не чаще раза в минуту и не больше 5 в час, с одного IP — 20 в час, всего — 200 в сутки.
 */
class MailCodes(private val random: SecureRandom = SecureRandom()) {
    /** Проверить пределы и сохранить новый код. Превышен предел — [ApiException] 429. */
    fun prepare(c: Connection, email: String, purpose: CodePurpose, ip: String?): String {
        // Одновременные запросы на один адрес — по очереди, иначе оба пройдут проверку «раз в минуту».
        c.queryOne("SELECT pg_advisory_xact_lock(hashtext(?))", "mail:$email") { }
        val (count, since, freeIn) = c.queryOne(
            """
            SELECT count(*), COALESCE(EXTRACT(EPOCH FROM now() - max(sent_at))::int, 1000000),
                   COALESCE(EXTRACT(EPOCH FROM min(sent_at) + interval '1 hour' - now())::int, 0)
            FROM mail_send WHERE email = ? AND sent_at > now() - interval '1 hour'
            """.trimIndent(),
            email,
        ) { Triple(it.getInt(1), it.getInt(2), it.getInt(3)) }!!
        if (since < RESEND_SECONDS) throw Errors.tooMany(RESEND_SECONDS - since)
        if (count >= PER_EMAIL_HOUR) throw Errors.tooMany(freeIn.coerceAtLeast(1))
        if (ip != null) {
            val (ipCount, ipFreeIn) = c.queryOne(
                "SELECT count(*), COALESCE(EXTRACT(EPOCH FROM min(sent_at) + interval '1 hour' - now())::int, 0) FROM mail_send WHERE ip = ? AND sent_at > now() - interval '1 hour'",
                ip,
            ) { it.getInt(1) to it.getInt(2) }!!
            if (ipCount >= PER_IP_HOUR) throw Errors.tooMany(ipFreeIn.coerceAtLeast(1))
        }
        val day = c.queryOne("SELECT count(*) FROM mail_send WHERE sent_at > now() - interval '1 day'") { it.getInt(1) }!!
        if (day >= PER_DAY) throw Errors.tooMany(3600)
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        c.update(
            """
            INSERT INTO mail_code (email, purpose, code_hash, expires_at) VALUES (?, ?, ?, now() + make_interval(mins => ?))
            ON CONFLICT (email, purpose) DO UPDATE SET code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at, attempts = 0
            """.trimIndent(),
            email, purpose.code, hash(email, purpose, code), MINUTES,
        )
        c.update("DELETE FROM mail_send WHERE sent_at < now() - interval '2 days'")
        c.update("INSERT INTO mail_send (email, ip) VALUES (?, ?)", email, ip)
        return code
    }

    /** Письмо не ушло — код и отметка об отправке не нужны (иначе человек упрётся в «раз в минуту»). */
    fun cancel(c: Connection, email: String, purpose: CodePurpose) {
        c.update("DELETE FROM mail_code WHERE email = ? AND purpose = ?", email, purpose.code)
        c.update(
            "DELETE FROM mail_send WHERE ctid = (SELECT ctid FROM mail_send WHERE email = ? ORDER BY sent_at DESC LIMIT 1)",
            email,
        )
    }

    /** Проверить код. Неверный — попытка засчитана (её надо зафиксировать — не бросать ошибку внутри транзакции). */
    fun check(c: Connection, email: String, purpose: CodePurpose, code: String): CodeCheck {
        val row = c.queryOne(
            "SELECT code_hash, expires_at > now(), attempts FROM mail_code WHERE email = ? AND purpose = ? FOR UPDATE",
            email, purpose.code,
        ) { Triple(it.getBytes(1), it.getBoolean(2), it.getInt(3)) } ?: return CodeCheck.Expired
        val (stored, alive, attempts) = row
        val drop = { c.update("DELETE FROM mail_code WHERE email = ? AND purpose = ?", email, purpose.code) }
        if (!alive || attempts >= MAX_ATTEMPTS) {
            drop()
            return CodeCheck.Expired
        }
        val clean = code.filter { !it.isWhitespace() }
        if (clean.length != 6 || !MessageDigest.isEqual(stored, hash(email, purpose, clean))) {
            val used = attempts + 1
            if (used >= MAX_ATTEMPTS) {
                drop()
                return CodeCheck.Expired
            }
            c.update("UPDATE mail_code SET attempts = ? WHERE email = ? AND purpose = ?", used, email, purpose.code)
            return CodeCheck.Wrong(MAX_ATTEMPTS - used)
        }
        drop()
        return CodeCheck.Ok
    }

    private fun hash(email: String, purpose: CodePurpose, code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("${purpose.code}:$email:$code".toByteArray(Charsets.UTF_8))

    companion object {
        const val MINUTES = 15
        const val MAX_ATTEMPTS = 5
        const val RESEND_SECONDS = 60
        const val PER_EMAIL_HOUR = 5
        const val PER_IP_HOUR = 20
        const val PER_DAY = 200
    }
}
