package com.akenory.personalka.server

import com.akenory.personalka.domain.format.AccountFormat
import com.akenory.personalka.domain.format.AdminFormat
import com.akenory.personalka.domain.format.ClubFormat
import kotlinx.datetime.LocalDate
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
    fun coaches(html: HTML, page: AdminPage, today: LocalDate) = html.shell(narrow = false) {
        header("hd adm-top") {
            h1 { +AdminFormat.coaches(page.coaches.size) }
            form(action = "/admin/logout", method = FormMethod.post) {
                csrf(page.session.csrf)
                button(type = ButtonType.submit, classes = "btn sec adm-small") { +AdminFormat.SIGN_OUT }
            }
        }
        section("sec") {
            p("mut") { +ClubFormat.PRIVATE }
            ul("card lst") {
                for (s in page.coaches) {
                    li("it adm-row") {
                        div("adm-who") {
                            span("b") { +ClubFormat.name(s.coach.displayName, s.coach.id == page.session.coach.id) }
                            span("mut") { +s.coach.email }
                            span(if (s.coach.suspended) "" else "mut") {
                                +ClubFormat.status(s.coach.confirmed, s.coach.suspended, s.syncedAt?.club()?.date, s.cabinets, today)
                            }
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

    private fun logParts(row: AdminLogRow): List<String> = when (row.action) {
        AdminSessions.SIGN_IN -> listOfNotNull(AdminFormat.SIGNED_IN, row.actor, row.ip)
        AdminSessions.SIGN_OUT -> listOfNotNull(AdminFormat.SIGNED_OUT, row.actor)
        AdminSessions.SUSPEND -> listOfNotNull(row.target, AdminFormat.SUSPENDED)
        AdminSessions.RESUME -> listOfNotNull(row.target, AdminFormat.RESUMED)
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
