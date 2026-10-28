package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.money.SptCase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Строки окон «Нужно сделать» шага 3.16. */
class TodoListFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun confirm() {
        assertTrue(TodoListFormat.CONFIRM_WHY.contains(s("ниже 75_%")))
        assertEquals("сегодня 17:00", TodoListFormat.today(LocalDateTime(2026, 10, 20, 17, 0)))
        assertEquals("ПТ · ББ", TodoListFormat.typePlace("ПТ", "ББ"))
        assertEquals("ПТ", TodoListFormat.typePlace("ПТ", ""))
        assertEquals(s("Надёжность 38_% · 19_отмен из 31"), TodoListFormat.reliability(Reliability(done = 12, cancelled = 19)))
        assertEquals(s("Надёжность: новый · 2_тренировки"), TodoListFormat.reliability(Reliability(done = 1, cancelled = 1)))
        assertEquals("Орлов М. придёт", TodoListFormat.comingMessage("Орлов М."))
    }

    @Test
    fun followupAndAbsent() {
        assertTrue(TodoListFormat.followupWhy(14).contains(s("за последние 14_дней")))
        assertTrue(TodoListFormat.followupWhy(14).contains(s("на 3_дня")))
        assertTrue(TodoListFormat.ABSENT_WHY.contains("не были 30 дней"))
    }

    @Test
    fun spt() {
        val case = SptCase("s1", "k1", "t1", "ty", "l", LocalDate(2026, 10, 21), windowUntil = LocalDate(2026, 11, 5))
        assertEquals(s("ср_21.10 · СПТ · ББ · окно до 05.11"), TodoListFormat.sptLine(case, "СПТ", "ББ"))
        assertEquals(s("ещё 8_дней"), TodoListFormat.sptLeft(LocalDate(2026, 11, 5), LocalDate(2026, 10, 28)))
        assertEquals(s("ещё 1_день"), TodoListFormat.sptLeft(LocalDate(2026, 11, 5), LocalDate(2026, 11, 4)))
        assertEquals("последний день", TodoListFormat.sptLeft(LocalDate(2026, 11, 5), LocalDate(2026, 11, 5)))
        assertEquals(s("доплата за покупку 500_₽"), TodoListFormat.sptBonus(50_000))
        assertEquals("", TodoListFormat.sptBonus(0))
    }

    @Test
    fun diaryGroupsConsents() {
        val today = LocalDate(2026, 10, 28)
        assertEquals("сегодня 09:00", TodoListFormat.at(LocalDateTime(2026, 10, 28, 9, 0), today))
        assertEquals(s("пн_26.10 18:00"), TodoListFormat.at(LocalDateTime(2026, 10, 26, 18, 0), today))
        assertEquals("удалить до 12.11", TodoListFormat.deleteBy(LocalDate(2026, 11, 12)))
    }
}
