package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.format.LegalDoc
import com.akenory.personalka.domain.format.LegalText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Что родитель прислал с экрана согласия: имя (у взрослого клиента поля нет), две галочки. */
data class ConsentForm(val name: String = "", val agree: Boolean = false, val photo: Boolean = false)

/** Ответ на присланный экран согласия. */
sealed interface ConsentSubmit {
    /** Не хватает галочки или имени — тот же экран с подсказками. */
    data class Retry(val screen: ConsentScreen) : ConsentSubmit

    /** Согласие для записи (ConsentRecord, 3.7): [givenBy] — у взрослого клиента `null`. */
    data class Accepted(val givenBy: String?, val photo: Boolean, val version: Int = LegalText.VERSION) : ConsentSubmit
}

/**
 * Экран согласия (6.1, макет `KbConsent`) сверху вниз. [form] — то, что уже ввели (после ошибки поля не пустеют);
 * [nameError], [agreeError] — подсказки под полем и под галочкой, `null` — нет.
 */
data class ConsentScreen(
    val clientName: String,
    val coachLine: String,
    val intro: String,
    val doc: LegalDoc,
    /** Ребёнок — поле «Ваши фамилия и имя» есть. */
    val askName: Boolean,
    val agreeText: String,
    val photoText: String,
    val form: ConsentForm,
    val nameError: String? = null,
    val agreeError: String? = null,
)

/** Экран «Отозвать согласие?» (`KbRevoke`) и «Согласие отозвано» (`KbRevoked`). */
data class RevokeScreen(val clientName: String, val lines: List<String>, val hint: String)

data class RevokedScreen(val text: String, val hint: String)

/**
 * Согласие перед кабинетом (ТЗ 6.1, 3.7, 10.3): кто даёт, что проверить, когда его спросить снова и когда удалить
 * данные после отзыва. Бумажного согласия нет — только этот экран.
 */
object ConsentRules {
    /** После отзыва данные удаляются в течение 30 дней (6.1). */
    const val DELETE_DAYS = 30

    /** Фамилия и имя — не длиннее, чем влезет в запись. */
    const val NAME_MAX = 100

    /** Действует ли записанное согласие редакции [version]: новая редакция с новыми целями спрашивает снова (3.7). */
    fun valid(version: Int): Boolean = version >= LegalText.ASK_AGAIN_FROM

    /** До какого дня тренер удаляет данные: отозвали 20.10 — до 19.11. */
    fun deleteBy(revoked: LocalDate): LocalDate = revoked.plus(DatePeriod(days = DELETE_DAYS))

    /** Имя без лишних пробелов. */
    fun cleanName(name: String): String = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(NAME_MAX)

    /** Экран согласия; [child] — клиент не взрослый (3.5). */
    fun screen(
        clientName: String,
        coachName: String,
        coachFullName: String,
        contact: String?,
        child: Boolean,
        form: ConsentForm = ConsentForm(),
    ) = ConsentScreen(
        clientName = clientName,
        coachLine = CabinetFormat.coach(coachName),
        intro = if (child) CabinetFormat.CONSENT_INTRO_CHILD else CabinetFormat.CONSENT_INTRO_ADULT,
        doc = LegalText.consent(child, coachFullName, contact),
        askName = child,
        agreeText = if (child) CabinetFormat.CONSENT_AGREE_CHILD else CabinetFormat.CONSENT_AGREE_ADULT,
        photoText = if (child) CabinetFormat.CONSENT_PHOTO_CHILD else CabinetFormat.CONSENT_PHOTO_ADULT,
        form = form,
    )

    /**
     * Проверить присланное: без первой галочки — тот же экран с «Чтобы открыть кабинет, отметьте согласие», у ребёнка
     * без имени — «Впишите ваши фамилию и имя». Всё верно — согласие для записи.
     */
    fun submit(screen: ConsentScreen, form: ConsentForm): ConsentSubmit {
        val name = cleanName(form.name)
        val nameError = CabinetFormat.CONSENT_NAME_EMPTY.takeIf { screen.askName && name.isEmpty() }
        val agreeError = CabinetFormat.CONSENT_AGREE_EMPTY.takeIf { !form.agree }
        if (nameError != null || agreeError != null) {
            return ConsentSubmit.Retry(screen.copy(form = form.copy(name = name), nameError = nameError, agreeError = agreeError))
        }
        return ConsentSubmit.Accepted(givenBy = name.takeIf { screen.askName }, photo = form.photo)
    }

    fun revoke(clientName: String, child: Boolean) = RevokeScreen(
        clientName = clientName,
        lines = listOf(CabinetFormat.REVOKE_CLOSES, CabinetFormat.revokeDeletes(child, DELETE_DAYS)),
        hint = CabinetFormat.REVOKE_HINT,
    )

    fun revoked(child: Boolean, revoked: LocalDate) = RevokedScreen(CabinetFormat.revokedText(child, deleteBy(revoked)), CabinetFormat.REVOKE_HINT)
}

/** «Фото и видео» в кабинете (6.3, п. 7): ссылка и подпись «Откроется Яндекс Диск». */
data class CabinetMedia(val url: String, val note: String)

object CabinetMediaRules {
    /**
     * Ссылка на фото и видео — только с согласием на фото и только http(s): иное (`javascript:` и прочее) не
     * показывается вовсе.
     */
    fun media(url: String?, photoConsent: Boolean): CabinetMedia? {
        if (!photoConsent) return null
        val link = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = host(link) ?: return null
        return CabinetMedia(link, CabinetFormat.mediaNote(host))
    }

    /** Имя сервера из адреса http(s), без `www.`; иначе `null`. */
    fun host(url: String): String? {
        val rest = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return null
        }
        val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@').substringBefore(':').lowercase()
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return host.removePrefix("www.")
    }
}
