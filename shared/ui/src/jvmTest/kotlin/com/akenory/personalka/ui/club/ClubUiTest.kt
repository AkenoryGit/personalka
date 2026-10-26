package com.akenory.personalka.ui.club

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.AccountResult
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.ClubFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ещё → Клуб» (шаг 5.2, ТЗ 5.17, 1.5): тренеры сервера и приостановка — только у администратора. */
@OptIn(ExperimentalTestApi::class)
class ClubUiTest {
    private val settings = TestData.ready()
    private val server = FakeServer()
    private val account = AccountRepository(server.api(), MemoryTokens(), MemoryPrefs(), "Samsung SM-F976B") { "coach-1" }

    private fun signIn(email: String) = runBlocking {
        assertEquals(AccountResult.Done, account.login(email, "длинный-пароль"))
    }

    private fun ComposeUiTest.openMore() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    account = account, clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.SETTINGS)))
    }

    @Test
    fun adminSuspendsAndResumesColleague() = runComposeUiTest {
        server.signedUp("boss@example.ru", "длинный-пароль", "Дмитрий")
        val lena = server.signedUp("lena@example.ru", "длинный-пароль", "Лена Смирнова")
        server.admin = "boss@example.ru"
        signIn("boss@example.ru")
        openMore()
        onNodeWithTag(moreRowTag(MoreItem.CLUB)).performClick()

        awaitNode(hasTestTag(coachTag(lena.id)))
        onNodeWithText("Дмитрий · вы").assertExists()
        onNodeWithTag(coachTag(lena.id)).assertTextContains("lena@example.ru", substring = true)
        onNodeWithText(ClubFormat.PRIVATE).assertExists()

        onNodeWithTag(coachTag(lena.id)).performClick()
        onNodeWithText(ClubFormat.SUSPEND_NOTE).assertExists()
        onNodeWithText(ClubFormat.SUSPEND).performClick()
        awaitNode(hasText(ClubFormat.SUSPENDED_DONE))
        assertTrue("lena@example.ru" in server.suspended)
        onNodeWithTag(coachTag(lena.id)).assertTextContains("Доступ приостановлен", substring = true)

        onNodeWithTag(coachTag(lena.id)).performClick()
        onNodeWithText(ClubFormat.RESUME).performClick()
        awaitNode(hasText(ClubFormat.RESUMED_DONE))
        assertTrue(server.suspended.isEmpty())
    }

    @Test
    fun colleagueHasNoClub() = runComposeUiTest {
        server.signedUp("boss@example.ru", "длинный-пароль", "Дмитрий")
        server.signedUp("lena@example.ru", "длинный-пароль", "Лена Смирнова")
        server.admin = "boss@example.ru"
        signIn("lena@example.ru")
        openMore()
        onNodeWithTag(moreRowTag(MoreItem.CLUB)).assertDoesNotExist()
        assertTrue(server.requests.none { it.first.startsWith("/admin") })
    }
}
