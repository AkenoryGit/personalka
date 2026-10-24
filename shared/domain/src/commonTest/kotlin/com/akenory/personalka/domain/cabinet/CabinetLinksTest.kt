package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.CabinetLinkFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ссылка кабинета в приложении (шаг 2.13, ТЗ 5.9, 6.1): код, текст «Отправить», согласие, «Последний раз открывали». */
class CabinetLinksTest {
    /** 20.10.2026 10:41 UTC = 15:41 в Тюмени. */
    private val now = 1_792_492_860_000L
    private val today = LocalDate(2026, 10, 20)

    @Test
    fun pinIsFourDigitsFromRandomBytes() {
        assertEquals("0000", CabinetLinkRules.pin(byteArrayOf(0, 0, 0, 0)))
        assertEquals("0001", CabinetLinkRules.pin(byteArrayOf(0, 0, 0, 1)))
        // 0xFFFFFFFF = 4 294 967 295 → 7295; знаковые байты не дают минуса.
        assertEquals("7295", CabinetLinkRules.pin(byteArrayOf(-1, -1, -1, -1)))
        assertFailsWith<IllegalArgumentException> { CabinetLinkRules.pin(byteArrayOf(1, 2)) }
        val all = (0 until 2000).map { CabinetLinkRules.pin(byteArrayOf((it shr 8).toByte(), it.toByte(), 37, (it * 7).toByte())) }
        assertTrue(all.all { Regex("\\d{4}").matches(it) })
    }

    @Test
    fun urlAndShareText() {
        val url = CabinetLinkRules.url("https://trener.planovo-app.ru/", "Sk8v1PLEy5xT7Bl9w44C")
        assertEquals("https://trener.planovo-app.ru/c/Sk8v1PLEy5xT7Bl9w44C", url)
        assertEquals(
            "Здравствуйте! Здесь расписание и итоги тренировок: $url\nКод для входа: 4719",
            CabinetLinkRules.shareText(url, "4719", adult = false),
        )
        assertEquals("Здравствуйте! Здесь расписание и итоги ваших тренировок: $url", CabinetLinkRules.shareText(url, null, adult = true))
    }

    @Test
    fun contactIsTrimmedAndRequired() {
        assertEquals("+7 912 000-00-00", CabinetLinkRules.contact("  +7 912   000-00-00 "))
        assertNull(CabinetLinkRules.contact("   "))
        assertEquals(CabinetLinkRules.CONTACT_MAX, CabinetLinkRules.contact("а".repeat(300))!!.length)
    }

    @Test
    fun consentLines() {
        val none = CabinetLinkRules.consent(null, child = true)
        assertEquals(ConsentState.NONE, none.state)
        assertEquals("Согласия пока нет — родитель отметит его при первом открытии", none.text)
        assertEquals("Согласия пока нет — клиент отметит его при первом открытии", CabinetLinkRules.consent(null, child = false).text)

        // 05.09.2026 12:00 по Тюмени.
        val accepted = now - 45L * 86_400_000L
        val given = CabinetLinkRules.consent(ConsentInfo(accepted, photo = true, givenBy = "Орлова Анна", revokedAt = null), child = true)
        assertEquals(ConsentLine("Согласие получено 05.09, отмечено в кабинете", ConsentState.GIVEN), given)
        assertEquals(
            "Согласие получено 05.09, отмечено в кабинете · без фото и видео",
            CabinetLinkRules.consent(ConsentInfo(accepted, photo = false, givenBy = null, revokedAt = null), child = false).text,
        )

        val revoked = CabinetLinkRules.consent(ConsentInfo(accepted, photo = true, givenBy = null, revokedAt = now), child = true)
        assertEquals(ConsentLine("Согласие отозвано 20.10 — удалите данные клиента до 19.11.2026", ConsentState.REVOKED), revoked)
    }

