package com.akenory.personalka.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Сообщение с «Отменить» (ТЗ 5.20): одно за раз, отмена срабатывает один раз и только у своего сообщения. */
class MessageStateTest {

    @Test
    fun undoRunsOnceAndHidesMessage() {
        val messages = MessageState()
        var undone = 0
        messages.showUndo("Тренировка отменена") { undone++ }
        val id = messages.current!!.id
        assertEquals("Отменить", messages.current!!.actionLabel)

        messages.act(id)
        messages.act(id)

        assertEquals(1, undone)
        assertNull(messages.current)
    }

    @Test
    fun newMessageReplacesOldAndOldActionStaysDone() {
        val messages = MessageState()
        var undoneFirst = 0
        var undoneSecond = 0
        messages.showUndo("Перенесено на вт 22.10, 17:15") { undoneFirst++ }
        val first = messages.current!!.id
        messages.showUndo("Тренировка отменена") { undoneSecond++ }

        // Опоздавшее нажатие на первое и его истёкшее время ничего не трогают.
        messages.act(first)
        messages.expire(first)

        assertEquals("Тренировка отменена", messages.current!!.text)
        assertEquals(0, undoneFirst)
        assertEquals(0, undoneSecond)
    }

    @Test
    fun expireHidesWithoutUndo() {
        val messages = MessageState()
        var undone = 0
        messages.showUndo("Отменено") { undone++ }
        messages.expire(messages.current!!.id)
        assertNull(messages.current)
        assertEquals(0, undone)
    }

    @Test
    fun plainMessageHasNoButton() {
        val messages = MessageState()
        messages.show("Сохранено")
        assertNull(messages.current!!.actionLabel)
        messages.dismiss()
        assertNull(messages.current)
    }
}
