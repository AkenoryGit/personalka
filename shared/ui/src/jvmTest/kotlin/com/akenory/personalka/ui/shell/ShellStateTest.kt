package com.akenory.personalka.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Куда ведут разделы, «Назад» и повторное нажатие (ТЗ 5.1) и что состояние сохраняется целиком. */
class ShellStateTest {

    @Test
    fun startsOnHomeWithNothingOpen() {
        val shell = ShellState()
        assertEquals(Section.HOME, shell.section)
        assertNull(shell.top())
        assertFalse(shell.canGoBack)
        assertFalse(shell.back(), "С «Главной» «Назад» сворачивает приложение")
    }

    @Test
    fun eachSectionKeepsItsOwnScreens() {
        val shell = ShellState()
        shell.select(Section.MORE)
        shell.open("settings")
        shell.select(Section.CALENDAR)
        assertNull(shell.top(), "В «Календаре» ничего не открыто")
        shell.select(Section.MORE)
        assertEquals("settings", shell.top(), "«Ещё» открыт там же, где был")
    }

    @Test
    fun backClosesScreenThenGoesHome() {
        val shell = ShellState()
        shell.select(Section.MORE)
        shell.open("groups")
        shell.open("group/1")
        assertTrue(shell.back())
        assertEquals("groups", shell.top())
        assertTrue(shell.back())
        assertNull(shell.top())
        assertEquals(Section.MORE, shell.section)
        assertTrue(shell.back())
        assertEquals(Section.HOME, shell.section)
        assertFalse(shell.canGoBack)
    }

    @Test
    fun reselectingSectionReturnsToItsStart() {
        val shell = ShellState()
        shell.select(Section.MORE)
        shell.open("settings")
        shell.select(Section.MORE)
        assertNull(shell.top())
        assertEquals(Section.MORE, shell.section)
    }

    @Test
    fun replaceTopDoesNotPileUpScreens() {
        // На разложенном Fold пункты «Ещё» меняются справа: «Назад» ведёт к списку, а не к прошлому пункту.
        val shell = ShellState()
        shell.select(Section.MORE)
        shell.replaceTop("groups")
        shell.replaceTop("settings")
        assertEquals(listOf("settings"), shell.stack())
        shell.back()
        assertNull(shell.top())
    }

    @Test
    fun stateSurvivesSaveAndRestore() {
        val shell = ShellState()
        shell.select(Section.CLIENTS)
        shell.open("client/0193")
        shell.select(Section.MORE)
        shell.open("settings")

        val scope = SaverScope { true }
        val saved = with(ShellState.Saver) { scope.save(shell) }
        assertNotNull(saved)
        val restored = assertNotNull(ShellState.Saver.restore(saved))

        assertEquals(Section.MORE, restored.section)
        assertEquals(listOf("settings"), restored.stack(Section.MORE))
        assertEquals(listOf("client/0193"), restored.stack(Section.CLIENTS))
        assertEquals(emptyList(), restored.stack(Section.CALENDAR))
    }
}
