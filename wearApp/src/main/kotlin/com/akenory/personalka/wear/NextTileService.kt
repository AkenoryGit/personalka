package com.akenory.personalka.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.akenory.personalka.domain.format.WatchFormat
import com.akenory.personalka.domain.watch.WatchDay
import com.akenory.personalka.domain.watch.WatchRules
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime

/**
 * Плитка «Ближайшая» (шаг 5.3т): что и когда, через сколько, «Ещё сегодня», деньги дня; с начала минус 15 минут —
 * «Проведено» или «Все пришли». Нажатие отмечает тренировку на часах сразу, телефон — по сообщению.
 */
class NextTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val clicked = requestParams.currentState.lastClickableId
        var day = WatchStore.load(this)
        // Данных ещё нет — спросить в фоне: пришли — плитка перерисуется сама.
        if (day == null) {
            val app = applicationContext
            WatchStore.scope.launch { WatchStore.fetch(app) }
        }
        if (day != null && clicked.startsWith(MARK)) {
            val id = clicked.removePrefix(MARK)
            day = WatchRules.marked(day, id)
            WatchStore.save(this, day)
            WatchStore.markDone(this, id)
        }
        val layout = layout(day, WatchStore.now(), requestParams.deviceConfiguration)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())

    private fun layout(day: WatchDay?, now: LocalDateTime, device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val builder = PrimaryLayout.Builder(device).setResponsiveContentInsetEnabled(true)
        val column = LayoutElementBuilders.Column.Builder()
        if (day == null) {
            column.addContent(line(WatchFormat.NO_DATA, Typography.TYPOGRAPHY_BODY1, maxLines = 3))
            return builder.setContent(column.build()).build()
        }
        val next = WatchRules.nearest(day, now)
        if (next == null || next.start.date != now.date) {
            column.addContent(line(WatchFormat.todayDone(next?.start, now.date), Typography.TYPOGRAPHY_BODY1, maxLines = 3))
        } else {
            builder.setPrimaryLabelTextContent(line(WatchFormat.title(next, now), Typography.TYPOGRAPHY_CAPTION1, color = MUTED))
            next.name?.let { column.addContent(line(it, Typography.TYPOGRAPHY_TITLE3, maxLines = 2)) }
            column.addContent(line(WatchFormat.typePlace(next), Typography.TYPOGRAPHY_BODY1))
            column.addContent(line(WatchFormat.startsIn(next, now), Typography.TYPOGRAPHY_CAPTION1, color = MUTED))
            val later = WatchRules.laterToday(day, next, now)
            if (later.isNotEmpty()) column.addContent(line(WatchFormat.laterToday(later.size, later.last().start), Typography.TYPOGRAPHY_CAPTION2, color = MUTED))
            if (WatchRules.canMark(next, now)) {
                val clickable = ModifiersBuilders.Clickable.Builder()
                    .setId(MARK + next.id)
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build()
                builder.setPrimaryChipContent(CompactChip.Builder(this, WatchFormat.mark(next), clickable, device).build())
            }
        }
        WatchRules.money(day, now.date)?.let { money ->
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            column.addContent(line(WatchFormat.money(money.earned, money.evening), Typography.TYPOGRAPHY_CAPTION2, maxLines = 2, color = MUTED))
        }
        return builder.setContent(column.build()).build()
    }

    private fun line(text: String, typography: Int, maxLines: Int = 1, color: Int = TEXT) =
        Text.Builder(this, text)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(maxLines)
            .build()

    private fun <T : Any> done(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(value)
        "personalka-tile"
    }

    private companion object {
        const val MARK = "mark:"
        const val RESOURCES = "1"

        /** Плитка перестраивается раз в 5 минут: «через N мин» и смена «Ближайшей». */
        const val FRESHNESS_MS = 5 * 60_000L

        /** Кремовый текст и приглушённый — цвета тёмной темы «Персоналки» (ТЗ 10.5). */
        const val TEXT = 0xFFF3EFE6.toInt()
        const val MUTED = 0xFF98958D.toInt()
    }
}
