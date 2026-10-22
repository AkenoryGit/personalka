package com.akenory.personalka.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.akenory.personalka.domain.ClubTime
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MINUTE_MS = 60_000L

/** Часы телефона в миллисекундах UTC. Тесты подставляют свои. */
@OptIn(ExperimentalTime::class)
fun systemMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * «Сейчас» по часам клуба (ТЗ 4.14), с точностью до минуты, обновляется в начале каждой минуты:
 * заголовок «Главной» меняет день в полночь, позже от этих часов идут линия «сейчас» и кнопки по времени.
 */
@Composable
fun rememberClubNow(clock: () -> Long): LocalDateTime {
    var now by remember(clock) { mutableStateOf(ClubTime.now(clock())) }
    LaunchedEffect(clock) {
        while (true) {
            delay(MINUTE_MS - clock() % MINUTE_MS)
            now = ClubTime.now(clock())
        }
    }
    return now
}
