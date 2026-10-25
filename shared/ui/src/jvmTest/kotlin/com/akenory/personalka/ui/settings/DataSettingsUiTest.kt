package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.backup.BackupFolder
import com.akenory.personalka.data.backup.BackupRepository
import com.akenory.personalka.data.backup.UpdateCopies
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.BackupFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ещё → Настройки → Данные» (шаг 1.15, ТЗ 5.16, 7.6): пароль, копия в папку, восстановление, календарь. */
@OptIn(ExperimentalTestApi::class)
class DataSettingsUiTest {
    private val settings = TestData.ready()
    private val folder = Folder()
    private val files = Files()
    private val backup = BackupRepository(TestData.dbOf(settings), MemoryPrefs(), folder, clock = TestData.clock)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private class Folder : BackupFolder {
        val files = mutableMapOf<String, ByteArray>()

        override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean {
            files[name] = bytes
            return true
        }

        override suspend fun list(folder: String): List<String> = files.keys.toList()

        override suspend fun delete(folder: String, name: String) {
            files.remove(name)
        }

        override suspend fun read(folder: String, name: String): ByteArray? = files[name]
    }

    /** Системные окна: папка выбирается сразу, файл — [file], «Поделиться» запоминает имя. */
    private class Files : PlatformFiles {
        var file: ByteArray? = null
        val shared = mutableListOf<String>()

        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) = onPicked("content://copies", "Копии")

        override fun openFile(onOpened: (ByteArray) -> Unit) {
            file?.let(onOpened)
        }

        override fun share(name: String, mime: String, bytes: ByteArray) {
            shared += name
        }
    }

    private fun ComposeUiTest.openData() {
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
        awaitNode(hasTestTag(DATA_ROW_TAG))
        onNodeWithText("Папка для копий не выбрана").assertIsDisplayed()
        onNodeWithTag(DATA_ROW_TAG).performClick()
        awaitNode(hasTestTag(CREATE_NOW_TAG))
    }

    @Test
    fun firstCopyAsksPasswordAndFolderThenRestores() = runComposeUiTest {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    backup = BackupTools(backup, files), clock = TestData.clock,
                )
            }
        }
        openData()
        onNodeWithText(BackupFormat.NO_COPIES).assertIsDisplayed()
        onNodeWithTag(UPDATE_COPY_TAG).assertDoesNotExist()

        // Шифрование включено, пароля нет: «Создать копию сейчас» сначала просит пароль.
        onNodeWithTag(CREATE_NOW_TAG).performScrollTo().performClick()
        onNodeWithText(BackupFormat.PASSWORD_WARNING).assertIsDisplayed()
        onNodeWithTag(PASSWORD_FIELD_TAG).performTextInput("секрет-123")
        onNodeWithTag(PASSWORD_REPEAT_TAG).performTextInput("секрет-12")
        onNodeWithText(BackupFormat.PASSWORD_SAVE).assertIsNotEnabled()
        onNodeWithTag(PASSWORD_REPEAT_TAG).performTextInput("3")
        onNodeWithText(BackupFormat.PASSWORD_SAVE).performClick()

        // Потом папка — и копия в ней.
        waitUntil(timeoutMillis = 10_000) { folder.files.isNotEmpty() }
        assertEquals(listOf("trener-backup-20261020-1540.zip"), folder.files.keys.toList())
        awaitNode(hasText(s("Последняя копия — вт_20.10_15:40")))
        onNodeWithText("Копии").assertIsDisplayed()

        // Восстановление своей копии: пароль не нужен, предпросмотр и «Заменить данные».
        files.file = folder.files.values.single()
        onNodeWithTag(RESTORE_TAG).performScrollTo().performClick()
        awaitNode(hasText(BackupFormat.RESTORE_CONFIRM))
        onNodeWithText(s("Копия от 20.10.2026_15:40")).assertIsDisplayed()
        onNodeWithText(BackupFormat.RESTORE_WARNING).assertIsDisplayed()
        onNodeWithText(BackupFormat.RESTORE_CONFIRM).performClick()
        awaitNode(hasText("Данные восстановлены из копии от 20.10.2026"))

        // Выгрузка в календарь — через «Поделиться». Внизу экрана ещё видно сообщение — нажатие действием кнопки.
        onNodeWithTag(CALENDAR_EXPORT_TAG).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { files.shared.isNotEmpty() }
        assertTrue(files.shared.single().endsWith(".ics"))

        // Выгрузка в Excel (3.8в) — тоже через «Поделиться», файл книги на день клуба.
        onNodeWithTag(EXCEL_EXPORT_TAG).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { files.shared.size == 2 }
        assertEquals("trener-data-20261020.xlsx", files.shared.last())
    }

    /** Копия перед обновлением базы (2.7б) лежит в папке приложения: своя кнопка, дальше — как обычное восстановление. */
    @Test
    fun copyBeforeUpdateIsRestoredFromAppFolder() = runComposeUiTest {
        val updates = Folder()
        val prefs = MemoryPrefs().apply {
            putString("backup.updateName", "trener-backup-20261019-0905.zip")
            putString("backup.updateAt", "2026-10-19T09:05")
        }
        val withCopy = BackupRepository(TestData.dbOf(settings), prefs, folder, UpdateCopies(updates, "update-backups"), TestData.clock)
        val plain = BackupRepository(TestData.dbOf(settings), MemoryPrefs().apply { putBoolean("backup.encrypt", false) }, folder, clock = TestData.clock)
        updates.files["trener-backup-20261019-0905.zip"] = runBlocking { plain.create()!!.bytes }
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    backup = BackupTools(withCopy, files), clock = TestData.clock,
                )
            }
        }
        openData()
        onNodeWithTag(UPDATE_COPY_TAG).performScrollTo().assertIsDisplayed()
        onNodeWithText(BackupFormat.updateCopyNote(LocalDateTime(2026, 10, 19, 9, 5))).assertIsDisplayed()
        onNodeWithTag(UPDATE_COPY_TAG).performClick()
        awaitNode(hasText(BackupFormat.RESTORE_CONFIRM))
        onNodeWithText(BackupFormat.RESTORE_CONFIRM).performClick()
        awaitNode(hasText("Данные восстановлены из копии от 20.10.2026"))
    }

    @Test
    fun wrongFileIsNotRestored() = runComposeUiTest {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    backup = BackupTools(backup, files), clock = TestData.clock,
                )
            }
        }
        openData()
        files.file = "не копия".encodeToByteArray()
        onNodeWithTag(RESTORE_TAG).performScrollTo().performClick()
        awaitNode(hasText(BackupFormat.NOT_BACKUP))
    }
}
