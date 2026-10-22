package com.akenory.personalka.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.akenory.personalka.data.clients.ClientRepository
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

    private val clients = mutableMapOf<SettingsRepository, ClientRepository>()

    /** До первого запуска: тренера нет. */
    fun fresh(): SettingsRepository {
        val db = inMemoryPersonalkaDatabase()
        return SettingsRepository(db, clock).also { clients[it] = ClientRepository(db, clock) }
    }

    /** Клиенты той же базы, что и [settings]. */
    fun clientsOf(settings: SettingsRepository): ClientRepository = clients.getValue(settings)

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
