package com.akenory.personalka.data.cabinet

import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.domain.format.AccountFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ссылки кабинетов в приложении (шаг 2.13, ТЗ 5.9, 3.7): токен создаёт телефон, на сервер уходит только SHA-256,
 * токен и код — в защищённом хранилище; новая ссылка заменяет старую, код меняется без новой ссылки. Сервер —
 * в памяти ([FakeServer], те же ответы, что `CabinetRoutes`). Имена придуманы.
 */
class CabinetRepositoryTest {
    private val server = FakeServer()
    private val opened = mutableListOf<PersonalkaDatabase>()
    private var bytes = 0

    private inner class Phone(token: String = "t-a") {
        val db = inMemoryPersonalkaDatabase().also { opened += it }
        val secrets = MemorySecrets()
        val tokens = MemoryTokens(token)

        // Случайность — по порядку, чтобы тест видел, какой код вышел.
        val cabinets = CabinetRepository(
            db,
            CabinetApi(server.client) { "https://trener.example.ru/" },
            tokens,
            secrets,
            { Samples.NOW },
            random = { size -> ByteArray(size) { (++bytes).toByte() } },
        )
    }

    private val client = Samples.client("Лебедева", "Ксения")

    private fun phone(): Phone {
        val coach = server.signedUp(EMAIL, "пароль-тренера")
        server.cabinetAccess += EMAIL
        server.sessions += FakeServer.Session(100, coach, "Телефон", "t-a")
        return Phone()
    }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    private fun sha(token: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    @Test
    fun openingIssuesALinkWithCodeAndOnlyTheHashLeavesThePhone(): Unit = runBlocking {
        val a = phone()
        a.db.clientDao().upsertClient(client)
        assertIs<CabinetResult.Done>(a.cabinets.setOpen(client.id, open = true, withPin = true))

        assertTrue(a.db.clientDao().client(client.id)!!.cabinetEnabled, "отметка у клиента — для синхронизации")
        val status = a.cabinets.status(client.id)
        assertTrue(status.online)
        val link = assertNotNull(status.link)
        val url = assertNotNull(link.url)
        assertTrue(url.startsWith("https://trener.example.ru/c/"), url)
        val token = url.substringAfter("/c/")
        assertEquals(43, token.length, "32 байта в base64url без «=»")
        assertEquals(sha(token), server.cabinets[client.id]!!.tokenHash)
        val sent = server.requests.last { it.first == "/cabinets/${client.id}" }.second.toString()
        assertFalse(token in sent, "сам токен на сервер не уходит")
        val pin = assertNotNull(link.pin)
        assertTrue(Regex("\\d{4}").matches(pin))
        assertEquals(pin, server.cabinets[client.id]!!.pin)
        assertTrue(link.pinOn)

        // Повторное «открыто» новую ссылку не выпускает.
        a.cabinets.setOpen(client.id, open = true, withPin = true)
        assertEquals(url, a.cabinets.status(client.id).link!!.url)
    }

    @Test
    fun closingKeepsTheLinkAndNewLinkReplacesTheOld(): Unit = runBlocking {
        val a = phone()
        a.db.clientDao().upsertClient(client)
        a.cabinets.setOpen(client.id, open = true, withPin = false)
        val first = a.cabinets.status(client.id).link!!
        assertNull(first.pin)
        assertFalse(first.pinOn)

        a.cabinets.setOpen(client.id, open = false, withPin = true)
        assertFalse(a.db.clientDao().client(client.id)!!.cabinetEnabled)
        assertEquals(first.url, a.cabinets.status(client.id).link!!.url, "закрытый кабинет ссылку не теряет")

        assertIs<CabinetResult.Done>(a.cabinets.issue(client.id, withPin = false))
        val second = a.cabinets.status(client.id).link!!
        assertNotEquals(first.url, second.url)
        assertEquals(sha(second.url!!.substringAfter("/c/")), server.cabinets[client.id]!!.tokenHash, "на сервере — только новая")
    }

    @Test
    fun codeChangesWithoutNewLink(): Unit = runBlocking {
        val a = phone()
        a.cabinets.issue(client.id, withPin = false)
        val url = a.cabinets.status(client.id).link!!.url

        a.cabinets.setPin(client.id, on = true)
        val withCode = a.cabinets.status(client.id).link!!
        assertEquals(url, withCode.url)
        assertEquals(server.cabinets[client.id]!!.pin, withCode.pin)
        assertNotNull(withCode.pin)

        a.cabinets.setPin(client.id, on = false)
        val without = a.cabinets.status(client.id).link!!
        assertEquals(url, without.url)
        assertNull(without.pin)
        assertNull(server.cabinets[client.id]!!.pin)
    }

    @Test
    fun offlineShowsTheRememberedLinkAndOtherPhoneLinkHasNoUrl(): Unit = runBlocking {
        val a = phone()
        a.cabinets.issue(client.id, withPin = true)
        val link = a.cabinets.status(client.id).link!!

        server.offline = true
        val offline = a.cabinets.status(client.id)
        assertFalse(offline.online)
        assertEquals(AccountFormat.OFFLINE, offline.problem)
        assertEquals(link.url, offline.link!!.url, "без связи ссылку можно отправить")
        assertEquals(link.pin, offline.link!!.pin)
        assertIs<CabinetResult.Failed>(a.cabinets.setPin(client.id, on = false))
        server.offline = false

        // Второй телефон того же тренера: ссылка есть, токена нет.
        val b = Phone()
        val other = b.cabinets.status(client.id).link!!
        assertNull(other.url)
        assertTrue(other.pinOn)
        assertNull(other.pin)
    }

    @Test
    fun revokedOnServerIsForgottenAndConsentIsRead(): Unit = runBlocking {
        val a = phone()
        a.cabinets.issue(client.id, withPin = false)
        server.cabinets.remove(client.id)
        server.consents[client.id] = buildJsonObject {
            put("client_id", client.id)
            put("accepted_at", Samples.NOW - 86_400_000L)
            put("photo", true)
            put("given_by_name", "Лебедева Анна")
            put("revoked_at", Samples.NOW)
        }
        val status = a.cabinets.status(client.id)
        assertNull(status.link)
        assertNull(a.secrets.read(CabinetRepository.key(client.id)), "отозванную ссылку телефон забывает")
        val consent = assertNotNull(status.consent)
        assertTrue(consent.photo)
        assertEquals("Лебедева Анна", consent.givenBy)
        assertEquals(Samples.NOW, consent.revokedAt)
    }

    @Test
    fun closedFeatureIsAMessage(): Unit = runBlocking {
        val a = phone()
        server.cabinetAccess.clear()
        val result = a.cabinets.issue(client.id, withPin = true)
        assertEquals("Кабинеты родителей не открыты — напишите администратору", (result as CabinetResult.Failed).message)
        assertNull(a.secrets.read(CabinetRepository.key(client.id)), "не выпущенная ссылка не запоминается")
    }

    private companion object {
        const val EMAIL = "coach@example.ru"
    }
}
