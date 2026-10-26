package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.home.TodoKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Строки уведомлений (ТЗ 5.19) и раздела «Дневник и напоминания» в настройках (5.16, шаг 2.5). */
object ReminderFormat {
    private const val DOT = " · "

    // --- Уведомления ---

    /** Каналы уведомлений: их видно в настройках телефона, каждый можно выключить отдельно. */
    const val CHANNEL_DIGEST = "Вечерняя сводка"
    const val CHANNEL_INSTANT = "Напоминания"

    /** Кнопка в уведомлении: сразу первая тренировка без дневника. */
    const val DIARY_ACTION = "Дневник"

    /** Заголовок сводки: «Нужно сделать: 3». */
    fun digestTitle(count: Int): String = HomeFormat.todoCount(count)

    /**
     * Текст сводки — пункты «Нужно сделать» по порядку через точку: «Группа не отмечена · 3 без дневника · 2 клиента
     * без записи». [items] — вид пункта и его число (`null` — пункт без числа).
     */
    fun digest(items: List<Pair<TodoKind, Int?>>): String =
        items.joinToString(DOT) { (kind, count) -> digestItem(kind, count ?: 1) }.replaceFirstChar { it.uppercaseChar() }

    private fun digestItem(kind: TodoKind, n: Int): String = when (kind) {
        TodoKind.UNMARKED_GROUPS ->
            if (n == 1) "группа не отмечена" else RuText.count(n, "группа", "группы", "групп") + " не отмечены"
        TodoKind.VACATION_NOTICE -> "предупредить об отпуске " + RuText.count(n, "клиента", "клиентов", "клиентов")
        TodoKind.NO_DIARY -> RuText.digits(n.toLong()) + NBSP + "без дневника"
        TodoKind.FREED_TIME -> "освободилось время"
        TodoKind.NOT_BOOKED -> RuText.count(n, "клиент", "клиента", "клиентов") + " без записи"
        TodoKind.SPT_PURCHASE -> "СПТ ждут покупки"
        TodoKind.ADVANCE -> "введите аванс"
        TodoKind.RECONCILE -> "расхождения с 1С"
        TodoKind.NO_RATE -> "тренировки без ставки"
        TodoKind.BACKUP -> "нет резервной копии"
        TodoKind.CONSENT_REVOKED -> "отозвано согласие"
    }

    const val DAY_PLAN_TITLE = "План на день"

    /** «Аванс» 25-го в 19:00 (5.19): нажатие — окно ввода суммы в «Зарплате». */
    const val ADVANCE_TITLE = "Аванс"
    const val ADVANCE_TEXT = "Введите сумму аванса"

    /** «Сегодня 9 тренировок, первая в 08:00»; одна — «Сегодня 1 тренировка, в 08:00». */
    fun dayPlan(count: Int, first: LocalDateTime): String =
        "Сегодня " + CalendarFormat.trainings(count) + (if (count == 1) ", в$NBSP" else ", первая в$NBSP") + DateFormat.time(first.time)

    /** «Дневник сразу»: «16:00–16:50 · заполните дневник»; заголовок — имя клиента или группа. */
    fun instantDiary(start: LocalDateTime, end: LocalDateTime): String = DateFormat.timeRange(start, end) + DOT + "заполните дневник"

    /** «Группа сразу»: «18:00–18:45 · отметьте, кто пришёл»; заголовок — группа. */
    fun instantGroup(start: LocalDateTime, end: LocalDateTime): String = DateFormat.timeRange(start, end) + DOT + "отметьте, кто пришёл"

    // --- «Настройки → Дневник и напоминания» ---

    const val TITLE = "Дневник и напоминания"
    const val DIARY = "Вести дневник"
    const val DIARY_NOTE = "План, факт и поведение на тренировке — какие поля, задаётся в типе"
    const val AUTO_DONE = "Автоотметка «Проведено»"
    const val AUTO_DONE_NOTE = "ПТ, сплит и СПТ отмечаются проведёнными сами в конце тренировки"
    const val DIGEST = "Вечерняя сводка"
    const val DIGEST_NOTE = "Одно уведомление со всеми делами дня. Если дел нет, не приходит"
    const val FOLLOWUP = "Клиенты без записи"
    const val FOLLOWUP_DAYS = "Нет записи дальше чем на"
    const val MORE = "Дополнительно"
    const val INSTANT_DIARY = "Дневник сразу"
    const val INSTANT_DIARY_NOTE = "Через 15 минут после конца тренировки без дневника"
    const val INSTANT_GROUP = "Группа сразу"
    const val INSTANT_GROUP_NOTE = "Через 15 минут после конца группы без отметки"
    const val DAY_PLAN = "План на день"
    const val DAY_PLAN_NOTE = "В 07:00: сколько тренировок и когда первая. Если тренировок нет, не приходит"
    const val ADVANCE = "Аванс"
    const val ADVANCE_NOTE = "В день аванса в 19:00: ввести, сколько пришло. Если сумма уже введена, не приходит"
    const val BLOCKED = "Уведомления «Персоналки» выключены в настройках телефона"
    const val BLOCKED_ACTION = "Включить"

    /** Поле времени сводки: «в 21:00». */
    fun at(time: LocalTime): String = "в$NBSP" + DateFormat.time(time)

    /** «14 дней» на чипе срока «без записи». */
    fun days(n: Int): String = RuText.count(n, "день", "дня", "дней")

    /** Строка раздела в «Настройках»: «Дневник · сводка в 21:00»; всё выключено — «Без дневника · без сводки». */
    fun summary(diary: Boolean, digest: Boolean, time: LocalTime): String =
        (if (diary) "Дневник" else "Без дневника") + DOT + if (digest) "сводка " + at(time) else "без сводки"
}
