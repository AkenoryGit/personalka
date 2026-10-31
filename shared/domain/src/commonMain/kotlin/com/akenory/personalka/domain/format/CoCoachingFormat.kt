package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.money.CoCoachingRules
import com.akenory.personalka.domain.money.Sharing
import com.akenory.personalka.domain.money.TrainingPay

/**
 * Цвет плашки «кто ведёт» (шаг 3.9п, просьба Дмитрия): своя группа один, своя вдвоём, группа коллеги (вдвоём или
 * тренер заменяет), ведёт коллега — тренера на ней нет.
 */
enum class WhoKind { MINE_ALONE, MINE_TOGETHER, THEIRS, NOT_ME }

/** Плашка «кто ведёт» на блоке группы: полная, короткая — для узкого блока, и её цвет (шаги 3.9л, 3.9п). */
data class BlockWho(val full: String, val short: String, val kind: WhoKind)

/** Слова совместного ведения групп (ТЗ 4.8, 5.4, 5.10, 5.16, шаг 3.7г). */
object CoCoachingFormat {
    private const val DOT = " · "

    const val COLLEAGUES = "Коллеги"
    const val NEW = "Новый коллега"
    const val NAME = "Имя"
    const val NAME_HINT = "Как вы зовёте коллегу, например «Аня»"
    const val SCHEME = "Схема выплат коллеги"
    const val SCHEME_NOTE = "Нужна для доли налога, когда группа числится на коллеге."
    const val EMPTY = "Коллег пока нет"
    const val EMPTY_TEXT = "Добавьте тех, с кем ведёте группы или кто вас заменяет."
    const val ADD = "Коллега"
    const val ARCHIVE = "Убрать в архив"
    const val UNARCHIVE = "Вернуть из архива"
    const val ARCHIVED = "В архиве"
    const val NO_COLLEAGUES = "Сначала добавьте коллегу: «Ещё → Настройки → Коллеги»."

    const val OWNER = "На ком числится"
    const val DEFAULT_CONDUCTED = "Кто обычно ведёт"
    const val CONDUCTED = "Проводим"
    const val WHO = "Кто ведёт"
    const val ME = "Я"
    const val COLLEAGUE = "Коллега"
    const val WHICH = "Какой коллега"
    const val GROUP_NOTE = "Будущие тренировки группы получат новые значения; изменённые в карточке — останутся."

    fun conducted(c: ConductedBy): String = when (c) {
        ConductedBy.SELF -> "Один"
        ConductedBy.SELF_AND_COLLEAGUE -> "С коллегой"
        ConductedBy.COLLEAGUE -> "Коллега (замена)"
        ConductedBy.EACH_OWN -> "Каждый своих"
    }

    /** «2 коллеги» в строке настроек; нет — «Пока нет». */
    fun count(n: Int): String = if (n == 0) "Пока нет" else RuText.count(n, "коллега", "коллеги", "коллег")

    /** Строка «Числится на: я» / «Числится на: Лена». */
    fun ownerLine(s: Sharing, names: Map<String, String>): String =
        "Числится на: " + if (s.owner == Owner.SELF) "я" else name(s.ownerColleagueId, names)

    /** Строка «Проводим: один» / «Проводим: с коллегой · Лена» / «Проводим: коллега (замена) · Лена». */
    fun conductedLine(s: Sharing, names: Map<String, String>, prefix: String = "Проводим: "): String {
        val what = conducted(s.conductedBy).replaceFirstChar { it.lowercaseChar() }
        val who = if (s.conductedBy == ConductedBy.SELF) null else name(s.colleague, names)
        return prefix + listOfNotNull(what, who).joinToString(DOT)
    }

    /** В списке групп у группы коллеги: «числится: Лена»; своя — ничего. */
    fun listNote(s: Sharing, names: Map<String, String>): String? =
        if (s.owner == Owner.COLLEAGUE) "числится: " + name(s.ownerColleagueId, names) else null

