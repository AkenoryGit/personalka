@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.akenory.personalka.data.backup

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

/**
 * Папки на iPhone (шаг 5.1). [fileBackupFolder] — обычный путь: копии перед обновлением базы ([UpdateCopies]) в личной
 * папке приложения. [bookmarkFolder] — папка, выбранная тренером в «Файлах» (iCloud Drive, «На iPhone»): адрес — закладка
 * (bookmark) в base64, доступ к ней открывается на каждую запись.
 */
fun fileBackupFolder(): BackupFolder = IosFolder { path -> NSURL.fileURLWithPath(path) }

fun bookmarkFolder(): BackupFolder = IosFolder { bookmark -> IosBookmarks.resolve(bookmark) }

private class IosFolder(private val url: (String) -> NSURL?) : BackupFolder {
    override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        access(folder) { dir ->
            NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
            // Запись через временный файл (atomically): оборванная запись не оставит половину копии.
            bytes.toNSData().writeToURL(dir.URLByAppendingPathComponent(name)!!, atomically = true)
        } ?: false
    }

    override suspend fun list(folder: String): List<String>? = withContext(Dispatchers.IO) {
        access(folder) { dir ->
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir.path!!, null)?.map { it as String }
        }
    }

    override suspend fun delete(folder: String, name: String) {
        withContext(Dispatchers.IO) { access(folder) { dir -> NSFileManager.defaultManager.removeItemAtURL(dir.URLByAppendingPathComponent(name)!!, null) } }
    }

    override suspend fun read(folder: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        access(folder) { dir -> NSData.dataWithContentsOfURL(dir.URLByAppendingPathComponent(name)!!)?.toByteArray() }
    }

    private inline fun <T> access(folder: String, block: (NSURL) -> T): T? {
        val dir = url(folder) ?: return null
        val scoped = dir.startAccessingSecurityScopedResource()
        return try {
            runCatching { block(dir) }.getOrNull()
        } finally {
            if (scoped) dir.stopAccessingSecurityScopedResource()
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
