import SwiftUI
import WidgetKit

// Обычные виджеты (шаг 5.10): те же, что на Android (шаги 5.9, 5.9а).

/// Колонка «Денег»: заголовок, сумма, «будет ≈ …», ПТ и отмены.
struct MoneyColumn: View {
    let title: String
    let amount: String
    let will: String
    let pt: String
    let cancelled: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title).font(.caption).foregroundColor(P.muted)
            Text(amount).font(.title2.weight(.semibold)).monospacedDigit().foregroundColor(P.text).lineLimit(1).minimumScaleFactor(0.6)
            Text(will).font(.caption).monospacedDigit().foregroundColor(P.muted).lineLimit(1)
            Text(pt).font(.caption).monospacedDigit().foregroundColor(P.text).lineLimit(1)
            if let cancelled { Text(cancelled).font(.caption).foregroundColor(P.muted).lineLimit(1) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// «Деньги» как на «Главной»; [compact] — средний виджет: справа оценка вместо плашки, без отмен и строки оценки.
struct MoneyBlock: View {
    let m: WMoney
    let compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(m.title).font(.caption.weight(.medium)).foregroundColor(P.muted).lineLimit(1)
                Spacer(minLength: 4)
                if compact, let p = m.paceShort {
                    Dot(behind: m.behind)
                    Text(p).font(.caption.weight(.medium)).foregroundColor(P.text).lineLimit(1)
                } else if !compact, let chip = m.tierChip {
                    Text(chip).font(.caption2.weight(.medium)).foregroundColor(P.text).lineLimit(1)
                        .padding(.horizontal, 8).padding(.vertical, 2).background(Capsule().fill(P.alt))
                }
            }
            HStack(alignment: .top, spacing: 12) {
                MoneyColumn(title: "Сегодня", amount: m.today, will: m.todayWill, pt: m.todayPt, cancelled: compact ? nil : m.todayCancelled)
                MoneyColumn(title: "За месяц", amount: m.month, will: m.monthWill, pt: m.monthPt, cancelled: compact ? nil : m.monthCancelled)
            }
            if let done = m.done, let forecast = m.forecast {
                PlanBar(done: done, forecast: forecast, tier: m.tier)
            }
            if !compact, let pace = m.pace {
                HStack(spacing: 6) {
                    Dot(behind: m.behind)
                    Text(pace).font(.caption.weight(.medium)).foregroundColor(P.text).lineLimit(1).minimumScaleFactor(0.8)
                }
            }
        }
    }
}

/// Правая часть шапки «Сейчас»: у идущей — живой отсчёт, у следующей — «в 12:00».
struct RightTime: View {
    let f: WFrame

    var body: some View {
        if f.running, let end = f.endDate {
            (Text("до конца ") + Text(end, style: .timer)).monospacedDigit()
        } else {
            Text(f.right ?? "")
        }
    }
}

/// «Сейчас» или «Следующая», имя и следующие тренировки дня.
struct NowBlock: View {
    let f: WFrame
    let rows: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(f.header).font(.caption.weight(.medium)).foregroundColor(P.muted).lineLimit(1)
                Spacer(minLength: 4)
                RightTime(f: f).font(.caption).foregroundColor(P.muted).lineLimit(1)
            }
            if let name = f.name {
                Link(destination: URL(string: "personalka://card/\(f.id ?? "")")!) {
                    HStack(spacing: 10) {
                        RoundedRectangle(cornerRadius: 1.5).fill(Color.stripe(f.light, f.dark)).frame(width: 3, height: 36)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(name).font(.headline).foregroundColor(P.text).lineLimit(1)
                            Text(f.typePlace ?? "").font(.caption).foregroundColor(P.muted).lineLimit(1)
                        }
                    }
                }
            }
            ForEach(f.next.prefix(rows)) { r in
                Link(destination: URL(string: "personalka://card/\(r.id)")!) {
                    HStack(spacing: 8) {
                        Text(r.time).font(.caption).monospacedDigit().foregroundColor(P.muted).frame(width: 40, alignment: .leading)
                        RoundedRectangle(cornerRadius: 1.5).fill(Color.stripe(r.light, r.dark)).frame(width: 3, height: 14)
                        Text(r.name).font(.subheadline.weight(.medium)).foregroundColor(P.text).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(r.typePlace).font(.caption).foregroundColor(P.muted).lineLimit(1)
                    }
                }
            }
            if let more = f.more {
                Text(more).font(.caption).foregroundColor(P.muted).padding(.leading, 51).lineLimit(1)
            }
        }
    }
}

struct LargeView: View {
    let e: PEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let m = e.money {
                MoneyBlock(m: m, compact: false)
                Rectangle().fill(P.divider).frame(height: 1)
            }
            if let f = e.frame {
                NowBlock(f: f, rows: 3)
            } else {
                NoData()
            }
            Spacer(minLength: 0)
        }
        .widgetBg(P.surface)
        .open(nil)
    }
}

struct MoneyView: View {
    let e: PEntry

    var body: some View {
        Group {
            if let m = e.money {
                MoneyBlock(m: m, compact: true)
            } else {
                NoData()
            }
        }
        .widgetBg(P.surface)
        .open(nil)
    }
}

/// «Сейчас / следующая»: маленький на экране «Домой» и три вида для экрана блокировки.
struct NowView: View {
    @Environment(\.widgetFamily) var family
    let e: PEntry

