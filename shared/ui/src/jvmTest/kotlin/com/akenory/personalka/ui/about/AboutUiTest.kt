package com.akenory.personalka.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.about.AppTheme
import com.akenory.personalka.domain.about.CrashLogRules
import com.akenory.personalka.domain.format.AboutFormat
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ещё → О приложении» (шаг 5.33): тема меняется сразу и запоминается, обновление, журнал ошибок. */
@OptIn(ExperimentalTestApi::class)
class AboutUiTest {
    private val settings = TestData.ready()

    /** Платформа понарошку: ответ о релизе и журнал задаёт тест. */
    private class FakeTools(var answer: String?, var log: String?) : AboutTools {
        override val build = 567
        var downloads = 0

        override suspend fun release(): String? = answer

        override fun download() {
            downloads++
        }

        override suspend fun crashLog(): String? = log

        override suspend fun clearCrashLog() {
            log = null
        }
    }

    private fun release(build: Int) = """{"name":"Последняя сборка","body":"Сборка $build (версия 0.1.$build) из коммита 391005a."}"""

    private fun ComposeUiTest.absent(tag: String) = assertTrue(onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty(), tag)

    private fun ComposeUiTest.start(
        prefs: DevicePrefs = MemoryPrefs(),
        tools: AboutTools? = null,
        share: ((String) -> Unit)? = null,
        onTheme: (AppTheme, Boolean) -> Unit = { _, _ -> },
    ) {
        setContent {
            Box(Modifier.size(412.dp, 960.dp)) {
                App(
                    versionName = "0.1.567",
                    settings = settings,
                    clients = TestData.clientsOf(settings),
                    groups = TestData.groupsOf(settings),
                    calendar = TestData.calendarOf(settings),
                    prefs = prefs,
                    shareText = share,
                    about = tools,
                    onTheme = onTheme,
                    clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(moreRowTag(MoreItem.ABOUT)))
        onNodeWithTag(moreRowTag(MoreItem.ABOUT)).performScrollTo().performClick()
        awaitNode(hasTestTag(ABOUT_THEME_TAG))
    }

    @Test
    fun themeChangesAtOnceAndIsRemembered() = runComposeUiTest {
        val prefs = MemoryPrefs()
        var dark: Boolean? = null
        start(prefs, onTheme = { _, d -> dark = d })
        onNodeWithText("0.1.567").assertExists()
        onNodeWithText(AboutFormat.theme(AppTheme.SYSTEM)).assertIsSelected()
        // Без платформы (iPhone, тесты) — только тема и версия.
        absent(ABOUT_CHECK_TAG)

        onNodeWithText(AboutFormat.theme(AppTheme.DARK)).performClick()
        waitUntil(timeoutMillis = 5_000) { dark == true }
        onNodeWithText(AboutFormat.theme(AppTheme.DARK)).assertIsSelected()
        assertEquals("dark", prefs.string(DevicePrefs.APP_THEME))

        onNodeWithText(AboutFormat.theme(AppTheme.LIGHT)).performClick()
        waitUntil(timeoutMillis = 5_000) { dark == false }
        assertEquals("light", prefs.string(DevicePrefs.APP_THEME))
    }

    @Test
    fun savedThemeOpensDark() = runComposeUiTest {
        val prefs = MemoryPrefs().apply { putString(DevicePrefs.APP_THEME, AppTheme.DARK.code) }
        var dark: Boolean? = null
        start(prefs, onTheme = { _, d -> dark = d })
        assertEquals(true, dark)
        onNodeWithText(AboutFormat.theme(AppTheme.DARK)).assertIsSelected()
    }

    @Test
    fun newerBuildCanBeDownloaded() = runComposeUiTest {
        val tools = FakeTools(release(570), null)
        start(tools = tools)
        // Журнал пустой — его не видно.
        absent(ABOUT_CLEAR_TAG)
        absent(ABOUT_DOWNLOAD_TAG)
        onNodeWithTag(ABOUT_CHECK_TAG).performClick()
        awaitNode(hasTestTag(ABOUT_DOWNLOAD_TAG))
        onNodeWithText(AboutFormat.available(570)).assertExists()
        absent(ABOUT_CHECK_TAG)
        onNodeWithTag(ABOUT_DOWNLOAD_TAG).performClick()
        assertEquals(1, tools.downloads)
    }

    @Test
    fun freshAndNoConnection() = runComposeUiTest {
        val tools = FakeTools(release(567), null)
        start(tools = tools)
        onNodeWithTag(ABOUT_CHECK_TAG).performClick()
        awaitNode(hasText(AboutFormat.FRESH))
        absent(ABOUT_DOWNLOAD_TAG)

        tools.answer = null
        onNodeWithTag(ABOUT_CHECK_TAG).performClick()
        awaitNode(hasText(AboutFormat.FAILED))
        absent(ABOUT_DOWNLOAD_TAG)
    }

    @Test
    fun crashLogIsSentAndCleared() = runComposeUiTest {
        val log = CrashLogRules.add(
            CrashLogRules.add(null, "Версия 0.1.560 · пт 20.11\n\njava.lang.IllegalStateException: раз"),
            "Версия 0.1.567 · сб 21.11\n\njava.lang.IllegalStateException: два",
        )
        val tools = FakeTools(null, log)
        var shared: String? = null
        start(tools = tools, share = { shared = it })
        awaitNode(hasText(AboutFormat.crashes(log)))

        onNodeWithTag(ABOUT_SEND_TAG).performScrollTo().performClick()
        val text = shared.orEmpty()
        assertTrue(text.startsWith("Персоналка 0.1.567 — журнал ошибок"), text)
        assertTrue(text.contains("IllegalStateException: два"), text)

        onNodeWithTag(ABOUT_CLEAR_TAG).performScrollTo().performClick()
        awaitNode(hasText(AboutFormat.CLEARED))
        absent(ABOUT_CLEAR_TAG)
        waitUntil(timeoutMillis = 5_000) { tools.log == null }
    }

    /**
     * Экран целиком в обеих темах: найдена новая сборка, в журнале две ошибки. С `ABOUT_SHOTS=папка` картинки
     * сохраняются (about-light.png, about-dark.png) — чтобы посмотреть экран без телефона.
     */
    @Test
    fun wholeScreenInBothThemes() {
        for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK)) runComposeUiTest {
            val prefs = MemoryPrefs().apply { putString(DevicePrefs.APP_THEME, theme.code) }
            val log = CrashLogRules.add(CrashLogRules.add(null, "Версия 0.1.560\n\nраз"), "Версия 0.1.567\n\nдва")
            start(prefs, FakeTools(release(570), log), share = {})
            onNodeWithTag(ABOUT_CHECK_TAG).performClick()
            awaitNode(hasTestTag(ABOUT_DOWNLOAD_TAG))
            awaitNode(hasTestTag(ABOUT_SEND_TAG))
            val image = onRoot().captureToImage()
            System.getenv("ABOUT_SHOTS")?.let { dir ->
                val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(dir, "about-${theme.code}.png").writeBytes(png)
            }
        }
    }
}
