package com.akenory.personalka.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.calendar.BlockRows
import com.akenory.personalka.domain.calendar.CalendarGrid
import com.akenory.personalka.domain.calendar.DayLayoutRules
import com.akenory.personalka.domain.calendar.DaySpan
import com.akenory.personalka.domain.calendar.GridHours
import com.akenory.personalka.domain.calendar.GridView
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.training.TrainingTime
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaColors
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.TypeColor
import com.akenory.personalka.ui.theme.tabular
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

const val CALENDAR_GRID_TAG = "calendar-grid"
const val CALENDAR_PAGER_TAG = "calendar-pager"

/** Колонка дня в сетке: блоки дня в ней рисуются на Canvas, поэтому тесты и TalkBack видят их через [CalendarBlocks]. */
fun calendarDayTag(date: LocalDate) = "calendar-day-$date"

/** Страница листания — день от 01.01.1970: так номер страницы не нужно сохранять отдельно от даты. */
internal fun dateOfPage(page: Int): LocalDate = EPOCH.plus(page, DateTimeUnit.DAY)

internal fun pageOf(date: LocalDate): Int = date.toEpochDays().toInt()

private val EPOCH = LocalDate(1970, 1, 1)

/** Листать можно до конца 2099 года. */
internal val PAGE_COUNT: Int = pageOf(LocalDate(2100, 1, 1))

/** Блок, как его видит тест и TalkBack: строки текста и место в колонке дня, dp от её левого и верхнего края. */
@Immutable
data class BlockInfo(
    val id: String,
    val lines: List<String>,
    val rows: BlockRows,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cancelled: Boolean,
)

/** Метка «+N», как её видит тест: подпись и скрытые тренировки. */
@Immutable
data class MoreInfo(val label: String, val ids: List<String>, val x: Float, val y: Float, val width: Float, val height: Float)

/** Блоки и метки «+N» колонки дня. */
@Immutable
data class DayBlocks(val blocks: List<BlockInfo>, val more: List<MoreInfo>)

val CalendarBlocks = SemanticsPropertyKey<DayBlocks>("CalendarBlocks")
var SemanticsPropertyReceiver.calendarBlocks by CalendarBlocks

/** Высота часа сетки, dp, и её часы — для тестов. */
val CalendarHour = SemanticsPropertyKey<Float>("CalendarHour")
var SemanticsPropertyReceiver.calendarHour by CalendarHour
val CalendarHours = SemanticsPropertyKey<GridHours>("CalendarHours")
var SemanticsPropertyReceiver.calendarHours by CalendarHours

/**
 * Положение сетки: верхняя минута суток и высота часа (ТЗ 5.3). Верхняя минута хранится как есть, а на экран идёт
 * в пределах сетки ([view]): «сейчас − 1 час» при открытии сам упирается в конец часов работы, а после сложения
 * Fold, когда экран стал выше или ниже, картинка снова встаёт в сетку.
 */
@Stable
class DayGridState(top: Float, hourDp: Float) {
    var top by mutableFloatStateOf(top)
        private set
    var hourDp by mutableFloatStateOf(CalendarGrid.clampHour(hourDp))
        private set

    fun view(hours: GridHours, heightDp: Float): GridView = GridView(top, hourDp).clamped(hours, heightDp)

    /** Прокрутка на [dyDp] вниз. Возвращает, на сколько dp сдвинулось на самом деле: у края сетки — меньше. */
    fun scrollBy(dyDp: Float, hours: GridHours, heightDp: Float): Float {
        val before = view(hours, heightDp)
        val after = before.scrolled(dyDp, hours, heightDp)
        top = after.top
        return (after.top - before.top) * before.dpPerMinute
    }

    fun zoomBy(factor: Float, focusDp: Float, hours: GridHours, heightDp: Float) {
        val after = view(hours, heightDp).zoomed(factor, focusDp, hours, heightDp)
        top = after.top
        hourDp = after.hourDp
    }

