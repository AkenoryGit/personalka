package com.akenory.personalka.server

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Письма через почтовый ящик (SMTP): разговор с сервером, само письмо, ошибки, выбор почты по настройкам.
 * Клиент — из Planovo, там же TLS (465 и STARTTLS) проверен на поддельном сервере
 * с самоподписанным сертификатом; здесь — без шифрования.
 */
class SmtpTest {
    /** Поддельный SMTP без TLS: запоминает команды и письмо; [reject] — что ответить на команду с этим началом. */
    private class FakeSmtp(private val password: String = "пароль-приложения", private val reject: Pair<String, String>? = null) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val commands = mutableListOf<String>()
        var message = ""

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                        val output = s.getOutputStream()
                        fun say(line: String) = output.write("$line\r\n".toByteArray()).also { output.flush() }
                        say("220 fake")
                        while (true) {
                            val line = input.readLine() ?: break
                            commands += line
                            val r = reject
                            when {
                                r != null && line.startsWith(r.first) -> say(r.second)
                                line.startsWith("EHLO") -> { say("250-fake"); say("250-SIZE 1000000"); say("250 AUTH LOGIN PLAIN") }
                                line.startsWith("AUTH PLAIN ") -> {
                                    val (_, _, p) = String(Base64.getDecoder().decode(line.removePrefix("AUTH PLAIN ")), Charsets.UTF_8).split('\u0000')
                                    say(if (p == password) "235 ok" else "535 5.7.8 Authentication failed")
                                }
                                line == "DATA" -> {
                                    say("354 go")
                                    val text = StringBuilder()
                                    while (true) {
                                        val l = input.readLine() ?: break
                                        if (l == ".") break
                                        text.append(l).append("\n")
                                    }
                                    message = text.toString()
                                    say("250 queued")
                                }
                                line == "QUIT" -> { say("221 bye"); break }
                                else -> say("250 ok")
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private val time = ZonedDateTime.of(2026, 10, 14, 13, 30, 0, 0, ZoneOffset.UTC)

    private fun mailer(fake: FakeSmtp, password: String = "пароль-приложения") = SmtpMailer(
        SmtpClient("127.0.0.1", fake.port, SmtpSecurity.NONE, "box@mail.ru", password),
        "Персоналка <box@mail.ru>",
        now = { time },
    )

    /** Слова `=?UTF-8?B?…?=` обратно в текст. */
    private fun decode(header: String) = Regex("=\\?UTF-8\\?B\\?([^?]*)\\?=").findAll(header)
        .joinToString("") { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) }


    @Test
    fun confirmCodeByMail() {
        FakeSmtp().use { fake ->
            val letter = Letters.confirm("Иван", "123456", 15)
            runBlocking { mailer(fake).send("trener@example.com", letter.subject, letter.text) }

            val auth = fake.commands.single { it.startsWith("AUTH") }
            assertEquals("\u0000box@mail.ru\u0000пароль-приложения", String(Base64.getDecoder().decode(auth.removePrefix("AUTH PLAIN ")), Charsets.UTF_8))
            assertTrue("MAIL FROM:<box@mail.ru>" in fake.commands)
            assertTrue("RCPT TO:<trener@example.com>" in fake.commands)
            assertEquals("QUIT", fake.commands.last())

            val (head, body) = fake.message.split("\n\n", limit = 2)
            val headers = head.lines().fold(mutableListOf<String>()) { acc, l -> if (l.startsWith(" ")) acc[acc.lastIndex] += l else acc += l; acc }
                .associate { it.substringBefore(": ") to it.substringAfter(": ") }
            assertEquals("Персоналка", decode(headers["From"]!!.substringBefore(" <")))
            assertTrue(headers["From"]!!.endsWith(" <box@mail.ru>"))
            assertEquals("Код подтверждения: 123456", decode(headers["Subject"]!!))
            assertEquals("Wed, 14 Oct 2026 13:30:00 GMT", headers["Date"])
            val decoded = String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
            assertTrue("Код для подтверждения почты в «Персоналке»: 123456\r\n" in decoded, decoded)
        }
    }

    @Test
    fun wrongPasswordAndRejectedAddress() {
        FakeSmtp().use { fake ->
            val e = assertFailsWith<MailException> { runBlocking { mailer(fake, password = "не тот").send("trener@example.com", "s", "t") } }
            assertTrue("535" in e.message!!, e.message)
            assertFalse("не тот" in e.message!!, "пароля в ошибке быть не должно")
        }
        FakeSmtp(reject = "RCPT" to "550 5.1.1 <trener@example.com>: user unknown").use { fake ->
            val e = assertFailsWith<MailException> { runBlocking { mailer(fake).send("trener@example.com", "s", "t") } }
            assertTrue("550" in e.message!!, e.message)
            assertFalse("trener@example.com" in e.message!!, "адреса в журнале сервера быть не должно: ${e.message}")
        }
        val closed = ServerSocket(0).use { it.localPort }
        val unreachable = SmtpMailer(SmtpClient("127.0.0.1", closed, SmtpSecurity.NONE, "u@mail.ru", "p"), "u@mail.ru")
        assertFailsWith<MailException> { runBlocking { unreachable.send("a@b.c", "s", "t") } }
    }

    @Test
    fun mailerFromSettings() {
        val base = mapOf(
            "TRENER_DB_URL" to "jdbc:postgresql://127.0.0.1/trener",
            "TRENER_DB_USER" to "trener",
            "TRENER_DB_PASSWORD" to "x",
            "TRENER_DOMAIN" to "trener.example.ru",
        )
        assertIs<NoMailer>(ServerConfig.fromEnv(base).mailer())
        val config = ServerConfig.fromEnv(
            base + mapOf("TRENER_SMTP_HOST" to "smtp.mail.ru", "TRENER_SMTP_USER" to "box@mail.ru", "TRENER_SMTP_PASSWORD" to "секрет"),
        )
        val smtp = config.smtp!!
        assertEquals(465, smtp.port)
        assertEquals(SmtpSecurity.SSL, SmtpSecurity.parse(smtp.tls, smtp.port))
        assertEquals("Персоналка <box@mail.ru>", smtp.from)
        assertIs<SmtpMailer>(config.mailer())
        assertFalse("секрет" in config.toString())
        assertEquals(SmtpSecurity.STARTTLS, SmtpSecurity.parse(null, 587))
    }
}