    var body: some View {
        switch family {
        case .accessoryInline: inline
        case .accessoryRectangular: rectangular
        case .accessoryCircular: circular
        default: small
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let f = e.frame {
                Text(f.label).font(.caption.weight(.medium)).foregroundColor(P.muted)
                Spacer(minLength: 0)
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 1.5).fill(Color.stripe(f.light, f.dark)).frame(width: 3, height: 38)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(f.name ?? "").font(.headline).foregroundColor(P.text).lineLimit(2).minimumScaleFactor(0.8)
                        Text(f.typePlace ?? "").font(.caption).foregroundColor(P.muted).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                Group {
                    if f.running, let end = f.endDate {
                        (Text("до конца ") + Text(end, style: .timer)).monospacedDigit()
                    } else {
                        Text(f.whenText ?? "")
                    }
                }
                .font(.subheadline.weight(.medium)).foregroundColor(P.text).lineLimit(1)
                if f.running, let s = f.startDate, let end = f.endDate, s < end {
                    ProgressView(timerInterval: s...end, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                        .tint(P.accent)
                }
                Text(f.count ?? "").font(.caption).foregroundColor(P.muted)
            } else {
                NoData()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetBg(P.surface)
        .open(e.frame?.id)
    }

    /// Строка у даты на экране блокировки: без имени — его увидят все рядом.
    private var inline: some View {
        Group {
            if let f = e.frame, f.id != nil {
                Text([f.typePlace, f.right].compactMap { $0 }.joined(separator: " · "))
            } else {
                Text(e.frame?.label ?? "Персоналка")
            }
        }
        .widgetBg(Color.clear, padded: false)
    }

    /// Прямоугольник «Сейчас»: имя скрыто, пока телефон заблокирован.
    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let f = e.frame {
                Group {
                    if f.running, let end = f.endDate {
                        (Text("Сейчас · до конца ") + Text(end, style: .timer)).monospacedDigit()
                    } else {
                        Text([f.label, f.right].compactMap { $0 }.joined(separator: " · "))
                    }
                }
                .font(.caption.weight(.semibold)).lineLimit(1)
                Text(f.name ?? "—").font(.headline).lineLimit(1).privacySensitive()
                Text([f.typePlace, f.count].compactMap { $0 }.joined(separator: " · ")).font(.caption).lineLimit(1)
            } else {
                Text("Откройте Персоналку").font(.caption)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetBg(Color.clear, padded: false)
        .open(e.frame?.id)
    }

    /// Кружок: у идущей — время до конца, у следующей — время начала.
    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            if let f = e.frame, f.running, let s = f.startDate, let end = f.endDate, s < end {
                ProgressView(timerInterval: s...end, countsDown: true, label: { EmptyView() }, currentValueLabel: {
                    Text(end, style: .timer).monospacedDigit().font(.system(size: 11, weight: .semibold))
                })
                .progressViewStyle(.circular)
            } else if let f = e.frame, f.id != nil {
                VStack(spacing: 0) {
                    Text(f.typePlace?.components(separatedBy: " · ").first ?? "").font(.system(size: 10, weight: .semibold))
                    Text(f.whenText?.replacingOccurrences(of: "в\u{00A0}", with: "") ?? "").font(.system(size: 13, weight: .bold)).monospacedDigit()
                }
            } else {
                Image(systemName: "moon.zzz")
            }
        }
        .widgetBg(Color.clear, padded: false)
        .open(e.frame?.id)
    }
}

/// «Месяц»: маленький на экране «Домой» и кружок «% плана» на экране блокировки.
struct MonthView: View {
    @Environment(\.widgetFamily) var family
    let e: PEntry

    var body: some View {
        if family == .accessoryCircular {
            circular
        } else {
            small
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let m = e.money {
                Text(m.monthName).font(.caption.weight(.medium)).foregroundColor(P.muted)
                Text(m.month).font(.title2.weight(.semibold)).monospacedDigit().foregroundColor(P.text).lineLimit(1).minimumScaleFactor(0.6)
                Text(m.monthWill).font(.caption).monospacedDigit().foregroundColor(P.muted).lineLimit(1)
                if let done = m.done, let forecast = m.forecast {
                    PlanBar(done: done, forecast: forecast, tier: m.tier).padding(.vertical, 4)
                }
                if let p = m.paceShort {
                    HStack(spacing: 6) {
                        Dot(behind: m.behind)
                        Text(p).font(.caption.weight(.medium)).foregroundColor(P.text).lineLimit(1).minimumScaleFactor(0.8)
                    }
                }
                Text(m.ptTier).font(.caption).foregroundColor(P.muted).lineLimit(1)
            } else {
                NoData()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetBg(P.surface)
        .open(nil)
    }

    private var circular: some View {
        let part = Double(e.money?.done ?? 0) / 1000
        return Gauge(value: part) {
            Text("план")
        } currentValueLabel: {
            Text("\(Int((part * 100).rounded()))%").privacySensitive()
        }
        .gaugeStyle(.accessoryCircular)
        .widgetBg(Color.clear, padded: false)
        .open(nil)
    }
}

struct LargeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.large", provider: Provider()) { LargeView(e: $0) }
            .configurationDisplayName("Деньги и расписание")
            .description("Заработок за сегодня и за месяц, идущая и следующие тренировки")
            .supportedFamilies([.systemLarge])
    }
}

struct MoneyWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.money", provider: Provider()) { MoneyView(e: $0) }
            .configurationDisplayName("Деньги")
            .description("Заработок за сегодня и за месяц, план")
            .supportedFamilies([.systemMedium])
    }
}

struct NowWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.now", provider: Provider()) { NowView(e: $0) }
            .configurationDisplayName("Сейчас и следующая")
            .description("Идущая или следующая тренировка; на экране блокировки имя скрыто, пока телефон заблокирован")
            .supportedFamilies([.systemSmall, .accessoryRectangular, .accessoryInline, .accessoryCircular])
    }
}

struct MonthWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.month", provider: Provider()) { MonthView(e: $0) }
            .configurationDisplayName("Месяц")
            .description("Заработок за месяц и план; на экране блокировки — кружок «% плана»")
            .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}
