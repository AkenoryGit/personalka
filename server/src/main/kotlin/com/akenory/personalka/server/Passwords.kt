package com.akenory.personalka.server

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Пароли тренеров (ТЗ 7.3): Argon2id, строка `$argon2id$v=19$m=19456,t=2,p=1$соль$хэш` — как у других программ,
 * параметры записаны в самой строке, поэтому их можно поднять позже без переделки базы.
 * 19 МБ и 2 прохода — нижняя граница рекомендаций OWASP: на VPS с 1 ядром проверка занимает доли секунды.
 */
class Passwords(
    private val memoryKb: Int = 19_456,
    private val iterations: Int = 2,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = argon(password, salt, memoryKb, iterations, 1, 32)
        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=1\$${b64(salt)}\$${b64(hash)}"
    }

    /** Пароль подходит к строке [stored]; испорченная строка — не подходит. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = runCatching { DECODER.decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { DECODER.decode(parts[5]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, argon(password, salt, m, t, p, expected.size))
    }

    /** Для неизвестной почты: проверка занимает столько же, сколько настоящая, — по времени не узнать, есть ли аккаунт. */
    val dummy: String by lazy { hash("нет такого аккаунта") }

    private fun argon(password: String, salt: ByteArray, m: Int, t: Int, p: Int, length: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m)
                .withIterations(t)
                .withParallelism(p)
                .withSalt(salt)
                .build(),
        )
        return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }

    private companion object {
        val DECODER: Base64.Decoder = Base64.getDecoder()

        fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
