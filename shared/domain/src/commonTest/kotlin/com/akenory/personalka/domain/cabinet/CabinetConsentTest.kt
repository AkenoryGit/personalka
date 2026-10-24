package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.format.LegalBlock
import com.akenory.personalka.domain.format.LegalText
import com.akenory.personalka.domain.format.RuText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Согласие перед кабинетом (ТЗ 6.1, 3.7; макеты `KbConsent`, `KbRevoke`, `KbRevoked`) на данных макетов 0.7. */
class CabinetConsentTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun child() = ConsentRules.screen("Орлова Ксения", "Дмитрий Дудник", "Дудник Дмитрий Викторович", "+7 912 000-00-00", child = true)

    private fun adult() = ConsentRules.screen("Яковлева Татьяна", "Дмитрий Дудник", "Дудник Дмитрий Викторович", "+7 912 000-00-00", child = false)

    private fun text(screen: ConsentScreen): String = screen.doc.sections.joinToString("\n") { section ->
        section.title + "\n" + section.blocks.joinToString("\n") { block ->
            when (block) {
                is LegalBlock.Para -> block.text
                is LegalBlock.Bullets -> block.items.joinToString("\n")
                is LegalBlock.Table -> block.rows.joinToString("\n") { "${it.first} — ${it.second}" }
            }
        }
    }

    @Test
    fun childScreenAsksParentName() {
        val screen = child()
        assertTrue(screen.askName)
        assertEquals("Тренер: Дмитрий Дудник", screen.coachLine)
        assertTrue("согласие родителя" in screen.intro)
        assertEquals(LegalText.CONSENT_TITLE, screen.doc.title)
        assertEquals("Редакция 1 от 24.10.2026", screen.doc.edition)
        val doc = text(screen)
        assertTrue("Тренер Дудник Дмитрий Викторович. Он сам отвечает за данные" in doc, "оператор — ФИО полностью")
        assertTrue("Вопросы и отзыв согласия: +7 912 000-00-00." in doc, "контакт из профиля")
        assertTrue("Какие данные ребёнка" in doc && "Ваши данные" in doc && "телефон для связи" in doc)
        assertTrue("Сведений о здоровье" in doc)
        assertFalse("бумажн" in doc, "бумажного согласия нет")
        assertTrue(screen.agreeText.startsWith("Я родитель (законный представитель)"))
        assertTrue("ребёнка" in screen.photoText)
    }

    @Test
    fun adultScreenHasNoNameField() {
        val screen = adult()
        assertFalse(screen.askName)
        assertTrue("ваше согласие" in screen.intro)
        assertEquals(CabinetFormat.CONSENT_AGREE_ADULT, screen.agreeText)
        val doc = text(screen)
        assertFalse("ребён" in doc, "у взрослого — без ребёнка")
        assertTrue("сервер запомнит время, IP-адрес и браузер" in doc)
    }

    @Test
    fun noContactStillSaysWhereToWrite() {
        val doc = LegalText.consent(child = true, coachFullName = "Дудник Дмитрий", contact = " ")
        val who = doc.sections.first { it.title == "Кто" }.blocks.single() as LegalBlock.Para
        assertTrue(who.text.endsWith("Вопросы и отзыв согласия: сообщением тренеру напрямую."))
    }

    @Test
    fun withoutAgreeTheSameScreenWithHint() {
        val retry = assertIs<ConsentSubmit.Retry>(ConsentRules.submit(child(), ConsentForm(name = "  Орлова   Марина ", agree = false, photo = true)))
        assertEquals("Чтобы открыть кабинет, отметьте согласие.", retry.screen.agreeError)
        assertNull(retry.screen.nameError)
        assertEquals("Орлова Марина", retry.screen.form.name, "введённое не теряется")
        assertTrue(retry.screen.form.photo)
    }

    @Test
    fun childNeedsParentName() {
        val retry = assertIs<ConsentSubmit.Retry>(ConsentRules.submit(child(), ConsentForm(name = "   ", agree = true)))
        assertEquals("Впишите ваши фамилию и имя.", retry.screen.nameError)
        assertNull(retry.screen.agreeError)

        val accepted = assertIs<ConsentSubmit.Accepted>(ConsentRules.submit(child(), ConsentForm(name = "Орлова Марина", agree = true, photo = false)))
        assertEquals(ConsentSubmit.Accepted(givenBy = "Орлова Марина", photo = false, version = 1), accepted)
    }

    @Test
    fun adultAcceptsWithoutName() {
        val accepted = assertIs<ConsentSubmit.Accepted>(ConsentRules.submit(adult(), ConsentForm(name = "лишнее", agree = true, photo = true)))
        assertNull(accepted.givenBy, "у взрослого имя не пишется")
        assertTrue(accepted.photo)
    }

    @Test
    fun versionsAndDeletionDate() {
        assertTrue(ConsentRules.valid(1))
        assertFalse(ConsentRules.valid(0))
        assertEquals(LocalDate(2026, 11, 19), ConsentRules.deleteBy(LocalDate(2026, 10, 20)))
        val revoke = ConsentRules.revoke("Орлова Ксения", child = true)
        assertEquals(s("Тренер удалит данные ребёнка из приложения и с сервера в течение 30_дней."), revoke.lines[1])
        assertEquals("Кабинет закрыт. Тренер удалит данные ребёнка до 19.11.2026.", ConsentRules.revoked(child = true, LocalDate(2026, 10, 20)).text)
        assertEquals("Кабинет закрыт. Тренер удалит ваши данные до 19.11.2026.", ConsentRules.revoked(child = false, LocalDate(2026, 10, 20)).text)
    }

    @Test
    fun mediaOnlyWithPhotoConsentAndWebLink() {
        assertEquals(CabinetMedia("https://disk.yandex.ru/d/abc", "Откроется Яндекс Диск"), CabinetMediaRules.media("https://disk.yandex.ru/d/abc", photoConsent = true))
        assertNull(CabinetMediaRules.media("https://disk.yandex.ru/d/abc", photoConsent = false), "без галочки про фото ссылки нет")
        assertNull(CabinetMediaRules.media(null, photoConsent = true), "нет ссылки — нет раздела")
        assertNull(CabinetMediaRules.media("javascript:alert(1)", photoConsent = true))
        assertNull(CabinetMediaRules.media("https://evil\"x.ru/", photoConsent = true))
        assertEquals("Откроется Облако Mail.ru", CabinetMediaRules.media("https://cloud.mail.ru/public/x", true)?.note)
        assertEquals("Откроется photos.example.ru", CabinetMediaRules.media("http://www.photos.example.ru:8080/a", true)?.note)
    }

    @Test
    fun pageGetsMediaAndConsentLine() {
        val input = CabinetInput(
            clientName = "Орлова Ксения",
            coachName = "Дмитрий Дудник",
            syncedAt = null,
            now = LocalDateTime(2026, 10, 20, 15, 41),
            upcomingCount = 4,
            cancelNoticeMinutes = 60,
            trainings = emptyList(),
            mediaUrl = "https://disk.yandex.ru/d/abc",
            consentDate = LocalDate(2026, 9, 5),
            photoConsent = true,
        )
        val page = CabinetRules.page(input)
        assertEquals("Откроется Яндекс Диск", page.media?.note)
        assertEquals("Согласие на обработку данных дано 05.09.2026", page.consentLine)
        assertNull(CabinetRules.page(input.copy(photoConsent = false)).media)
    }
}
