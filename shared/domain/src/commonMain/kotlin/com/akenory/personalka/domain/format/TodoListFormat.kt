package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.client.FollowupRules
import com.akenory.personalka.domain.client.ReliabilityRules
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.group.GroupAbsence
import com.akenory.personalka.domain.money.SptCase
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil

/**
 * Окна пунктов «Нужно сделать» (ТЗ 5.2, шаг 3.16): сверху — почему эти люди здесь и что сделать, ниже — каждый
 * со своими подробностями и кнопками.
 */
object TodoListFormat {
    private const val DOT = " · "

    /** «Уточнить, придёт ли»: кто часто отменяет и сегодня придёт. */
    val CONFIRM_WHY: String =
        "Эти клиенты часто отменяют — надёжность ниже ${ReliabilityRules.MIDDLE_FROM}$NBSP%. Сегодня у них тренировка: " +
            "напишите или позвоните, точно ли придут, чтобы время не пропало. Ответ отметьте здесь."

    const val COMING = "Придёт"
    const val NOT_COMING = "Не придёт"

    /** Сообщение после «Придёт»: «Соколова А. придёт». */
    fun comingMessage(name: String): String = "$name придёт"

    /** Справа от имени: «сегодня 17:00». */
    fun today(start: LocalDateTime): String = "сегодня " + DateFormat.time(start.time)

    /** Под именем: «ПТ · ББ». */
    fun typePlace(typeShort: String, placeShort: String): String = listOf(typeShort, placeShort).filter { it.isNotBlank() }.joinToString(DOT)

    /** Метка: «Надёжность 62 % · 19 отмен из 50». */
    fun reliability(r: Reliability): String = listOf(ReliabilityFormat.label(r), ReliabilityFormat.basis(r)).filter { it.isNotBlank() }.joinToString(DOT)

    /** «Клиенты без записи»: последняя прошла, следующей нет. */
    fun followupWhy(days: Int): String =
        "У этих клиентов прошла последняя тренировка, а следующей нет — за последние ${RuText.count(days, "день", "дня", "дней")}. " +
            "Запишите на новое время или спросите, будет ли ходить дальше. Кнопка «Написал» уберёт клиента на ${RuText.count(FollowupRules.CONTACT_HIDE_DAYS, "день", "дня", "дней")}."

    /** «Не ходят в группу»: в составе, но давно не были. */
    val ABSENT_WHY: String =
        "Эти дети в составе группы, но не были ${GroupAbsence.DAYS} дней или ходят реже половины занятий. Узнайте у родителей, " +
            "ходит ли ребёнок ещё. Если нет — уберите из группы, чтобы его не ждали на каждой тренировке. " +
            "«Оставить» — спрошу ещё раз через ${GroupAbsence.DAYS} дней."

    /** «СПТ ждут покупки»: окно покупки со скидкой. */
    const val SPT_WHY =
        "У этих клиентов прошла стартовая тренировка. Если они купят блок со скидкой до конца окна, вам начислится доплата. " +
            "Напомните о скидке, а когда купят — отметьте."

    const val SPT_BOUGHT = "Купил блок"

    /** «СПТ ср 21.10 · ББ · окно до 05.11». */
    fun sptLine(case: SptCase, typeShort: String, placeShort: String): String =
        SptFormat.line(case.sptDate, typeShort, placeShort) + DOT + "окно до " + DateFormat.dayMonth(case.windowUntil)

    /** Справа от имени: «ещё 8 дней», в последний день — «последний день». */
    fun sptLeft(windowUntil: LocalDate, today: LocalDate): String {
        val days = today.daysUntil(windowUntil)
        return if (days <= 0) "последний день" else "ещё " + RuText.count(days, "день", "дня", "дней")
    }

    /** «доплата за покупку 500 ₽»; без доплаты — пусто. */
    fun sptBonus(bonus: Long): String = if (bonus > 0) "доплата за покупку " + MoneyFormat.rub(bonus) else ""

    /** Время тренировки справа: сегодня — «сегодня 18:00», раньше — «пн 26.10 18:00». */
    fun at(start: LocalDateTime, today: LocalDate): String =
        if (start.date == today) TodoListFormat.today(start) else DateFormat.short(start.date) + " " + DateFormat.time(start.time)

    /** «Без дневника» (шаг 3.16а). */
    const val DIARY_WHY =
        "Эти тренировки проведены, а дневник не заполнен — без него в истории клиента и в кабинете родителя пусто. " +
            "Заполните, пока помните."

    const val FILL = "Заполнить"

    /** «Группа без отметки» (шаг 3.16а). */
    const val GROUPS_WHY =
        "Эти группы закончились, а кто пришёл — не отмечено. Без отметки не считаются зарплата и посещаемость детей."

    const val MARK = "Отметить, кто пришёл"

    /** «Удалить данные клиента» (шаг 3.16а). */
    const val CONSENT_WHY =
        "Эти клиенты отозвали согласие на обработку данных — их данные нужно удалить до срока. Откройте карточку, " +
            "меню «…» → «Удалить полностью»."

    const val OPEN_CARD = "Открыть карточку"

    /** «удалить до 12.11». */
    fun deleteBy(date: LocalDate): String = "удалить до " + DateFormat.dayMonth(date)
}
