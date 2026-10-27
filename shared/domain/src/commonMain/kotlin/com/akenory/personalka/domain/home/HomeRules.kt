package com.akenory.personalka.domain.home

import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/**
 * Тренировка для «Главной» (ТЗ 5.2) — только то, что нужно правилам. Дежурств среди них нет: это не тренировки.
 *
 * @param group групповая — для пункта «Группы без отметки».
 * @param marked проведена или в группе отмечен хоть один ребёнок.
 */
data class HomeSlot(
    val id: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val group: Boolean,
    val cancelled: Boolean,
    val marked: Boolean,
) {
    val end: LocalDateTime get() = TrainingTime.end(start, durationMin)
}

/**
 * Пункты «Нужно сделать» (5.2) в порядке ТЗ: в этом порядке они и идут в блоке. Пункт есть, только если ему есть что
 * показать; число пунктов — счётчик дел на значке «Главной».
 */
enum class TodoKind {
    /** Сегодня придёт клиент с красной надёжностью — уточнить, придёт ли (шаг 3.14). */
    CONFIRM_VISIT,

    /** Группа закончилась, а посещаемость не отмечена (4.1). */
    UNMARKED_GROUPS,

    /** Предупредить клиентов об отпуске (5.16) — шаг 3.12: пока каждого не отметили «Предупредил». */
    VACATION_NOTICE,

    /** Проведённые без дневника — с дневником (шаг 2.1). */
    NO_DIARY,

    /** Освободилось время, которое ждут (4.16) — шаг 2.4. */
    FREED_TIME,

    /** Настал день «Напомнить с» у листа ожидания (4.16) — шаг 3.9х. */
    WAIT_REMIND,

    /** Клиенты без записи (4.15) — шаг 2.4. */
    NOT_BOOKED,

    /** Не ходят в группу 30 дней и больше (5.10) — шаг 3.9у. */
    GROUP_ABSENT,

    /** СПТ ждут покупки — этап 3. */
    SPT_PURCHASE,

    /** Аванс: фактическая сумма с 25-го — этап 3. */
    ADVANCE,

    /** Расхождения с 1С — этап 4. */
    RECONCILE,

    /** Тренировки без ставки — этап 3. */
    NO_RATE,

    /** Резервная копия ([BackupTodo]). */
    BACKUP,

    /** Отозванные согласия (6.1) — этап 2. */
    CONSENT_REVOKED,
}

/** Что не так с копией в режиме «Только телефон» (1.5, 7.6). */
enum class BackupTodo {
    /** Папка для автокопии не выбрана: данные только в этом телефоне. */
    NO_FOLDER,

    /** Папка есть, а копии нет больше 7 дней. */
    STALE,
}

/** «Главная» (ТЗ 5.2): «Ближайшая», что ещё сегодня и завтра, расписание дня на разложенном и пункты «Нужно сделать». */
object HomeRules {
    /**
     * «Ближайшая» — идущая сейчас, а если такой нет — следующая по началу (шаг 3.9ф, просьба Дмитрия 27.10.2026: на
     * тренировке тренер смотрит её план). Идущая остаётся до конца, пока её не отметили: ПТ отметится сама в конце
     * (4.1), группа — когда отметят детей. Отменённые и уже проведённые («Проведено» с начала минус 15 минут)
     * пропускаются. `null` — впереди ничего нет.
     */
    fun nearest(slots: List<HomeSlot>, now: LocalDateTime): HomeSlot? =
        slots.filter { !it.cancelled && !it.marked && it.end > now }
            .minWithOrNull(compareBy({ it.start }, { it.id }))

    /** Сегодняшние после «Ближайшей» — «Ещё сегодня: 3, последняя в 19:00». Отменённые не считаются. */
    fun laterToday(slots: List<HomeSlot>, nearest: HomeSlot, today: LocalDate): List<HomeSlot> =
        slots.filter { !it.cancelled && it.id != nearest.id && it.start.date == today && it.start >= nearest.start }
            .sortedBy { it.start }

    /** «Сегодня всё»: сегодня больше нечего проводить — «Ближайшей» нет или она в другой день. */
    fun todayDone(nearest: HomeSlot?, today: LocalDate): Boolean = nearest == null || nearest.start.date != today

    /** Расписание дня на разложенном: сегодняшние по времени, отменённые тоже. */
    fun day(slots: List<HomeSlot>, today: LocalDate): List<HomeSlot> =
        slots.filter { it.start.date == today }.sortedWith(compareBy({ it.start }, { it.id }))

    /** Прошедшие — закончились; они свёрнуты в строку «Показать прошедшие · 7». */
    fun isPast(slot: HomeSlot, now: LocalDateTime): Boolean = slot.end <= now

    /** Идёт сейчас: «идёт» в расписании дня. */
    fun isOngoing(slot: HomeSlot, now: LocalDateTime): Boolean = !slot.cancelled && slot.start <= now && now < slot.end

    /** Завтрашние, кроме отменённых, — «Завтра: 9 тренировок, первая в 07:00» под расписанием дня. */
    fun tomorrow(slots: List<HomeSlot>, today: LocalDate): List<HomeSlot> {
        val next = today.plus(1, DateTimeUnit.DAY)
        return slots.filter { !it.cancelled && it.start.date == next }.sortedBy { it.start }
    }

    /**
     * Группы без отметки — первый пункт «Нужно сделать»: группа закончилась, не отменена и в ней никого не отметили.
     * В конце она не закрывается сама (4.1), поэтому висит здесь, пока тренер не отметит детей или «Все пришли».
     */
    fun unmarkedGroups(slots: List<HomeSlot>, now: LocalDateTime): List<HomeSlot> =
        slots.filter { it.group && !it.cancelled && !it.marked && it.end <= now }.sortedBy { it.start }

    /**
     * Пункт про копию (5.2, режим «Только телефон»): папка не выбрана — «Выберите папку для копии»; выбрана, а
     * последней копии нет или ей больше 7 дней ([BackupRules.remind]) — «Нет копии больше 7 дней». Копия появилась —
     * пункт уходит сам.
     */
    fun backup(folderChosen: Boolean, last: LocalDateTime?, now: LocalDateTime): BackupTodo? = when {
        !folderChosen -> BackupTodo.NO_FOLDER
        BackupRules.remind(last, now) -> BackupTodo.STALE
        else -> null
    }
}
