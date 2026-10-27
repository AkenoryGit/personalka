package com.akenory.personalka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.AvatarAge
import com.akenory.personalka.domain.client.AvatarLook
import com.akenory.personalka.domain.client.AvatarRules
import com.akenory.personalka.domain.format.AvatarFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.theme.PersonalkaTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Аватарки (шаг 3.13): выбор и удаление фото, запрет без согласия на фото, фото удалённых клиентов. */
@OptIn(ExperimentalTestApi::class)
class AvatarUiTest {
    private class Photos(var next: ByteArray? = byteArrayOf(1, 2, 3)) : PlatformPhotos {
        val files = HashMap<String, ByteArray>()
        override fun pick(onPicked: (ByteArray) -> Unit) { next?.let(onPicked) }
        override fun read(key: String) = files[key]
        override fun write(key: String, bytes: ByteArray) { files[key] = bytes }
        override fun delete(key: String) { files.remove(key) }
        override fun keys() = files.keys.toSet()
        override fun decode(bytes: ByteArray) = ImageBitmap(4, 4)
    }

    private val look = AvatarLook("АД", 3, AvatarAge.KID, Sex.FEMALE)

    @Test
    fun pickThenRemovePhoto() = runComposeUiTest {
        val platform = Photos()
        val avatars = AvatarPhotos(platform)
        setContent {
            PersonalkaTheme {
                CompositionLocalProvider(LocalAvatarPhotos provides avatars) { EditableAvatar(look, "c1", 64.dp) }
            }
        }
        onNodeWithTag(AVATAR_TAG).performClick()
        onNodeWithText(AvatarFormat.PICK).assertIsDisplayed()
        onNodeWithTag(AVATAR_PICK_TAG).performClick()
        assertTrue(avatars.has("c1"))
        assertEquals(setOf("c1"), platform.files.keys)

        onNodeWithTag(AVATAR_TAG).performClick()
        onNodeWithText(AvatarFormat.CHANGE).assertIsDisplayed()
        onNodeWithTag(AVATAR_REMOVE_TAG).performClick()
        assertFalse(avatars.has("c1"))
        assertTrue(platform.files.isEmpty())
    }

    @Test
    fun noPhotoConsentErasesPhotoAndBlocksPicking() = runComposeUiTest {
        val platform = Photos().apply { files["c1"] = byteArrayOf(1) }
        val avatars = AvatarPhotos(platform)
        avatars.forbid(setOf("c1"))
        assertTrue(platform.files.isEmpty())
        setContent {
            PersonalkaTheme {
                CompositionLocalProvider(LocalAvatarPhotos provides avatars) { EditableAvatar(look, "c1", 64.dp) }
            }
        }
        onNodeWithTag(AVATAR_TAG).performClick()
        onNodeWithText(AvatarFormat.NO_CONSENT).assertIsDisplayed()
        onNodeWithTag(AVATAR_PICK_TAG).assertDoesNotExist()
        avatars.pick("c1")
        assertFalse(avatars.has("c1"))
    }

    @Test
    fun photosOfGoneClientsAreErasedButCoachStays() {
        val platform = Photos().apply {
            files["c1"] = byteArrayOf(1)
            files["c2"] = byteArrayOf(2)
            files[AvatarRules.COACH_KEY] = byteArrayOf(3)
        }
        AvatarPhotos(platform).keepOnly(setOf("c1"))
        assertEquals(setOf("c1", AvatarRules.COACH_KEY), platform.files.keys)
    }

    @Test
    fun drawnAvatarWithoutPhotos() = runComposeUiTest {
        setContent { PersonalkaTheme { EditableAvatar(look, "c1", 40.dp) } }
        // Без фото в телефоне аватарка только рисуется: меню нет.
        onNodeWithTag(AVATAR_TAG).assertDoesNotExist()
    }

    /**
     * Все рисунки (шаг 3.13а): шесть возрастов × мужской, женский, не известен — рисуются без ошибок
     * и все разные. С `AVATAR_SHEET=путь.png` лист сохраняется картинкой — чтобы показать Дмитрию.
     */
    @Test
    fun everyFigureDrawsAndDiffers() = runComposeUiTest {
        val sexes = listOf(Sex.MALE, Sex.FEMALE, null)
        setContent { Sheet(sexes) }
        val image = onNodeWithTag(SHEET_TAG).captureToImage()
        // Лист для Дмитрия — вдвое крупнее, как на телефоне.
        System.getenv("AVATAR_SHEET")?.let { path ->
            val scene = ImageComposeScene(944, 1312, Density(2f)) { Sheet(sexes) }
            File(path).writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        // Один цвет, разные возраст и пол — разные картинки.
        val pixels = image.toPixelMap()
        val cells = AvatarAge.entries.indices.flatMap { row ->
            sexes.indices.map { col ->
                val x0 = 8 + 4 + col * 48 + 3 * 104
                val y0 = 8 + 4 + row * 104
                (0 until 40).flatMap { y ->
                    (0 until 40).map { x -> pixels[((x0 + x) * density.density).toInt(), ((y0 + y) * density.density).toInt()] }
                }
            }
        }
        assertEquals(cells.size, cells.toSet().size)
    }

    @Composable
    private fun Sheet(sexes: List<Sex?>) {
        PersonalkaTheme {
            Column(Modifier.background(Color.White).padding(8.dp).testTag(SHEET_TAG)) {
                AvatarAge.entries.forEachIndexed { row, age ->
                    Row {
                        sexes.forEachIndexed { col, sex ->
                            val look = AvatarLook("АД", (row * 3 + col) % AvatarRules.TONES, age, sex)
                            Avatar(look, null, 96.dp, Modifier.padding(4.dp))
                        }
                        sexes.forEach { sex ->
                            Avatar(AvatarLook("АД", 4, age, sex), null, 40.dp, Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val SHEET_TAG = "avatar-sheet"
    }
}
