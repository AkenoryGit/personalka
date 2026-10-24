package com.akenory.personalka.server

import com.akenory.personalka.domain.cabinet.CabinetChart
import com.akenory.personalka.domain.cabinet.CabinetItem
import com.akenory.personalka.domain.cabinet.CabinetMonth
import com.akenory.personalka.domain.cabinet.CabinetPage
import com.akenory.personalka.domain.cabinet.CabinetResultLine
import com.akenory.personalka.domain.cabinet.CabinetResults
import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.SUMMARY
import kotlinx.html.ThScope
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
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
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe

/**
 * HTML кабинета родителя (ТЗ 6.3, макеты 0.7): без JavaScript — сворачивание на `<details>`, код — обычной формой;
 * стили — свой файл `/cabinet.css` (CSP `default-src 'self'` запрещает встроенные). Все слова и числа приходят из
 * [CabinetPage] — здесь только разметка; текст экранирует kotlinx.html.
 */
object CabinetHtml {
    const val CSS_PATH = "/cabinet.css"

    /** Страница кабинета. */
    fun page(html: HTML, page: CabinetPage) = html.shell(narrow = false) {
        header("hd") {
            h1 { +page.clientName }
            p { +page.coachLine }
            page.dataLine?.let { p("mut num") { +it } }
        }
        if (page.upcoming.isNotEmpty()) {
            section("sec") {
                h2 { +CabinetFormat.UPCOMING }
                ul("card lst") { page.upcoming.forEach { item(it, upcoming = true) } }
            }
        }
        section("sec") {
            if (page.counters.isNotEmpty()) {
                h2 { +CabinetFormat.VISITS }
                div("card") {
                    table("tb") {
                        thead {
                            tr {
                                th(scope = ThScope.col) { span("vh") { +CabinetFormat.STATUS } }
                                th(scope = ThScope.col) { +page.monthColumn }
                                th(scope = ThScope.col) { +"Всего" }
                            }
                        }
                        tbody {
                            page.counters.forEach { row ->
                                tr {
                                    th(scope = ThScope.row) {
                                        span("rl") {
                                            span("sw ${statusClass(row.outcome)}") { attributes["aria-hidden"] = "true" }
                                            span { +row.outcome.label }
                                        }
                                    }
                                    td { +row.month.toString() }
                                    td { +row.total.toString() }
                                }
                            }
                        }
                    }
                }
            }
            div("card rule") {
                unsafe { +INFO_ICON }
                p { +page.rule }
            }
        }
        if (page.currentMonth != null || page.earlier != null) {
            section("sec") {
                h2 { +CabinetFormat.HISTORY }
                div("stack") {
                    page.currentMonth?.let { month(it, open = true) }
                    page.earlier?.let { earlier ->
                        details("mo") {
                            summary { summaryLine(CabinetFormat.EARLIER, earlier.count) }
                            div("stack nest") { earlier.months.forEach { month(it, open = false) } }
                        }
                    }
                }
            }
        }
        page.results?.let { results(it) }
        footer("ft") {
            p { +CabinetFormat.FOOTER }
        }
    }

    /** Код доступа (6.1): форма уходит на [action]; [error] — текст под полем, [blocked] — поле и кнопка выключены. Имён на этой странице нет. */
    fun code(html: HTML, action: String, error: String?, blocked: Boolean) = html.shell(narrow = true) {
        div("badge") { unsafe { +LOCK_ICON } }
        h1 { +CabinetFormat.CODE_TITLE }
        p { +CabinetFormat.CODE_HINT }
        form(action = action, method = FormMethod.post, classes = "frm") {
            label("vh") {
                htmlFor = "pin"
                +CabinetFormat.CODE_LABEL
            }
            input(InputType.text, name = "code", classes = if (error != null && !blocked) "code err" else "code") {
                id = "pin"
                attributes["inputmode"] = "numeric"
                attributes["pattern"] = "[0-9]*"
                maxLength = "4"
                autoComplete = "one-time-code"
                placeholder = "····"
                if (blocked) disabled = true else autoFocus = true
                if (error != null) attributes["aria-describedby"] = "pinmsg"
                if (error != null && !blocked) attributes["aria-invalid"] = "true"
            }
            error?.let {
                p("msg") {
                    id = "pinmsg"
                    unsafe { +ALERT_ICON }
                    span { +it }
                }
            }
            button(type = ButtonType.submit, classes = "btn") {
                if (blocked) disabled = true
                +CabinetFormat.CODE_ENTER
            }
            p("mut") { +CabinetFormat.CODE_REMEMBER }
        }
    }

    /** Сообщение без данных: недействительная ссылка, слишком много запросов, ошибка. */
    fun message(html: HTML, title: String, text: String, icon: String = LINK_OFF_ICON) = html.shell(narrow = true) {
        div("badge") { unsafe { +icon } }
        h1 { +title }
        p { +text }
    }

