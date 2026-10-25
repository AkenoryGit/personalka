package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.money.PurchaseProblem
import kotlinx.datetime.LocalDate

/**
 * Строки СПТ в приложении (ТЗ 4.9, шаг 3.7а): блок «Стартовая тренировка» в «Инфо» клиента (макет ClCardInfo), окно
 * «Купил блок со скидкой», вопрос на первой оплаченной ПТ и пункт «СПТ ждут покупки» на «Главной».
 */
object SptFormat {
    private const val DOT = " · "

    /** Подпись блока в «Инфо». */
    const val TITLE = "Стартовая тренировка"

    /** Строка СПТ: «пн 12.10 · СПТ · ББ». */
    fun line(date: LocalDate, typeShort: String, placeShort: String): String =
        listOf(DateFormat.short(date), typeShort, placeShort).filter { it.isNotBlank() }.joinToString(DOT)

    /** Статус дела: «Ждём покупку блока до 12.11», «Блок куплен 20.10», «Блок со скидкой не куплен до 12.11». */
    fun status(status: SptCaseStatus, windowUntil: LocalDate, purchaseDate: LocalDate?): String = when (status) {
        SptCaseStatus.OPEN -> "Ждём покупку блока до ${DateFormat.dayMonth(windowUntil)}"
        SptCaseStatus.CLOSED -> "Блок куплен ${DateFormat.dayMonth(purchaseDate ?: windowUntil)}"
        SptCaseStatus.NOT_CLOSED -> "Блок со скидкой не куплен до ${DateFormat.dayMonth(windowUntil)}"
    }

    /** Кнопка блока и заголовок окна (4.9, п. 3). */
    const val BOUGHT = "Купил блок со скидкой"

    /** Кнопка у закрытой СПТ: поправить дату покупки или снять отметку. */
    const val CHANGE = "Изменить покупку"

    const val PURCHASE_DATE = "Дата покупки"

    /** Подпись в окне: «СПТ пн 12.10 · скидка до 12.11 включительно». */
    fun window(sptDate: LocalDate, windowUntil: LocalDate): String =
        "СПТ ${DateFormat.short(sptDate)}$DOT" + "скидка до ${DateFormat.dayMonth(windowUntil)} включительно"

    /** Доплата за покупку идёт в месяц покупки (4.9, п. 3): «Доплата 140 ₽ — в зарплату за ноябрь». */
    fun bonusNote(bonus: Long, purchase: LocalDate): String =
        "Доплата ${MoneyFormat.rub(bonus)} — в зарплату за ${DateFormat.month(purchase).lowercase()}"

    /** Почему дату нельзя сохранить. */
    fun problem(problem: PurchaseProblem, sptDate: LocalDate, windowUntil: LocalDate): String = when (problem) {
        PurchaseProblem.BEFORE_SPT -> "Не раньше СПТ — ${DateFormat.dayMonth(sptDate)}"
        PurchaseProblem.AFTER_WINDOW -> "Скидка действует до ${DateFormat.dayMonth(windowUntil)}: позже — обычный блок"
    }

    const val SAVE = "Сохранить"
    const val UNMARK = "Снять отметку"

    /** Сообщения с «Отменить». */
    fun closedMessage(name: String): String = "$name — покупка блока отмечена"
    fun reopenedMessage(name: String): String = "$name — отметка покупки снята"

    // --- Вопрос на первой оплаченной ПТ (4.9, п. 4–5) ---

    /** В окне: «Засчитать СПТ закрытой?». */
    const val ASK_CLOSE = "Засчитать СПТ закрытой?"

    /** После окна: «Блок был куплен со скидкой до 12.11?». */
    fun askRetro(windowUntil: LocalDate): String = "Блок был куплен со скидкой до ${DateFormat.dayMonth(windowUntil)}?"

    /** Подпись вопроса: «Орлов М. · СПТ пн 12.10». */
    fun askNote(name: String, sptDate: LocalDate): String = "$name${DOT}СПТ$NBSP${DateFormat.short(sptDate)}"

    /** Ответ «Да» — окно с датой покупки. */
    const val ASK_YES = "Да, купил блок"

    // --- «Нужно сделать» (5.2, макет HomeEvening) ---

    const val TODO = "СПТ ждут покупки"

    /** Подпись пункта: «ближайший срок 12.11 · Яковлева Т.». */
    fun todoNote(windowUntil: LocalDate, name: ClientName): String =
        "ближайший срок ${DateFormat.dayMonth(windowUntil)}$DOT${CalendarFormat.client(name)}"
}
