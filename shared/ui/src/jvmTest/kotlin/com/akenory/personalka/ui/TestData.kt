package com.akenory.personalka.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime

/** База в памяти для тестов экранов — та же Room, что на телефоне. Имена придуманы. */
internal object TestData {
    val now = LocalDateTime(2026, 10, 20, 15, 40)
    val clock: () -> Long = { ClubTime.toMillis(now) }
    val coach = CoachName("Соколов", "Андрей", "Петрович")

    /** До первого запуска: тренера нет. */
    fun fresh(): SettingsRepository = SettingsRepository(inMemoryPersonalkaDatabase(), clock)

    /** Первый запуск пройден с местами и типами по умолчанию. */
    fun ready(): SettingsRepository = fresh().also {
        runBlocking { it.completeFirstRun(coach, ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes) }
    }
}

/**
 * Ждать, пока на экране появится узел, — база отвечает из своего потока, и Compose об этом не знает.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
    waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
}