    private fun HTML.shell(narrow: Boolean, content: FlowContent.() -> Unit) {
        lang = "ru"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "robots", content = "noindex, nofollow")
            meta(name = "referrer", content = "no-referrer")
            meta(name = "color-scheme", content = "light dark")
            title { +CabinetFormat.TITLE }
            link(rel = "stylesheet", href = CSS_PATH)
        }
        body {
            main(if (narrow) "pg nar" else "pg") {
                div("bar") {
                    unsafe { +LOGO }
                    span { +CabinetFormat.TITLE }
                }
                content()
            }
        }
    }

    /** «Результаты» (6.3, п. 6; макет `KbCabinet`): рекорды по бассейнам, график с чипами-ссылками, все результаты свёрнутыми. */
    private fun FlowContent.results(results: CabinetResults) {
        section("sec") {
            id = RESULTS_ID
            h2 { +CabinetFormat.RESULTS }
            results.records.forEach { pool ->
                h3 { +pool.title }
                ul("card lst") { pool.lines.forEach { resultLine(it) } }
            }
            results.chart?.let { chart(it) }
            details("mo") {
                summary { summaryLine(CabinetFormat.ALL_RESULTS, results.allCount) }
                ul("lst") { results.all.forEach { resultLine(it) } }
            }
        }
    }

    private fun UL.resultLine(line: CabinetResultLine) {
        li("rr") {
            span("ct") {
                span { +line.event }
                span("mut num") { +line.line }
            }
            span("tm") { +line.time }
        }
    }

    private fun FlowContent.chart(chart: CabinetChart) {
        div("card chart") {
            h3 { +chart.title }
            if (chart.chips.size > 1) {
                nav("chips") {
                    attributes["aria-label"] = CabinetFormat.CHART_CHIPS
                    chart.chips.forEach { chip ->
                        a(href = "?d=${chip.key}#$RESULTS_ID", classes = if (chip.selected) "chip on" else "chip") {
                            if (chip.selected) attributes["aria-current"] = "true"
                            +chip.label
                        }
                    }
                }
            } else {
                p { +chart.chips.single().label }
            }
            div("plot") {
                attributes["role"] = "img"
                attributes["aria-label"] = chart.summary
                unsafe { +ChartSvg.axis(chart) }
                unsafe { +ChartSvg.plot(chart) }
            }
            p("lg") {
                if (chart.legendCompetition) {
                    span {
                        unsafe { +LEGEND_FILLED }
                        +CabinetFormat.LEGEND_COMPETITION
                    }
                }
                if (chart.legendTraining) {
                    span {
                        unsafe { +LEGEND_OPEN }
                        +CabinetFormat.LEGEND_TRAINING
                    }
                }
            }
            p("mut") { +chart.hint }
        }
    }

    private fun FlowContent.month(month: CabinetMonth, open: Boolean) {
        details("mo") {
            if (open) attributes["open"] = "open"
            summary { summaryLine(month.title, month.count) }
            ul("lst") { month.items.forEach { item(it, upcoming = false) } }
        }
    }

    private fun SUMMARY.summaryLine(title: String, count: String) {
        span("mt") { +title }
        span("mut") { +count }
        unsafe { +CHEVRON }
    }

    private fun UL.item(item: CabinetItem, upcoming: Boolean) {
        li("it") {
            val status = item.status
            if (status != null) {
                div("top") {
                    p("dt") { +item.whenText }
                    span("st ${statusClass(status)}") { +status.label }
                }
            } else {
                p("dt") { +item.whenText }
            }
            p { +item.typePlace }
            item.fields.forEach { (name, text) ->
                div("f") {
                    span("k") { +name }
                    span { +text }
                }
            }
            item.note?.let { p("mut") { +it } }
        }
    }

    private fun statusClass(outcome: ClientOutcome): String = when (outcome) {
        ClientOutcome.DONE -> "done"
        ClientOutcome.CANCELLED_CHARGED -> "chg"
        ClientOutcome.CANCELLED, ClientOutcome.PLANNED -> "cnc"
    }

    const val RESULTS_ID = "res"

    private const val LEGEND_FILLED =
        """<svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><circle class="pf" cx="7" cy="7" r="6"/></svg>"""
    private const val LEGEND_OPEN =
        """<svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><circle class="po" cx="7" cy="7" r="5"/></svg>"""

    // Значки — встроенным SVG из макетов (внешних файлов и шрифтов нет, 6.2).
    private const val LOGO =
        """<svg width="28" height="28" viewBox="0 0 72 72" aria-hidden="true"><rect class="lgr" x="1" y="1" width="70" height="70" rx="16" fill="#F3EFE6"/><path d="M23 53 V21 H49 V53" fill="none" stroke="#15171A" stroke-width="8"/><circle cx="36" cy="47" r="5" fill="#FF5A1F"/></svg>"""
    private const val INFO_ICON =
        """<svg class="ic" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.2"/></svg>"""
    private const val ALERT_ICON =
        """<svg class="ic" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 16.5v.2"/></svg>"""
    private const val CHEVRON =
        """<svg class="ic chev" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>"""
    private const val LOCK_ICON =
        """<svg class="ic" width="32" height="32" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>"""
    const val LINK_OFF_ICON =
        """<svg class="ic" width="32" height="32" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1"/><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"/><path d="M4 4l16 16"/></svg>"""
    const val CLOCK_ICON =
        """<svg class="ic" width="32" height="32" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>"""
}
