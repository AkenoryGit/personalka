package com.akenory.personalka.data.sync

import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.Samples
import com.akenory.personalka.data.db.entity.SyncOutboxEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.sync.SyncProblem
import com.akenory.personalka.domain.sync.SyncRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синхронизация телефона (шаг 2.10, ТЗ 7.2): два телефона одного тренера через сервер в памяти ([FakeServer] — те же
 * ответы, что сервер 2.9), обрывы сети и потерянные ответы. Имена придуманы.
 */
class SyncRepositoryTest {
    private val server = FakeServer()
    private var now = Samples.NOW
    private val opened = mutableListOf<PersonalkaDatabase>()

    private inner class Phone(token: String) {
        val db = inMemoryPersonalkaDatabase().also { opened += it }
        val prefs = MemoryPrefs()
        val tokens = MemoryTokens(token)
        var email: String? = EMAIL
        val sync = SyncRepository(db, SyncApi(server.client) { "https://trener.example.ru" }, tokens, prefs, owner = { email }) { now }
        val clients get() = db.clientDao()

        suspend fun live() = db.clientDao().observeAllClients().first()

        suspend fun outbox(): List<SyncOutboxEntity> = db.syncDao().next(10_000)
    }

    /** Тренер с открытой «Копией на сервере» и два его телефона. */
    private fun twoPhones(): Pair<Phone, Phone> {
        val coach = server.signedUp(EMAIL, "пароль-тренера")
        server.serverCopy += EMAIL
        server.sessions += FakeServer.Session(100, coach, "Телефон", "t-a")
        server.sessions += FakeServer.Session(101, coach, "Планшет", "t-b")
        return Phone("t-a") to Phone("t-b")
    }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    @Test
    fun everyChangeLandsInTheQueueOnceWithItsLastMoment(): Unit = runBlocking {
        val (a, _) = twoPhones()
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        a.clients.upsertClient(client.copy(firstName = "Ксюша", sync = client.sync.touched(Samples.NOW + 1_000)))
        assertEquals(listOf(SyncOutboxEntity("client", client.id, Samples.NOW + 1_000)), a.outbox(), "Две правки — одна строка очереди")

        a.clients.eraseClient(client.id)
        assertEquals(listOf(SyncOutboxEntity("client", client.id, Samples.NOW + 1_001)), a.outbox(), "«Удалить полностью» — тоже в очередь, не раньше самой записи")
    }

    @Test
    fun twoPhonesSeeEachOthersChanges(): Unit = runBlocking {
        val (a, b) = twoPhones()
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        assertTrue(a.sync.connect())
        assertEquals(0, a.db.syncDao().pending(), "Первая выгрузка ушла вся")
        assertEquals(1, server.liveRows(EMAIL, "client").size)

        // Второй телефон берёт данные с сервера: своё (созданное до подключения) стирается.
        b.clients.upsertClient(Samples.client("Пробная", "Запись"))
        assertTrue(b.sync.connect(takeFromServer = true))
        assertEquals(listOf("Ксения"), b.live().map { it.firstName })
        assertEquals(0, b.db.syncDao().pending(), "Пришедшее с сервера назад не отправляется")

        now += 60_000
        b.clients.upsertClient(client.copy(firstName = "Ксюша", sync = client.sync.touched(now)))
        assertTrue(b.sync.sync())
        assertTrue(a.sync.sync())
        assertEquals("Ксюша", a.clients.client(client.id)!!.firstName)
        assertEquals(0, a.db.syncDao().pending())
        assertNotNull(a.sync.state.value.lastSync)
    }

