package com.akenory.personalka.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.akenory.personalka.ui.shell.NavBarHeight
import com.akenory.personalka.ui.shell.WideFrom
import com.akenory.personalka.ui.theme.PersonalkaTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.datetime.LocalDate
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.components.swallowTaps

const val TOUR_CARD_TAG = "tour-card"
const val TOUR_SECOND_TAG = "tour-second"
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
 * Где знакомство (5.18, шаги 5.3б, 5.3г): текущая остановка или `null` — знакомства нет. Остановка хранится
 * в настройках телефона, поэтому переживает выгрузку приложения; знакомство — про этот телефон, в копию и на сервер
 * не уходит.
 */
@Stable
class TourState(private val prefs: DevicePrefs) {
    var stop: TourStop? by mutableStateOf(TourStop.of(prefs.string(KEY)))
        private set

    /** День, на котором первая остановка обводит пустой час: сегодня, а если сегодня места нет — завтра. */
    var slotDay: LocalDate? by mutableStateOf(null)

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

/**
 * Карточка остановки. [main] — главная кнопка, есть всегда: «Дальше» проходит остановку и без действия (полного
 * пропуска знакомства нет — решение Дмитрия 28.10.2026). [second] — второе действие текстом («Дальше» рядом
 * с «Настроить» и «Выбрать папку»). [gesture] — что сделать на экране, строкой над кнопками.
 */
class TourCard(
    val stop: TourStop,
    val title: String,
    val texts: List<String>,
    val main: Pair<String, () -> Unit>,
    val second: Pair<String, () -> Unit>? = null,
    val gesture: String? = null,
    /** Обводить ли места: в открытой карточке клиента рамка вокруг списка слева только мешает. */
    val holes: Boolean = true,
    /** Карточка снизу стоит выше «+»: в «Календаре» тренировку можно добавить и им. */
    val aboveFab: Boolean = false,
)

/**
 * Знакомство поверх приложения: затемнение с вырезами вокруг мест остановки и карточка. Затемнение есть всегда —
 * карточка не сливается с экраном; нажатия оно не ловит: тренер делает настоящее действие на экране под ним.
 * Карточка встаёт снизу (над панелью разделов) или сверху — где она меньше закрывает обведённые места.
 */
@Composable
fun TourOverlay(tour: TourState, card: TourCard) {
    val colors = PersonalkaTheme.colors
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cardHeight by remember { mutableStateOf(0) }
    val keys = if (card.holes) TourTarget.of(card.stop) else emptySet()
    val holes = keys.mapNotNull { tour.targets[it] }.filter { it.width > 0f && it.height > 0f }
        .map { it.translate(-origin.x, -origin.y) }
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val heightPx = with(density) { maxHeight.toPx() }
        val wide = maxWidth >= WideFrom
        val pad = with(density) { 6.dp.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(colors.scrim)
            val ring = 2.dp.toPx()
            val radius = 12.dp.toPx()
            holes.forEach { r ->
                val topLeft = Offset(r.left - pad, r.top - pad)
                val size = Size(r.width + 2 * pad, r.height + 2 * pad)
                drawRoundRect(Color.Transparent, topLeft, size, CornerRadius(radius), blendMode = BlendMode.Clear)
                drawRoundRect(colors.primary, topLeft, size, CornerRadius(radius), style = Stroke(ring))
            }
        }
        // На сложенном внизу — панель разделов: карточка стоит над ней, разделы остаются под рукой.
        val nav = if (wide) 0.dp else NavBarHeight
        val bottomGap = nav + if (card.aboveFab) Dimens.fab + Dimens.space4 * 2 else Dimens.space3
        val gapPx = with(density) { bottomGap.toPx() } + navBottom
        val topPx = statusTop + with(density) { Dimens.space3.toPx() }
        val top = TourPlacement.atTop(
            holes = holes.map { (it.top - pad)..(it.bottom + pad) },
            card = cardHeight.toFloat(),
            topStart = topPx,
            bottomEnd = heightPx - gapPx,
        )
        Box(
            Modifier.fillMaxSize().padding(horizontal = Dimens.space3),
            contentAlignment = if (top) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            TourCardView(
                card,
                Modifier
                    .then(if (top) Modifier.statusBarsPadding().padding(top = Dimens.space3) else Modifier.navigationBarsPadding().padding(bottom = bottomGap))
                    .onSizeChanged { cardHeight = it.height },
            )
        }
    }
}

/** Куда поставить карточку: сверху, только если там она закроет обведённые места меньше, чем снизу. */
internal object TourPlacement {
    fun atTop(holes: List<ClosedFloatingPointRange<Float>>, card: Float, topStart: Float, bottomEnd: Float): Boolean {
        if (holes.isEmpty() || card <= 0f) return false
        fun covered(from: Float, to: Float) = holes.sumOf { h -> maxOf(0f, minOf(to, h.endInclusive) - maxOf(from, h.start)).toDouble() }
        return covered(topStart, topStart + card) < covered(bottomEnd - card, bottomEnd)
    }
}

@Composable
private fun TourCardView(card: TourCard, modifier: Modifier) {
    val colors = PersonalkaTheme.colors
    Column(
        modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .shadow(12.dp, MaterialTheme.shapes.extraLarge)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colors.surface)
            .border(1.dp, colors.outline, MaterialTheme.shapes.extraLarge)
            // Нажатие по карточке не уходит на экран под ней.
            .swallowTaps()
            .semantics {
                paneTitle = card.title
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(TOUR_CARD_TAG)
            .padding(start = Dimens.space5, end = Dimens.space5, top = Dimens.space4, bottom = Dimens.space4),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(TourFormat.step(card.stop), style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TourStop.entries.forEach { s ->
                    Box(
                        Modifier.size(width = 16.dp, height = 4.dp)
                            .background(if (s.ordinal <= card.stop.ordinal) colors.primary else colors.divider, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Text(card.title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        card.texts.forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        }
        card.gesture?.let {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.medium).padding(horizontal = Dimens.space3, vertical = Dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Icon(PIcons.Info, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            card.second?.let { (label, action) ->
                TextButton(onClick = action, modifier = Modifier.heightIn(min = Dimens.button).testTag(TOUR_SECOND_TAG)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = colors.text)
                }
                Spacer(Modifier.width(Dimens.space2))
            }
            PrimaryButton(card.main.first, card.main.second, Modifier.testTag(TOUR_NEXT_TAG))
        }
    }
}