    /** «Сегодня»: снова «сейчас − 1 час». */
    fun showNow(now: LocalTime) {
        top = CalendarGrid.openTop(now)
    }

    companion object {
        val Saver: Saver<DayGridState, Any> = Saver(
            save = { arrayListOf(it.top, it.hourDp) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val list = saved as List<Float>
                DayGridState(list[0], list[1])
            },
        )
    }
}

/**
 * Сетка дня (ТЗ 5.3): колонка времени 48 dp и дни, которые листаются свайпом. Прокрутка и щипок — общие для всех
 * дней. Всё рисуется на Canvas, а прокрутка только сдвигает готовую картинку: блоки при ней не перерисовываются.
 *
 * @param itemsOf тренировки дня, отменённые тоже.
 * @param onZoomEnd щипок закончен — масштаб пора запомнить.
 */
@Composable
internal fun DayGrid(
    pager: PagerState,
    grid: DayGridState,
    hours: GridHours,
    now: LocalDateTime,
    itemsOf: (LocalDate) -> List<CalendarTraining>,
    onOpen: (CalendarTraining) -> Unit,
    onMore: (List<CalendarTraining>) -> Unit,
    onZoomEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PersonalkaTheme.colors
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.background(colors.surface).clipToBounds()) {
        val heightDp = maxHeight.value
        val currentHours by rememberUpdatedState(hours)
        val currentHeight by rememberUpdatedState(heightDp)
        val currentZoomEnd by rememberUpdatedState(onZoomEnd)
        val hourDp = grid.hourDp
        // Прокрутка читается только при рисовании: при ней не пересобирается ни экран, ни блоки.
        val scrollPx: () -> Float = {
            val view = grid.view(currentHours, currentHeight)
            (view.top - currentHours.startMinute) * view.dpPerMinute * density
        }
        val maxScrollPx: () -> Float = {
            (currentHours.count * grid.hourDp - currentHeight).coerceAtLeast(0f) * density
        }
        val scrollState = rememberScrollableState { delta ->
            -grid.scrollBy(-delta / density, currentHours, currentHeight) * density
        }

        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(grid) {
                    pinch(
                        onZoom = { factor, focus -> grid.zoomBy(factor, focus.y / density, currentHours, currentHeight) },
                        onEnd = { currentZoomEnd() },
                    )
                }
                .scrollable(scrollState, Orientation.Vertical)
                .semantics {
                    verticalScrollAxisRange = ScrollAxisRange(value = scrollPx, maxValue = maxScrollPx)
                    scrollBy { _, y ->
                        grid.scrollBy(y / density, currentHours, currentHeight)
                        true
                    }
                    calendarHour = hourDp
                    calendarHours = hours
                }
                .testTag(CALENDAR_GRID_TAG),
        ) {
            TimeColumn(hours, hourDp, scrollPx)
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(CALENDAR_PAGER_TAG),
                key = { it },
            ) { page ->
                val date = dateOfPage(page)
                DayColumn(date, itemsOf(date), hours, hourDp, now, scrollPx, onOpen, onMore)
            }
        }
    }
}

/**
 * Щипок двумя пальцами (ТЗ 5.3): [onZoom] получает множитель и точку между пальцами. Пока пальцев два, их движение
 * забирается до прокрутки и листания: сетка не едет и день не меняется.
 */
private suspend fun PointerInputScope.pinch(
    onZoom: (factor: Float, focus: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    onZoom(factor, event.calculateCentroid(useCurrent = true))
                    zoomed = true
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zoomed) onEnd()
    }
}