    @Test
    fun laterEditWinsWhicheverPhoneSyncsFirst(): Unit = runBlocking {
        val (a, b) = twoPhones()
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        a.sync.connect()
        b.sync.connect(takeFromServer = true)

        // Без связи оба правят одну запись: на планшете — позже.
        a.clients.upsertClient(client.copy(contactName = "Папа", sync = client.sync.touched(Samples.NOW + 10_000)))
        b.clients.upsertClient(client.copy(contactName = "Бабушка", sync = client.sync.touched(Samples.NOW + 20_000)))

        // Планшет отправляет первым, телефон — вторым: правка телефона старше и сервером не принимается.
        assertTrue(b.sync.sync())
        assertTrue(a.sync.sync())
        assertEquals("Бабушка", a.clients.client(client.id)!!.contactName, "Поздняя правка пришла на телефон")
        assertEquals(0, a.db.syncDao().pending(), "Старая правка телефона ушла из очереди")
        assertTrue(b.sync.sync())
        assertEquals("Бабушка", b.clients.client(client.id)!!.contactName)
        assertEquals("Бабушка", (server.liveRows(EMAIL, "client").single().data["contactName"] as JsonPrimitive).content)
    }

    @Test
    fun editMadeDuringAnExchangeIsNotOverwrittenByAnOlderOne(): Unit = runBlocking {
        val (a, b) = twoPhones()
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        a.sync.connect()
        b.sync.connect(takeFromServer = true)

        a.clients.upsertClient(client.copy(contactName = "Папа", sync = client.sync.touched(Samples.NOW + 10_000)))
        a.sync.sync()
        // Планшет отправил очередь и пришёл за изменениями — а тренер в эту секунду правит ту же запись.
        server.beforePull = {
            server.beforePull = null
            b.clients.upsertClient(client.copy(contactName = "Бабушка", sync = client.sync.touched(Samples.NOW + 20_000)))
        }
        assertTrue(b.sync.sync())
        assertEquals("Бабушка", b.clients.client(client.id)!!.contactName, "Пришедшая правка старше своей — своя остаётся")
        assertEquals(1, b.db.syncDao().pending(), "И ждёт отправки")
        b.sync.sync()
        a.sync.sync()
        assertEquals("Бабушка", a.clients.client(client.id)!!.contactName)
    }

    @Test
    fun networkDropLosesNothing(): Unit = runBlocking {
        val (a, b) = twoPhones()
        a.sync.connect()
        b.sync.connect(takeFromServer = true)

        // Нет связи: правка ждёт в очереди, пауза повтора растёт.
        server.offline = true
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        assertFalse(a.sync.sync())
        assertFalse(a.sync.sync())
        assertEquals(SyncProblem.OFFLINE, a.sync.state.value.problem)
        assertEquals(2, a.sync.state.value.failures)
        assertEquals(60_000L, SyncRules.retryDelay(a.sync.state.value.failures))
        assertEquals(1, a.db.syncDao().pending())

        // Связь вернулась — правка уходит, ошибки забыты.
        server.offline = false
        assertTrue(a.sync.sync())
        assertEquals(0, a.db.syncDao().pending())
        assertNull(a.sync.state.value.problem)
        assertEquals(0, a.sync.state.value.failures)

        // Обрыв посреди обмена: сервер принял, ответ потерян — правка остаётся и уходит ещё раз без дубля.
        now += 60_000
        a.clients.upsertClient(client.copy(firstName = "Ксюша", sync = client.sync.touched(now)))
        server.loseAnswers = true
        assertFalse(a.sync.sync())
        val acceptedOnce = server.accepted.size
        assertEquals(1, a.db.syncDao().pending())
        server.loseAnswers = false
        assertTrue(a.sync.sync())
        assertEquals(acceptedOnce, server.accepted.size, "Повтор той же отправки не записан второй раз")
        assertEquals(0, a.db.syncDao().pending())

        assertTrue(b.sync.sync())
        assertEquals("Ксюша", b.clients.client(client.id)!!.firstName)
    }

