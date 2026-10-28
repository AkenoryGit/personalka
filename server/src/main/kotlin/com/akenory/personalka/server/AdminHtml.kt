package com.akenory.personalka.server

import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.account.FeatureGrant
import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.format.AdminFormat
import com.akenory.personalka.domain.format.ClubFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul

/**
 * HTML админки (шаг 5.2б; макет `admin/adminka-maket.png` в файлах проекта): стили кабинета `/cabinet.css` и свои
 * дополнения `/admin.css`, без JavaScript. Слова — из [AdminFormat] и [ClubFormat]; текст экранирует kotlinx.html.
 */
object AdminHtml {
    const val CSS_PATH = "/admin.css"

    /** Вход: почта и пароль; [error] — что не так. */
    fun login(html: HTML, error: String?) = html.shell(narrow = true) {
        h1 { +AdminFormat.SIGN_IN }
        p("mut") { +AdminFormat.SIGN_IN_NOTE }
        form(action = "/admin/login", method = FormMethod.post, classes = "frm cf") {
            field("email", AccountFormat.EMAIL, InputType.email, "username", error != null)
            field("password", AccountFormat.PASSWORD, InputType.password, "current-password", error != null)
            error?.let { message(it) }
            button(type = ButtonType.submit, classes = "btn") { +AdminFormat.NEXT }
        }
    }

    /** Код из письма; [note] — куда ушёл код, [error] — неверный код. */
    fun code(html: HTML, email: String, note: String?, error: String?) = html.shell(narrow = true) {
        h1 { +AdminFormat.CODE_TITLE }
        note?.let { p("mut") { +it } }
        form(action = "/admin/code", method = FormMethod.post, classes = "frm cf") {
            input(InputType.hidden, name = "email") { value = email }
            label("vh") {
                htmlFor = "code"
                +AdminFormat.CODE_TITLE
            }
            input(InputType.text, name = "code", classes = if (error != null) "inp err" else "inp") {
                id = "code"
                attributes["inputmode"] = "numeric"
                maxLength = "6"
                autoComplete = "one-time-code"
                autoFocus = true
                placeholder = "······"
            }
            error?.let { message(it) }
            button(type = ButtonType.submit, classes = "btn") { +AdminFormat.ENTER }
        }
        div("ft") { linkTo("/admin", AdminFormat.AGAIN) }
    }

    /** Тренеры сервера с «Приостановить» / «Возобновить» и журнал. */
    fun coaches(html: HTML, page: AdminPage, now: LocalDateTime) = html.shell(narrow = false) {
        val today = now.date
        header("hd adm-top") {
            h1 { +AdminFormat.coaches(page.coaches.size) }
            form(action = "/admin/logout", method = FormMethod.post) {
                csrf(page.session.csrf)
                button(type = ButtonType.submit, classes = "btn sec adm-small") { +AdminFormat.SIGN_OUT }
            }
        }
        p(if (AdminFormat.serverAlarm(page.server, now)) "adm-srv msg" else "adm-srv mut num") { +AdminFormat.server(page.server, now) }
        section("sec") {
            p("mut") { +ClubFormat.PRIVATE }
            ul("card lst") {
                for (s in page.coaches) {
                    li("it adm-row") {
                        div("adm-who") {
                            a(href = "/admin/coaches/${s.coach.id}", classes = "b lnk-row") { +ClubFormat.name(s.coach.displayName, s.coach.id == page.session.coach.id) }
                            span("mut") { +s.coach.email }
                            span(if (s.coach.suspended) "" else "mut") {
                                +ClubFormat.status(s.coach.confirmed, s.coach.suspended, s.syncedAt?.club()?.date, s.cabinets, today)
                            }
                            span("mut") { +openLine(page.grants[s.coach.id].orEmpty(), today) }
                        }
                        if (!s.coach.admin) {
                            val suspend = !s.coach.suspended
                            form(action = "/admin/coaches/${s.coach.id}/${if (suspend) "suspend" else "resume"}", method = FormMethod.post) {
                                csrf(page.session.csrf)
                                button(type = ButtonType.submit, classes = "btn sec adm-small") {
                                    attributes["title"] = if (suspend) ClubFormat.SUSPEND_NOTE else ClubFormat.RESUME_NOTE
                                    +(if (suspend) ClubFormat.SUSPEND else ClubFormat.RESUME)
                                }
                            }
                        }
                    }
                }
            }
        }
        section("sec") {
            h2 { +AdminFormat.DEFAULTS }
            form(action = "/admin/defaults", method = FormMethod.post, classes = "card adm-form") {
                csrf(page.session.csrf)
                for (f in Feature.entries) {
                    label("chk") {
                        input(InputType.checkBox, name = "f") {
                            value = f.code
                            checked = f in page.defaults
                        }
                        span { +f.title }
                    }
                }
                p("mut") { +AdminFormat.DEFAULTS_NOTE }
                button(type = ButtonType.submit, classes = "btn sec adm-small") { +AdminFormat.SAVE }
            }
        }
        section("sec") {
            h2 { +AdminFormat.LOG }
            if (page.log.isEmpty()) {
                p("mut") { +AdminFormat.LOG_EMPTY }
            } else {
                ul("card lst") {
                    for (row in page.log) {
                        li("it mut num") { +AdminFormat.log(row.at.club(), today.year, logParts(row)) }
                    }
                }
            }
        }
    }

