package com.akenory.personalka.domain

import kotlin.random.Random

/**
 * Идентификаторы записей (ТЗ 3.1): UUID версии 7, создаются на устройстве, сервер их не меняет.
 *
 * Первые 48 бит — момент создания в миллисекундах UTC, поэтому новые записи сортируются после старых
 * (внутри одной миллисекунды порядок случайный). Остальные 74 бита — случайные.
 * Время передаётся снаружи: так генератор не зависит от часов и проверяется тестом.
 */
object Ids {
    private const val HEX = "0123456789abcdef"

    /** Формат `xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx`, строчными буквами. */
    val V7_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** Новый UUID v7 на момент [nowMillis] (миллисекунды от 01.01.1970 UTC). */
    fun newId(nowMillis: Long, random: Random = Random.Default): String {
        require(nowMillis in 0..0xFFFF_FFFF_FFFFL) { "Момент вне 48 бит: $nowMillis" }
        val bytes = random.nextBytes(16)
        for (i in 0..5) {
            bytes[i] = (nowMillis ushr (8 * (5 - i))).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte() // версия 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // вариант RFC 9562
        return format(bytes)
    }

    /** Момент создания из UUID v7, миллисекунды UTC. */
    fun createdMillis(id: String): Long {
        require(V7_PATTERN.matches(id)) { "Не UUID v7: $id" }
        return id.replace("-", "").substring(0, 12).toLong(16)
    }

    private fun format(bytes: ByteArray): String = buildString(36) {
        bytes.forEachIndexed { i, b ->
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }
}