    @Test
    fun clientErasedOnOnePhoneDisappearsEverywhere(): Unit = runBlocking {
        val (a, b) = twoPhones()
        val client = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(client)
        a.sync.connect()
        b.sync.connect(takeFromServer = true)
        assertNotNull(b.clients.client(client.id))

        a.clients.eraseClient(client.id)
        assertTrue(a.sync.sync())
        assertEquals(JsonObject(emptyMap()), server.rows.values.single { it.id == client.id }.data, "На сервере от клиента — пустая запись")
        assertTrue(b.sync.sync())
        assertNull(b.clients.client(client.id), "Стёрт и на втором телефоне")
        assertEquals(0, b.db.syncDao().pending(), "Стирание не отправляется назад")

        // Клиента стёрли посреди обмена, а с сервера пришла его правка: стирание новее, запись не воскресает.
        val other = Samples.client("Орлова", "Мира")
        a.clients.upsertClient(other)
        a.sync.sync()
        b.sync.sync()
        now += 60_000
        a.clients.upsertClient(other.copy(firstName = "Мирослава", sync = other.sync.touched(now)))
        a.sync.sync()
        now += 1_000
        server.beforePull = {
            server.beforePull = null
            b.clients.eraseClient(other.id)
        }
        assertTrue(b.sync.sync())
        assertNull(b.clients.client(other.id))
        assertTrue(b.sync.sync())
        assertTrue(a.sync.sync())
        assertNull(a.clients.client(other.id), "Стирание дошло и до первого телефона")
    }

    @Test
    fun longOfflinePhoneGetsEverythingAgain(): Unit = runBlocking {
        val (a, b) = twoPhones()
        val first = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(first)
        a.sync.connect()
        b.sync.connect(takeFromServer = true)

        val second = Samples.client("Орлова", "Мира")
        a.clients.upsertClient(second)
        a.clients.upsertClient(first.copy(sync = first.sync.deleted(Samples.NOW + 5_000)))
        a.sync.sync()
        // Прошло 90 дней: сервер стёр удалённые, планшет не синхронизировался — ему 410 и всё заново.
        server.purgeDeleted()
        val planned = Samples.client("Пахомова", "Вера")
        b.clients.upsertClient(planned)
        assertTrue(b.sync.sync())
        assertNotNull(b.clients.client(second.id), "Новое пришло, хотя номер планшета устарел")
        assertNull(b.sync.state.value.problem)
        assertTrue(server.liveRows(EMAIL, "client").any { it.id == planned.id }, "Своё планшет сначала отправил")
    }

    @Test
    fun firstUploadGoesInBatchesWithProgress(): Unit = runBlocking {
        val (a, b) = twoPhones()
        repeat(1_200) { a.clients.upsertClient(Samples.client("Клиент$it", "Имя")) }
        assertTrue(a.sync.connect())
        assertEquals(1_200, server.liveRows(EMAIL, "client").size)
        assertNull(a.sync.state.value.progress, "Полоса прогресса убрана после выгрузки")

        assertTrue(b.sync.connect(takeFromServer = true))
        assertEquals(1_200, b.live().size, "Новое устройство получило все страницы")
    }

