package com.akenory.personalka.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Папка для копий на Android — выбранная системным диалогом (Storage Access Framework): память телефона,
 * карта или облако, если его приложение даёт свои папки. Доступ к ней приложение держит постоянно.
 */
fun safBackupFolder(context: Context): BackupFolder = SafBackupFolder(context.applicationContext.contentResolver)

private class SafBackupFolder(private val resolver: ContentResolver) : BackupFolder {
    override suspend fun write(folder: String, name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tree = Uri.parse(folder)
            val existing = children(tree)?.get(name)
            val doc = existing ?: DocumentsContract.createDocument(resolver, root(tree), mime(name), name) ?: return@runCatching false
            // «wt» — переписать с начала: файл с тем же именем (копия в ту же минуту) заменяется.
            resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    override suspend fun list(folder: String): List<String>? = withContext(Dispatchers.IO) {
        runCatching { children(Uri.parse(folder))?.keys?.toList() }.getOrNull()
    }

    override suspend fun delete(folder: String, name: String) {
        withContext(Dispatchers.IO) {
            runCatching { children(Uri.parse(folder))?.get(name)?.let { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }

    private fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Файлы папки по именам; `null` — папка недоступна. */
    private fun children(tree: Uri): Map<String, Uri>? {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(list, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
        }
    }

    private fun mime(name: String): String = if (name.endsWith(".zip")) "application/zip" else "application/octet-stream"
}
