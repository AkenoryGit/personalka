package com.akenory.personalka.server

import com.akenory.personalka.domain.format.BackupMark
import com.akenory.personalka.domain.format.ServerStatus
import java.io.File
import java.time.OffsetDateTime

/**
 * Строка «Сервер» в админке (шаг 5.3, ТЗ 5.17, 7.5): версия сборки, место на диске и последняя ночная копия базы.
 * Копию отмечает `deploy/backup.sh` в файле [backupStatus] одной строкой «время ⇥ ok|ошибка ⇥ подробности»;
 * файла нет или он не читается — копий ещё не было. Данных тренеров здесь нет.
 */
class ServerState(
    private val version: String,
    private val backupStatus: File?,
    private val disk: File = File("/"),
) {
    fun read(): ServerStatus {
        val total = runCatching { disk.totalSpace }.getOrDefault(0L)
        val free = runCatching { disk.usableSpace }.getOrDefault(0L)
        return ServerStatus(version, (total - free).coerceAtLeast(0), total, backup())
    }

    private fun backup(): BackupMark? {
        val line = runCatching { backupStatus?.takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() } }.getOrNull() ?: return null
        return parse(line)
    }

    companion object {
        /** «2026-10-28T03:30:12+05:00⇥ok⇥28.10.2026: …». У удачной копии подробности не показываются — там имена файлов. */
        fun parse(line: String): BackupMark? {
            val parts = line.split('\t')
            val at = runCatching { OffsetDateTime.parse(parts[0].trim()).club() }.getOrNull() ?: return null
            val ok = parts.getOrNull(1)?.trim() == "ok"
            return BackupMark(at, ok, if (ok) "" else parts.getOrNull(2)?.trim().orEmpty())
        }
    }
}
