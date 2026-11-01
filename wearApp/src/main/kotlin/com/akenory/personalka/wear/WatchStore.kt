package com.akenory.personalka.wear

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.watch.WatchCodec
import com.akenory.personalka.domain.watch.WatchDay
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDateTime

/**
 * Последнее расписание с телефона (шаг 5.3т) — в памяти часов, чтобы ячейки и плитка отвечали сразу, даже без связи с
 * телефоном. Пути и ключ — те же, что у `WatchBridge` телефона.
 */
object WatchStore {
    const val PATH = "/personalka/day"
    private const val KEY = "day"
    private const val MARK = "/personalka/mark"
    private const val HELLO = "/personalka/hello"
    private const val FILE = "watch"

    /** Для отправки сообщений телефону, не дожидаясь ответа. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun now(): LocalDateTime = ClubTime.now(System.currentTimeMillis())

    fun load(context: Context): WatchDay? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)?.let(WatchCodec::decode)

    /** Новые данные: запомнить и попросить ячейки и плитку перерисоваться. */
    fun save(context: Context, text: String) {
        if (WatchCodec.decode(text) == null) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
        refresh(context)
    }

    fun save(context: Context, day: WatchDay) = save(context, WatchCodec.encode(day))

    /** Данных нет (часы только что поставили): взять элемент из Data Layer, а если его нет — попросить телефон. */
    suspend fun fetch(context: Context): WatchDay? {
        load(context)?.let { return it }
        try {
            val items = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*$PATH")).await()
            try {
                items.firstOrNull()?.let { item ->
                    DataMapItem.fromDataItem(item).dataMap.getString(KEY)?.let { save(context, it) }
                }
            } finally {
                items.release()
            }
            load(context)?.let { return it }
            send(context, HELLO, ByteArray(0))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Нет связи с телефоном — покажем «Откройте Персоналку на телефоне».
        }
        return null
    }

    /** «Проведено» с плитки: телефон отметит тренировку тем же правилом, что карточка. */
    fun markDone(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                send(app, MARK, id.toByteArray(Charsets.UTF_8))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun send(context: Context, path: String, data: ByteArray) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val messages = Wearable.getMessageClient(context)
        nodes.forEach { messages.sendMessage(it.id, path, data).await() }
    }

    fun refresh(context: Context) {
        listOf(
            NextComplicationService::class.java,
            MoneyComplicationService::class.java,
            PlanComplicationService::class.java,
            TodoComplicationService::class.java,
        ).forEach { cls ->
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, cls)).requestUpdateAll() }
        }
        runCatching { TileService.getUpdater(context).requestUpdate(NextTileService::class.java) }
    }
}
