package com.akenory.personalka.server

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Строка «Сервер» в админке (шаг 5.3): отметка ночной копии из `backup-status`. */
class ServerStateTest {
    @Test
    fun okLineHidesFileNames() {
        val mark = ServerState.parse("2026-10-27T22:30:12+00:00\tok\t28.10.2026: trener-20261028.dump.age, 512 байт")!!
        assertEquals(LocalDateTime(2026, 10, 28, 3, 30, 12), mark.at, "время — по клубу")
        assertTrue(mark.ok)
        assertEquals("", mark.detail)
    }

    @Test
    fun errorLineKeepsStage() {
        val mark = ServerState.parse("2026-10-28T03:31:00+05:00\tошибка\tшифрование")!!
        assertFalse(mark.ok)
        assertEquals("шифрование", mark.detail)
    }

    @Test
    fun brokenLineOrNoFileMeansNoBackups() {
        assertNull(ServerState.parse("не время\tok\t"))
        val missing = ServerState("0.1.test", java.io.File("/нет/такого/файла")).read()
        assertNull(missing.backup)
        assertEquals("0.1.test", missing.version)
        assertTrue(missing.diskTotal > 0 && missing.diskUsed in 0..missing.diskTotal)
    }
}
