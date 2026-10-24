package com.akenory.personalka.data.backup

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Обычная папка файловой системы ([BackupFolder] с путём вместо адреса): на телефоне — личная папка приложения
 * для копий перед обновлением базы ([UpdateCopies]), на компьютере — для тестов.
 */
fun fileBackupFolder(): BackupFolder = FileBackupFolder

private object FileBackupFolder : BackupFolder {
    override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(folder).apply { mkdirs() }
            // Сначала во временный файл: оборванная запись не оставит половину копии под настоящим именем.
            val part = File(dir, "$name.part")
            part.writeBytes(bytes)
            part.renameTo(File(dir, name)) || error("Не переименовать $part")
        }.getOrDefault(false)
    }

    override suspend fun list(folder: String): List<String>? = withContext(Dispatchers.IO) { File(folder).list()?.toList() }

    override suspend fun delete(folder: String, name: String) {
        withContext(Dispatchers.IO) { File(folder, name).delete() }
    }

    override suspend fun read(folder: String, name: String): ByteArray? =
        withContext(Dispatchers.IO) { runCatching { File(folder, name).readBytes() }.getOrNull() }
}