    /**
     * Кто ведёт — плашкой на блоке группы в календаре (шаги 3.9л, 3.9п): [BlockWho.full] — «Один», «Вдвоём · Лена»,
     * «Ведёт Лена», «Один · числится Лена»; [BlockWho.short] — для узкого блока: «Вдвоём»; [BlockWho.kind] — цвет
     * плашки: по нему видно, так ли стоит группа.
     */
    fun blockWho(s: Sharing, names: Map<String, String>): BlockWho {
        val n = CoCoachingRules.normalize(s)
        val who = name(n.colleague, names)
        val mine = n.owner == Owner.SELF
        val owner = if (mine) null else "числится $who"
        return when (n.conductedBy) {
            ConductedBy.SELF -> BlockWho(listOfNotNull(ALONE, owner).joinToString(DOT), ALONE, if (mine) WhoKind.MINE_ALONE else WhoKind.THEIRS)
            ConductedBy.SELF_AND_COLLEAGUE ->
                BlockWho(listOf(TOGETHER, owner ?: who).joinToString(DOT), TOGETHER, if (mine) WhoKind.MINE_TOGETHER else WhoKind.THEIRS)
            ConductedBy.COLLEAGUE -> "Ведёт $who".let { BlockWho(listOfNotNull(it, owner).joinToString(DOT), it, WhoKind.NOT_ME) }
            ConductedBy.EACH_OWN ->
                BlockWho(listOf(EACH_OWN, owner ?: who).joinToString(DOT), EACH_OWN, if (mine) WhoKind.MINE_TOGETHER else WhoKind.THEIRS)
        }
    }

    /** Подпись в строке ребёнка коллеги и заголовок выбора «Чей ребёнок» (шаг 5.3м). */
    const val WHOSE = "Чей ребёнок"
    const val WHOSE_GUEST = "Чей"
    const val MY_KID = "Мой"
    const val MY_KID_SHORT = "мой"

    /** Над составом группы «Каждый своих»: «Моих 7 · Лены 5» (5.10, шаг 5.3м). */
    fun kidsSplit(mine: Int, theirs: Int, colleague: String): String =
        "Моих ${RuText.digits(mine.toLong())}$DOT${RuText.genitive(colleague)} ${RuText.digits(theirs.toLong())}"

    /**
     * Строка оплаты в карточке группы «Каждый своих» (5.4, шаг 5.3м): «мои 7 × 270 ₽ = 1 890 ₽ · Лены 5: отдаёте
     * 1 262,25 ₽»; в группе коллеги — «мои 7 × 270 ₽ = 1 890 ₽ · Лена отдаёт вам 1 767,15 ₽». Никто не пришёл — `null`.
     */
    fun eachOwnPay(pay: TrainingPay, mineGroup: Boolean, colleague: String): String? {
        val paid = pay.paidClients
        if (paid == 0 || pay.rateMissing) return null
        val mine = paid - pay.colleagueClients
        val rate = pay.amount / paid
        val head = "мои ${RuText.digits(mine.toLong())} × ${MoneyFormat.rub(rate)} = ${MoneyFormat.rub(pay.earned)}"
        val money = MoneyFormat.rub(kotlin.math.abs(pay.settlement))
        val tail = when {
            pay.settlement == 0L -> null
            mineGroup -> "${RuText.genitive(colleague)} ${RuText.digits(pay.colleagueClients.toLong())}: отдаёте $money"
            else -> "$colleague отдаёт вам $money"
        }
        return listOfNotNull(head, tail).joinToString(DOT)
    }

    private const val ALONE = "Один"
    private const val TOGETHER = "Вдвоём"
    private const val EACH_OWN = "Каждый своих"

    private fun name(id: String?, names: Map<String, String>): String = id?.let { names[it] } ?: SalaryFormat.COLLEAGUE
}
