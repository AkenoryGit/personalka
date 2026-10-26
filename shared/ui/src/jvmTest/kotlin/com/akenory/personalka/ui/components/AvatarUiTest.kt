package com.akenory.personalka.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.AvatarAge
import com.akenory.personalka.domain.client.AvatarLook
import com.akenory.personalka.domain.client.AvatarRules
import com.akenory.personalka.domain.format.AvatarFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.theme.PersonalkaTheme
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
}
