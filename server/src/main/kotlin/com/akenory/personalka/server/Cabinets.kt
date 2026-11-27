package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.cabinet.CabinetInput
import com.akenory.personalka.domain.cabinet.CabinetMediaRules
import com.akenory.personalka.domain.cabinet.CabinetTraining
import com.akenory.personalka.domain.cabinet.CabinetVisitRules
import com.akenory.personalka.domain.cabinet.CabinetVisits
import com.akenory.personalka.domain.cabinet.ConsentRules
import com.akenory.personalka.domain.cabinet.ConsentScreen
import com.akenory.personalka.domain.cabinet.ConsentSubmit
import com.akenory.personalka.domain.cabinet.RevokeScreen
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.LegalDoc
import com.akenory.personalka.domain.format.LegalText
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Действующая ссылка на кабинет. */
data class CabinetAccess(
    val id: Long,
    val coachId: String,
    val clientId: String,
    val pinHash: String?,
    val pinFailures: Int,
    val blockedUntil: Long?,
)

/** Действующая ссылка для админки: выдана, последнее открытие и переход на «Фото и видео» по всем ссылкам клиента. */
private data class VisitLink(
    val coachId: String,
    val clientId: String,
    val createdAt: OffsetDateTime,
    val lastOpened: OffsetDateTime?,
    val lastMedia: OffsetDateTime?,
)

/** Ссылка для списка в приложении. */
data class CabinetLink(val clientId: String, val createdAt: OffsetDateTime, val lastOpenedAt: OffsetDateTime?, val hasPin: Boolean, val tokenHash: ByteArray)

/** Действующее согласие клиента (ConsentRecord, 3.7). */
data class Consent(val acceptedAt: Long, val version: Int, val photo: Boolean, val givenBy: String?)

/** Последнее согласие клиента — для приложения: «Согласие получено», «Нет согласия на фото», «Удалить данные клиента». */
data class ConsentInfo(val clientId: String, val acceptedAt: Long, val photo: Boolean, val givenBy: String?, val revokedAt: Long?)

/** Кто в кабинете: имя клиента, тренер (как в шапке и полностью — в согласии), контакт, ребёнок ли, часы клуба. */
data class CabinetSubject(
    val clientName: String,
    val coachName: String,
    val coachFullName: String,
    val contact: String?,
    val child: Boolean,
    val zone: TimeZone,
)

/** Ответ на введённый код. */
sealed interface PinCheck {
    /** Верно: значение cookie-пропуска на 30 дней. */
    data class Passed(val pass: String) : PinCheck

    data class Wrong(val left: Int) : PinCheck

    data object Blocked : PinCheck
}

/**
 * Кабинеты родителей (ТЗ 6.1, 6.2, 3.7): ссылки, код доступа и данные страницы. Страница собирается только из записей
 * одного клиента, найденного по токену: его участия, тренировки этих участий, их типы и места (6.2) — чужие участия
 * и группа сервер не читает вовсе. Всё — внутри транзакции вызывающего; [now] — часы сервера (в тестах — свои).
 */
