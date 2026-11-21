package com.akenory.personalka.domain.about

import com.akenory.personalka.domain.format.AboutFormat
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «О приложении» (шаг 5.33): тема, проверка обновления, журнал ошибок. */
class AboutRulesTest {
    @Test
    fun themeFollowsPhoneByDefault() {
        assertEquals(AppTheme.SYSTEM, AppTheme.of(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.of("что-то старое"))
        assertEquals(AppTheme.DARK, AppTheme.of("dark"))
        assertEquals(AppTheme.LIGHT, AppTheme.of(AppTheme.LIGHT.code))
        assertTrue(AppTheme.SYSTEM.isDark(systemDark = true))
        assertFalse(AppTheme.SYSTEM.isDark(systemDark = false))
        assertTrue(AppTheme.DARK.isDark(systemDark = false))
        assertFalse(AppTheme.LIGHT.isDark(systemDark = true))
    }

    /** Ответ GitHub о релизе «Последняя сборка» — как его пишет build.yml. */
    private val release = """{"tag_name":"latest-build","name":"Последняя сборка","body":"Сборка 570 (версия 0.1.570) из коммита 391005a. Ставится поверх прежней, данные сохраняются."}"""

    @Test
    fun updateComparesBuildNumbers() {
        assertEquals(570, UpdateRules.latestBuild(release))
        assertEquals(UpdateCheck.Available(570), UpdateRules.check(567, release))
        assertEquals(UpdateCheck.Fresh, UpdateRules.check(570, release))
        // Сборка из ветки шага бывает новее, чем в main, — это тоже «последняя версия».
        assertEquals(UpdateCheck.Fresh, UpdateRules.check(575, release))
    }

    @Test
    fun updateFailsWithoutAnswer() {
        assertEquals(UpdateCheck.Failed, UpdateRules.check(567, null))
        assertEquals(UpdateCheck.Failed, UpdateRules.check(567, """{"message":"API rate limit exceeded"}"""))
        assertNull(UpdateRules.latestBuild(""))
    }

    @Test
    fun crashLogKeepsLastFive() {
        var log: String? = null
        for (i in 1..7) log = CrashLogRules.add(log, "Версия 0.1.$i · сб 21.11\n\njava.lang.IllegalStateException: $i")
        val entries = CrashLogRules.entries(log)
        assertEquals(5, entries.size)
        assertTrue(entries.first().startsWith("Версия 0.1.3"))
        assertTrue(entries.last().endsWith("IllegalStateException: 7"))
        assertEquals("0.1.7", CrashLogRules.versionOf(entries.last()))
    }

    @Test
    fun crashLogDropsOldWhenTooLong() {
        val big = "Версия 0.1.1\n\n" + "x".repeat(CrashLogRules.MAX_LENGTH - 20)
        val log = CrashLogRules.add(big, "Версия 0.1.2\n\nкороткая")
        assertEquals(listOf("Версия 0.1.2\n\nкороткая"), CrashLogRules.entries(log))
        assertTrue(CrashLogRules.add(null, "y".repeat(CrashLogRules.MAX_LENGTH + 10)).length <= CrashLogRules.MAX_LENGTH)
        assertEquals(emptyList(), CrashLogRules.entries(""))
        assertEquals(emptyList(), CrashLogRules.entries(null))
    }

    @Test
    fun crashSummary() {
        val one = CrashLogRules.add(null, "Версия 0.1.560 · пт 20.11\n\nошибка")
        assertEquals("1${NBSP}ошибка · последняя в версии 0.1.560", AboutFormat.crashes(one))
        val two = CrashLogRules.add(one, "Версия 0.1.567 · сб 21.11\n\nошибка")
        assertEquals("2${NBSP}ошибки · последняя в версии 0.1.567", AboutFormat.crashes(two))
        assertEquals("1${NBSP}ошибка", AboutFormat.crashes("без версии"))
    }

    @Test
    fun texts() {
        assertEquals("Как в телефоне", AboutFormat.theme(AppTheme.SYSTEM))
        assertEquals("Тёмная", AboutFormat.theme(AppTheme.DARK))
        assertEquals("Есть новая версия 0.1.570", AboutFormat.available(570))
        assertTrue(AboutFormat.crashReport("0.1.567", "лог").startsWith("Персоналка 0.1.567 — журнал ошибок"))
    }
}