/** Колонка времени 48 dp: подписи часов; первая — под верхним краем сетки, последняя — над нижним. */
@Composable
private fun TimeColumn(hours: GridHours, hourDp: Float, scrollPx: () -> Float) {
    val colors = PersonalkaTheme.colors
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.tabular()
    val labels = remember(hours, style, measurer) {
        (hours.startHour..hours.endHour).map { measurer.measure(DateFormat.minuteOfDay(it * 60), style) }
    }
    Box(Modifier.width(Dimens.timeColumn).fillMaxHeight().clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .height((hours.count * hourDp).dp)
                .graphicsLayer { translationY = -scrollPx() },
        ) {
            val hourPx = hourDp.dp.toPx()
            val edge = 2.dp.toPx()
            labels.forEachIndexed { i, label ->
                val line = i * hourPx
                val top = when (i) {
                    0 -> edge
                    labels.lastIndex -> line - label.size.height - edge
                    else -> line - label.size.height / 2f
                }
                drawText(label, color = colors.textMuted, topLeft = Offset(size.width - 6.dp.toPx() - label.size.width, top))
            }
        }
    }
}

/**
 * Колонка дня. Слои снизу вверх (ТЗ 5.3): фон дня и приглушённое прошлое → линии сетки → тренировки → линия «сейчас».
 * Дежурства и запреты встанут между фоном и линиями в шаге 1.10.
 */
@Composable
private fun DayColumn(
    date: LocalDate,
    items: List<CalendarTraining>,
    hours: GridHours,
    hourDp: Float,
    now: LocalDateTime,
    scrollPx: () -> Float,
    onOpen: (CalendarTraining) -> Unit,
    onMore: (List<CalendarTraining>) -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val measurer = rememberTextMeasurer()
    val styles = BlockStyles(
        name = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp),
        meta = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp).tabular(),
        more = MaterialTheme.typography.labelMedium.tabular(),
    )
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val widthDp = maxWidth.value
        val day = remember(items, widthDp, hours, hourDp) { dayBlocks(items, widthDp, hours, hourDp) }
        val byId = remember(items) { items.associateBy { it.id } }
        // Строки блоков измеряются один раз: щипок меняет высоту блоков, а ширину и строки — редко.
        val texts = remember(items, widthDp, colors, styles) { HashMap<String, List<TextLayoutResult>>() }
        val currentDay by rememberUpdatedState(day)
        val currentById by rememberUpdatedState(byId)
        val currentOpen by rememberUpdatedState(onOpen)
        val currentMore by rememberUpdatedState(onMore)

        Canvas(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .height((hours.count * hourDp).dp)
                .graphicsLayer { translationY = -scrollPx() }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val x = tap.x / density
                        val y = tap.y / density
                        val block = currentDay.blocks.lastOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
                        val more = currentDay.more.lastOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
                        when {
                            block != null -> currentById[block.id]?.let(currentOpen)
                            more != null -> currentMore(more.ids.mapNotNull { currentById[it] })
                        }
                    }
                }
                .semantics {
                    calendarBlocks = day
                    contentDescription = DateFormat.short(date) + day.blocks.joinToString("") { "; " + it.lines.joinToString(", ") }
                }
                .testTag(calendarDayTag(date)),
        ) {
            drawGrid(date, hours, hourDp, now, colors)
            day.blocks.forEach { block ->
                val training = byId[block.id] ?: return@forEach
                val lines = texts.getOrPut("${block.id}/${block.rows}") { measureLines(measurer, block, training.cancelled, styles) }
                drawBlock(block, training, lines, colors)
            }
            day.more.forEach { drawMore(it, measurer, styles.more, colors) }
            if (date == now.date) drawNow(hours, hourDp, now, colors)
        }
    }
}

@Immutable
private data class BlockStyles(val name: TextStyle, val meta: TextStyle, val more: TextStyle)

/** Отступы блока от рамки цвета (1 dp зазора уже вычтен): слева за полосой 9 dp, сверху 4 dp, у одной строки — 3. */
private val BLOCK_GAP = 1.dp
private val BLOCK_RADIUS = 5.dp
private val TEXT_START = 9.dp
private val TEXT_END = 6.dp
private val TEXT_TOP = 4.dp
private val TEXT_TOP_ONE = 3.dp