class Cabinets(
    val now: () -> Long = System::currentTimeMillis,
    private val passwords: Passwords = Passwords(),
    private val random: SecureRandom = SecureRandom(),
) {
    // --- Ссылки (для приложения, шаг 2.13) ---

    /** Новая ссылка клиента: прежняя отзывается (3.7). [tokenHash] — SHA-256 токена, [pin] — 4 цифры или null. */
    fun issue(c: Connection, coachId: String, clientId: String, tokenHash: ByteArray, pin: String?) {
        revoke(c, coachId, clientId)
        c.update(
            "INSERT INTO cabinet_access (coach_id, client_id, token_hash, pin_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            coachId, clientId, tokenHash, pin?.let(passwords::hash), at(now()),
        )
    }

    /** Отозвать ссылку клиента; ответ — была ли действующая. */
    fun revoke(c: Connection, coachId: String, clientId: String): Boolean = c.update(
        "UPDATE cabinet_access SET revoked_at = ? WHERE coach_id = ? AND client_id = ? AND revoked_at IS NULL",
        at(now()), coachId, clientId,
    ) > 0

    /**
     * Сменить код действующей ссылки клиента, не меняя её (шаг 2.13): [pin] — 4 цифры или null (без кода). Попытки
     * и блокировка обнуляются, прежние пропуски по коду больше не действуют — новый код спросится у всех.
     * Ответ — была ли действующая ссылка.
     */
    fun changePin(c: Connection, coachId: String, clientId: String, pin: String?): Boolean {
        val id = c.queryOne(
            "SELECT id FROM cabinet_access WHERE coach_id = ? AND client_id = ? AND revoked_at IS NULL FOR UPDATE",
            coachId, clientId,
        ) { it.getLong(1) } ?: return false
        c.update(
            "UPDATE cabinet_access SET pin_hash = ?, pin_failures = 0, pin_blocked_until = NULL WHERE id = ?",
            pin?.let(passwords::hash), id,
        )
        c.update("DELETE FROM cabinet_pass WHERE access_id = ?", id)
        return true
    }

    fun links(c: Connection, coachId: String): List<CabinetLink> = c.query(
        "SELECT client_id, created_at, last_opened_at, pin_hash IS NOT NULL, token_hash FROM cabinet_access WHERE coach_id = ? AND revoked_at IS NULL ORDER BY created_at",
        coachId,
    ) { CabinetLink(it.getString(1), it.getObject(2, OffsetDateTime::class.java), it.getObject(3, OffsetDateTime::class.java), it.getBoolean(4), it.getBytes(5)) }

    // --- Страница ---

    /**
     * Ссылка по токену, если кабинет открыт: ссылка не отозвана, тренер не приостановлен и у него открыты «Кабинеты»,
     * кабинеты включены в настройках тренера, клиент не удалён и кабинет у него включён (6, 6.1). Иначе null —
     * одинаковый ответ для любой причины.
     */
    fun open(c: Connection, accounts: Accounts, token: String): CabinetAccess? {
        if (token.length !in TOKEN_LENGTH) return null
        val access = c.queryOne(
            """
            SELECT id, coach_id, client_id, pin_hash, pin_failures, pin_blocked_until FROM cabinet_access
            WHERE token_hash = ? AND revoked_at IS NULL
            """.trimIndent(),
            Accounts.tokenHash(token),
            row = ::access,
        ) ?: return null
        return access.takeIf { isOpen(c, accounts, it) }
    }

    /** Действующая ссылка клиента [clientId] тренера [coachId], если кабинет открыт (как в [open]). */
    fun openFor(c: Connection, accounts: Accounts, coachId: String, clientId: String): CabinetAccess? = c.queryOne(
        """
        SELECT id, coach_id, client_id, pin_hash, pin_failures, pin_blocked_until FROM cabinet_access
        WHERE coach_id = ? AND client_id = ? AND revoked_at IS NULL
        """.trimIndent(),
        coachId, clientId,
        row = ::access,
    )?.takeIf { isOpen(c, accounts, it) }

    private fun access(row: ResultSet) = CabinetAccess(
        row.getLong(1), row.getString(2), row.getString(3), row.getString(4), row.getInt(5),
        row.getObject(6, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
    )

    private fun isOpen(c: Connection, accounts: Accounts, access: CabinetAccess): Boolean {
        val coach = accounts.byId(c, access.coachId) ?: return false
        if (coach.suspended) return false
        if (Feature.CABINETS !in AccountRules.open(accounts.grants(c, coach), ClubTime.today(now()))) return false
        val settings = row(c, coach.id, "coach_settings", "coachId", coach.id) ?: return false
        if (!settings.flag("cabinetsEnabled")) return false
        val client = row(c, coach.id, "client", "id", access.clientId) ?: return false
        return client.flag("cabinetEnabled")
    }

    // --- «Как видит родитель» (6.4, шаг 2.13б) ---

    private data class Preview(val coachId: String, val clientId: String, val until: Long)

    /** Токены предпросмотра — в памяти сервера: живут 15 минут, после перезапуска их нет — тренер нажмёт ещё раз. */
    private val previews = ConcurrentHashMap<String, Preview>()

    /**
     * Токен предпросмотра кабинета клиента [clientId] для тренера [coachId]: 32 случайных байта в base64url на
     * [PREVIEW_MINUTES] минут. Сервер хранит только SHA-256 — как у ссылок.
     */
    fun preview(coachId: String, clientId: String): String {
        val now = now()
        previews.entries.removeIf { it.value.until <= now }
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        previews[previewKey(token)] = Preview(coachId, clientId, now + PREVIEW_MINUTES * 60_000L)
        return token
    }

    /** Ссылка клиента по токену предпросмотра, если он не просрочен и кабинет открыт; иначе null. */
    fun previewAccess(c: Connection, accounts: Accounts, token: String): CabinetAccess? {
        if (token.length !in TOKEN_LENGTH) return null
        val preview = previews[previewKey(token)]?.takeIf { it.until > now() } ?: return null
        return openFor(c, accounts, preview.coachId, preview.clientId)
    }

    private fun previewKey(token: String): String = Base64.getEncoder().encodeToString(Accounts.tokenHash(token))

    /** Пропуск из cookie подходит к ссылке и не просрочен. */
    fun passValid(c: Connection, access: CabinetAccess, pass: String?): Boolean {
        if (pass == null || pass.length > 64) return false
        return c.queryOne(
            "SELECT 1 FROM cabinet_pass WHERE pass_hash = ? AND access_id = ? AND expires_at > ?",
            Accounts.tokenHash(pass), access.id, at(now()),
        ) { true } ?: false
    }

    fun blocked(access: CabinetAccess): Boolean = access.blockedUntil?.let { it > now() } ?: false

    /**
     * Проверить код (6.1): 5 неверных подряд — блокировка на 15 минут. Верный — пропуск на 30 дней. Попытки считаются
     * под блокировкой строки — два запроса сразу не получат лишнюю попытку.
     */
    fun checkPin(c: Connection, access: CabinetAccess, code: String): PinCheck {
        val row = c.queryOne(
            "SELECT pin_hash, pin_failures, pin_blocked_until FROM cabinet_access WHERE id = ? FOR UPDATE",
            access.id,
        ) { Triple(it.getString(1), it.getInt(2), it.getObject(3, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()) }
            ?: return PinCheck.Blocked
        val (hash, failures, blockedUntil) = row
        val now = now()
        if (blockedUntil != null && blockedUntil > now) return PinCheck.Blocked
        if (hash != null && passwords.verify(code, hash)) {
            c.update("UPDATE cabinet_access SET pin_failures = 0, pin_blocked_until = NULL WHERE id = ?", access.id)
            c.update("DELETE FROM cabinet_pass WHERE expires_at < ?", at(now))
            val pass = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            c.update(
                "INSERT INTO cabinet_pass (pass_hash, access_id, expires_at) VALUES (?, ?, ?)",
                Accounts.tokenHash(pass), access.id, at(now + PASS_DAYS * DAY_MS),
            )
            return PinCheck.Passed(pass)
        }
        val failed = failures + 1
        if (failed >= PIN_ATTEMPTS) {
            c.update(
                "UPDATE cabinet_access SET pin_failures = 0, pin_blocked_until = ? WHERE id = ?",
                at(now + BLOCK_MINUTES * 60_000L), access.id,
            )
            return PinCheck.Blocked
        }
        c.update("UPDATE cabinet_access SET pin_failures = ? WHERE id = ?", failed, access.id)
        return PinCheck.Wrong(PIN_ATTEMPTS - failed)
    }

    /**
     * Родитель открыл кабинет — видно тренеру (3.7). Если с прошлого открытия этой ссылки прошло
     * [CabinetVisitRules.NEW_VISIT_MINUTES] минут и больше — ещё одно посещение в счёт дня клуба (шаг 5.35).
     * Строка ссылки блокируется: два запроса сразу не посчитают одно посещение дважды.
     */
    fun opened(c: Connection, access: CabinetAccess) {
        val now = now()
        val previous = c.queryOne("SELECT last_opened_at FROM cabinet_access WHERE id = ? FOR UPDATE", access.id) {
            it.getObject(1, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()
        }
        c.update("UPDATE cabinet_access SET last_opened_at = ? WHERE id = ?", at(now), access.id)
        if (!CabinetVisitRules.newVisit(previous, now)) return
        c.update(
            """
            INSERT INTO cabinet_visit (coach_id, client_id, day, visits) VALUES (?, ?, ?, 1)
            ON CONFLICT (coach_id, client_id, day) DO UPDATE SET visits = cabinet_visit.visits + 1
            """.trimIndent(),
            access.coachId, access.clientId, java.time.LocalDate.parse(ClubTime.today(now).toString()),
        )
    }

    /**
     * Адрес «Фото и видео» клиента [access], если ссылка есть в его кабинете: согласие на фото и адрес http(s) (6.3).
     */
    fun mediaUrl(c: Connection, access: CabinetAccess, consent: Consent): String? {
        val client = row(c, access.coachId, "client", "id", access.clientId) ?: return null
        return CabinetMediaRules.media(client.textOrNull("mediaUrl"), consent.photo)?.url
    }

    /**
     * Переход по «Фото и видео» из кабинета (шаг 5.36): время последнего перехода этой ссылки и, если с прошлого
     * прошло [CabinetVisitRules.NEW_VISIT_MINUTES] минут и больше, — ещё один переход в счёт дня клуба.
     */
    fun mediaOpened(c: Connection, access: CabinetAccess) {
        val now = now()
        val previous = c.queryOne("SELECT last_media_at FROM cabinet_access WHERE id = ? FOR UPDATE", access.id) {
            it.getObject(1, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()
        }
        c.update("UPDATE cabinet_access SET last_media_at = ? WHERE id = ?", at(now), access.id)
        if (!CabinetVisitRules.newVisit(previous, now)) return
        c.update(
            """
            INSERT INTO cabinet_visit (coach_id, client_id, day, visits, media) VALUES (?, ?, ?, 0, 1)
            ON CONFLICT (coach_id, client_id, day) DO UPDATE SET media = cabinet_visit.media + 1
            """.trimIndent(),
            access.coachId, access.clientId, java.time.LocalDate.parse(ClubTime.today(now).toString()),
        )
    }

    // --- Посещения для админки (шаги 5.35, 5.36) ---

    /** С какого дня клуба считаются посещения: день обновления сервера, в котором они появились. */
    fun visitsSince(c: Connection): LocalDate? =
        c.queryOne("SELECT min(at) FROM cabinet_visit_since") { it.getObject(1, OffsetDateTime::class.java) }
            ?.let { ClubTime.today(it.toInstant().toEpochMilli()) }

    /**
     * Кабинеты с действующей ссылкой — тренера [coachId] или всех (null), по тренерам: день выдачи ссылки, последнее
     * открытие по всем ссылкам клиента и посещения по дням. Имена и «закрыт» ([withNames]) читаются из записей
     * тренера — только для своих клиентов администратора: данных коллег админка не видит (5.17).
     */
    fun visits(c: Connection, coachId: String?, withNames: Boolean = false): Map<String, List<CabinetVisits>> {
        val only = if (coachId == null) "" else " AND a.coach_id = ?"
        val args = listOfNotNull(coachId).toTypedArray()
        val links = c.query(
            """
            SELECT a.coach_id, a.client_id, a.created_at,
                   (SELECT max(b.last_opened_at) FROM cabinet_access b WHERE b.coach_id = a.coach_id AND b.client_id = a.client_id),
                   (SELECT max(b.last_media_at) FROM cabinet_access b WHERE b.coach_id = a.coach_id AND b.client_id = a.client_id)
            FROM cabinet_access a WHERE a.revoked_at IS NULL$only
            """.trimIndent(),
            *args,
        ) {
            VisitLink(
                it.getString(1), it.getString(2), it.getObject(3, OffsetDateTime::class.java),
                it.getObject(4, OffsetDateTime::class.java), it.getObject(5, OffsetDateTime::class.java),
            )
        }
        val days = c.query(
            "SELECT coach_id, client_id, day, visits, media FROM cabinet_visit a WHERE true$only",
            *args,
        ) { Triple(it.getString(1) to it.getString(2), LocalDate.parse(it.getString(3)), it.getInt(4) to it.getInt(5)) }
            .groupBy({ it.first }, { it.second to it.third })
        val cabinetsOn = if (withNames && coachId != null) row(c, coachId, "coach_settings", "coachId", coachId)?.flag("cabinetsEnabled") == true else true
        // Согласие на фото — последнее согласие клиента, не отозванное (как в приложении, 2.13).
        val photo = if (withNames && coachId != null) consents(c, coachId).filter { it.photo && it.revokedAt == null }.map { it.clientId }.toSet() else emptySet()
        fun local(t: OffsetDateTime?) = t?.let { ClubTime.now(it.toInstant().toEpochMilli()) }
        return links.map { link ->
            val record = if (withNames) row(c, link.coachId, "client", "id", link.clientId) else null
            val counts = days[link.coachId to link.clientId].orEmpty()
            link.coachId to CabinetVisits(
                clientId = link.clientId,
                issued = ClubTime.today(link.createdAt.toInstant().toEpochMilli()),
                lastOpened = local(link.lastOpened),
                days = counts.filter { it.second.first > 0 }.associate { it.first to it.second.first },
                name = record?.let(::clientName)?.takeIf { it.isNotBlank() },
                closed = withNames && (!cabinetsOn || record == null || !record.flag("cabinetEnabled")),
                lastMedia = local(link.lastMedia),
                media = counts.filter { it.second.second > 0 }.associate { it.first to it.second.second },
                mediaShown = record != null && link.clientId in photo && CabinetMediaRules.media(record.textOrNull("mediaUrl"), true) != null,
            )
        }.groupBy({ it.first }, { it.second })
    }

    // --- Согласие (6.1, 3.7) ---

    /**
     * Действующее согласие клиента [access]: последнее неотозванное, если его редакция не требует спросить снова.
     * Согласие — у клиента, а не у ссылки: новая ссылка без отзыва его не сбрасывает.
     */
    fun consent(c: Connection, access: CabinetAccess): Consent? = c.queryOne(
        """
        SELECT accepted_at, text_version, items, given_by_name FROM consent_record
        WHERE coach_id = ? AND client_id = ? AND revoked_at IS NULL ORDER BY accepted_at DESC, id DESC LIMIT 1
        """.trimIndent(),
        access.coachId, access.clientId,
    ) {
        @Suppress("UNCHECKED_CAST")
        val items = (it.getArray(3).array as Array<String>).toSet()
        Consent(it.getObject(1, OffsetDateTime::class.java).toInstant().toEpochMilli(), it.getInt(2), PHOTO in items, it.getString(4))
    }?.takeIf { ConsentRules.valid(it.version) }

    /** Экран согласия клиента [access]. */
    fun consentScreen(c: Connection, access: CabinetAccess): ConsentScreen {
        val who = subject(c, access)
        return ConsentRules.screen(who.clientName, who.coachName, who.coachFullName, who.contact, who.child)
    }

    /** Экран «Отозвать согласие?» клиента [access]. */
    fun revokeScreen(c: Connection, access: CabinetAccess): RevokeScreen {
        val who = subject(c, access)
        return ConsentRules.revoke(who.clientName, who.child)
    }

    /** Записать согласие: время, редакция, пункты, имя родителя, адрес и браузер (3.7). */
    fun accept(c: Connection, access: CabinetAccess, accepted: ConsentSubmit.Accepted, ip: String?, userAgent: String?) {
        val items = listOfNotNull(PERSONAL, PHOTO.takeIf { accepted.photo })
        c.update(
            """
            INSERT INTO consent_record (coach_id, client_id, accepted_at, text_version, items, given_by_name, ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            access.coachId, access.clientId, at(now()), accepted.version, c.createArrayOf("text", items.toTypedArray()),
            accepted.givenBy, ip?.take(64), userAgent?.take(USER_AGENT_MAX),
        )
    }

    /**
     * Отозвать согласие (6.1): согласие помечается отозванным, ссылка клиента — тоже: дальше она отвечает как
     * недействительная, а новую тренер выпускает в приложении, и согласие спрашивается снова. Ответ — день отзыва.
     */
    fun withdraw(c: Connection, access: CabinetAccess): Pair<CabinetSubject, LocalDate> {
        val who = subject(c, access)
        c.update(
            "UPDATE consent_record SET revoked_at = ? WHERE coach_id = ? AND client_id = ? AND revoked_at IS NULL",
            at(now()), access.coachId, access.clientId,
        )
        revoke(c, access.coachId, access.clientId)
        return who to ClubTime.today(now(), who.zone)
    }

    /** Последнее согласие каждого клиента тренера — для приложения (2.13). */
    fun consents(c: Connection, coachId: String): List<ConsentInfo> = c.query(
        """
        SELECT DISTINCT ON (client_id) client_id, accepted_at, items, given_by_name, revoked_at FROM consent_record
        WHERE coach_id = ? ORDER BY client_id, accepted_at DESC, id DESC
        """.trimIndent(),
        coachId,
    ) {
        @Suppress("UNCHECKED_CAST")
        val items = (it.getArray(3).array as Array<String>).toSet()
        ConsentInfo(
            it.getString(1), it.getObject(2, OffsetDateTime::class.java).toInstant().toEpochMilli(), PHOTO in items, it.getString(4),
            it.getObject(5, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
        )
    }

    /**
     * Политика обработки тренера [coachId] (10.3): опубликована для всех, без токена. Нет такого тренера, он
     * приостановлен или ещё не заполнил профиль — null.
     */
    fun policy(c: Connection, accounts: Accounts, coachId: String): LegalDoc? {
        if (coachId.isBlank() || coachId.length > 64) return null
        val coach = accounts.byId(c, coachId) ?: return null
        if (coach.suspended) return null
        val profile = row(c, coach.id, "coach", "coachId", coach.id) ?: return null
        val full = fullName(profile) ?: return null
        return LegalText.policy(full, profile.textOrNull("contactForParents"))
    }

    private fun subject(c: Connection, access: CabinetAccess): CabinetSubject {
        val owner = access.coachId
        val client = row(c, owner, "client", "id", access.clientId)!!
        val coach = row(c, owner, "coach", "coachId", owner)
        val zone = zone(coach)
        val birth = client.textOrNull("birthDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val shortName = coachName(c, owner, coach)
        return CabinetSubject(
            clientName = clientName(client),
            coachName = shortName,
            coachFullName = coach?.let(::fullName) ?: shortName,
            contact = coach?.textOrNull("contactForParents"),
            child = !ClientRules.isAdult(birth, client.flag("isAdult"), ClubTime.today(now(), zone)),
            zone = zone,
        )
    }

    private fun fullName(coach: JsonObject): String? =
        listOf(coach.text("lastName"), coach.text("firstName"), coach.text("middleName")).map(String::trim).filter(String::isNotEmpty)
            .joinToString(" ").takeIf { it.isNotEmpty() }

    private fun clientName(client: JsonObject): String =
        listOf(client.text("lastName"), client.text("firstName")).filter { it.isNotBlank() }.joinToString(" ")

    private fun coachName(c: Connection, owner: String, coach: JsonObject?): String =
        coach?.let { listOf(it.text("firstName"), it.text("lastName")).filter(String::isNotBlank).joinToString(" ") }
            ?.takeIf { it.isNotBlank() }
            ?: accounts(c, owner)

    private fun zone(coach: JsonObject?): TimeZone =
        coach?.text("timezone")?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: ClubTime.defaultZone

    /**
     * Данные страницы клиента [access]: имя клиента и тренера, настройки, участия клиента и только их тренировки,
     * результаты клиента — без заметки тренера; ссылка на фото и дата согласия [consent].
     * Удалённые записи не показываются; тренировка без типа или места — тоже (такого не бывает, но чужое не угадывается).
     */
    fun input(c: Connection, access: CabinetAccess, consent: Consent? = null): CabinetInput {
        val owner = access.coachId
        val client = row(c, owner, "client", "id", access.clientId)!!
        val coach = row(c, owner, "coach", "coachId", owner)
        val settings = row(c, owner, "coach_settings", "coachId", owner)
        val zone = zone(coach)
        val participations = rows(c, listOf(owner), "participation", "clientId", listOf(access.clientId))
        val trainings = rows(c, listOf(owner), "training", "id", participations.map { it.text("trainingId") })
            .associateBy { it.text("id") }
        val types = rows(c, listOf(owner, Sync.CLUB), "training_type", "id", trainings.values.map { it.text("typeId") })
            .associateBy { it.text("id") }
        val places = rows(c, listOf(owner, Sync.CLUB), "location", "id", trainings.values.map { it.text("locationId") })
            .associateBy { it.text("id") }
        // История в группе — с первого «пришёл» (4.4, шаг 5.3н): раньше прошедшие тренировки группы не видны.
        val clubNow = ClubTime.now(now(), zone)
        val starts = GroupRules.historyStarts(
            participations.mapNotNull { p ->
                val training = trainings[p.text("trainingId")] ?: return@mapNotNull null
                val group = training.textOrNull("groupId") ?: return@mapNotNull null
                val date = runCatching { LocalDateTime.parse(training.text("start")) }.getOrNull()?.date ?: return@mapNotNull null
                Triple(group, date, p.text("status") == ParticipationStatus.ATTENDED.name)
            },
        )
        val list = participations.mapNotNull { p ->
            val training = trainings[p.text("trainingId")] ?: return@mapNotNull null
            val begins = runCatching { LocalDateTime.parse(training.text("start")) }.getOrNull()
            if (begins != null && GroupRules.hiddenFromHistory(
                    training.textOrNull("groupId"), begins.date, begins <= clubNow,
                    enumOr(p.text("status"), ParticipationStatus.EXPECTED), starts,
                )
            ) {
                return@mapNotNull null
            }
            val type = types[training.text("typeId")] ?: return@mapNotNull null
            val place = places[training.text("locationId")] ?: return@mapNotNull null
            CabinetTraining(
                start = runCatching { LocalDateTime.parse(training.text("start")) }.getOrNull() ?: return@mapNotNull null,
                typeName = type.text("name"),
                placeName = place.text("name"),
                trainingStatus = enumOr(training.text("status"), TrainingStatus.PLANNED),
                participationStatus = enumOr(p.text("status"), ParticipationStatus.EXPECTED),
                plan = training.textOrNull("plan"),
                fact = training.textOrNull("fact"),
                behavior = p.textOrNull("behavior"),
                showPlan = type.flag("diaryPlan"),
                showFact = type.flag("diaryFact"),
                showBehavior = type.flag("diaryBehavior"),
                cancelReason = p.textOrNull("cancelReason") ?: training.textOrNull("cancelReason"),
                notifiedAt = p.textOrNull("notifiedAt")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                group = type.text("kind") == TrainingKind.GROUP.name,
            )
        }
        val results = rows(c, listOf(owner), "result", "clientId", listOf(access.clientId)).mapNotNull { r ->
            val style = SwimStyle.entries.firstOrNull { it.name == r.text("style") } ?: return@mapNotNull null
            ResultItem(
                id = r.text("id"),
                date = runCatching { LocalDate.parse(r.text("date")) }.getOrNull() ?: return@mapNotNull null,
                event = SwimEvent(style, r.long("distanceM")?.toInt() ?: return@mapNotNull null, r.long("poolM")?.toInt() ?: return@mapNotNull null),
                timeCs = r.long("timeCs")?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null,
                kind = enumOr(r.text("kind"), ResultKind.TRAINING),
                eventName = r.textOrNull("eventName"),
            )
        }
        val synced = c.queryOne("SELECT synced_at FROM coach WHERE id = ?", owner) {
            it.getObject(1, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()
        }
        return CabinetInput(
            clientName = clientName(client),
            coachName = coachName(c, owner, coach),
            syncedAt = synced?.let { ClubTime.now(it, zone) },
            now = ClubTime.now(now(), zone),
            upcomingCount = settings?.long("upcomingInCard")?.toInt() ?: DEFAULT_UPCOMING,
            cancelNoticeMinutes = settings?.long("cancelNoticeMinutes")?.toInt() ?: DEFAULT_NOTICE,
            trainings = list,
            results = results,
            mediaUrl = client.textOrNull("mediaUrl"),
            consentDate = consent?.let { ClubTime.today(it.acceptedAt, zone) },
            photoConsent = consent?.photo ?: false,
            groupDiary = settings?.flag("cabinetGroupDiary") == true,
        )
    }

    private fun accounts(c: Connection, coachId: String): String =
        c.queryOne("SELECT display_name FROM coach WHERE id = ?", coachId) { it.getString(1) }.orEmpty()

    // Имена полей [field] — только из этого файла, не из запроса: их можно вписать в SQL.

    /** Одна живая запись таблицы телефона тренера, у которой поле [field] равно [value]. */
    private fun row(c: Connection, owner: String, entity: String, field: String, value: String): JsonObject? = c.queryOne(
        "SELECT data::text FROM sync_row WHERE owner = ? AND entity = ? AND deleted_at IS NULL AND data->>'$field' = ? ORDER BY updated_at DESC LIMIT 1",
        owner, entity, value,
    ) { Json.parseToJsonElement(it.getString(1)) as JsonObject }

    /** Живые записи таблицы [entity] владельцев [owners], у которых поле [field] — одно из [values]. */
    private fun rows(c: Connection, owners: List<String>, entity: String, field: String, values: List<String>): List<JsonObject> {
        if (values.isEmpty()) return emptyList()
        return c.query(
            "SELECT data::text FROM sync_row WHERE owner = ANY(?) AND entity = ? AND deleted_at IS NULL AND data->>'$field' = ANY(?)",
            c.createArrayOf("text", owners.toTypedArray()), entity, c.createArrayOf("text", values.distinct().toTypedArray()),
        ) { Json.parseToJsonElement(it.getString(1)) as JsonObject }
    }

    companion object {
        /** Токен — 32 байта в base64url: 43 знака; с запасом на другую длину, но не строки любой длины. */
        val TOKEN_LENGTH = 40..64
        const val PIN_ATTEMPTS = 5
        const val PREVIEW_MINUTES = 15
        const val BLOCK_MINUTES = 15
        const val PASS_DAYS = 30
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_UPCOMING = 4
        const val DEFAULT_NOTICE = 60
        const val USER_AGENT_MAX = 500

        /** Пункты согласия в записи (3.7). */
        const val PERSONAL = "personal_data"
        const val PHOTO = "photo_video"

        private fun at(millis: Long): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}

/** Поле записи телефона: строка, пустая — null. */
private fun JsonObject.textOrNull(name: String): String? = text(name).takeIf { it.isNotBlank() }

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** Логическое поле записи телефона: в SQLite — 0 или 1. */
private fun JsonObject.flag(name: String): Boolean {
    val value = this[name] as? JsonPrimitive ?: return false
    return value.booleanOrNull ?: (value.longOrNull?.let { it != 0L } ?: false)
}
