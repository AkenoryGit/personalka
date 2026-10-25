package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Настройки → Данные → Аккаунт» (шаг 2.8а, ТЗ 7.3): регистрация с кодом, вход, сброс пароля, устройства, выход. */
@OptIn(ExperimentalTestApi::class)
class AccountUiTest {
    private val settings = TestData.ready()
    private val server = FakeServer()
    private val tokens = MemoryTokens()
    private val account = AccountRepository(server.api(), tokens, MemoryPrefs(), "Samsung SM-F976B") { "coach-1" }

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

    private fun ComposeUiTest.openAccount() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    backup = BackupTools(BackupRepository(TestData.dbOf(settings), MemoryPrefs(), NoFolder, clock = TestData.clock), NoFiles),
                    account = account, clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasTestTag(DATA_ROW_TAG))
        onNodeWithTag(DATA_ROW_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_ROW_TAG))
        onNodeWithTag(ACCOUNT_ROW_TAG).assertTextContains(AccountFormat.SIGNED_OUT, substring = true)
        onNodeWithTag(ACCOUNT_ROW_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_SUBMIT_TAG))
    }

    @Test
    fun registerWithCodeThenSignOut() = runComposeUiTest {
        openAccount()
        onNodeWithText(AccountFormat.REGISTER).performClick()
        // Имя подставлено из «Профиля».
        onNodeWithTag(ACCOUNT_NAME_TAG).assertTextContains("Андрей")
        onNodeWithTag(ACCOUNT_EMAIL_TAG).performTextInput("coach@example.ru")
        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextInput("короткий")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasText("Пароль — не короче 10 знаков"))
        assertTrue(server.requests.isEmpty())

        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextReplacement("длинный-пароль")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_CODE_TAG))
        onNodeWithText(AccountFormat.codeSent("coach@example.ru", 15)).assertIsDisplayed()
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).assertIsNotEnabled()

        onNodeWithTag(ACCOUNT_CODE_TAG).performTextInput("000000")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasText("Неверный код. Осталось 4 попытки"))
        onNodeWithTag(ACCOUNT_CODE_TAG).performTextReplacement(FakeServer.CODE)
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()

        // Вошли: имя, почта и это устройство в списке; закрытого не видно.
        awaitNode(hasTestTag(ACCOUNT_SIGN_OUT_TAG))
        onNodeWithText("coach@example.ru").assertIsDisplayed()
        awaitNode(hasText("Samsung SM-F976B · это устройство"))
        onNodeWithText("Открыто:", substring = true).assertDoesNotExist()

        onNodeWithTag(ACCOUNT_SIGN_OUT_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(ACCOUNT_SUBMIT_TAG))
        assertNull(tokens.read())
        assertTrue(server.sessions.isEmpty())
    }

    @Test
    fun wrongPasswordThenSignInAndCloseOtherDevice() = runComposeUiTest {
        server.signedUp("coach@example.ru", "длинный-пароль", "Андрей")
        server.admin = "coach@example.ru"
        openAccount()
        onNodeWithTag(ACCOUNT_EMAIL_TAG).performTextInput("coach@example.ru")
        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextInput("чужой-пароль-1")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasText("Неверная почта или пароль"))
        assertNull(tokens.read())

        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextReplacement("длинный-пароль")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasText("Открыто: Официально с налогом 6,5${RuText.NBSP}%, Кабинеты родителей, Копия на сервере"))
        val other = server.sessions.first { it.token == "t-other" }
        awaitNode(hasText("Второй телефон · был ${s("чт_22.10")}"))

        // «Выйти на этом устройстве» у второго телефона.
        onNodeWithTag(deviceTag(other.id)).performClick()
        onNodeWithText(AccountFormat.SIGN_OUT_DEVICE).performClick()
        awaitNode(hasText(AccountFormat.SIGNED_OUT_DEVICE))
        assertTrue(server.sessions.none { it.token == "t-other" })
        onNodeWithTag(deviceTag(other.id)).assertDoesNotExist()
    }

    @Test
    fun forgotPasswordByCode() = runComposeUiTest {
        server.signedUp("coach@example.ru", "старый-пароль-1", "Андрей")
        openAccount()
        onNodeWithTag(ACCOUNT_FORGOT_TAG).performClick()
        onNodeWithText(AccountFormat.RESET_NOTE).assertIsDisplayed()
        onNodeWithTag(ACCOUNT_EMAIL_TAG).performTextInput("coach@example.ru")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasTestTag(ACCOUNT_CODE_TAG))
        onNodeWithTag(ACCOUNT_CODE_TAG).performTextInput(FakeServer.CODE)
        onNodeWithTag(ACCOUNT_PASSWORD_TAG).performTextInput("новый-пароль-2")
        onNodeWithTag(ACCOUNT_SUBMIT_TAG).performClick()
        awaitNode(hasText(AccountFormat.PASSWORD_SAVED))
        awaitNode(hasTestTag(ACCOUNT_SIGN_OUT_TAG))
        assertEquals("новый-пароль-2", server.coaches.getValue("coach@example.ru").password)
        assertEquals(listOf("Samsung SM-F976B"), server.sessions.map { it.device })
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)
}