/**
 * Блоки дня в dp от левого и верхнего края колонки: раскладка наложений и строки по высоте — правила `shared/domain`.
 * Тренировки начинаются в 8 dp от края — там полоски зон (1.10) — и кончаются в 8 dp от правого.
 */
internal fun dayBlocks(items: List<CalendarTraining>, widthDp: Float, hours: GridHours, hourDp: Float): DayBlocks {
    val inset = Dimens.blockInset.value
    val inner = (widthDp - 2 * inset).coerceAtLeast(0f)
    val spans = items.map { it.span() }
    val spanOf = spans.associateBy { it.id }
    val byId = items.associateBy { it.id }
    val layout = DayLayoutRules.layout(spans, DayLayoutRules.maxColumns(inner))
    fun y(minute: Int) = CalendarGrid.heightDp(minute - hours.startMinute, hourDp)
    val blocks = layout.blocks.map { place ->
        val span = spanOf.getValue(place.id)
        val training = byId.getValue(place.id)
        val column = inner / place.columns
        val height = CalendarGrid.heightDp(span.end - span.start, hourDp)
        val rows = CalendarGrid.rows(height)
        BlockInfo(
            id = place.id,
            lines = training.lines(rows),
            rows = rows,
            x = inset + place.column * column,
            y = y(span.start),
            width = place.span * column,
            height = height,
            cancelled = training.cancelled,
        )
    }
    val more = layout.more.map { m ->
        val column = inner / m.columns
        MoreInfo(
            label = CalendarFormat.more(m.ids.size),
            ids = m.ids,
            x = inset + m.column * column,
            y = y(m.start),
            width = column,
            height = CalendarGrid.heightDp(m.end - m.start, hourDp),
        )
    }
    return DayBlocks(blocks, more)
}

/** Минуты тренировки в её дне; через полночь — до конца дня. */
internal fun CalendarTraining.span(): DaySpan {
    val from = ClubTime.minuteOfDay(start)
    return DaySpan(id, from, minOf(from + durationMin, ClubTime.MINUTES_PER_DAY))
}

/** Строки блока по его высоте (ТЗ 5.3): имя, время, тип · место; имя и «начало · место»; имя и начало одной строкой. */
private fun CalendarTraining.lines(rows: BlockRows): List<String> = when (rows) {
    BlockRows.THREE -> listOf(
        title,
        DateFormat.timeRange(start, TrainingTime.end(start, durationMin)),
        CalendarFormat.typePlace(typeShort, placeShort),
    )
    BlockRows.TWO -> listOf(title, CalendarFormat.startPlace(start, placeShort))
    BlockRows.ONE -> listOf(CalendarFormat.oneLine(title, start))
}

private fun DrawScope.measureLines(measurer: TextMeasurer, block: BlockInfo, cancelled: Boolean, styles: BlockStyles): List<TextLayoutResult> {
    val width = (block.width.dp - BLOCK_GAP * 2 - TEXT_START - TEXT_END).toPx().toInt().coerceAtLeast(0)
    val decoration = if (cancelled) TextDecoration.LineThrough else null
    return block.lines.mapIndexed { i, line ->
        val style = if (i == 0) styles.name else styles.meta
        measurer.measure(
            text = line,
            style = style.copy(textDecoration = decoration),
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = width),
        )
    }
}

/** Фон дня, приглушённое прошлое (только фон), линии каждый час и пунктир на получасе. */
private fun DrawScope.drawGrid(date: LocalDate, hours: GridHours, hourDp: Float, now: LocalDateTime, colors: PersonalkaColors) {
    val hourPx = hourDp.dp.toPx()
    val past = when {
        date < now.date -> size.height
        date > now.date -> 0f
        else -> ((ClubTime.minuteOfDay(now) - hours.startMinute) * hourPx / 60f).coerceIn(0f, size.height)
    }
    if (past > 0f) drawRect(colors.pastTint, size = Size(size.width, past))
    val stroke = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
    for (i in 0..hours.count) {
        val y = i * hourPx + stroke / 2
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), stroke)
        if (i < hours.count) {
            val half = y + hourPx / 2
            drawLine(colors.divider, Offset(0f, half), Offset(size.width, half), stroke, pathEffect = dash)
        }
    }
}

