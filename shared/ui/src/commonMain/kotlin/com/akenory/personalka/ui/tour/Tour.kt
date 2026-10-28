package com.akenory.personalka.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.tour.TourStop
import com.akenory.personalka.domain.format.TourFormat
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme

const val TOUR_CARD_TAG = "tour-card"
const val TOUR_SKIP_TAG = "tour-skip"
const val TOUR_NEXT_TAG = "tour-next"

/** Места, которые знакомство обводит рамкой (макет `docs/mockups/tour`). */
object TourTarget {
    const val SLOT = "slot"
    const val STATUS = "status"
    const val CANCEL = "cancel"
    const val CLIENT = "client"
    const val ADD_CLIENT = "add-client"
    const val TODO = "todo"
    const val MONEY = "money"
    const val BACKUP_FOLDER = "backup-folder"

    /** Какие места обводятся на остановке; на «Зарплате» рамки нет — там весь экран мастера. */
    fun of(stop: TourStop): Set<String> = when (stop) {
        TourStop.CALENDAR -> setOf(SLOT)
        TourStop.CARD -> setOf(STATUS, CANCEL)
        TourStop.CLIENTS -> setOf(CLIENT, ADD_CLIENT)
        TourStop.HOME -> setOf(TODO, MONEY)
        TourStop.SALARY -> emptySet()
        TourStop.BACKUP -> setOf(BACKUP_FOLDER)
    }
}

/**
 * Где знакомство (5.18, шаг 5.3б): текущая остановка или `null` — знакомства нет. Остановка хранится в настройках
 * телефона, поэтому переживает выгрузку приложения; знакомство — про этот телефон, в копию и на сервер не уходит.
 */
@Stable
class TourState(private val prefs: DevicePrefs) {
    var stop: TourStop? by mutableStateOf(TourStop.of(prefs.string(KEY)))
        private set

    /** Места на экране, которые сейчас видны, — в координатах окна. */
    internal val targets = mutableStateMapOf<String, Rect>()

    fun go(next: TourStop?) {
        stop = next
        prefs.putString(KEY, next?.name.orEmpty())
    }

    companion object {
        const val KEY = "tour.stop"
    }
}

val LocalTour = staticCompositionLocalOf<TourState?> { null }

/** Отметить место для рамки знакомства. Пока знакомства нет, ничего не делает. */
fun Modifier.tourTarget(key: String): Modifier = composed {
    val tour = LocalTour.current
    if (tour?.stop == null) return@composed this
    DisposableEffect(tour, key) { onDispose { tour.targets.remove(key) } }
    onGloballyPositioned { tour.targets[key] = it.boundsInRoot() }
}

/** Карточка остановки: что сделать и кнопки. [main] — главная кнопка или `null`, если ждём жест ([gesture]). */
class TourCard(
    val stop: TourStop,
    val title: String,
    val texts: List<String>,
    val gesture: String? = null,
    val main: Pair<String, () -> Unit>? = null,
    val skip: Pair<String, () -> Unit>,
    /** Обводить ли места: в открытой карточке клиента рамка вокруг списка слева только мешает. */
    val holes: Boolean = true,
    /** Карточка стоит выше «+»: в «Календаре» тренировку можно добавить и им. */
    val aboveFab: Boolean = false,
)

/**
 * Знакомство поверх приложения: затемнение с вырезами вокруг мест остановки и карточка снизу (или сверху, если
 * места внизу экрана — например, кнопки карточки тренировки). Затемнение нажатия не ловит: тренер делает настоящее
 * действие на экране под ним. Мест на экране нет (раздел ушёл, остановка без рамки) — нет и затемнения.
 */
@Composable
fun TourOverlay(tour: TourState, card: TourCard) {
    val colors = PersonalkaTheme.colors
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val keys = if (card.holes) TourTarget.of(card.stop) else emptySet()
    val holes = keys.mapNotNull { tour.targets[it] }.filter { it.width > 0f && it.height > 0f }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val heightPx = with(density) { maxHeight.toPx() }
        if (holes.isNotEmpty()) {
            val pad = with(density) { 6.dp.toPx() }
            val ring = with(density) { 2.dp.toPx() }
            val radius = with(density) { 12.dp.toPx() }
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                drawRect(colors.scrim)
                holes.forEach { r ->
                    val topLeft = Offset(r.left - origin.x - pad, r.top - origin.y - pad)
                    val size = Size(r.width + 2 * pad, r.height + 2 * pad)
                    drawRoundRect(Color.Transparent, topLeft, size, CornerRadius(radius), blendMode = BlendMode.Clear)
                    drawRoundRect(colors.primary, topLeft, size, CornerRadius(radius), style = Stroke(ring))
                }
            }
        }
        // Места в нижней половине — карточка сверху, чтобы не закрыть их.
        val low = holes.isNotEmpty() && holes.minOf { it.top - origin.y } > heightPx / 2
        Box(
            Modifier.fillMaxSize().padding(horizontal = Dimens.space3),
            contentAlignment = if (low) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            val bottom = if (card.aboveFab) Dimens.navBar + Dimens.fab + Dimens.space4 * 2 else Dimens.space3
            TourCardView(
                card,
                if (low) Modifier.statusBarsPadding().padding(top = Dimens.space3) else Modifier.navigationBarsPadding().padding(bottom = bottom),
            )
        }
    }
}

@Composable
private fun TourCardView(card: TourCard, modifier: Modifier) {
    val colors = PersonalkaTheme.colors
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.extraLarge)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colors.surface)
            // Нажатие по карточке не уходит на экран под ней.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics {
                paneTitle = card.title
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(TOUR_CARD_TAG)
            .padding(start = Dimens.space4, end = Dimens.space2, top = Dimens.space4, bottom = Dimens.space2),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Text(TourFormat.step(card.stop), style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TourStop.entries.forEach { s ->
                    Box(
                        Modifier.size(width = 14.dp, height = 4.dp)
                            .background(if (s.ordinal <= card.stop.ordinal) colors.primary else colors.divider, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Text(card.title, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.padding(end = Dimens.space2))
        card.texts.forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.padding(end = Dimens.space2))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = card.skip.second, modifier = Modifier.heightIn(min = Dimens.button).testTag(TOUR_SKIP_TAG)) {
                Text(card.skip.first, style = MaterialTheme.typography.labelLarge, color = colors.text)
            }
            Spacer(Modifier.weight(1f))
            val main = card.main
            if (main != null) {
                PrimaryButton(main.first, main.second, Modifier.padding(end = Dimens.space2).testTag(TOUR_NEXT_TAG))
            } else if (card.gesture != null) {
                Text(
                    card.gesture,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.weight(3f, fill = false).padding(end = Dimens.space2),
                )
            }
        }
    }
}
