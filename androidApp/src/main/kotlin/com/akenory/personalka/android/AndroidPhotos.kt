package com.akenory.personalka.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.akenory.personalka.ui.components.PlatformPhotos
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Фото аватарок (шаг 3.13): выбор — системное окно фото Android (разрешений не просит), хранение — папка
 * `avatars` в закрытой памяти приложения. В системную копию Google она не попадает (`allowBackup="false"`, ТЗ 10.3).
 * Создаётся в `onCreate` окна: окно выбора регистрируется до его показа.
 */
class AndroidPhotos(private val activity: ComponentActivity) : PlatformPhotos {
    private var onPhoto: ((ByteArray) -> Unit)? = null
    private val dir = File(activity.filesDir, "avatars")

    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = onPhoto
        onPhoto = null
        if (uri == null || callback == null) return@registerForActivityResult
        runCatching {
            // ImageDecoder сам поворачивает снимок по EXIF и сразу уменьшает: большое фото не заполняет память.
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = SIDE * 2f / minOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            square(bitmap)
        }.getOrNull()?.let(callback)
    }

    override fun pick(onPicked: (ByteArray) -> Unit) {
        onPhoto = onPicked
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun read(key: String): ByteArray? = file(key).takeIf { it.exists() }?.readBytes()

    override fun write(key: String, bytes: ByteArray) {
        dir.mkdirs()
        file(key).writeBytes(bytes)
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    override fun keys(): Set<String> =
        dir.listFiles()?.filter { it.name.endsWith(EXT) }?.mapTo(HashSet()) { it.name.removeSuffix(EXT) }.orEmpty()

    override fun decode(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    /** id клиентов — буквы, цифры и дефисы; другое в имени файла не пропускается. */
    private fun file(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + EXT)

    /** Квадрат по центру, [SIDE] px, JPEG 85 % — около 30 КБ на фото. */
    private fun square(bitmap: Bitmap): ByteArray {
        val side = minOf(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SIDE) Bitmap.createScaledBitmap(cropped, SIDE, SIDE, true) else cropped
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val SIDE = 384
        const val EXT = ".jpg"
    }
}