/**
 * Блок (ТЗ 5.3): фон — светлый тон цвета типа, слева полоса 3 dp, текст тёмным тоном того же цвета (в тёмной теме
 * наоборот); вокруг — зазор 1 dp цвета фона. Отменённая — серая и зачёркнутая, текст без прозрачности.
 */
private fun DrawScope.drawBlock(block: BlockInfo, training: CalendarTraining, lines: List<TextLayoutResult>, colors: PersonalkaColors) {
    val gap = BLOCK_GAP.toPx()
    val rect = Rect(
        left = block.x.dp.toPx() + gap,
        top = block.y.dp.toPx() + gap,
        right = (block.x + block.width).dp.toPx() - gap,
        bottom = (block.y + block.height).dp.toPx() - gap,
    )
    if (rect.width <= 0f || rect.height <= 0f) return
    val tone = TypeColor.of(training.color).tone(colors.isDark)
    val fill = if (training.cancelled) colors.surfaceAlt else tone.block
    val stripe = if (training.cancelled) colors.outline else tone.stripe
    val text = if (training.cancelled) colors.textMuted else tone.text
    val shape = Path().apply { addRoundRect(RoundRect(rect, cornerRadius = CornerRadius(BLOCK_RADIUS.toPx()))) }
    clipPath(shape) {
        drawRect(fill, rect.topLeft, rect.size)
        drawRect(stripe, rect.topLeft, Size(Dimens.blockStripe.toPx(), rect.height))
        var y = rect.top + (if (block.rows == BlockRows.ONE) TEXT_TOP_ONE else TEXT_TOP).toPx()
        lines.forEach { line ->
            drawText(line, color = text, topLeft = Offset(rect.left + TEXT_START.toPx(), y))
            y += line.size.height
        }
    }
}

/** Метка «+N»: нейтральная плашка в последней колонке кластера, по нажатию — список скрытых. */
private fun DrawScope.drawMore(more: MoreInfo, measurer: TextMeasurer, style: TextStyle, colors: PersonalkaColors) {
    val gap = BLOCK_GAP.toPx()
    val rect = Rect(
        left = more.x.dp.toPx() + gap,
        top = more.y.dp.toPx() + gap,
        right = (more.x + more.width).dp.toPx() - gap,
        bottom = (more.y + more.height).dp.toPx() - gap,
    )
    if (rect.width <= 0f || rect.height <= 0f) return
    drawRoundRect(colors.surfaceAlt, rect.topLeft, rect.size, CornerRadius(BLOCK_RADIUS.toPx()))
    val label = measurer.measure(more.label, style, maxLines = 1, softWrap = false)
    // По центру плашки, а у длинной — по центру её первых 48 dp: метка рядом с началом скрытых.
    val area = minOf(rect.height, Dimens.touch.toPx())
    drawText(label, color = colors.text, topLeft = Offset(rect.center.x - label.size.width / 2f, rect.top + (area - label.size.height) / 2f))
}

/** Линия «сейчас» — фирменная оранжевая с точкой, поверх тренировок (ТЗ 5.3). */
private fun DrawScope.drawNow(hours: GridHours, hourDp: Float, now: LocalDateTime, colors: PersonalkaColors) {
    val minute = ClubTime.minuteOfDay(now)
    if (minute < hours.startMinute || minute > hours.endMinute) return
    val y = (minute - hours.startMinute) * hourDp.dp.toPx() / 60f
    val dot = Dimens.nowDot.toPx()
    drawLine(colors.accentGraphic, Offset(0f, y), Offset(size.width, y), Dimens.nowLine.toPx())
    drawCircle(colors.accentGraphic, radius = dot / 2, center = Offset(dot / 2, y))
}
