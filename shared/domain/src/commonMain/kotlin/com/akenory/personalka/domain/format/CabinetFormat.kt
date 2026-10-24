package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number

/**
 * Строки кабинета родителя (ТЗ 6.1, 6.3; макеты 0.7). Имена не склоняются и пол не используется: имя — только в
 * именительном падеже, дальше — «ребёнок». Весь текст на «вы».
 */
object CabinetFormat {
    private const val DOT = " · "

    const val TITLE = "Кабинет тренировок"
    const val UPCOMING = "Ближайшие тренировки"
    const val VISITS = "Посещения"
    const val HISTORY = "История"
    const val EARLIER = "Ранее"
    const val PLAN = "План"
    const val FACT = "Факт"
    const val BEHAVIOR = "Поведение"
    const val STATUS = "Статус"
    const val FOOTER = "По всем вопросам пишите тренеру напрямую."

    // --- Результаты (6.3, п. 6) ---
    const val RESULTS = "Результаты"
    const val CHART_TITLE = "Время по дистанции"
    const val CHART_CHIPS = "Дистанция"
    const val CHART_HINT = ResultFormat.CHART_HINT + "."
    const val ALL_RESULTS = ResultFormat.ALL
    const val LEGEND_COMPETITION = ResultFormat.LEGEND_COMPETITION
    const val LEGEND_TRAINING = ResultFormat.LEGEND_TRAINING

    private val MONTH_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    // --- Код доступа (6.1) ---
    const val CODE_TITLE = "Введите код"
    const val CODE_HINT = "Код из 4 цифр вам сообщил тренер."
    const val CODE_LABEL = "Код доступа"
    const val CODE_ENTER = "Войти"
    const val CODE_REMEMBER = "На этом устройстве код больше не спросят 30 дней."
    const val CODE_BLOCKED = "Слишком много попыток. Попробуйте через 15 минут."
    const val CODE_FORMAT = "Введите 4 цифры."

    // --- Недействительная ссылка и предел запросов (6.1, 6.2) ---
    const val INVALID_TITLE = "Ссылка больше не действует"
    const val INVALID_TEXT = "Попросите тренера прислать новую."
    const val TOO_MANY_TITLE = "Слишком много запросов"
    const val TOO_MANY_TEXT = "Подождите минуту и обновите страницу."
    const val ERROR_TITLE = "Не получилось открыть кабинет"
    const val ERROR_TEXT = "Попробуйте обновить страницу через несколько минут."

    // --- Согласие (6.1, макет `KbConsent`) ---
    const val CONSENT_INTRO_CHILD =
        "В кабинете — расписание, записи тренера о тренировках и результаты. Чтобы открыть его, тренеру нужно согласие родителя на обработку данных ребёнка."
    const val CONSENT_INTRO_ADULT =
        "В кабинете — расписание, записи тренера о тренировках и результаты. Чтобы открыть его, тренеру нужно ваше согласие на обработку ваших данных."
    const val CONSENT_NAME = "Ваши фамилия и имя"
    const val CONSENT_NAME_HINT = "Например, Иванова Анна"
    const val CONSENT_AGREE_CHILD =
        "Я родитель (законный представитель) ребёнка и даю согласие на обработку его персональных данных на условиях выше."
    const val CONSENT_AGREE_ADULT = "Даю согласие на обработку моих персональных данных на условиях выше."
    const val CONSENT_PHOTO_CHILD = "Разрешаю хранить фото и видео ребёнка и показывать их в этом кабинете"
    const val CONSENT_PHOTO_ADULT = "Разрешаю хранить мои фото и видео и показывать их в этом кабинете"
    const val CONSENT_OPTIONAL = " — по желанию"
    const val CONSENT_CONTINUE = "Продолжить"
    const val CONSENT_AGREE_EMPTY = "Чтобы открыть кабинет, отметьте согласие."
    const val CONSENT_NAME_EMPTY = "Впишите ваши фамилию и имя."
    const val POLICY_LINK = LegalText.POLICY_TITLE
    const val POLICY_MISSING = "Страница не найдена"
    const val POLICY_MISSING_TEXT = "Проверьте адрес или спросите тренера."

    // --- Фото и видео, подвал, отзыв (6.1, 6.3; макеты `KbCabinet`, `KbRevoke`, `KbRevoked`) ---
    const val MEDIA = "Фото и видео"
    const val REVOKE = "Отозвать согласие"
    const val REVOKE_TITLE = "Отозвать согласие?"
    const val REVOKE_BACK = "Вернуться в кабинет"
    const val REVOKE_CLOSES = "Кабинет закроется по этой ссылке — для вас и для всех, с кем вы ею делились."
    const val REVOKE_HINT = "Если передумаете, напишите тренеру — он пришлёт новую ссылку."
    const val REVOKED_TITLE = "Согласие отозвано"

