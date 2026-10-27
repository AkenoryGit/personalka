package com.akenory.personalka.data.sync

import com.akenory.personalka.domain.sync.SyncRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Когда обмениваться (ТЗ 7.2, п. 4), пока приложение открыто: сразу при запуске, через 5 секунд после последней
 * правки (пока обмен идёт без ошибок), раз в 15 минут, по просьбе ([now] — возврат в приложение, «Синхронизировать»); ошибка связи — повтор
 * с нарастающей паузой до 15 минут ([SyncRules.nextPause]). Фон при закрытом приложении — задача платформы,
 * она зовёт тот же [SyncRepository.sync].
 */
class SyncRunner(private val sync: SyncRepository) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Обменяться сейчас, не дожидаясь паузы. */
    fun now() {
        wake.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            sync.pending.collectLatest { count ->
                if (count > 0 && !sync.state.value.running && sync.state.value.problem == null) {
                    delay(SyncRules.QUIET_MS)
                    now()
                }
            }
        }
        while (true) {
            try {
                sync.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Обмен сам превращает сбои в SyncProblem; что всё же вылетело (ключи телефона) — не повод ронять
                // приложение при каждом запуске: попробуем в следующий раз.
            }
            val s = sync.state.value
            val refused = s.problem != null && s.problem !in SyncRepository.RETRIED
            val pause = if (!s.connected) SyncRules.PERIOD_MS else SyncRules.nextPause(s.failures, sync.pendingNow(), refused)
            withTimeoutOrNull(pause) { wake.receive() }
        }
    }
}
