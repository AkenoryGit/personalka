package com.akenory.personalka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenory.personalka.domain.cabinet.CabinetLinkRules
import com.akenory.personalka.domain.cabinet.ConsentInfo
import com.akenory.personalka.domain.client.AvatarAge
import com.akenory.personalka.domain.client.AvatarLook
import com.akenory.personalka.domain.client.AvatarRules
import com.akenory.personalka.domain.format.AvatarFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.PlaceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Фото аватарок в телефоне (шаг 3.13): галерея, папка в закрытой памяти приложения и раскодирование картинки.
 * На сервер, в кабинет и в копии фото не попадают (ТЗ 11.5, 10.3).
 */
interface PlatformPhotos {
    /** Системный выбор фото; выбранное — уже квадратом по центру, JPEG не больше 384 px. Отмена — ничего. */
    fun pick(onPicked: (ByteArray) -> Unit)

    fun read(key: String): ByteArray?

    fun write(key: String, bytes: ByteArray)

    fun delete(key: String)

    /** Ключи сохранённых фото: id клиентов и [AvatarRules.COACH_KEY]. */
    fun keys(): Set<String>

    fun decode(bytes: ByteArray): ImageBitmap?
}

/**
 * Фото аватарок для экранов: какие есть, раскодированные — в памяти. Фото клиента без согласия на фото (6.1)
 * не хранится: [forbid] стирает его, и выбрать новое нельзя, пока согласия нет.
 */
@Stable
class AvatarPhotos(private val platform: PlatformPhotos) {
    /** Ключ → номер версии фото: новое фото под тем же ключом раскодируется заново. */
    private val saved = mutableStateMapOf<String, Int>().apply { platform.keys().forEach { put(it, 0) } }
    private val decoded = HashMap<String, Pair<Int, ImageBitmap>>()
    private var forbidden by mutableStateOf<Set<String>>(emptySet())

    fun has(key: String): Boolean = key in saved && key !in forbidden

    fun allowed(key: String): Boolean = key !in forbidden

    internal fun version(key: String): Int = saved[key] ?: -1

    internal suspend fun load(key: String): ImageBitmap? {
        val version = saved[key] ?: return null
        decoded[key]?.takeIf { it.first == version }?.let { return it.second }
        val bitmap = withContext(Dispatchers.Default) { platform.read(key)?.let(platform::decode) } ?: return null
        decoded[key] = version to bitmap
        return bitmap
    }

    fun pick(key: String) {
        if (!allowed(key)) return
        platform.pick { bytes ->
            platform.write(key, bytes)
            saved[key] = (saved[key] ?: 0) + 1
        }
    }

    fun remove(key: String) {
        platform.delete(key)
        saved.remove(key)
        decoded.remove(key)
    }

    /** Клиенты без согласия на фото: их фото стираются, новое не выбирается. */
    fun forbid(keys: Set<String>) {
        forbidden = keys
        keys.filter { it in saved }.forEach(::remove)
    }

    /** Фото удалённых и объединённых клиентов стираются: остаются только [ids] и фото тренера. */
    fun keepOnly(ids: Set<String>) {
        saved.keys.filter { it != AvatarRules.COACH_KEY && it !in ids }.forEach(::remove)
    }
}

/** Фото аватарок; `null` — фото не выбираются (тесты, телефон без галереи): видны только рисованные. */
val LocalAvatarPhotos = staticCompositionLocalOf<AvatarPhotos?> { null }

/**
 * Фото аватарок для всего приложения (шаг 3.13) — платформа оборачивает им `App`. Фото клиентов без согласия
 * на фото (6.1) стираются по [consents] — последнему ответу сервера, который помнит телефон; фото удалённых
 * и объединённых клиентов — по [clientIds].
 */
@Composable
fun ProvideAvatarPhotos(
    photos: PlatformPhotos,
    clientIds: Flow<Set<String>>,
    consents: Flow<Map<String, ConsentInfo>>,
    content: @Composable () -> Unit,
) {
    val avatars = remember(photos) { AvatarPhotos(photos) }
    LaunchedEffect(avatars, consents) {
        consents.collect { all -> avatars.forbid(all.filterValues { CabinetLinkRules.noPhoto(it) }.keys) }
    }
    LaunchedEffect(avatars, clientIds) {
        // Пустой список — база ещё открывается или клиентов нет: стирать по нему нечего.
        clientIds.collect { ids -> if (ids.isNotEmpty()) avatars.keepOnly(ids) }
    }
    CompositionLocalProvider(LocalAvatarPhotos provides avatars, content = content)
}

