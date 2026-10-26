package com.akenory.personalka.data.reminders

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.domain.client.FollowupRules
import com.akenory.personalka.domain.reminder.ReminderRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime

/** Настройки дневника и напоминаний тренера (3.2, 5.16). «План на день» — настройка телефона, она не здесь. */
data class DiaryPrefs(
    val diary: Boolean = false,
    val autoDone: Boolean = true,
    val digest: Boolean = true,
    val digestTime: LocalTime = ReminderRules.DIGEST_DEFAULT,
    val instantDiary: Boolean = false,
    val instantGroup: Boolean = false,
    val followup: Boolean = true,
    val followupDays: Int = FollowupRules.DEFAULT_DAYS,
)

/**
 * Раздел «Дневник и напоминания» (ТЗ 5.16, 5.19, шаг 2.5): каждое значение пишется в [CoachSettingsEntity], как только
 * выбрано, без «Сохранить». Дневник, «Нужно сделать» и уведомления читают настройки сами — выключенный дневник сразу
 * убирает пункт «Без дневника».
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class ReminderSettingsRepository internal constructor(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val coaches = db.coachDao()

    val prefs: Flow<DiaryPrefs> = coaches.observeSettings().map { s ->
        if (s == null) {
            DiaryPrefs()
        } else {
            DiaryPrefs(
                s.diaryEnabled,
                s.autoDonePersonal,
                s.digestEnabled,
                s.digestTime,
                s.instantDiary,
                s.instantGroup,
                s.followupEnabled,
                s.followupDays,
            )
        }
    }

    /** Первый запуск пройден: до него напоминать не о чем. */
    suspend fun ready(): Boolean = coaches.currentCoach() != null

    suspend fun saveDiary(on: Boolean) = update { it.copy(diaryEnabled = on) }

    suspend fun saveAutoDone(on: Boolean) = update { it.copy(autoDonePersonal = on) }

    suspend fun saveDigest(on: Boolean) = update { it.copy(digestEnabled = on) }

    suspend fun saveDigestTime(time: LocalTime) = update { it.copy(digestTime = time) }

    suspend fun saveInstantDiary(on: Boolean) = update { it.copy(instantDiary = on) }

    suspend fun saveInstantGroup(on: Boolean) = update { it.copy(instantGroup = on) }

    suspend fun saveFollowup(on: Boolean) = update { it.copy(followupEnabled = on) }

    suspend fun saveFollowupDays(days: Int) {
        require(days in ReminderRules.FOLLOWUP_DAYS) { "Срок «без записи»: $days" }
        update { it.copy(followupDays = days) }
    }

    private suspend fun update(change: (CoachSettingsEntity) -> CoachSettingsEntity) {
        val coach = coaches.currentCoach() ?: return
        val settings = coaches.settings(coach.id) ?: return
        coaches.upsertSettings(change(settings).copy(sync = settings.sync.touched(clock())))
    }
}
