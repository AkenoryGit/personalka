package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.akenory.personalka.data.sync.Attach
import com.akenory.personalka.data.sync.SignOutCheck
import com.akenory.personalka.data.sync.SyncApi
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.format.SyncFormat
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.home.SyncTodo
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.setup.FIRST_SIGN_IN_TAG
import com.akenory.personalka.ui.setup.FIRST_START_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Синхронизация на экранах (шаги 2.10а, 5.1а; ТЗ 7.2, 1.5, 5.18): вход сам включает копию, «Выйти» отправляет правки
 * и может убрать данные с телефона, «Включить копию», «Синхронизировать», «Удалить копию с сервера»; данные на сервере
 * с другого телефона; данные другого аккаунта на телефоне; «Войти» на первом запуске. Сервер — в памяти.
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
        val sync = SyncRepository(
            TestData.dbOf(settings), SyncApi(server.client) { url }, tokens, prefs, owner = { account.account.value?.email }, clock = TestData.clock,
        )
        val tools = SyncTools(sync) { CoroutineScope(Dispatchers.Default).launch { sync.sync() } }

        fun signIn(email: String = EMAIL) = runBlocking { account.login(email, PASSWORD) }
    }

    private fun coach(serverCopy: Boolean = true, email: String = EMAIL) = server.signedUp(email, PASSWORD).also {
        if (serverCopy) server.serverCopy += email
    }

    /** Вход формой «Аккаунта» — из «Данных». */
    private fun ComposeUiTest.signInHere(email: String = EMAIL) {
        onNodeWithTag(ACCOUNT_ROW_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_EMAIL_TAG))
        onNodeWithTag(ACCOUNT_EMAIL_TAG).performTextInput(email)
        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextInput(PASSWORD)
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
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
    fun signInTurnsOnCopyAndSignOutCanWipeThePhone() = runComposeUiTest {
        coach()
        val phone = Phone(TestData.ready())
        show(phone)
        openData()
        // Без входа раздела «Сервер» нет.
        onNodeWithText(SyncFormat.SECTION).assertDoesNotExist()

        // Вход сам включает копию: условия — строкой у формы, данные телефона ушли на сервер.
        signInHere()
        waitUntil(timeoutMillis = 5_000) { server.liveRows(EMAIL, "coach").isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { !phone.sync.state.value.running && phone.sync.state.value.lastSync != null }
        assertTrue(phone.sync.state.value.connected)
        assertEquals(TestData.coach.firstName, server.liveRows(EMAIL, "coach").single().data["firstName"].toString().trim('"'))

        // «Выйти»: все правки на сервере — можно убрать данные с телефона.
        awaitNode(hasTestTag(ACCOUNT_SIGN_OUT_TAG))
        onNodeWithTag(ACCOUNT_SIGN_OUT_TAG).performScrollTo().performClick()
        awaitNode(hasText(SyncFormat.SIGN_OUT_SENT))
        onNodeWithTag(SYNC_WIPE_TAG).performClick()
        onNodeWithText(SyncFormat.WIPE_NOTE).assertExists()
        onNode(hasText(SyncFormat.SIGN_OUT_DO) and hasClickAction() and hasAnyAncestor(hasTestTag(PANEL_TAG))).performClick()

        // Телефон — как после установки, на сервере всё осталось.
        awaitNode(hasTestTag(FIRST_SIGN_IN_TAG))
        assertEquals(null, runBlocking { phone.settings.coach.first() })
        assertEquals(null, phone.account.account.value)
        assertEquals(1, server.liveRows(EMAIL, "coach").size)
        assertTrue(!phone.sync.state.value.connected)
    }

    @Test
    fun signOutWithoutConnectionKeepsThePhone() = runComposeUiTest {
        coach()
        val phone = Phone(TestData.ready())
        phone.signIn()
        assertTrue(runBlocking { phone.sync.attach() } == Attach.Done)
        show(phone)
        openData()
        server.offline = true
        // Правка без связи — на сервер не ушла.
        runBlocking { phone.settings.saveName(CoachName(TestData.coach.lastName, "Андрюша")) }
        onNodeWithTag(ACCOUNT_ROW_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_SIGN_OUT_TAG))
        onNodeWithTag(ACCOUNT_SIGN_OUT_TAG).performScrollTo().performClick()
        awaitNode(hasText("Не ушло на сервер правок", substring = true))
        onNodeWithTag(SYNC_WIPE_TAG).assertDoesNotExist()
        onNode(hasText(SyncFormat.SIGN_OUT_DO) and hasClickAction() and hasAnyAncestor(hasTestTag(PANEL_TAG))).performClick()
        awaitNode(hasTestTag(ACCOUNT_SUBMIT_TAG))
        assertEquals("Андрюша", runBlocking { phone.settings.coach.first()?.firstName }, "Данные в телефоне на месте")
    }

    @Test
    fun turnOnCopyLaterThenSyncAndDeleteIt() = runComposeUiTest {
        coach()
        val phone = Phone(TestData.ready())
        // Вошли до шага 5.1а: копия ещё не включена.
        phone.signIn()
        show(phone)
        openData()

        awaitNode(hasTestTag(SYNC_CONNECT_TAG))
        onNodeWithText(SyncFormat.COPY_OFF).assertExists()
        onNodeWithTag(SYNC_CONNECT_TAG).performClick()
        awaitNode(hasTestTag(SYNC_STATUS_TAG))
        waitUntil(timeoutMillis = 5_000) { server.liveRows(EMAIL, "coach").isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { !phone.sync.state.value.running && phone.sync.state.value.lastSync != null }
        onNodeWithTag(SYNC_STATUS_TAG).assertTextContains(SyncFormat.SYNCED, substring = true)

        // Нет связи: «Синхронизировать» показывает причину, данные в телефоне на месте.
        server.offline = true
        onNodeWithTag(SYNC_NOW_TAG).performClick()
        awaitNode(hasTestTag(SYNC_PROBLEM_TAG))
        onNodeWithTag(SYNC_PROBLEM_TAG).assertTextContains("Нет связи", substring = true)
        server.offline = false

        onNodeWithTag(SYNC_DISCONNECT_TAG).performClick()
        onNodeWithText(SyncFormat.DISCONNECT_TEXT).assertExists()
        onNodeWithText(SyncFormat.DISCONNECT_CONFIRM).performClick()
        // Копия стёрта, аккаунт вышел (сервер закрыл все входы), раздела «Сервер» нет, в телефоне всё осталось.
        waitUntil(timeoutMillis = 5_000) { phone.account.account.value == null }
        // Экран перерисовывается после выхода не сразу — ждём, пока раздел пропадёт (в Actions бывало раньше времени).
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SyncFormat.SECTION).fetchSemanticsNodes().isEmpty() }
        onNodeWithText(SyncFormat.SECTION).assertDoesNotExist()
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

        // Этот телефон начинал сам: его данные с данными сервера не сливаются, их можно только заменить.
        val phone = Phone(TestData.ready())
        show(phone)
        openData()
        signInHere()

        awaitNode(hasText(SyncFormat.SERVER_HAS_DATA))
        onNodeWithText(SyncFormat.TAKE).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { phone.settings.coach.first()?.id } == firstCoach }
        assertEquals(1, server.liveRows(EMAIL, "coach").size, "Второго тренера на сервере не появилось")
        assertTrue(phone.sync.state.value.connected)
    }

    @Test
    fun otherAccountDataIsReplacedOnlyWhenAllOnServer() = runComposeUiTest {
        coach()
        val other = coach(email = OTHER)
        // У второго аккаунта своя копия на сервере — с его телефона.
        server.sessions += FakeServer.Session(200, other, "Его телефон", "t-his")
        val his = TestData.ready()
        val hisSync = SyncRepository(TestData.dbOf(his), SyncApi(server.client) { url }, MemoryTokens("t-his"), MemoryPrefs(), clock = TestData.clock)
        assertTrue(runBlocking { hisSync.connect() })
        val hisCoach = runBlocking { his.coach.first()!!.id }

        // На этом телефоне — данные первого аккаунта, все на сервере: вышли через «Выйти» без стирания.
        val phone = Phone(TestData.ready())
        phone.signIn()
        assertTrue(runBlocking { phone.sync.attach() } == Attach.Done)
        assertEquals(SignOutCheck.SENT, runBlocking { phone.sync.beforeSignOut() })
        runBlocking { phone.account.logout() }

        show(phone)
        openData()
        signInHere(OTHER)
        awaitNode(hasText(SyncFormat.otherAccount(EMAIL, complete = true)))
        onNodeWithText(SyncFormat.REPLACE).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { phone.settings.coach.first()?.id } == hisCoach }
        assertEquals(1, server.liveRows(EMAIL, "coach").size, "Данные первого аккаунта на сервере целы")
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

        // Данные загружаются сами — тренер пришёл с сервера, приложение открылось само.
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
        const val OTHER = "colleague@example.ru"
    }
}
