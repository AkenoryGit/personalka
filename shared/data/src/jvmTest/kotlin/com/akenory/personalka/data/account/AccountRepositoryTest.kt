package com.akenory.personalka.data.account

import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.AccountFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Аккаунт в телефоне (шаг 2.8а, ТЗ 7.3): регистрация с кодом, вход, сброс, устройства, выход, без связи. */
class AccountRepositoryTest {
    private val server = FakeServer()
    private val tokens = MemoryTokens()
    private val prefs = MemoryPrefs()

    private fun repo() = AccountRepository(server.api(), tokens, prefs, "Samsung SM-F976B") { "coach-1" }

    @Test
    fun registerConfirmAndSignIn() = runBlocking<Unit> {
        val account = repo()
        val sent = account.register("  Дмитрий ", " Coach@Example.ru ", "длинный-пароль")
        assertEquals(AccountResult.CodeSent("coach@example.ru", 15), sent)
        // Почта — маленькими буквами, имя без пробелов, id тренера из телефона.
        val body = server.requests.last().second!!
        assertEquals("coach@example.ru", (body["email"] as JsonPrimitive).content)
        assertEquals("Дмитрий", (body["display_name"] as JsonPrimitive).content)
        assertEquals("coach-1", (body["coach_id"] as JsonPrimitive).content)
        assertNull(account.account.value)

        val wrong = account.confirm("coach@example.ru", "длинный-пароль", "000000")
        assertEquals(AccountResult.Failed("wrong_code", "Неверный код. Осталось 4 попытки"), wrong)

        assertEquals(AccountResult.Done, account.confirm("coach@example.ru", "длинный-пароль", " 123456 "))
        val signedIn = assertNotNull(account.account.value)
        assertEquals("Дмитрий", signedIn.name)
        assertEquals(listOf("Samsung SM-F976B"), signedIn.sessions.map { it.name })
        assertTrue(signedIn.sessions.single().current)
        assertNotNull(tokens.read())
    }

    @Test
    fun formIsCheckedBeforeServer() = runBlocking<Unit> {
        val account = repo()
        assertEquals(AccountFormat.EMAIL_WRONG, (account.register("Имя", "не почта", "длинный-пароль") as AccountResult.Failed).message)
        assertEquals("Пароль — не короче 10 знаков", (account.register("Имя", "a@b.ru", "короткий") as AccountResult.Failed).message)
        assertEquals(AccountFormat.NAME_EMPTY, (account.register("  ", "a@b.ru", "длинный-пароль") as AccountResult.Failed).message)
        assertEquals(AccountFormat.PASSWORD_EMPTY, (account.login("a@b.ru", "") as AccountResult.Failed).message)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun wrongPasswordIsRefused() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        assertEquals(AccountResult.Failed("wrong_password", "Неверная почта или пароль"), account.login("coach@example.ru", "чужой-пароль-123"))
        assertEquals(AccountResult.Failed("wrong_password", "Неверная почта или пароль"), account.login("other@example.ru", "длинный-пароль"))
        assertNull(account.account.value)
        assertNull(tokens.read())
    }

    @Test
    fun loginBeforeConfirmAsksForCode() = runBlocking<Unit> {
        val account = repo()
        account.register("Дмитрий", "coach@example.ru", "длинный-пароль")
        assertEquals(AccountResult.CodeSent("coach@example.ru", 15), account.login("coach@example.ru", "длинный-пароль"))
        assertEquals(2, server.codesSent.size)
        assertEquals(AccountResult.Done, account.confirm("coach@example.ru", "длинный-пароль", FakeServer.CODE))
    }

    @Test
    fun signInShowsDevicesAndFeatures() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль", "Дмитрий")
        server.admin = "coach@example.ru"
        val account = repo()
        assertEquals(AccountResult.Done, account.login("coach@example.ru", "длинный-пароль"))
        val signedIn = assertNotNull(account.account.value)
        assertTrue(signedIn.admin)
        assertEquals(Feature.entries.toSet(), signedIn.features.map { it.feature }.toSet())
        assertEquals(listOf("Второй телефон" to false, "Samsung SM-F976B" to true), signedIn.sessions.map { it.name to it.current })
        assertEquals("Samsung SM-F976B", (server.requests.first { it.first == "/auth/login" }.second!!["device_name"] as JsonPrimitive).content)