const val AVATAR_TAG = "avatar"
const val AVATAR_PICK_TAG = "avatar-pick"
const val AVATAR_REMOVE_TAG = "avatar-remove"

/**
 * Аватарка: фото, если оно есть под [photoKey], иначе рисованный пловец [look] — шапочка цвета человека
 * с инициалами, у девочек и женщин — пучок под шапочкой, у подростков и взрослых — очки для плавания на лбу,
 * у детей — румянец, у тех, кому 60+, — очки, у тренера — свисток.
 */
@Composable
fun Avatar(look: AvatarLook, photoKey: String?, size: Dp, modifier: Modifier = Modifier) {
    val photos = LocalAvatarPhotos.current
    val shownKey = photoKey?.takeIf { photos?.has(it) == true }
    val version = shownKey?.let { photos?.version(it) } ?: -1
    val bitmap by produceState<ImageBitmap?>(null, photos, shownKey, version) {
        value = shownKey?.let { photos?.load(it) }
    }
    Box(modifier.size(size).clip(CircleShape).semantics { contentDescription = AvatarFormat.DESCRIPTION }) {
        val photo = bitmap
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Swimmer(look, Modifier.fillMaxSize())
        }
    }
}

/**
 * Аватарка, которую можно сменить: нажатие открывает меню «Выбрать фото» / «Другое фото» и «Убрать фото»,
 * без согласия на фото — только «Нет согласия на фото». Без [LocalAvatarPhotos] — просто аватарка.
 */
