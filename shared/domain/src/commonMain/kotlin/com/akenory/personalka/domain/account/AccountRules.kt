package com.akenory.personalka.domain.account

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Закрытые возможности аккаунта (решение Дмитрия 24.10.2026, ТЗ 1.5, 7.3): каждая открывается отдельно,
 * со своей датой «до». [code] — значение в базе сервера и в ответе `/me`; новая возможность — новая строка
 * перечисления, таблицы не меняются. Закрытое в приложении не видно вообще (ТЗ 10.7).
 */
enum class Feature(val code: String, val title: String) {
    /** Схема выплат «Половина официально» (4.11): считается в телефоне — скрыть можно, защитить нельзя. */
    HALF_OFFICIAL("half_official", "Половина официально"),

    /** Кабинеты родителей (раздел 6): показывают данные с сервера, поэтому открывают и копию на сервере. */
    CABINETS("cabinets", "Кабинеты родителей"),

    /** Копия на сервере: синхронизация, второй телефон, восстановление (7.2). */
    SERVER_COPY("server_copy", "Копия на сервере"),
    ;

    /** Что открывается вместе с этой возможностью. */
    val includes: Set<Feature>
        get() = if (this == CABINETS) setOf(SERVER_COPY) else emptySet()

    companion object {
        fun of(code: String): Feature? = entries.firstOrNull { it.code == code }
    }
}

/** Возможность открыта до [until] включительно; `null` — без срока (так у администратора). */
data class FeatureGrant(val feature: Feature, val until: LocalDate?)

/** Правила аккаунта: почта, пароль, имя и открытые возможности. Одни и те же для сервера и формы в приложении. */
object AccountRules {
    /** Пароль — не короче 10 знаков (7.3). */
    const val PASSWORD_MIN = 10

    /** Длиннее пароли не нужны, а Argon2 на сервере не должен считать мегабайты. */
    const val PASSWORD_MAX = 200

    const val NAME_MAX = 60

    /** За сколько дней до конца срока напомнить тренеру. */
    const val REMIND_DAYS = 7

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    /** Адрес почты без пробелов по краям, маленькими буквами; неверный — `null`. */
    fun email(text: String): String? = text.trim().lowercase().takeIf { it.length <= 254 && EMAIL.matches(it) }

    /** Что не так с паролем — для показа; `null` — подходит. Пробелы по краям — часть пароля, их не режем. */
    fun passwordProblem(password: String): String? = when {
        password.length < PASSWORD_MIN -> "Пароль — не короче $PASSWORD_MIN знаков"
        password.length > PASSWORD_MAX -> "Пароль — не длиннее $PASSWORD_MAX знаков"
        password.isBlank() -> "Пароль не может состоять из пробелов"
        else -> null
    }

    /** Имя тренера: без пробелов по краям и лишних внутри; пустое — `null`. */
    fun name(text: String): String? = text.trim().replace(Regex("""\s+"""), " ").take(NAME_MAX).takeIf { it.isNotEmpty() }

    /**
     * Срок каждой возможности с учётом того, что одна открывает другую: «Кабинеты» до 31.12 открывают
     * «Копию на сервере» тоже до 31.12, если своя дата у копии раньше или её нет. Ключ есть — возможность
     * когда-то открывали; значение `null` — без срока.
     */
    fun effective(grants: List<FeatureGrant>): Map<Feature, LocalDate?> {
        val result = linkedMapOf<Feature, LocalDate?>()
        fun add(feature: Feature, until: LocalDate?) {
            if (!result.containsKey(feature)) {
                result[feature] = until
            } else {
                val was = result[feature]
                result[feature] = if (was == null || until == null) null else maxOf(was, until)
            }
        }
        for (grant in grants) {
            add(grant.feature, grant.until)
            grant.feature.includes.forEach { add(it, grant.until) }
        }
        return Feature.entries.filter { it in result }.associateWith { result[it] }
    }

    /** Открыто на [today]: срок не задан или ещё не прошёл (последний день — включительно). */
    fun open(grants: List<FeatureGrant>, today: LocalDate): Set<Feature> =
        effective(grants).filter { (_, until) -> until == null || today <= until }.keys

    /** Возможности, срок которых кончается в ближайшие [REMIND_DAYS] дней, — для напоминания тренеру. */
    fun endingSoon(grants: List<FeatureGrant>, today: LocalDate): Map<Feature, LocalDate> =
        effective(grants).mapNotNull { (feature, until) ->
            until?.takeIf { today <= it && it <= today.plus(DatePeriod(days = REMIND_DAYS)) }?.let { feature to it }
        }.toMap()

    /** У администратора открыто всё и без срока. */
    fun admin(): List<FeatureGrant> = Feature.entries.map { FeatureGrant(it, null) }
}
