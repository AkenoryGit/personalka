package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate

// Шаг 3.13, ТЗ 5.8, 5.9, 5.16. Аватарка без фото: рисованный пловец в шапочке с инициалами.

/**
 * Кого рисует аватарка без фото (шаг 3.13а): малыш до 3 лет, ребёнок 4–11, подросток 12–17, взрослый 18+
 * (с 60 — в очках) — и сам тренер.
 */
enum class AvatarAge { BABY, KID, TEEN, ADULT, SENIOR, COACH }

/**
 * Аватарка без фото (шаг 3.13): [initials] на шапочке, цвет [tone] из [AvatarRules.TONES] — один и тот же
 * у человека всегда, фигура — по возрасту [age] и полу [sex] (`null` — не указан и не угадан).
 */
data class AvatarLook(val initials: String, val tone: Int, val age: AvatarAge, val sex: Sex?)

/** Аватарки по умолчанию: инициалы, цвет и фигура (шаг 3.13). */
object AvatarRules {
    /** Сколько цветов у аватарок; оранжевого среди них нет (ТЗ 10.4: он — ровно в пяти местах). */
    const val TONES = 12

    /** До 3 лет включительно — малыш, с 4 — ребёнок (шаг 3.13а). */
    const val BABY_UNTIL = 4

    /** С 12 лет — подросток. */
    const val TEEN_AGE = 12

    /** С 60 лет — в очках. */
    const val SENIOR_AGE = 60

    /** Ключ фото тренера в папке аватарок; у клиентов ключ — id клиента. */
    const val COACH_KEY = "coach"

    /** «АД» у «Дельфенурова Аделина»: первая буква имени и фамилии; нет одной — одна буква, нет обеих — «?». */
    fun initials(lastName: String, firstName: String): String =
        listOf(firstName, lastName).mapNotNull { part -> part.firstOrNull { it.isLetter() }?.uppercaseChar() }
            .joinToString("").ifEmpty { "?" }

    /** Цвет по ФИО — одинаковый на любом телефоне и после перезапуска: не `hashCode`, а своя сумма. */
    fun tone(lastName: String, firstName: String): Int {
        val text = ClientRules.key("$lastName $firstName")
        var sum = 7
        for (c in text) sum = (sum * 31 + c.code) and 0x7FFFFFFF
        return sum % TONES
    }

    /** Возраст для рисунка: без даты рождения — как отмечен «Взрослый» (3.5), иначе ребёнок. */
    fun age(birth: LocalDate?, manualAdult: Boolean, today: LocalDate): AvatarAge {
        if (birth == null) return if (manualAdult) AvatarAge.ADULT else AvatarAge.KID
        val years = ClientRules.age(birth, today)
        return when {
            years >= SENIOR_AGE -> AvatarAge.SENIOR
            years >= ClientRules.ADULT_AGE -> AvatarAge.ADULT
            years >= TEEN_AGE -> AvatarAge.TEEN
            years >= BABY_UNTIL -> AvatarAge.KID
            else -> AvatarAge.BABY
        }
    }

    /** Аватарка клиента: пол не указан — угадывается по отчеству и фамилии, как подсказка в форме (5.8). */
    fun client(
        name: ClientName,
        sex: Sex?,
        birth: LocalDate?,
        manualAdult: Boolean,
        today: LocalDate,
    ): AvatarLook = AvatarLook(
        initials = initials(name.lastName, name.firstName),
        tone = tone(name.lastName, name.firstName),
        age = age(birth, manualAdult, today),
        sex = sex ?: ClientRules.guessSex(name.middleName, name.lastName)?.sex,
    )

    /** Аватарка тренера — со свистком; пол у тренера не вводится, угадывается по отчеству и фамилии. */
    fun coach(lastName: String, firstName: String, middleName: String?): AvatarLook = AvatarLook(
        initials = initials(lastName, firstName),
        tone = tone(lastName, firstName),
        age = AvatarAge.COACH,
        sex = ClientRules.guessSex(middleName, lastName)?.sex,
    )
}