@Composable
fun EditableAvatar(look: AvatarLook, photoKey: String, size: Dp, modifier: Modifier = Modifier) {
    val photos = LocalAvatarPhotos.current
    if (photos == null) {
        Avatar(look, photoKey, size, modifier)
        return
    }
    val colors = PersonalkaTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(Modifier.clickable(role = Role.Button) { open = true }.testTag(AVATAR_TAG)) {
            Avatar(look, photoKey, size)
            // Значок карандаша — подсказка, что аватарку можно сменить.
            Box(
                Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(22.dp).clip(CircleShape)
                    .background(colors.surface).border(1.dp, colors.divider, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PIcons.Edit, contentDescription = null, tint = colors.text, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            if (!photos.allowed(photoKey)) {
                Text(
                    AvatarFormat.NO_CONSENT,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.error,
                )
                return@DropdownMenu
            }
            val has = photos.has(photoKey)
            DropdownMenuItem(
                text = { Text(if (has) AvatarFormat.CHANGE else AvatarFormat.PICK, style = MaterialTheme.typography.bodyLarge, color = colors.text) },
                onClick = {
                    open = false
                    photos.pick(photoKey)
                },
                leadingIcon = { Icon(PIcons.Edit, contentDescription = null, tint = colors.text) },
                modifier = Modifier.testTag(AVATAR_PICK_TAG),
            )
            if (has) {
                DropdownMenuItem(
                    text = { Text(AvatarFormat.REMOVE, style = MaterialTheme.typography.bodyLarge, color = colors.error) },
                    onClick = {
                        open = false
                        photos.remove(photoKey)
                    },
                    leadingIcon = { Icon(PIcons.Delete, contentDescription = null, tint = colors.error) },
                    modifier = Modifier.testTag(AVATAR_REMOVE_TAG),
                )
            }
        }
    }
}

/**
 * Цвета аватарок — тона мест без красного, оранжевого и янтарного (оранжевый — ровно в пяти местах, 10.4):
 * фон — светлый тон места, шапочка — [avatarCap], буквы на ней белые.
 * Рисунок одинаков в светлой и тёмной теме, как фото.
 */
private val avatarTones: List<PlaceColor> = listOf(
    PlaceColor.LIME, PlaceColor.GREEN, PlaceColor.EMERALD, PlaceColor.TEAL, PlaceColor.SKY, PlaceColor.BLUE,
    PlaceColor.INDIGO, PlaceColor.VIOLET, PlaceColor.MAGENTA, PlaceColor.PINK, PlaceColor.BROWN, PlaceColor.SLATE,
).also { check(it.size == AvatarRules.TONES) }

/** Шапочка: между полосой места и его тёмным текстом — ярче текста, но белые буквы читаются не хуже 4,5 : 1. */
internal fun avatarCap(tone: Int): Color =
    avatarTones[tone.coerceIn(0, avatarTones.lastIndex)].light.let { lerp(it.stripe, it.text, 0.45f) }

private val skin = Color(0xFFF6D3B5)
private val blush = Color(0xFFF2A7A0)
private val eyes = Color(0xFF2F2A26)
private val lens = Color(0xFF9FD4E8)
private val whistle = Color(0xFFC9CED6)

/** Рисунок в квадрате 100 × 100 условных точек — растягивается до любого размера. */
@Composable
private fun Swimmer(look: AvatarLook, modifier: Modifier) {
    val tone = avatarTones[look.tone.coerceIn(0, avatarTones.lastIndex)].light
    val cap = avatarCap(look.tone)
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val u = size.minDimension / 100f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawRect(tone.block(2))

        val kid = look.age == AvatarAge.KID
        val faceR = if (kid) 29f else 27f
        val faceC = p(50f, 62f)
        // Пучок под шапочкой — у девочек и женщин.
        if (look.sex == Sex.FEMALE) drawCircle(cap, 11f * u, p(50f, 17f))
        // Лицо.
        drawCircle(skin, faceR * u, faceC)
        if (kid) {
            drawCircle(blush, 4.5f * u, p(35f, 73f), alpha = 0.7f)
            drawCircle(blush, 4.5f * u, p(65f, 73f), alpha = 0.7f)
        }
        // Шапочка: купол над лбом до середины лица.
        val capPath = Path().apply {
            moveTo(19f * u, 58f * u)
            cubicTo(15f * u, 6f * u, 85f * u, 6f * u, 81f * u, 58f * u)
            cubicTo(66f * u, 52f * u, 34f * u, 52f * u, 19f * u, 58f * u)
            close()
        }
        drawPath(capPath, cap)
        // Глаза и улыбка.
        val eyeY = 67f
        if (look.sex == Sex.FEMALE) {
            drawLine(eyes, p(37f, eyeY - 4f), p(35f, eyeY - 6.5f), 1.6f * u)
            drawLine(eyes, p(63f, eyeY - 4f), p(65f, eyeY - 6.5f), 1.6f * u)
        }
        drawCircle(eyes, 2.8f * u, p(41f, eyeY))
        drawCircle(eyes, 2.8f * u, p(59f, eyeY))
        drawArc(
            eyes, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = p(42f, 69f), size = Size(16f * u, 10f * u), style = Stroke(2.2f * u),
        )
        when (look.age) {
            AvatarAge.TEEN, AvatarAge.ADULT -> goggles(u)
            AvatarAge.SENIOR -> glasses(u, eyeY)
            AvatarAge.COACH -> whistle(u)
            AvatarAge.KID -> Unit
        }
        // Инициалы на шапочке.
        val style = TextStyle(color = Color.White, fontSize = (17f * u).toSp(), fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        val text = measurer.measure(look.initials, style)
        drawText(text, topLeft = Offset((size.width - text.size.width) / 2f, 38f * u - text.size.height / 2f))
    }
}

/** Очки для плавания, поднятые на шапочку. */
private fun DrawScope.goggles(u: Float) {
    val strap = Color.Black.copy(alpha = 0.35f)
    drawLine(strap, Offset(19f * u, 53f * u), Offset(81f * u, 53f * u), 2.2f * u)
    for (x in listOf(34f, 54f)) {
        drawRoundRect(lens, Offset(x * u, 49f * u), Size(12f * u, 8f * u), CornerRadius(4f * u))
        drawRoundRect(Color.White, Offset(x * u, 49f * u), Size(12f * u, 8f * u), CornerRadius(4f * u), style = Stroke(1.4f * u))
    }
}

/** Обычные очки — у тех, кому 60 и больше. */
private fun DrawScope.glasses(u: Float, eyeY: Float) {
    val frame = Color(0xFF4A4A4A)
    drawCircle(frame, 6.5f * u, Offset(41f * u, eyeY * u), style = Stroke(1.6f * u))
    drawCircle(frame, 6.5f * u, Offset(59f * u, eyeY * u), style = Stroke(1.6f * u))
    drawLine(frame, Offset(47.5f * u, eyeY * u), Offset(52.5f * u, eyeY * u), 1.6f * u)
}

/** Свисток тренера на шнурке. */
private fun DrawScope.whistle(u: Float) {
    drawLine(Color.Black.copy(alpha = 0.4f), Offset(37f * u, 86f * u), Offset(50f * u, 93f * u), 1.6f * u)
    drawLine(Color.Black.copy(alpha = 0.4f), Offset(63f * u, 86f * u), Offset(50f * u, 93f * u), 1.6f * u)
    drawRoundRect(whistle, Offset(43f * u, 89f * u), Size(15f * u, 9f * u), CornerRadius(4.5f * u))
    drawCircle(Color(0xFF8A919C), 2f * u, Offset(53f * u, 93.5f * u))
}
