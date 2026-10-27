package com.akenory.personalka.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Письма тренерам (коды подтверждения и сброса). Не удалось отправить — [MailException]. */
interface Mailer {
    val enabled: Boolean get() = true

    suspend fun send(to: String, subject: String, text: String)
}

class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Почта не настроена: регистрация и сброс пароля отвечают «письма пока не отправляются». */
object NoMailer : Mailer {
    override val enabled = false

    override suspend fun send(to: String, subject: String, text: String) = throw MailException("Отправка писем не настроена")
}

/**
 * Письма через ящик по SMTP — пока ящик Planovo на Mail.ru (решение Дмитрия 24.10.2026). Отправитель — [from]
 * («Персоналка <адрес ящика>»): чужой адрес Mail.ru не примет. В тексте ошибки адрес получателя заменён на «<адрес>».
 */
class SmtpMailer(
    private val client: SmtpClient,
    private val from: String,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) : Mailer {
    override suspend fun send(to: String, subject: String, text: String) {
        val message = MailMessage.build(from, to, subject, text, now())
        try {
            withContext(Dispatchers.IO) { client.send(mailAddress(from), to, message) }
        } catch (e: SmtpException) {
            throw MailException("${client.host}: ${e.message.orEmpty().replace(to, "<адрес>")}", e)
        } catch (e: java.io.IOException) {
            throw MailException("${client.host}:${client.port} недоступен: ${e.message.orEmpty().replace(to, "<адрес>")}", e)
        }
    }
}

/** Почта из настроек: есть ящик — SMTP, иначе письма не отправляются. */
fun ServerConfig.mailer(): Mailer {
    val smtp = smtp ?: return NoMailer
    return SmtpMailer(SmtpClient(smtp.host, smtp.port, SmtpSecurity.parse(smtp.tls, smtp.port), smtp.user, smtp.password), smtp.from)
}

/** Тексты писем: тема с кодом — его видно прямо в уведомлении почты. */
object Letters {
    fun confirm(name: String, code: String, minutes: Int) = Letter(
        subject = "Код подтверждения: $code",
        text = """
            Здравствуйте, $name!

            Код для подтверждения почты в «Персоналке»: $code
            Он действует $minutes минут.

            Если вы не регистрировались в «Персоналке», просто удалите это письмо.
        """.trimIndent(),
    )

    fun reset(name: String, code: String, minutes: Int) = Letter(
        subject = "Код для нового пароля: $code",
        text = """
            Здравствуйте, $name!

            Код для нового пароля в «Персоналке»: $code
            Он действует $minutes минут. После смены пароля все устройства, кроме этого, выйдут из аккаунта.

            Если вы не просили новый пароль, просто удалите это письмо: старый пароль продолжит работать.
        """.trimIndent(),
    )

    fun admin(name: String, code: String, minutes: Int) = Letter(
        subject = "Код входа в админку: $code",
        text = """
            Здравствуйте, $name!

            Код для входа в админку «Персоналки»: $code
            Он действует $minutes минут.

            Если вы сейчас не входили в админку, кто-то знает ваш пароль — смените его в приложении
            («Аккаунт → Забыли пароль?»). Без этого кода в админку не войти.
        """.trimIndent(),
    )
}

data class Letter(val subject: String, val text: String)