        // Профиль переживает перезапуск приложения: тот же токен и сохранённые данные.
        val again = AccountRepository(server.api(), tokens, prefs, "Samsung SM-F976B") { "coach-1" }
        assertEquals(signedIn, again.account.value)
    }

    @Test
    fun signOutOtherDevice() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        account.login("coach@example.ru", "длинный-пароль")
        val other = account.account.value!!.sessions.first { !it.current }
        assertEquals(AccountResult.Done, account.closeSession(other))
        assertEquals(listOf(true), account.account.value!!.sessions.map { it.current })
        assertTrue(server.sessions.none { it.token == "t-other" })
        // Устройство уже вышло — текст сервера.
        assertEquals(AccountResult.Failed("session_not_found", "Это устройство уже вышло"), account.closeSession(other))
    }

    @Test
    fun logoutForgetsTokenEvenOffline() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        account.login("coach@example.ru", "длинный-пароль")
        server.offline = true
        assertEquals(AccountResult.Done, account.logout())
        assertNull(account.account.value)
        assertNull(tokens.read())
        assertNull(AccountRepository(server.api(), tokens, prefs, "x") { null }.account.value)
    }

    @Test
    fun logoutClosesSessionOnServer() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        account.login("coach@example.ru", "длинный-пароль")
        val mine = account.account.value!!.sessions.first { it.current }
        // «Выйти на этом устройстве» у своей строки — тот же выход.
        assertEquals(AccountResult.Done, account.closeSession(mine))
        assertNull(account.account.value)
        assertEquals(listOf("t-other"), server.sessions.map { it.token })
    }

    @Test
    fun resetPasswordByCode() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "старый-пароль-1")
        val account = repo()
        assertEquals(AccountResult.CodeSent("coach@example.ru", 15), account.requestReset("coach@example.ru"))
        // На чужую почту письма нет, а ответ тот же.
        assertEquals(AccountResult.CodeSent("other@example.ru", 15), account.requestReset("other@example.ru"))
        assertEquals(listOf("coach@example.ru"), server.codesSent)
        assertIs<AccountResult.Failed>(account.reset("coach@example.ru", "111111", "новый-пароль-2"))
        assertEquals(AccountResult.Done, account.reset("coach@example.ru", FakeServer.CODE, "новый-пароль-2"))
        // Второй телефон вышел, этот вошёл с новым паролем.
        assertEquals(listOf(true), account.account.value!!.sessions.map { it.current })
        assertEquals("новый-пароль-2", server.coaches.getValue("coach@example.ru").password)
    }

    @Test
    fun expiredSessionSignsOut() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        account.login("coach@example.ru", "длинный-пароль")
        server.sessions.clear()
        assertEquals(AccountResult.Failed("unauthorized", "Вход устарел — войдите заново"), account.refresh())
        assertNull(account.account.value)
        assertNull(tokens.read())
    }

    @Test
    fun offlineKeepsProfile() = runBlocking<Unit> {
        server.signedUp("coach@example.ru", "длинный-пароль")
        val account = repo()
        account.login("coach@example.ru", "длинный-пароль")
        server.offline = true
        assertEquals(AccountResult.Failed(AccountException.OFFLINE, AccountFormat.OFFLINE), account.refresh())
        assertNotNull(account.account.value)
        assertEquals(AccountResult.Failed(AccountException.OFFLINE, AccountFormat.OFFLINE), account.login("coach@example.ru", "длинный-пароль"))
    }

    @Test
    fun foreignAnswerIsServerError() = runBlocking<Unit> {
        // Не ответ «Персоналки» (страница Caddy, 502) — общий текст, а не HTML.
        val api = AccountApi(HttpClient(MockEngine { respondBad() })) { "https://x" }
        val account = AccountRepository(api, tokens, prefs, "x") { null }
        assertEquals(AccountResult.Failed(AccountException.SERVER, AccountFormat.SERVER_ERROR), account.login("a@b.ru", "пароль"))
    }

    @Test
    fun serverAddressFromSettings() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val api = AccountApi(
            HttpClient(MockEngine { request -> seen += request.url.toString(); respondBad() }),
        ) { "https://my.server.ru/" }
        AccountRepository(api, tokens, prefs, "x") { null }.requestReset("a@b.ru")
        assertEquals(listOf("https://my.server.ru/api/v1/auth/reset/request"), seen)
    }
}

private fun MockRequestHandleScope.respondBad() = respond("<html>502</html>", HttpStatusCode.BadGateway)
