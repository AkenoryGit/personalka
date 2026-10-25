package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.money.PurchaseProblem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки СПТ шага 3.7а — по макетам ClCardInfo и HomeEvening. */
class SptFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)
    private val spt = LocalDate(2026, 10, 12)
    private val until = LocalDate(2026, 11, 12)

    @Test
    fun infoBlock() {
        assertEquals(s("пн_12.10 · СПТ · ББ"), SptFormat.line(spt, "СПТ", "ББ"))
        assertEquals("Ждём покупку блока до 12.11", SptFormat.status(SptCaseStatus.OPEN, until, null))
        assertEquals("Блок куплен 20.10", SptFormat.status(SptCaseStatus.CLOSED, until, LocalDate(2026, 10, 20)))
        assertEquals("Блок со скидкой не куплен до 12.11", SptFormat.status(SptCaseStatus.NOT_CLOSED, until, null))
    }

    @Test
    fun purchasePanel() {
        assertEquals(s("СПТ пн_12.10 · скидка до 12.11 включительно"), SptFormat.window(spt, until))
        assertEquals(s("Доплата 140_₽ — в зарплату за ноябрь"), SptFormat.bonusNote(14_000, LocalDate(2026, 11, 3)))
        assertEquals("Не раньше СПТ — 12.10", SptFormat.problem(PurchaseProblem.BEFORE_SPT, spt, until))
        assertEquals("Скидка действует до 12.11: позже — обычный блок", SptFormat.problem(PurchaseProblem.AFTER_WINDOW, spt, until))
    }

    @Test
    fun askAndTodo() {
        assertEquals("Блок был куплен со скидкой до 12.11?", SptFormat.askRetro(until))
        assertEquals(s("Орлов М. · СПТ_пн_12.10"), SptFormat.askNote("Орлов М.", spt))
        assertEquals(s("ближайший срок 12.11 · Яковлева_Т."), SptFormat.todoNote(until, ClientName("Яковлева", "Татьяна")))
    }
}