    @Test
    fun serverRefusalsKeepTheQueue(): Unit = runBlocking {
        val (a, _) = twoPhones()
        a.sync.connect()
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))

        // Сервер ещё не обновился до новой версии базы — изменения ждут, повтор как без связи.
        server.schemaVersion = SyncRules.SCHEMA_VERSION - 1
        assertFalse(a.sync.sync())
        assertEquals(SyncProblem.SERVER_OUTDATED, a.sync.state.value.problem)
        assertEquals(1, a.sync.state.value.failures)

        // Приложение слишком старое — 426, обмен стоит, правка в очереди.
        server.schemaVersion = SyncRules.SCHEMA_VERSION + 2
        assertFalse(a.sync.sync())
        assertEquals(SyncProblem.UPDATE_REQUIRED, a.sync.state.value.problem)
        assertEquals(1, a.db.syncDao().pending())

        server.schemaVersion = SyncRules.SCHEMA_VERSION
        assertTrue(a.sync.sync())
        assertEquals(0, a.db.syncDao().pending())

        // Копию закрыли — данные на телефоне на месте, ответ «закрыто».
        server.serverCopy -= EMAIL
        a.clients.upsertClient(Samples.client("Орлова", "Мира"))
        assertFalse(a.sync.sync())
        assertEquals(SyncProblem.CLOSED, a.sync.state.value.problem)
        assertEquals(2, a.live().size)
    }

    @Test
    fun closedCopyDoesNotConnect(): Unit = runBlocking {
        server.signedUp(EMAIL, "пароль-тренера")
        val coach = server.coaches.getValue(EMAIL)
        server.sessions += FakeServer.Session(100, coach, "Телефон", "t-a")
        val a = Phone("t-a")
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertFalse(a.sync.connect())
        assertEquals(SyncProblem.CLOSED, a.sync.state.value.problem)
        assertFalse(a.sync.state.value.connected)
        assertFalse(a.sync.connect(takeFromServer = true))
        assertEquals(1, a.live().size, "Без открытой копии телефон не стирается")
    }

    @Test
    fun clubDataFromACoachIsNotKeptButLeavesTheQueue(): Unit = runBlocking {
        val (a, _) = twoPhones()
        a.db.referenceDao().upsertClubRate(Samples.clubRate())
        assertTrue(a.sync.connect())
        assertEquals(0, a.db.syncDao().pending())
        assertTrue(server.rows.values.none { it.entity == "club_rate_grid" }, "Сетку клуба пишет только администратор")
    }

    @Test
    fun disconnectErasesServerDataAndKeepsThePhone(): Unit = runBlocking {
        val (a, _) = twoPhones()
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        a.sync.connect()
        assertTrue(server.rows.isNotEmpty())

        assertTrue(a.sync.disconnect())
        assertTrue(server.rows.isEmpty())
        assertFalse(a.sync.state.value.connected)
        assertEquals(1, a.live().size)
        val before = server.requests.size
        assertFalse(a.sync.sync(), "Отключённый телефон не обменивается")
        assertEquals(before, server.requests.size)
    }

    @Test
    fun otherAccountOnThePhoneDoesNotMixData(): Unit = runBlocking {
        val (a, _) = twoPhones()
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertTrue(a.sync.connect())

        // Выход и вход в другой аккаунт, у которого на сервере свой клиент.
        val other = server.signedUp(OTHER, "пароль-другого")
        server.serverCopy += OTHER
        server.sessions += FakeServer.Session(200, other, "Телефон", "t-c")
        val c = Phone("t-c").apply { email = OTHER }
        c.clients.upsertClient(Samples.client("Чужая", "Запись"))
        assertTrue(c.sync.connect())

        a.tokens.write("t-c")
        a.email = OTHER
        a.clients.upsertClient(Samples.client("Новая", "Правка"))
        val before = server.requests.size
        assertFalse(a.sync.sync(), "В чужом аккаунте обмена нет")
        assertEquals(before, server.requests.size)
        assertEquals(SyncProblem.OTHER_ACCOUNT, a.sync.state.value.problem)
        assertFalse(a.sync.disconnect(), "«Отключиться» не стирает чужие данные")
        assertEquals(before, server.requests.size)
        assertEquals(listOf("Ксения", "Правка"), a.live().map { it.firstName }.sorted(), "Данные телефона на месте, чужих нет")
        assertEquals(1, server.liveRows(EMAIL, "client").size, "Первый аккаунт на сервере не тронут")
        assertEquals(1, server.liveRows(OTHER, "client").size, "Во второй аккаунт ничего не ушло")

        // Вернулись в свой аккаунт — обмен идёт дальше без нового подключения.
        a.tokens.write("t-a")
        a.email = EMAIL
        assertTrue(a.sync.sync())
        assertTrue(a.sync.state.value.connected)
        assertEquals(2, server.liveRows(EMAIL, "client").size, "Правка, сделанная под чужим входом, ушла в свой аккаунт")
        assertEquals(1, server.liveRows(OTHER, "client").size)
    }

    @Test
    fun sameAccountAfterSignOutKeepsSyncing(): Unit = runBlocking {
        val (a, _) = twoPhones()
        assertTrue(a.sync.connect())
        a.tokens.write(null)
        a.email = null
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertFalse(a.sync.sync())
        assertTrue(a.sync.state.value.connected, "Выход сервер не отключает")

        a.tokens.write("t-a")
        a.email = EMAIL.uppercase()
        assertTrue(a.sync.sync(), "Вошли в тот же аккаунт — обмен идёт дальше")
        assertEquals(1, server.liveRows(EMAIL, "client").size, "Правка без входа ушла")
    }

    @Test
    fun signInOnAnEmptyPhoneTakesTheServerData(): Unit = runBlocking {
        val (a, b) = twoPhones()
        a.db.coachDao().upsertCoach(Samples.coach())
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertEquals(Attach.Done, a.sync.attach(), "Свои данные, на сервере пусто — первая выгрузка")
        assertEquals(1, server.liveRows(EMAIL, "client").size)

        assertEquals(Attach.Done, b.sync.attach(), "Пустой телефон — данные с сервера")
        assertEquals(listOf("Ксения"), b.live().map { it.firstName })
        assertTrue(b.sync.state.value.connected)
    }

    @Test
    fun ownDataOnlyOnThePhoneIsNotMergedWithTheServer(): Unit = runBlocking {
        val (a, b) = twoPhones()
        a.db.coachDao().upsertCoach(Samples.coach())
        assertEquals(Attach.Done, a.sync.attach())
        // Второй телефон начинал сам — его тренер другой, слить нельзя: двойные типы и места.
        b.db.coachDao().upsertCoach(Samples.coach().copy(id = "coach-b"))
        val before = server.requests.count { it.first.contains("push") }
        assertEquals(Attach.ServerHasData, b.sync.attach())
        assertFalse(b.sync.state.value.connected)
        assertEquals(before, server.requests.count { it.first.contains("push") }, "Ничего не отправлено")
    }

    @Test
    fun signInAgainRebuildsACopyDeletedFromAnotherPhone(): Unit = runBlocking {
        // Ограничение 2.10а: копию удалили с одного телефона, второй дальше слал только новые правки.
        val (a, b) = twoPhones()
        a.db.coachDao().upsertCoach(Samples.coach())
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertEquals(Attach.Done, a.sync.attach())
        assertEquals(Attach.Done, b.sync.attach())

        assertTrue(a.sync.disconnect())
        assertTrue(server.rows.isEmpty())
        // Сервер закрыл все входы: второй телефон больше не шлёт, его правка ждёт в очереди.
        b.clients.upsertClient(Samples.client("Новая", "Правка"))
        assertFalse(b.sync.sync())
        assertEquals(SyncProblem.SIGNED_OUT, b.sync.state.value.problem)

        // Вошли снова на втором — на сервер уходит вся его база, а не только новая правка.
        val coach = server.coaches.getValue(EMAIL)
        server.sessions += FakeServer.Session(102, coach, "Планшет", "t-b2")
        b.tokens.write("t-b2")
        assertEquals(Attach.Done, b.sync.attach())
        assertEquals(listOf("Ксения", "Правка"), server.liveRows(EMAIL, "client").map { it.data["firstName"].toString().trim('"') }.sorted())

        // Первый телефон помнит, что данные его аккаунта, — после входа сливается, а не спрашивает «заменить?».
        server.sessions += FakeServer.Session(103, coach, "Телефон", "t-a2")
        a.tokens.write("t-a2")
        assertEquals(Attach.Done, a.sync.attach())
        assertEquals(listOf("Ксения", "Правка"), a.live().map { it.firstName }.sorted())
    }

    @Test
    fun signOutSendsEverythingAndOnlyThenThePhoneCanBeWiped(): Unit = runBlocking {
        val (a, _) = twoPhones()
        a.db.coachDao().upsertCoach(Samples.coach())
        assertEquals(Attach.Done, a.sync.attach())

        // Без связи не всё ушло — стирать нельзя.
        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        server.offline = true
        assertEquals(SignOutCheck.NOT_SENT, a.sync.beforeSignOut())
        assertFalse(a.sync.wipePhone())
        assertEquals(1, a.live().size)

        // В другой аккаунт: данные первого не все на сервере — убрать их нельзя.
        server.offline = false
        a.email = OTHER
        assertEquals(Attach.OtherAccount(EMAIL, complete = false), a.sync.attach())

        // Вернулись, вышли со связью — всё ушло, теперь данные первого можно убрать.
        a.email = EMAIL
        assertEquals(SignOutCheck.SENT, a.sync.beforeSignOut())
        assertEquals(1, server.liveRows(EMAIL, "client").size)
        a.email = OTHER
        assertEquals(Attach.OtherAccount(EMAIL, complete = true), a.sync.attach())
        // Правка после выхода — снова не полная.
        a.clients.upsertClient(Samples.client("Новая", "Правка"))
        assertEquals(Attach.OtherAccount(EMAIL, complete = false), a.sync.attach())

        a.email = EMAIL
        assertEquals(SignOutCheck.SENT, a.sync.beforeSignOut())
        assertTrue(a.sync.wipePhone())
        assertTrue(a.live().isEmpty())
        assertNull(a.db.coachDao().currentCoach())
        assertFalse(a.sync.state.value.connected)
        assertEquals(2, server.liveRows(EMAIL, "client").size, "На сервере всё осталось")
    }

    @Test
    fun deviceIdIsMadeOnce() {
        val (a, _) = twoPhones()
        val id = a.sync.device
        now += 1_000
        assertEquals(id, a.sync.device)
        assertEquals(id, a.prefs.string(SyncRepository.DEVICE))
    }

    private companion object {
        const val EMAIL = "trener@example.ru"
        const val OTHER = "drugoy@example.ru"
    }
    @Test
    fun checkServerTellsCoachDataFromClubData(): Unit = runBlocking {
        val (a, b) = twoPhones()
        // Сетка клуба от администратора видна всем — но это не данные тренера.
        val boss = server.signedUp("admin@example.ru", "пароль-админа")
        server.admin = "admin@example.ru"
        server.sessions += FakeServer.Session(102, boss, "Админ", "t-admin")
        val admin = Phone("t-admin")
        admin.db.referenceDao().upsertClubRate(Samples.clubRate())
        assertTrue(admin.sync.connect())
        assertEquals(ServerData.EMPTY, b.sync.checkServer(), "Пусто: только сетка клуба")

        a.clients.upsertClient(Samples.client("Лебедева", "Ксения"))
        assertTrue(a.sync.connect())
        assertEquals(ServerData.HAS_DATA, b.sync.checkServer(), "Данные с первого телефона")
        assertFalse(b.sync.state.value.connected, "Проверка не подключает")

        server.offline = true
        assertEquals(ServerData.FAILED, b.sync.checkServer())
        assertEquals(SyncProblem.OFFLINE, b.sync.state.value.problem)
    }

    @Test
    fun oldestPendingChangeIsKnown(): Unit = runBlocking {
        val (a, _) = twoPhones()
        assertNull(a.sync.oldestPending.first())
        val first = Samples.client("Лебедева", "Ксения")
        a.clients.upsertClient(first)
        a.clients.upsertClient(Samples.client("Орлова", "Вера").let { it.copy(sync = it.sync.touched(Samples.NOW + 5_000)) })
        assertEquals(first.sync.updatedAt, a.sync.oldestPending.first())
        assertTrue(a.sync.connect())
        assertNull(a.sync.oldestPending.first(), "Всё ушло — ждать нечего")
    }

}
