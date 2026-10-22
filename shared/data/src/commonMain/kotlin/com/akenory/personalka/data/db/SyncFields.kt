package com.akenory.personalka.data.db

/**
 * Служебные поля каждой записи (ТЗ 3.1). Встраиваются в таблицу колонками `coachId`, `createdAt`,
 * `updatedAt`, `version`, `deletedAt`.
 *
 * Моменты — миллисекунды от 01.01.1970 UTC. Удаление мягкое: запись помечается [deletedAt],
 * остаётся в базе для синхронизации и стирается физически через 90 дней.
 */
data class SyncFields(
    /** Тренер, которому принадлежит запись; у данных клуба — администратор, который их внёс. */
    val coachId: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** +1 при каждом изменении. */
    val version: Int = 1,
    val deletedAt: Long? = null,
) {
    /** Поля после изменения записи в момент [now]. */
    fun touched(now: Long): SyncFields = copy(updatedAt = now, version = version + 1)

    /** Поля после мягкого удаления в момент [now]. */
    fun deleted(now: Long): SyncFields = copy(updatedAt = now, version = version + 1, deletedAt = now)

    companion object {
        /** Поля новой записи тренера [coachId], созданной в момент [now]. */
        fun new(coachId: String, now: Long): SyncFields = SyncFields(coachId, createdAt = now, updatedAt = now)
    }
}