    /** «Согласие на обработку данных дано 05.09.2026». */
    fun consentGiven(date: LocalDate): String = "Согласие на обработку данных дано ${DateFormat.full(date)}"

    /** «Тренер удалит данные ребёнка из приложения и с сервера в течение 30 дней.» */
    fun revokeDeletes(child: Boolean, days: Int): String =
        "Тренер удалит ${whose(child)} из приложения и с сервера в течение ${RuText.count(days, "дня", "дней", "дней")}."

    /** «Кабинет закрыт. Тренер удалит данные ребёнка до 19.11.2026.» */
    fun revokedText(child: Boolean, deleteBy: LocalDate): String = "Кабинет закрыт. Тренер удалит ${whose(child)} до ${DateFormat.full(deleteBy)}."

    private fun whose(child: Boolean) = if (child) "данные ребёнка" else "ваши данные"

    /** «Откроется Яндекс Диск» — куда ведёт ссылка «Фото и видео»; незнакомый сервис — его адрес. */
    fun mediaNote(host: String): String {
        val name = when {
            host == "yadi.sk" || host.startsWith("disk.yandex.") || host.startsWith("disk.360.yandex.") -> "Яндекс Диск"
            host == "cloud.mail.ru" -> "Облако Mail.ru"
            host == "drive.google.com" || host == "photos.google.com" || host == "photos.app.goo.gl" -> "Google Диск"
            host == "vk.com" || host == "vk.ru" -> "ВКонтакте"
            else -> host
        }
        return "Откроется $name"
    }

    /** «Неверный код. Осталось 3 попытки.» */
    fun wrongCode(left: Int): String = "Неверный код. Осталось ${RuText.count(left, "попытка", "попытки", "попыток")}."

    /** «Тренер: Дмитрий Дудник». */
    fun coach(name: String): String = "Тренер: $name"

    /** «Данные на 20.10.2026, 15:41» — время последней синхронизации тренера. */
    fun dataAt(at: LocalDateTime): String = "Данные на ${DateFormat.full(at.date)}, ${DateFormat.time(at.time)}"

    /** «вт 20.10 · 13:30». */
    fun whenText(start: LocalDateTime): String = DateFormat.short(start.date) + DOT + DateFormat.time(start.time)

    /** «Октябрь» — столбец счётчиков. */
    fun monthColumn(date: LocalDate): String = DateFormat.month(date).replaceFirstChar { it.uppercaseChar() }

    /** «Октябрь 2026» — месяц истории. */
    fun monthTitle(date: LocalDate): String = DateFormat.monthYear(date)

    /** «6 тренировок». */
    fun trainings(count: Int): String = RuText.count(count, "тренировка", "тренировки", "тренировок")

    /** Правило отмены (6.3, п. 4): время из настроек тренера полными словами — «1 час», «1 час 30 минут», «2 часа». */
    fun rule(minutes: Int): String =
        "Если вы предупреждаете об отмене меньше чем за ${DurationFormat.long(minutes)} до начала, " +
            "тренировка считается проведённой и списывается."

    /**
     * Под отменённой: причина, у отмены со списанием — ещё «предупредили за 40 мин» ([noticeMinutes] до начала;
     * отрицательное — после начала). Нет ни того, ни другого — строки нет.
     */
    fun cancelNote(reason: String?, noticeMinutes: Long?): String? {
        val notice = noticeMinutes?.let { if (it < 0) "предупредили после начала" else "предупредили за$NBSP" + DurationFormat.short(it) }
        return when {
            reason != null && notice != null -> reason + DOT + notice
            reason != null -> reason
            notice != null -> notice.replaceFirstChar { it.uppercaseChar() }
            else -> null
        }
    }

    /** «бассейн 25 м». */
    fun poolLower(poolM: Int): String = ResultFormat.pool(poolM).replaceFirstChar { it.lowercaseChar() }

    /** «Личные рекорды · бассейн 25 м». */
    fun recordsTitle(poolM: Int): String = ResultFormat.RECORDS + DOT + poolLower(poolM)

    /** «сен» — подпись месяца на графике, когда месяцев много. */
    fun monthShort(date: LocalDate): String = MONTH_SHORT[date.month.number - 1]

    /** Для чтения с экрана: «50 м вольный стиль: 6 результатов, с 44,10 03.09.2026 до 39,62 10.10.2026, лучшее 39,62». */
    fun chartSummary(event: String, count: Int, firstTime: String, firstDate: LocalDate, lastTime: String, lastDate: LocalDate, best: String): String =
        "$event: ${RuText.count(count, "результат", "результата", "результатов")}, " +
            "с $firstTime ${DateFormat.full(firstDate)} до $lastTime ${DateFormat.full(lastDate)}, лучшее $best"
}
