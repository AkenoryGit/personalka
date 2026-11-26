package com.akenory.personalka.domain.cabinet

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus

/**
 * Кабинет одного клиента в админке (шаг 5.35): [issued] — день выдачи действующей ссылки, [lastOpened] — последнее
 * открытие (по всем ссылкам клиента, по клубу), [days] — посещения по дням клуба; [name] — имя, только у своих
 * клиентов администратора (у коллег — null), [closed] — кабинет выключен у клиента или клиент удалён.
 */
data class CabinetVisits(
    val clientId: String,
    val issued: LocalDate,
    val lastOpened: LocalDateTime?,
    val days: Map<LocalDate, Int>,
    val name: String? = null,
    val closed: Boolean = false,
)

/** Как пользуются кабинетом: заходили за [CabinetVisitRules.RECENT_DAYS] дней, давно или ни разу. */
enum class CabinetUse { ACTIVE, IDLE, NEVER }

/** Строка кабинета: [recent] — посещений за 30 дней, [recentDays] — в сколько разных дней, [total] — всего. */
data class CabinetUseLine(val visits: CabinetVisits, val use: CabinetUse, val recent: Int, val recentDays: Int, val total: Int)

/** Итог кабинетов тренера для строки в админке. */
data class CabinetUsage(val total: Int, val active: Int, val idle: Int, val never: Int)

/**
 * Посещения кабинетов (шаг 5.35, просьба Дмитрия 26.11.2026: видеть в админке, кто пользуется кабинетом, а кто нет).
 * Посещение — открытие кабинета по ссылке; повторные открытия меньше чем через [NEW_VISIT_MINUTES] минут после
 * предыдущего — то же посещение (листание графика, обновление страницы). «Как видит родитель» не считается (6.4).
 */
object CabinetVisitRules {
    const val NEW_VISIT_MINUTES = 30
    const val RECENT_DAYS = 30

    /** Новое ли посещение: прошлого открытия не было или оно было [NEW_VISIT_MINUTES] минут назад и раньше. */
    fun newVisit(previousMs: Long?, nowMs: Long): Boolean =
        previousMs == null || nowMs - previousMs >= NEW_VISIT_MINUTES * 60_000L

    /** Первый день окна «за 30 дней»: сегодня и 29 дней до него. */
    fun recentFrom(today: LocalDate): LocalDate = today.minus(DatePeriod(days = RECENT_DAYS - 1))

    fun use(v: CabinetVisits, today: LocalDate): CabinetUse = when {
        v.lastOpened == null -> CabinetUse.NEVER
        v.lastOpened.date >= recentFrom(today) -> CabinetUse.ACTIVE
        else -> CabinetUse.IDLE
    }

    fun line(v: CabinetVisits, today: LocalDate): CabinetUseLine {
        val from = recentFrom(today)
        val recent = v.days.filterKeys { it in from..today }.values.filter { it > 0 }
        return CabinetUseLine(v, use(v, today), recent.sum(), recent.size, v.days.values.sum())
    }

    fun usage(list: List<CabinetVisits>, today: LocalDate): CabinetUsage {
        val uses = list.map { use(it, today) }
        return CabinetUsage(list.size, uses.count { it == CabinetUse.ACTIVE }, uses.count { it == CabinetUse.IDLE }, uses.count { it == CabinetUse.NEVER })
    }

    /**
     * Строки по группам: заходят, давно не заходили, ни разу. В первых двух — последние открывшие сверху; среди
     * не открывавших — сначала те, кому ссылку выдали раньше всех: их пора спросить первыми. При равенстве — по имени.
     */
    fun lines(list: List<CabinetVisits>, today: LocalDate): Map<CabinetUse, List<CabinetUseLine>> {
        val byName = compareBy<CabinetUseLine> { it.visits.name?.lowercase() ?: "" }.thenBy { it.visits.clientId }
        val lines = list.map { line(it, today) }
        return CabinetUse.entries.associateWith { use ->
            val group = lines.filter { it.use == use }
            when (use) {
                CabinetUse.NEVER -> group.sortedWith(compareBy<CabinetUseLine> { it.visits.issued }.then(byName))
                else -> group.sortedWith(compareByDescending<CabinetUseLine> { it.visits.lastOpened }.then(byName))
            }
        }.filterValues { it.isNotEmpty() }
    }
}
