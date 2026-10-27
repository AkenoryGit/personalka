package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Аватарки без фото (шаг 3.13): инициалы, цвет, возраст и пол. Имена придуманы. */
class AvatarRulesTest {
    private val today = LocalDate(2026, 10, 26)

    @Test
    fun initialsAreFirstNameThenLastName() {
        assertEquals("АД", AvatarRules.initials("Дельфенурова", "Аделина"))
        assertEquals("ЁК", AvatarRules.initials(" ковалёв", "ёжик"))
        assertEquals("К", AvatarRules.initials("Ковалёв", ""))
        assertEquals("?", AvatarRules.initials("", " "))
    }

    @Test
    fun toneIsStableAndInRange() {
        val a = AvatarRules.tone("Ковалёв", "Артём")
        assertEquals(a, AvatarRules.tone("ковалев", "артём"))
        val all = listOf("Иванова Мария", "Петров Иван", "Сидоров Олег", "Лебедева Анна", "Ким Ли", "Орлов Глеб")
            .map { val (l, f) = it.split(" "); AvatarRules.tone(l, f) }
        assertTrue(all.all { it in 0 until AvatarRules.TONES })
        assertTrue(all.toSet().size > 1)
    }

    @Test
    fun ageGroupsByBirthDate() {
        assertEquals(AvatarAge.BABY, AvatarRules.age(LocalDate(2026, 3, 1), false, today))
        assertEquals(AvatarAge.BABY, AvatarRules.age(LocalDate(2022, 10, 27), false, today))
        assertEquals(AvatarAge.KID, AvatarRules.age(LocalDate(2022, 10, 26), false, today))
        assertEquals(AvatarAge.KID, AvatarRules.age(LocalDate(2015, 5, 12), false, today))
        assertEquals(AvatarAge.KID, AvatarRules.age(LocalDate(2014, 10, 27), false, today))
        assertEquals(AvatarAge.TEEN, AvatarRules.age(LocalDate(2014, 10, 26), false, today))
        assertEquals(AvatarAge.TEEN, AvatarRules.age(LocalDate(2008, 10, 27), false, today))
        assertEquals(AvatarAge.ADULT, AvatarRules.age(LocalDate(2008, 10, 26), false, today))
        assertEquals(AvatarAge.SENIOR, AvatarRules.age(LocalDate(1966, 1, 1), false, today))
    }

    @Test
    fun withoutBirthDateAgeFollowsAdultMark() {
        assertEquals(AvatarAge.KID, AvatarRules.age(null, false, today))
        assertEquals(AvatarAge.ADULT, AvatarRules.age(null, true, today))
    }

    @Test
    fun sexFromCardOrGuessed() {
        val girl = AvatarRules.client(ClientName("Дельфенурова", "Аделина"), null, LocalDate(2017, 5, 12), false, today)
        assertEquals(Sex.FEMALE, girl.sex)
        assertEquals("АД", girl.initials)
        assertEquals(AvatarAge.KID, girl.age)
        val set = AvatarRules.client(ClientName("Дельфенурова", "Аделина"), Sex.MALE, null, false, today)
        assertEquals(Sex.MALE, set.sex)
        assertNull(AvatarRules.client(ClientName("Ким", "Саша"), null, null, false, today).sex)
    }

    @Test
    fun coachHasOwnFigure() {
        val coach = AvatarRules.coach("Соколов", "Пётр", "Ильич")
        assertEquals(AvatarAge.COACH, coach.age)
        assertEquals(Sex.MALE, coach.sex)
        assertEquals("ПС", coach.initials)
    }
}
