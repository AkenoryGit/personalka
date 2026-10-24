package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.CabinetLinkFormat
import kotlinx.datetime.LocalDate

/**
 * Последнее согласие клиента из кабинета (ответ `GET /api/v1/cabinets`, шаг 2.12): моменты — миллисекунды UTC.
 * [givenBy] — фамилия и имя родителя, у взрослого клиента пусто.
 */
data class ConsentInfo(val acceptedAt: Long, val photo: Boolean, val givenBy: String?, val revokedAt: Long?)

/** Как отметить согласие в окне «Кабинет»: строка и её вид. */
data class ConsentLine(val text: String, val state: ConsentState)

enum class ConsentState { GIVEN, NONE, REVOKED }

/**
 * Ссылки кабинетов в приложении (ТЗ 5.9, 6.1, 3.7; шаг 2.13): адрес, код доступа, текст «Отправить», состояние
 * согласия. Токен и код создаёт телефон, на сервер уходит только SHA-256 токена; код сервер хэширует сам.
 */
object CabinetLinkRules {
    /** Байт случайности на токен: 32 байта в base64url без «=» — 43 знака (3.7, макеты 0.7). */
    const val TOKEN_BYTES = 32

    /** Контакт для родителей — до этой длины (3.2). */
    const val CONTACT_MAX = 100

    /** Адрес кабинета: `https://trener.planovo-app.ru/c/<токен>`. */
    fun url(server: String, token: String): String = server.trimEnd('/') + "/c/" + token

    /**
     * Код доступа — 4 цифры из случайных байтов [random] (не меньше 4): 0000–9999 почти поровну — перекос
     * от остатка деления 2³² на 10 000 меньше миллионной доли.
     */
    fun pin(random: ByteArray): String {
        require(random.size >= 4) { "Для кода нужно 4 случайных байта" }
        val n = random.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return (n % 10_000).toString().padStart(4, '0')
    }

    /** Текст «Отправить» (5.9): ссылка, код — отдельной строкой, если он есть. У взрослого — «ваших тренировок». */
    fun shareText(url: String, pin: String?, adult: Boolean): String = buildString {
        append(if (adult) CabinetLinkFormat.SHARE_ADULT else CabinetLinkFormat.SHARE_CHILD)
        append(": ")
        append(url)
        if (pin != null) append("\n").append(CabinetLinkFormat.sharePin(pin))
    }

    /** Контакт для родителей без лишних пробелов; пусто — `null`: без него кабинеты не включаются (3.2). */
    fun contact(text: String): String? = text.trim().replace(Regex("\\s+"), " ").take(CONTACT_MAX).ifEmpty { null }

    /**
     * Отметка согласия в окне «Кабинет» (6.1): «Согласие получено 05.09, отмечено в кабинете»; отозвано — с датой,
     * до которой удалить данные; нет — что родитель отметит его при первом открытии.
     */
    fun consent(info: ConsentInfo?, child: Boolean): ConsentLine {
        if (info == null) return ConsentLine(CabinetLinkFormat.consentNone(child), ConsentState.NONE)
        val revoked = info.revokedAt
        if (revoked != null) {
            val day = ClubTime.now(revoked).date
            return ConsentLine(CabinetLinkFormat.consentRevoked(day, ConsentRules.deleteBy(day)), ConsentState.REVOKED)
        }
        val given: LocalDate = ClubTime.now(info.acceptedAt).date
        return ConsentLine(CabinetLinkFormat.consentGiven(given, info.photo), ConsentState.GIVEN)
    }
}
