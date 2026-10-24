package com.akenory.personalka.server

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.cabinet.CabinetInput
import com.akenory.personalka.domain.cabinet.CabinetTraining
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/** Действующая ссылка на кабинет. */
data class CabinetAccess(
    val id: Long,
    val coachId: String,
    val clientId: String,
    val pinHash: String?,
    val pinFailures: Int,
    val blockedUntil: Long?,
)

/** Ссылка для списка в приложении. */
data class CabinetLink(val clientId: String, val createdAt: OffsetDateTime, val lastOpenedAt: OffsetDateTime?)

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

    fun links(c: Connection, coachId: String): List<CabinetLink> = c.query(
        "SELECT client_id, created_at, last_opened_at FROM cabinet_access WHERE coach_id = ? AND revoked_at IS NULL ORDER BY created_at",
        coachId,
    ) { CabinetLink(it.getString(1), it.getObject(2, OffsetDateTime::class.java), it.getObject(3, OffsetDateTime::class.java)) }

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
        ) {
            CabinetAccess(
                it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5),
                it.getObject(6, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
            )
        } ?: return null
        val coach = accounts.byId(c, access.coachId) ?: return null
        if (coach.suspended) return null
        if (Feature.CABINETS !in AccountRules.open(accounts.grants(c, coach), ClubTime.today(now()))) return null
        val settings = row(c, coach.id, "coach_settings", "coachId", coach.id) ?: return null
        if (!settings.flag("cabinetsEnabled")) return null
        val client = row(c, coach.id, "client", "id", access.clientId) ?: return null
        if (!client.flag("cabinetEnabled")) return null
        return access
    }

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

    /** Родитель открыл кабинет — видно тренеру (3.7). */
    fun opened(c: Connection, access: CabinetAccess) {
        c.update("UPDATE cabinet_access SET last_opened_at = ? WHERE id = ?", at(now()), access.id)
    }

    /**
     * Данные страницы клиента [access]: имя клиента и тренера, настройки, участия клиента и только их тренировки.
     * Удалённые записи не показываются; тренировка без типа или места — тоже (такого не бывает, но чужое не угадывается).
     */
    fun input(c: Connection, access: CabinetAccess): CabinetInput {
        val owner = access.coachId
        val client = row(c, owner, "client", "id", access.clientId)!!
        val coach = row(c, owner, "coach", "coachId", owner)
        val settings = row(c, owner, "coach_settings", "coachId", owner)
        val zone = coach?.text("timezone")?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: ClubTime.defaultZone
        val participations = rows(c, listOf(owner), "participation", "clientId", listOf(access.clientId))
        val trainings = rows(c, listOf(owner), "training", "id", participations.map { it.text("trainingId") })
            .associateBy { it.text("id") }
        val types = rows(c, listOf(owner, Sync.CLUB), "training_type", "id", trainings.values.map { it.text("typeId") })
            .associateBy { it.text("id") }
        val places = rows(c, listOf(owner, Sync.CLUB), "location", "id", trainings.values.map { it.text("locationId") })
            .associateBy { it.text("id") }
        val list = participations.mapNotNull { p ->
            val training = trainings[p.text("trainingId")] ?: return@mapNotNull null
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
            )
        }
        val synced = c.queryOne("SELECT synced_at FROM coach WHERE id = ?", owner) {
            it.getObject(1, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()
        }
        return CabinetInput(
            clientName = listOf(client.text("lastName"), client.text("firstName")).filter { it.isNotBlank() }.joinToString(" "),
            coachName = coach?.let { listOf(it.text("firstName"), it.text("lastName")).filter(String::isNotBlank).joinToString(" ") }
                ?.takeIf { it.isNotBlank() }
                ?: accounts(c, owner),
            syncedAt = synced?.let { ClubTime.now(it, zone) },
            now = ClubTime.now(now(), zone),
            upcomingCount = settings?.long("upcomingInCard")?.toInt() ?: DEFAULT_UPCOMING,
            cancelNoticeMinutes = settings?.long("cancelNoticeMinutes")?.toInt() ?: DEFAULT_NOTICE,
            trainings = list,
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
        const val BLOCK_MINUTES = 15
        const val PASS_DAYS = 30
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_UPCOMING = 4
        const val DEFAULT_NOTICE = 60

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
