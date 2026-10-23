package com.akenory.personalka.android

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.akenory.personalka.ui.settings.PlatformFiles
import java.io.File

/**
 * Системные окна для копий (шаг 1.15): выбор папки и файла — Storage Access Framework, «Поделиться» — Intent
 * с файлом из временной папки через FileProvider. Создаётся в `onCreate` окна: окна выбора регистрируются до его показа.
 */
class AndroidFiles(private val activity: ComponentActivity) : PlatformFiles {
    private var onFolder: ((String, String) -> Unit)? = null
    private var onFile: ((ByteArray) -> Unit)? = null

    private val folderPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val callback = onFolder
        onFolder = null
        if (uri == null || callback == null) return@registerForActivityResult
        // Доступ к папке — навсегда: автокопия пишет в неё и после перезапуска телефона.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
        callback(uri.toString(), folderName(uri))
    }

    private val filePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onFile
        onFile = null
        if (uri == null || callback == null) return@registerForActivityResult
        runCatching { activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let(callback)
    }

    override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) {
        onFolder = onPicked
        folderPicker.launch(null)
    }

    override fun openFile(onOpened: (ByteArray) -> Unit) {
        onFile = onOpened
        // Копию присылают по-разному (zip, «файл»), поэтому выбор — из всех файлов.
        filePicker.launch(arrayOf("*/*"))
    }

    override fun share(name: String, mime: String, bytes: ByteArray) {
        val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
        // Прошлые файлы с данными клиентов во временной папке не копятся.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, name))
    }

    /** Название папки для строки «Папка для копий»: «Personalka», «Download». */
    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        activity.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: tree.lastPathSegment.orEmpty().substringAfterLast(':').ifEmpty { "Папка" }
}