    @Test
    fun lastOpened() {
        assertEquals("Ещё не открывали", CabinetLinkFormat.lastOpened(null, today))
        assertEquals("Последний раз открывали: сегодня, 09:05", CabinetLinkFormat.lastOpened(LocalDateTime(2026, 10, 20, 9, 5), today))
        assertEquals("Последний раз открывали: вчера, 21:14", CabinetLinkFormat.lastOpened(LocalDateTime(2026, 10, 19, 21, 14), today))
        assertEquals("Последний раз открывали: пн 12.10, 18:00", CabinetLinkFormat.lastOpened(LocalDateTime(2026, 10, 12, 18, 0), today))
        assertEquals("Последний раз открывали: 30.12.2025, 18:00", CabinetLinkFormat.lastOpened(LocalDateTime(2025, 12, 30, 18, 0), today))
    }

    @Test
    fun titleAndCode() {
        assertEquals("Кабинет · Орлова К.", CabinetLinkFormat.title("Орлова", "Ксения"))
        assertEquals("4719 · спрашивается при входе", CabinetLinkFormat.codeOn("4719"))
        assertEquals("Спрашивается при входе", CabinetLinkFormat.codeOn(null))
    }

    @Test
    fun noPhotoMarkOnlyWhenParentSaidNoOrRevoked() {
        assertEquals(false, CabinetLinkRules.noPhoto(null), "согласия ещё нет — пометки нет")
        assertEquals(false, CabinetLinkRules.noPhoto(ConsentInfo(now, photo = true, givenBy = null, revokedAt = null)))
        assertTrue(CabinetLinkRules.noPhoto(ConsentInfo(now, photo = false, givenBy = null, revokedAt = null)))
        assertTrue(CabinetLinkRules.noPhoto(ConsentInfo(now, photo = true, givenBy = null, revokedAt = now)))
    }

    @Test
    fun revokedConsentsAreDueNearestFirstAndOnlyForClientsInThePhone() {
        val day = 86_400_000L
        val consents = mapOf(
            "a" to ConsentInfo(now - 10 * day, photo = true, givenBy = "Орлова Анна", revokedAt = now),
            "b" to ConsentInfo(now - 10 * day, photo = false, givenBy = null, revokedAt = now - 5 * day),
            "c" to ConsentInfo(now - 10 * day, photo = true, givenBy = null, revokedAt = null),
            "gone" to ConsentInfo(now - 10 * day, photo = true, givenBy = null, revokedAt = now),
        )
        val names = mapOf("a" to ClientName("Орлова", "Ксения"), "b" to ClientName("Соколов", "Пётр"), "c" to ClientName("Белова", "Ия"))
        val due = CabinetLinkRules.due(consents, names)
        assertEquals(listOf("b", "a"), due.map { it.clientId })
        assertEquals(LocalDate(2026, 11, 14), due[0].deleteBy, "отозвано 15.10 — удалить до 14.11")
        assertEquals(LocalDate(2026, 11, 19), CabinetLinkRules.deleteBy(consents["a"]))
        assertNull(CabinetLinkRules.deleteBy(consents["c"]))

        assertEquals("Удалить данные клиента", CabinetLinkFormat.deleteData(1))
        assertEquals("Удалить данные клиентов", CabinetLinkFormat.deleteData(2))
        assertEquals("Соколов\u00A0П., Орлова\u00A0К. · осталось 25\u00A0дней", CabinetLinkFormat.deleteNote(due.map { it.name }, due[0].deleteBy, today))
    }

    @Test
    fun daysLeft() {
        val by = LocalDate(2026, 11, 19)
        assertEquals("осталось 30\u00A0дней", CabinetLinkFormat.left(by, LocalDate(2026, 10, 20)))
        assertEquals("остался 21\u00A0день", CabinetLinkFormat.left(by, LocalDate(2026, 10, 29)))
        assertEquals("осталось 2\u00A0дня", CabinetLinkFormat.left(by, LocalDate(2026, 11, 17)))
        assertEquals("остался 1\u00A0день", CabinetLinkFormat.left(by, LocalDate(2026, 11, 18)))
        assertEquals("сегодня последний день", CabinetLinkFormat.left(by, by))
        assertEquals("срок прошёл 19.11", CabinetLinkFormat.left(by, LocalDate(2026, 11, 20)))
    }
}