    /** Возможности тренера: переключатель и дата «до» у каждой; «Сохранить» заменяет все разом. */
    fun coach(html: HTML, page: AdminCoachPage) = html.shell(narrow = true) {
        div("ft") { a(href = "/admin") { +("← " + AdminFormat.ALL_COACHES) } }
        header("hd") {
            h1 { +ClubFormat.name(page.coach.coach.displayName, page.coach.coach.id == page.session.coach.id) }
            p("mut") { +page.coach.coach.email }
        }
        val grants = page.grants.associate { it.feature to it.until }
        form(action = "/admin/coaches/${page.coach.coach.id}/features", method = FormMethod.post, classes = "frm cf") {
            csrf(page.session.csrf)
            h2 { +AdminFormat.FEATURES }
            ul("card lst") {
                for (f in Feature.entries) {
                    li("it adm-feat") {
                        label("chk") {
                            input(InputType.checkBox, name = "f") {
                                value = f.code
                                checked = f in grants
                            }
                            span("adm-who") {
                                span("b") { +f.title }
                                span("mut") { +AdminFormat.note(f) }
                            }
                        }
                        label("adm-until") {
                            span("mut") { +AdminFormat.UNTIL }
                            input(InputType.date, name = "until_${f.code}", classes = "inp") {
                                grants[f]?.let { value = it.toString() }
                            }
                        }
                    }
                }
            }
            p("mut") { +AdminFormat.UNTIL_HINT }
            page.error?.let { message(it) }
            button(type = ButtonType.submit, classes = "btn") { +AdminFormat.SAVE }
            p("mut") { +AdminFormat.APPLIES }
        }
    }

    /** «Открыто: Копия на сервере — до 31.12.2026» (как в «Аккаунте» тренера) или «Ничего не открыто». */
    private fun openLine(grants: List<FeatureGrant>, today: LocalDate): String {
        val open = AccountRules.open(grants, today)
        val effective = AccountRules.effective(grants).filterKeys { it in open }
        return AccountFormat.open(effective, AccountRules.endingSoon(grants, today).keys) ?: AdminFormat.NOTHING_OPEN
    }

    private fun logParts(row: AdminLogRow): List<String> = when (row.action) {
        AdminSessions.SIGN_IN -> listOfNotNull(AdminFormat.SIGNED_IN, row.actor, row.ip)
        AdminSessions.SIGN_OUT -> listOfNotNull(AdminFormat.SIGNED_OUT, row.actor)
        AdminSessions.SUSPEND -> listOfNotNull(row.target, AdminFormat.SUSPENDED)
        AdminSessions.RESUME -> listOfNotNull(row.target, AdminFormat.RESUMED)
        AdminSessions.FEATURES, AdminSessions.DEFAULTS -> listOfNotNull(row.target)
        else -> listOfNotNull(row.action, row.target)
    }

    private fun FlowContent.field(name: String, title: String, type: InputType, auto: String, error: Boolean) {
        label("lbl") {
            htmlFor = name
            +title
        }
        input(type, name = name, classes = if (error) "inp err" else "inp") {
            id = name
            autoComplete = auto
            required = true
        }
    }

    private fun FlowContent.message(text: String) {
        p("msg") { span { +text } }
    }

    private fun FlowContent.csrf(value: String) {
        input(InputType.hidden, name = "csrf") { this.value = value }
    }

    private fun FlowContent.linkTo(href: String, text: String) {
        a(href = href) { +text }
    }

    private fun HTML.shell(narrow: Boolean, content: FlowContent.() -> Unit) {
        lang = "ru"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "robots", content = "noindex, nofollow")
            meta(name = "referrer", content = "no-referrer")
            meta(name = "color-scheme", content = "light dark")
            title { +AdminFormat.TITLE }
            link(rel = "stylesheet", href = CabinetHtml.CSS_PATH)
            link(rel = "stylesheet", href = CSS_PATH)
        }
        body {
            main(if (narrow) "pg nar" else "pg") {
                div("bar") { span { +AdminFormat.TITLE } }
                content()
            }
        }
    }
}
