package com.akenory.personalka.server

import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.sync.SyncOrder
import com.akenory.personalka.domain.sync.SyncRules
import com.akenory.personalka.domain.sync.SyncStamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/** Изменение записи от телефона (7.2, п. 2): служебные поля и сама строка таблицы. */
data class Change(
    val entity: String,
    val id: String,
    val version: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val data: JsonObject,
)

/** Что сервер сделал с изменением; каждое из них телефон убирает из очереди. */
enum class PushResult(val code: String) {
    /** Записано (или уже было записано этим же изменением — повтор отправки). */
    ACCEPTED("accepted"),

    /** У сервера новее — придёт телефону при выдаче. */
    STALE("stale"),

    /** Данные клуба меняет только администратор (7.1). */
    CLUB_ONLY("club_only"),
}

/** Записанная на сервере строка — для выдачи. */
data class StoredRow(
    val entity: String,
    val id: String,
    val version: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val device: String,
    val data: JsonObject,
    val seq: Long,
)

/**
 * Хранение синхронизации (ТЗ 7.2, 3.8): строки телефона по тренеру (данные клуба — общие), «последнее изменение
 * побеждает» — [SyncRules.order], сквозной номер `seq`, удалённые — 90 дней. Всё — внутри транзакции вызывающего.
 * [now] — часы сервера (в тестах — свои).
 */
class Sync(val now: () -> Long = System::currentTimeMillis) {

    /** Чья запись: тренера или клуба (поле `scope` записи; сетка ставок — всегда клуба). */
    fun owner(change: Change, coachId: String): String =
        if (change.entity in SyncRules.CLUB_ENTITIES || change.data.text("scope") == Scope.CLUB.name) CLUB else coachId

    /** Принять пачку изменений тренера [coach] с устройства [device]; ответ — по изменению на каждое. */
    fun push(c: Connection, coach: Coach, device: String, changes: List<Change>): List<Pair<PushResult, Long?>> {
        // Отправки — по одной на весь сервер: номера seq видны в порядке записи (см. V3__sync.sql).
        c.queryOne("SELECT pg_advisory_xact_lock(hashtext('sync'))") { }
        val now = now()
        return changes.map { change ->
            val owner = owner(change, coach.id)
            if (owner == CLUB && !coach.admin) return@map PushResult.CLUB_ONLY to null
            val stored = c.queryOne(
                "SELECT updated_at, device_id, seq FROM sync_row WHERE owner = ? AND entity = ? AND id = ?",
                owner, change.entity, change.id,
            ) { SyncStamp(it.getLong(1), it.getString(2)) to it.getLong(3) }
            val stamp = SyncStamp(SyncRules.acceptedAt(change.updatedAt, now), device)
            when (SyncRules.order(stamp, stored?.first)) {
                SyncOrder.OLDER -> PushResult.STALE to null
                SyncOrder.SAME -> PushResult.ACCEPTED to stored!!.second
                SyncOrder.NEWER -> {
                    val seq = c.queryOne(
                        """
                        INSERT INTO sync_row (owner, entity, id, version, updated_at, deleted_at, device_id, data, seq, coach_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, nextval('sync_seq'), ?)
                        ON CONFLICT (owner, entity, id) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at,
                            deleted_at = EXCLUDED.deleted_at, device_id = EXCLUDED.device_id, data = EXCLUDED.data,
                            seq = EXCLUDED.seq, coach_id = EXCLUDED.coach_id
                        RETURNING seq
                        """.trimIndent(),
                        owner, change.entity, change.id, change.version, stamp.updatedAt, change.deletedAt, device,
                        change.data.toString(), coach.id,
                    ) { it.getLong(1) }
                    PushResult.ACCEPTED to seq
                }
            }
        }
    }

    /**
     * Изменения тренера и клуба после номера [since], не больше [limit], по порядку. null — удалённые записи после
     * [since] уже стёрты: устройству нужна полная синхронизация (7.2, п. 6).
     */
    fun pull(c: Connection, coachId: String, since: Long, limit: Int): List<StoredRow>? {
        if (since > 0 && since < purgedSeq(c)) return null
        return c.query(
            """
            SELECT entity, id, version, updated_at, deleted_at, device_id, data::text, seq FROM sync_row
            WHERE owner IN (?, ?) AND seq > ? ORDER BY seq LIMIT ?
            """.trimIndent(),
            coachId, CLUB, since, limit,
        ) { rs ->
            StoredRow(
                entity = rs.getString(1),
                id = rs.getString(2),
                version = rs.getLong(3),
                updatedAt = rs.getLong(4),
                deletedAt = rs.getObject(5)?.let { (it as Number).toLong() },
                device = rs.getString(6),
                data = Json.parseToJsonElement(rs.getString(7)) as JsonObject,
                seq = rs.getLong(8),
            )
        }
    }

    /**
     * Стереть записи, удалённые больше 90 дней назад, и запомнить старший стёртый номер: устройство, которое
     * спросит изменения до него, получит «нужна полная синхронизация». Ответ — сколько стёрто.
     */
    fun purge(c: Connection): Int {
        c.queryOne("SELECT pg_advisory_xact_lock(hashtext('sync'))") { }
        val purged = c.query(
            "DELETE FROM sync_row WHERE deleted_at IS NOT NULL AND deleted_at < ? RETURNING seq",
            SyncRules.purgeBefore(now()),
        ) { it.getLong(1) }
        val top = purged.maxOrNull() ?: return 0
        if (top > purgedSeq(c)) {
            c.update(
                "INSERT INTO server_info (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
                PURGED_KEY, top.toString(),
            )
        }
        return purged.size
    }

    /** «Отключиться от сервера» (7.1): записи тренера и его сессии стираются; аккаунт и данные клуба остаются. */
    fun deleteCoachData(c: Connection, coachId: String): Int {
        val rows = c.update("DELETE FROM sync_row WHERE owner = ?", coachId)
        c.update("DELETE FROM coach_session WHERE coach_id = ?", coachId)
        return rows
    }

    private fun purgedSeq(c: Connection): Long =
        c.queryOne("SELECT value FROM server_info WHERE key = ?", PURGED_KEY) { it.getString(1).toLong() } ?: 0

    companion object {
        /** Владелец данных клуба. */
        const val CLUB = ""
        private const val PURGED_KEY = "sync_purged_seq"
    }
}
