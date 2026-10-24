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
    const val SCHEMA_VERSION = 3

    /** Изменений в одной отправке, не больше. */
    const val PUSH_MAX = 500

    /** Изменений на странице выдачи: по умолчанию и не больше. */
    const val PULL_DEFAULT = 500
    const val PULL_MAX = 1000

    /** Момент из будущего больше чем на 5 минут заменяется временем сервера: часы телефона ушли вперёд. */
    const val FUTURE_MS = 5 * 60 * 1000L

    /** Удалённые записи хранятся 90 дней, потом стираются совсем. */
    const val TOMBSTONE_DAYS = 90

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

    /** Момент изменения, который запомнит сервер: из будущего больше чем на 5 минут — «сейчас». */
    fun acceptedAt(updatedAt: Long, now: Long): Long = if (updatedAt > now + FUTURE_MS) now else updatedAt

    /** Удалённая раньше этого момента запись стирается совсем. */
    fun purgeBefore(now: Long): Long = now - TOMBSTONE_DAYS * DAY_MS

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}

/** Что не так с версией схемы: приложение старое — 426 «обновите», новее сервера — сервер ещё не обновился. */
enum class SchemaProblem { TOO_OLD, TOO_NEW }
