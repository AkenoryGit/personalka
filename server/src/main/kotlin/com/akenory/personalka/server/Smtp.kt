package com.akenory.personalka.server

// Клиент SMTP — из Planovo (server/.../Smtp.kt) без изменений по сути: письма идут через тот же ящик Mail.ru.

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Как защищено соединение с почтовым сервером. */
enum class SmtpSecurity {
    /** Сразу TLS (порт 465 — так у Mail.ru). */
    SSL,

    /** Обычное соединение, потом STARTTLS (порт 587); сервер не предлагает STARTTLS — ошибка, без шифрования не шлём. */
    STARTTLS,

    /** Без шифрования — только для проверок на localhost (CI). */
    NONE,
    ;

    companion object {
        /** Из настройки: `ssl`, `starttls`, `none`; не задано — по порту (465 — ssl, иначе starttls). */
        fun parse(value: String?, port: Int): SmtpSecurity = when (value?.trim()?.lowercase()) {
            null, "" -> if (port == 465) SSL else STARTTLS
            "ssl", "tls" -> SSL
            "starttls" -> STARTTLS
            "none" -> NONE
            else -> error("TRENER_SMTP_TLS: ssl, starttls или none, а не «$value»")
        }
    }
}

/** Ответ почтового сервера не тот, что ждали, или соединение оборвалось. Пароля и текста письма в сообщении нет. */
class SmtpException(message: String) : Exception(message)

/** Адрес из «Персоналка <x@mail.ru>» или просто «x@mail.ru». */
fun mailAddress(from: String): String = Regex("<([^<>]+)>").find(from)?.groupValues?.get(1)?.trim() ?: from.trim()

/**
 * Клиент SMTP на одной JDK: одно письмо — одно соединение (писем — десятки в день). TLS с проверкой сертификата
 * и имени сервера, вход `AUTH PLAIN` (или `LOGIN`, если сервер умеет только его).
 * Для Mail.ru: `smtp.mail.ru`, 465, SSL, логин — адрес ящика, пароль — «пароль для внешнего приложения»;
 * отправитель письма — тот же адрес, иначе Mail.ru письмо не примет.
 */
class SmtpClient(
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    private val user: String,
    private val password: String,
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val timeoutMs: Int = 20_000,
    private val helo: String = "trener.planovo-app.ru",
) {
    /** Отправить готовое письмо [message] (заголовки и тело, строки через CRLF) от [from] к [to]. */
    fun send(from: String, to: String, message: String) {
        if (security == SmtpSecurity.NONE && host !in setOf("127.0.0.1", "localhost")) throw SmtpException("без шифрования — только localhost")
        var socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            if (security == SmtpSecurity.SSL) socket = secure(socket)
            var line = Line(socket)
            line.reply("соединение", 220)
            var features = line.command("EHLO $helo", 250)
            if (security == SmtpSecurity.STARTTLS) {
                if (features.none { it.uppercase().startsWith("STARTTLS") }) throw SmtpException("$host не предлагает STARTTLS")
                line.command("STARTTLS", 220)
                socket = secure(socket)
                line = Line(socket)
                features = line.command("EHLO $helo", 250)
            }
            if (user.isNotEmpty()) login(line, features)
            line.command("MAIL FROM:<$from>", 250)
            line.command("RCPT TO:<$to>", 250, 251)
            line.command("DATA", 354)
            line.data(message)
            line.reply("письмо", 250)
            runCatching { line.command("QUIT", 221) }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun login(line: Line, features: List<String>) {
        val auth = features.firstOrNull { it.uppercase().startsWith("AUTH") }?.uppercase()?.split(' ', '=').orEmpty()
        if ("PLAIN" !in auth && "LOGIN" in auth) {
            line.command("AUTH LOGIN", 334)
            line.command(b64(user), 334, shown = "логин")
            line.command(b64(password), 235, shown = "пароль")
        } else {
            line.command("AUTH PLAIN " + b64("\u0000$user\u0000$password"), 235, shown = "AUTH PLAIN")
        }
    }

    /** TLS поверх соединения: сертификат и имя сервера проверяются (как в браузере). */
    private fun secure(plain: Socket): Socket {
        val s = tls.createSocket(plain, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.soTimeout = timeoutMs
        s.startHandshake()
        return s
    }

    /** Строки протокола поверх соединения. */
    private class Line(socket: Socket) {
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val output: OutputStream = socket.getOutputStream()

        /** Команда и ответ; код не из [ok] — ошибка (в ней вместо логина и пароля — [shown]). */
        fun command(text: String, vararg ok: Int, shown: String = text): List<String> {
            output.write("$text\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
            return reply(shown, *ok)
        }

        /** Ответ сервера (может быть в несколько строк: «250-…», последняя — «250 …»); строки без кода. */
        fun reply(what: String, vararg ok: Int): List<String> {
            val lines = mutableListOf<String>()
            var code: Int
            while (true) {
                val l = input.readLine() ?: throw SmtpException("$what: почтовый сервер закрыл соединение")
                code = l.take(3).toIntOrNull() ?: throw SmtpException("$what: непонятный ответ «${l.take(80)}»")
                lines += l.drop(4)
                if (l.getOrNull(3) != '-') break
            }
            if (code !in ok) throw SmtpException("$what: $code ${lines.joinToString(" ").take(200)}")
            return lines
        }

        /** Текст письма после DATA: строки, начинающиеся с точки, удваивают её; в конце — строка из одной точки. */
        fun data(message: String) {
            val body = message.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            output.write((body.trimEnd('\r', '\n') + "\r\n.\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private companion object {
        fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    }
}

/** Письмо по правилам почты (RFC 5322 и MIME): заголовки по-русски — в кодировке UTF-8, текст — base64. */
object MailMessage {
    fun build(from: String, to: String, subject: String, text: String, now: ZonedDateTime, id: String = UUID.randomUUID().toString()): String {
        val domain = mailAddress(from).substringAfter('@', "planovo-app.ru")
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        return listOf(
            "From: ${address(from)}",
            "To: <$to>",
            "Subject: ${header(subject)}",
            "Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(now)}",
            "Message-ID: <$id@$domain>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            body,
        ).joinToString("\r\n") + "\r\n"
    }

    /** «Имя <адрес>»: имя не латиницей — словами MIME. */
    private fun address(from: String): String {
        val email = mailAddress(from)
        val name = from.substringBefore('<', "").trim().trim('"')
        return if (name.isEmpty()) "<$email>" else "${header(name)} <$email>"
    }

    /** Заголовок: латиница — как есть, иначе слова `=?UTF-8?B?…?=` (по 64 знака), через перенос строки. */
    fun header(text: String): String {
        if (text.all { it in ' '..'~' }) return text
        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val n = s.toByteArray(Charsets.UTF_8).size
            if (bytes + n > CHUNK) {
                words += word(chunk.toString())
                chunk.clear()
                bytes = 0
            }
            chunk.append(s)
            bytes += n
            i += Character.charCount(cp)
        }
        if (chunk.isNotEmpty()) words += word(chunk.toString())
        return words.joinToString("\r\n ")
    }

    /** Байт текста в одном слове: 52 знака base64 — строка «Subject: …» укладывается в 78 знаков. */
    private const val CHUNK = 39

    private fun word(s: String) = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="
}
