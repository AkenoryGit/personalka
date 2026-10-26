package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.sync.SyncApi
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.format.SyncFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.SyncTodo
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.setup.FIRST_LOAD_TAG
import com.akenory.personalka.ui.setup.FIRST_SIGN_IN_TAG
import com.akenory.personalka.ui.setup.FIRST_START_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Синхронизация на экранах (шаг 2.10а, ТЗ 7.2, 1.5, 5.18): «Сервер» в «Данных» — условия с галочкой, первая выгрузка,
 * «Синхронизировать», «Отключиться»; данные на сервере с другого телефона; «Войти» на первом запуске. Сервер — в памяти.
 */
@OptIn(ExperimentalTestApi::class)
class SyncUiTest {
    private val server = FakeServer()
    private val url = "https://trener.example.ru"

    private object NoFolder : BackupFolder {
        override suspend fun write(folder: String, name: String, bytes: ByteArray) = false
        override suspend fun list(folder: String) = emptyList<String>()
        override suspend fun delete(folder: String, name: String) = Unit
        override suspend fun read(folder: String, name: String): ByteArray? = null
    }

    private object NoFiles : PlatformFiles {
        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) = Unit
        override fun openFile(onOpened: (ByteArray) -> Unit) = Unit
        override fun share(name: String, mime: String, bytes: ByteArray) = Unit
    }

    /** Телефон: база, вход и синхронизация с общим токеном — как на Android. */
    private inner class Phone(val settings: SettingsRepository) {
        val tokens = MemoryTokens()
        val prefs = MemoryPrefs()
        val account = AccountRepository(server.api(url), tokens, prefs, "Samsung SM-F976B") { settings.coach.first()?.id }
        val sync = SyncRepository(TestData.dbOf(settings), SyncApi(server.client) { url }, tokens, prefs, clock = TestData.clock)
        val tools = SyncTools(sync) { CoroutineScope(Dispatchers.Default).launch { sync.sync() } }

        fun signIn() = runBlocking { account.login(EMAIL, PASSWORD) }
    }

    private fun coach(serverCopy: Boolean = true) {
        server.signedUp(EMAIL, PASSWORD)
        if (serverCopy) server.serverCopy += EMAIL
    }

    private fun ComposeUiTest.show(phone: Phone) {
        val settings = phone.settings
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    backup = BackupTools(BackupRepository(TestData.dbOf(settings), MemoryPrefs(), NoFolder, clock = TestData.clock), NoFiles),
                    account = phone.account, sync = phone.tools, clock = TestData.clock,
                )
            }
        }
    }

    private fun ComposeUiTest.openData() {
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasTestTag(DATA_ROW_TAG))
        onNodeWithTag(DATA_ROW_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_ROW_TAG))
    }

    @Test
    fun connectWithTermsThenSyncAndDisconnect() = runComposeUiTest {
        coach()
        val phone = Phone(TestData.ready())
        phone.signIn()
        show(phone)
        openData()

        awaitNode(hasTestTag(SYNC_CONNECT_TAG))
        onNodeWithTag(SYNC_CONNECT_TAG).performClick()
        // Без галочки под условиями не подключается.
        onNodeWithText(SyncFormat.DO_CONNECT).assertIsNotEnabled()
        onNodeWithTag(SYNC_AGREE_TAG).performClick()
        onNodeWithText(SyncFormat.DO_CONNECT).assertIsEnabled().performClick()

        awaitNode(hasTestTag(SYNC_STATUS_TAG))
        waitUntil(timeoutMillis = 5_000) { server.liveRows(EMAIL, "coach").isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { !phone.sync.state.value.running && phone.sync.state.value.lastSync != null }
        onNodeWithTag(SYNC_STATUS_TAG).assertTextContains(SyncFormat.SYNCED, substring = true)
        assertEquals(TestData.coach.firstName, server.liveRows(EMAIL, "coach").single().data["firstName"].toString().trim('"'))

        // Нет связи: «Синхронизировать» показывает причину, данные в телефоне на месте.
        server.offline = true
        onNodeWithTag(SYNC_NOW_TAG).performClick()
        awaitNode(hasTestTag(SYNC_PROBLEM_TAG))
        onNodeWithTag(SYNC_PROBLEM_TAG).assertTextContains("Нет связи", substring = true)
        server.offline = false

        onNodeWithTag(SYNC_DISCONNECT_TAG).performClick()
        onNodeWithText(SyncFormat.DISCONNECT_TEXT).assertExists()
        onNodeWithText(SyncFormat.DISCONNECT_CONFIRM).performClick()
        awaitNode(hasTestTag(SYNC_CONNECT_TAG))
        assertTrue(server.liveRows(EMAIL, "coach").isEmpty(), "Данные на сервере стёрты")
        assertTrue(runBlocking { phone.settings.coach.first() } != null, "В телефоне всё осталось")
    }

    @Test
    fun serverWithDataFromAnotherPhoneIsTaken() = runComposeUiTest {
        coach()
        // Первый телефон уже выгрузил свои данные (сессия «Второй телефон» — токен t-other).
        val first = TestData.ready()
        val firstSync = SyncRepository(TestData.dbOf(first), SyncApi(server.client) { url }, MemoryTokens("t-other"), MemoryPrefs(), clock = TestData.clock)
        assertTrue(runBlocking { firstSync.connect() })
        val firstCoach = runBlocking { first.coach.first()!!.id }

        val phone = Phone(TestData.ready())
        phone.signIn()
        show(phone)
        openData()
        awaitNode(hasTestTag(SYNC_CONNECT_TAG))
        onNodeWithTag(SYNC_CONNECT_TAG).performClick()
        onNodeWithTag(SYNC_AGREE_TAG).performClick()
        onNodeWithText(SyncFormat.DO_CONNECT).performClick()

        awaitNode(hasText(SyncFormat.SERVER_HAS_DATA))
        onNodeWithText(SyncFormat.TAKE).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { phone.settings.coach.first()?.id } == firstCoach }
        awaitNode(hasTestTag(SYNC_STATUS_TAG))
        assertEquals(1, server.liveRows(EMAIL, "coach").size, "Второго тренера на сервере не появилось")
    }

    @Test
    fun signInOnFirstRunLoadsDataFromServer() = runComposeUiTest {
        coach()
        val first = TestData.ready()
        val firstSync = SyncRepository(TestData.dbOf(first), SyncApi(server.client) { url }, MemoryTokens("t-other"), MemoryPrefs(), clock = TestData.clock)
        assertTrue(runBlocking { firstSync.connect() })

        val phone = Phone(TestData.fresh())
        show(phone)
        awaitNode(hasTestTag(FIRST_SIGN_IN_TAG))
        onNodeWithTag(FIRST_SIGN_IN_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_EMAIL_TAG))
        onNodeWithTag(ACCOUNT_EMAIL_TAG).performTextInput(EMAIL)
        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextInput(PASSWORD)
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()

        awaitNode(hasTestTag(FIRST_LOAD_TAG))
        onNodeWithTag(FIRST_LOAD_TAG).performClick()
        // Тренер пришёл с сервера — приложение открылось само.
        awaitNode(hasTestTag(navTag(Section.HOME)))
        assertEquals(TestData.coach.lastName, runBlocking { phone.settings.coach.first()?.lastName })
        assertTrue(phone.sync.state.value.connected)
    }

    @Test
    fun firstRunWithoutServerCopyOffersToStartOnPhone() = runComposeUiTest {
        coach(serverCopy = false)
        val phone = Phone(TestData.fresh())
        phone.signIn()
        show(phone)
        awaitNode(hasTestTag(FIRST_SIGN_IN_TAG))
        onNodeWithTag(FIRST_SIGN_IN_TAG).performClick()
        awaitNode(hasText(SyncFormat.FIRST_CLOSED))
        onNodeWithTag(FIRST_START_TAG).performClick()
        awaitNode(hasText("Как вас зовут?"))
    }

    @Test
    fun unsentForADayReplacesBackupReminder() {
        val late = homeTodos(emptyList(), null, TestData.now, sync = SyncTodo(late = true, lastSync = null))
        assertEquals(listOf(SyncFormat.LATE), late.map { it.title })
        assertEquals(SyncFormat.lateNote(null), late.single().note)
        assertTrue(homeTodos(emptyList(), null, TestData.now, sync = SyncTodo(late = false, lastSync = null)).isEmpty())
    }

    private companion object {
        const val EMAIL = "coach@example.ru"
        const val PASSWORD = "пароль-тренера"
    }
}
