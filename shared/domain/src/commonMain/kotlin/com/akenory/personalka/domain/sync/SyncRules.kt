package com.akenory.personalka.domain.sync

/**
 * Изменение одной записи для синхронизации (ТЗ 7.2): служебные поля 3.1 и [device] — устройство, которое её
 * изменило (при равных моментах побеждает большее). Моменты — миллисекунды UTC, как в базе телефона.
 */
data class SyncStamp(val updatedAt: Long, val device: String)

/** Сравнение версий одной записи: старая, та же (повтор) или новая. */
enum class SyncOrder { OLDER, SAME, NEWER }

/**
 * Правила синхронизации (ТЗ 7.2) — одни для сервера (2.9) и телефона (2.10): «последнее изменение побеждает»,
 * пачки, удалённые записи, версии схемы.
 */
object SyncRules {
    /** Версия схемы базы телефона, с которой работает этот код; совпадает с `PersonalkaDatabase.VERSION` (тест). */
    const val SCHEMA_VERSION = 4

    /** Изменений в одной отправке, не больше. */
    const val PUSH_MAX = 500

    /** Изменений на странице выдачи: по умолчанию и не больше. */
    const val PULL_DEFAULT = 500
    const val PULL_MAX = 1000

    /** Момент из будущего больше чем на 5 минут заменяется временем сервера: часы телефона ушли вперёд. */
    const val FUTURE_MS = 5 * 60 * 1000L

    /** Удалённые записи хранятся 90 дней, потом стираются совсем. */
    const val TOMBSTONE_DAYS = 90

    /** Телефон: обмен через 5 секунд после последнего изменения (7.2, п. 4) — пачка правок уходит одной отправкой. */
    const val QUIET_MS = 5_000L

    /** Телефон: обмен в фоне раз в 15 минут; это же — самая длинная пауза повтора после ошибки сети. */
    const val PERIOD_MS = 15 * 60 * 1000L

    /** Первая пауза повтора после ошибки сети; дальше — вдвое длиннее, но не больше [PERIOD_MS]. */
    const val RETRY_FIRST_MS = 30_000L

    /**
     * Синхронизируемые таблицы — все таблицы базы телефона (тест в `shared/data` сверяет список). Таблица,
     * которой здесь нет, сервером не принимается.
     */
    val ENTITIES: Set<String> = setOf(
        // 3.2
        "coach", "coach_settings", "category_version", "pay_scheme_version",
        // 3.3
        "location", "training_type", "rate_version", "club_rate_grid", "tier_scheme_version",
        // 3.4
        "schedule_block", "series", "series_client",
        // 3.5
        "client", "client_group", "group_member", "training", "participation", "result", "spt_case", "wait_entry",
        "client_relation",
    )

    /** Таблицы, где все записи — данные клуба (ТЗ 3.3); у остальных область — поле `scope` записи. */
    val CLUB_ENTITIES: Set<String> = setOf("club_rate_grid")

    /** Версия схемы, с которой сервер работает: нынешняя и предыдущая (7.2, п. 7). */
    fun schemaProblem(version: Int?, current: Int = SCHEMA_VERSION): SchemaProblem? = when {
        version == null || version < current - 1 -> SchemaProblem.TOO_OLD
        version > current -> SchemaProblem.TOO_NEW
        else -> null
    }

    /** Пришедшая запись [incoming] против хранящейся [stored]: новее — по моменту, при равенстве — по устройству. */
    fun order(incoming: SyncStamp, stored: SyncStamp?): SyncOrder = when {
        stored == null -> SyncOrder.NEWER
        incoming.updatedAt != stored.updatedAt -> if (incoming.updatedAt > stored.updatedAt) SyncOrder.NEWER else SyncOrder.OLDER
        incoming.device != stored.device -> if (incoming.device > stored.device) SyncOrder.NEWER else SyncOrder.OLDER
        else -> SyncOrder.SAME
    }

    /**
     * Телефон: применить ли пришедшую с сервера запись [incoming] к своей, изменённой в [localUpdatedAt] (`null` — записи нет).
     * Своя правка ещё не отправлена ([pending]) — то же правило, что на сервере, со своим устройством [device]. Отправлена —
     * сервер уже решил, кто новее: применяется всё, что не старше своей (то же самое, пришедшее обратно, — безвредно).
     */
    fun applyIncoming(incoming: SyncStamp, localUpdatedAt: Long?, pending: Boolean, device: String): Boolean = when {
        localUpdatedAt == null -> true
        pending -> order(incoming, SyncStamp(localUpdatedAt, device)) == SyncOrder.NEWER
        else -> incoming.updatedAt >= localUpdatedAt
    }

    /** Пауза перед повтором после [failures] ошибок сети подряд (7.2, п. 4): 30 с, 1, 2, 4, 8 минут, дальше — 15 минут. */
    fun retryDelay(failures: Int): Long {
        if (failures <= 0) return 0
        var delay = RETRY_FIRST_MS
        repeat(failures - 1) { delay = (delay * 2).coerceAtMost(PERIOD_MS) }
        return delay
    }

    /**
     * Телефон: пауза до следующего обмена. Сервер отказал ([refused]: копия закрыта, приложение старое, вход устарел) —
     * обычные 15 минут; ошибка связи — [retryDelay]; в очереди осталось изменённое за время обмена — [QUIET_MS];
     * иначе — 15 минут или до новой правки.
     */
    fun nextPause(failures: Int, pending: Int, refused: Boolean): Long = when {
        refused -> PERIOD_MS
        failures > 0 -> retryDelay(failures)
        pending > 0 -> QUIET_MS
        else -> PERIOD_MS
    }

    /** Момент изменения, который запомнит сервер: из будущего больше чем на 5 минут — «сейчас». */
    fun acceptedAt(updatedAt: Long, now: Long): Long = if (updatedAt > now + FUTURE_MS) now else updatedAt

    /** Изменения ждут отправки дольше этого — пункт «Нужно сделать» на «Главной» (5.2). */
    const val LATE_MS = 24 * 60 * 60 * 1000L

    /** Запись клуба (ТЗ 3.3): вся таблица сетки клуба или запись с `scope = CLUB`; остальное — данные тренера. */
    fun isClub(entity: String, scope: String?): Boolean = entity in CLUB_ENTITIES || scope == "CLUB"

    /**
     * Телефон, «Нужно сделать» (5.2): изменения не отправлены больше суток. [oldestPending] — самая давняя правка
     * в очереди, [lastSync] — последний полный обмен (после него очередь была пуста): правка ждёт не дольше, чем с
     * позднего из двух моментов.
     */
    fun late(pending: Int, oldestPending: Long?, lastSync: Long?, now: Long): Boolean =
        pending > 0 && oldestPending != null && now - maxOf(oldestPending, lastSync ?: 0) > LATE_MS

    /** Удалённая раньше этого момента запись стирается совсем. */
    fun purgeBefore(now: Long): Long = now - TOMBSTONE_DAYS * DAY_MS

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}

/** Что не так с версией схемы: приложение старое — 426 «обновите», новее сервера — сервер ещё не обновился. */
enum class SchemaProblem { TOO_OLD, TOO_NEW }
