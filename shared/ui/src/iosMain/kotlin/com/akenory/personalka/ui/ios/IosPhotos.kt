package com.akenory.personalka.ui.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.akenory.personalka.data.backup.toByteArray
import com.akenory.personalka.data.backup.toNSData
import com.akenory.personalka.ui.components.PlatformPhotos
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject

/**
 * Фото аватарок на iPhone (шаг 5.1, как 3.13): выбор — системное окно фото (PHPicker, разрешений не просит),
 * хранение — папка `avatars` в личной папке приложения. Квадрат по центру 384 px, JPEG 85 %.
 */
@OptIn(ExperimentalForeignApi::class)
object IosPhotos : PlatformPhotos {
    private const val SIDE = 384.0
    private const val EXT = ".jpg"
    private val dir: String by lazy { "${IosApp.folder}/avatars".also { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) } }
    private var delegate: Picker? = null

    override fun pick(onPicked: (ByteArray) -> Unit) {
        val config = PHPickerConfiguration().apply {
            setFilter(PHPickerFilter.imagesFilter)
            setSelectionLimit(1)
        }
        val picker = PHPickerViewController(configuration = config)
        val callback = Picker { image ->
            delegate = null
            image?.let(::square)?.let { bytes -> onMain { onPicked(bytes) } }
        }
        delegate = callback
        picker.delegate = callback
        present(picker)
    }

    override fun read(key: String): ByteArray? = NSData.dataWithContentsOfURL(url(key))?.toByteArray()

    override fun write(key: String, bytes: ByteArray) {
        bytes.toNSData().writeToURL(url(key), atomically = true)
    }

    override fun delete(key: String) {
        NSFileManager.defaultManager.removeItemAtURL(url(key), null)
    }

    override fun keys(): Set<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
            .map { it as String }.filter { it.endsWith(EXT) }.mapTo(HashSet()) { it.removeSuffix(EXT) }

    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    /** id клиентов — буквы, цифры и дефисы; другое в имени файла не пропускается. */
    private fun url(key: String) = NSURL.fileURLWithPath("$dir/" + key.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + EXT)

    /** Квадрат по центру, не больше [SIDE] px; поворот по EXIF UIImage учитывает сам при рисовании. */
    private fun square(image: UIImage): ByteArray? {
        val (w, h) = image.size.useContents { width to height }
        val side = minOf(w, h)
        val out = minOf(side, SIDE)
        val scale = out / side
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(out, out), format = format)
        val squared = renderer.imageWithActions { _ ->
            image.drawInRect(CGRectMake(-(w - side) / 2 * scale, -(h - side) / 2 * scale, w * scale, h * scale))
        }
        return UIImageJPEGRepresentation(squared, 0.85)?.toByteArray()
    }

    private const val IMAGE = "public.image"

    private class Picker(private val done: (UIImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, null)
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
            if (provider == null || !provider.hasItemConformingToTypeIdentifier(IMAGE)) {
                done(null)
                return
            }
            // Данные снимка (HEIC, JPEG, PNG) — UIImage раскодирует сам, с поворотом по EXIF.
            provider.loadDataRepresentationForTypeIdentifier(IMAGE) { data, _ -> done(data?.let { UIImage(data = it) }) }
        }
    }
}
