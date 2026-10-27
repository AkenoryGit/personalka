package com.akenory.personalka.ui.ios

import com.akenory.personalka.data.backup.IosBookmarks
import com.akenory.personalka.data.backup.toByteArray
import com.akenory.personalka.data.backup.toNSData
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.settings.SharedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject

/**
 * Файлы на iPhone (шаг 5.1): папка копий и файлы выбираются окном «Файлов» (UIDocumentPicker), отправка — системным
 * «Поделиться» (UIActivityViewController). Отчёт 1С, открытый в «Персоналке» из почты или «Файлов» («Открыть в…»),
 * приходит через [receive] — как «Поделиться» на Android.
 */
object IosFiles : PlatformFiles {
    private val incomingFlow = MutableStateFlow<SharedFile?>(null)
    override val incoming: StateFlow<SharedFile?> get() = incomingFlow

    /** Окно выбора держит делегат слабо — текущий хранится здесь, пока окно открыто. */
    private var delegate: Picker? = null

    override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) {
        pick(listOf(UTTypeFolder)) { url ->
            IosBookmarks.of(url)?.let { onPicked(it, url.lastPathComponent ?: "") }
        }
    }

    override fun openFile(onOpened: (ByteArray) -> Unit) {
        pick(listOf(UTTypeItem)) { url -> read(url)?.let(onOpened) }
    }

    override fun openReport(onOpened: (SharedFile) -> Unit) {
        pick(listOf(UTTypePDF)) { url -> read(url)?.let { onOpened(SharedFile(url.lastPathComponent ?: "", it)) } }
    }

    override fun takeIncoming() {
        incomingFlow.value = null
    }

    /** Файл, открытый в приложении извне; `true` — это PDF, «Сверки» его заберут. */
    fun receive(url: NSURL): Boolean {
        val name = url.lastPathComponent ?: return false
        if (!name.endsWith(".pdf", ignoreCase = true)) return false
        val bytes = read(url) ?: return false
        incomingFlow.value = SharedFile(name, bytes)
        return true
    }

    override fun share(name: String, mime: String, bytes: ByteArray) {
        // Файл с настоящим именем во временной папке: его имя увидит получатель.
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name)
        if (!bytes.toNSData().writeToURL(url, atomically = true)) return
        present(UIActivityViewController(listOf(url), null))
    }

    fun shareText(text: String) = present(UIActivityViewController(listOf(text), null))

    fun copy(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    private fun pick(types: List<UTType>, onPicked: (NSURL) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = false)
        val callback = Picker { url ->
            delegate = null
            url?.let(onPicked)
        }
        delegate = callback
        picker.delegate = callback
        picker.allowsMultipleSelection = false
        present(picker)
    }

    private fun read(url: NSURL): ByteArray? {
        val scoped = url.startAccessingSecurityScopedResource()
        return try {
            NSData.dataWithContentsOfURL(url)?.toByteArray()
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private class Picker(private val done: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = done(null)
    }
}
