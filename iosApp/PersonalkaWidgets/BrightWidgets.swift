import SwiftUI
import UIKit
import WidgetKit

// Яркие виджеты (шаг 5.10а, как на Android — шаг 5.9б): мало цифр, картинка и Тюля.

private func gradient(_ a: UInt32, _ b: UInt32) -> LinearGradient {
    LinearGradient(colors: [Color(UIColor(rgb: a)), Color(UIColor(rgb: b))], startPoint: .topLeading, endPoint: .bottomTrailing)
}

private let white = Color.white
private let graphite = Color(UIColor(rgb: 0x15171A))

/// Тюля плывёт: картинка из каталога (вектор).
struct SwimmingSeal: View {
    var width: CGFloat = 46

    var body: some View {
        Image("TyulyaSwim").resizable().scaledToFit().frame(width: width)
    }
}

// MARK: - «Бассейн плана»

struct PoolTank: View {
    let done: Int
    let planDone: Bool

    var body: some View {
        GeometryReader { g in
            let h = g.size.height
            let planY = h * 0.15
            let level: CGFloat = planDone ? 0.07 : 1 - (1 - 0.15) * CGFloat(min(max(done, 0), 1000)) / 1000
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 14).fill(white.opacity(0.1))
                Wave(level: level).fill(Color(UIColor(rgb: 0x4FC3F7)))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                RoundedRectangle(cornerRadius: 14).stroke(white.opacity(0.6), lineWidth: 2)
                Path { p in
                    p.move(to: CGPoint(x: 0, y: planY))
                    p.addLine(to: CGPoint(x: g.size.width, y: planY))
                }
                .stroke(white, style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                Text("план").font(.system(size: 10)).foregroundColor(white).position(x: 20, y: planY - 7)
                SwimmingSeal(width: g.size.width * 0.5).position(x: g.size.width / 2, y: max(h * level - 2, 14))
            }
        }
    }
}

struct PoolView: View {
    let e: PEntry

    var body: some View {
        HStack(spacing: 16) {
            PoolTank(done: e.money?.done ?? 0, planDone: e.money?.planDone ?? false).frame(width: 96)
            VStack(alignment: .leading, spacing: 4) {
                if let m = e.money {
                    Text(m.monthName).font(.footnote).foregroundColor(Color(UIColor(rgb: 0xCFE3FA)))
                    Text(m.month).font(.system(size: 30, weight: .bold)).monospacedDigit().foregroundColor(white).lineLimit(1).minimumScaleFactor(0.6)
                    Text(m.planPercent ?? "План не задан").font(.subheadline.weight(.medium)).foregroundColor(white)
                    if let note = m.poolNote {
                        Text(note).font(.caption).foregroundColor(white).lineLimit(2)
                            .padding(.horizontal, 9).padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Color(UIColor(rgb: 0x2E6DB3))))
                    }
                } else {
                    NoData(color: white)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetBg(gradient(0x0A3F78, 0x1565C0))
        .open(nil)
    }
}

// MARK: - «Дорожка»

struct LaneTrack: View {
    let progress: Int

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let x = 30 + (w - 70) * CGFloat(min(max(progress, 0), 1000)) / 1000
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 10).fill(Color(UIColor(rgb: 0x26A69A))).frame(height: 32).offset(y: 4)
                RoundedRectangle(cornerRadius: 10).fill(white.opacity(0.22)).frame(width: x, height: 32).offset(y: 4)
                HStack(spacing: 0) {
                    ForEach(0..<Int(w / 12), id: \.self) { i in
                        Circle().fill(i % 2 == 0 ? white : P.yellow).frame(width: 6, height: 6).frame(width: 12)
                    }
                }
                .offset(y: 1)
                Text("финиш").font(.system(size: 11)).foregroundColor(white).position(x: w - 26, y: 21)
                SwimmingSeal(width: 50).position(x: x, y: 21)
            }
        }
        .frame(height: 40)
    }
}

struct LaneView: View {
    let e: PEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let f = e.frame {
                HStack {
                    Text(f.laneLabel).font(.caption.weight(.bold)).tracking(1).foregroundColor(white)
                    Spacer()
                    RightTime(f: f).font(.footnote.weight(.medium)).foregroundColor(white)
                }
                Spacer(minLength: 0)
                Text(f.name ?? "Сегодня всё").font(.system(size: 24, weight: .bold)).foregroundColor(white).lineLimit(1).minimumScaleFactor(0.7)
                Text(f.laneSub ?? "").font(.footnote).foregroundColor(Color(UIColor(rgb: 0xE0F6F1))).lineLimit(1)
                Spacer(minLength: 0)
                LaneTrack(progress: f.lane)
            } else {
                NoData(color: white)
            }
        }
        .widgetBg(gradient(0x00514A, 0x00796B))
        .open(e.frame?.id)
    }
}

// MARK: - «Тюля»

struct TyulyaView: View {
    let e: PEntry

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(e.frame?.mascot?.sleep == true ? "TyulyaSleep" : "TyulyaWave").resizable().scaledToFit().frame(width: 104)
            VStack(alignment: .leading, spacing: 4) {
                if let m = e.frame?.mascot {
                    Text(m.title).font(.system(size: 26, weight: .bold)).foregroundColor(graphite).lineLimit(1).minimumScaleFactor(0.7)
                    Text(m.line).font(.subheadline).foregroundColor(graphite).lineLimit(2)
                    if let pill = m.pill {
                        Text(pill).font(.caption.weight(.medium)).foregroundColor(graphite).lineLimit(1)
                            .padding(.horizontal, 9).padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Color(UIColor(rgb: 0xF2C94C))))
                    }
                } else {
                    Text("Привет!").font(.system(size: 26, weight: .bold)).foregroundColor(graphite)
                    NoData(color: graphite)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetBg(gradient(0xFFE08A, 0xFFC94D))
        .open(e.frame?.phase == "NOW" || e.frame?.phase == "NEXT" ? e.frame?.id : nil)
    }
}

// MARK: - «Заряд бонуса»

struct BonusView: View {
    let e: PEntry

    var body: some View {
        let part = CGFloat(e.money?.bonusPart ?? 0) / 1000
        let color = (e.money?.tier ?? 1) >= 2 ? Color(UIColor(rgb: 0x7CE495)) : P.yellow
        return VStack(spacing: 4) {
            ZStack {
                Circle().stroke(white.opacity(0.2), lineWidth: 9)
                Circle().trim(from: 0, to: part).stroke(color, style: StrokeStyle(lineWidth: 9, lineCap: .round)).rotationEffect(.degrees(-90))
                Bolt().fill(color).frame(width: 20, height: 38)
            }
            .frame(width: 76, height: 76)
            if let m = e.money {
                Text(m.bonusBig ?? "Ступеней нет").font(.system(size: 22, weight: .bold)).monospacedDigit().foregroundColor(white).lineLimit(1).minimumScaleFactor(0.7)
                Text(m.bonusSmall ?? m.ptTier).font(.caption).foregroundColor(Color(UIColor(rgb: 0xEAE2FF))).lineLimit(1)
            } else {
                NoData(color: white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .widgetBg(gradient(0x3A1F8A, 0x6A3FD8))
        .open(nil)
    }
}

// MARK: - «День»

struct Drops: View {
    let done: Int
    let total: Int

    var body: some View {
        let rows = total > 12 ? 2 : 1
        let perRow = max(1, (total + rows - 1) / rows)
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(0..<rows, id: \.self) { r in
                HStack(spacing: 4) {
                    ForEach(0..<perRow, id: \.self) { c in
                        let i = r * perRow + c
                        if i < total {
                            Capsule().fill(i < done ? P.yellow : Color(UIColor(rgb: 0x3E9A65))).frame(height: 9)
                        }
                    }
                }
            }
        }
    }
}

struct DayView: View {
    let e: PEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Сегодня").font(.caption).foregroundColor(Color(UIColor(rgb: 0xD9F7E4)))
            if let m = e.money {
                Text(m.todaySigned).font(.system(size: 28, weight: .bold)).monospacedDigit().foregroundColor(white).lineLimit(1).minimumScaleFactor(0.6)
                Text(m.todayWill).font(.caption).monospacedDigit().foregroundColor(Color(UIColor(rgb: 0xD9F7E4))).lineLimit(1)
            } else {
                NoData(color: white)
            }
            Spacer(minLength: 0)
            if let f = e.frame, f.todayTotal > 0 {
                Drops(done: f.todayDone, total: f.todayTotal)
            }
            Text(e.frame?.dayLine ?? "Сегодня тренировок нет").font(.caption.weight(.medium)).foregroundColor(white).lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetBg(gradient(0x0B6B35, 0x0E7F3F))
        .open(nil)
    }
}

// MARK: - «До получки»

struct Bars: View {
    let total: Int
    let passed: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<min(max(total, 1), 31), id: \.self) { i in
                RoundedRectangle(cornerRadius: 3).fill(i < passed ? Color(UIColor(rgb: 0xF3B6D6)) : P.yellow).frame(height: 14)
            }
        }
    }
}

struct PaydayView: View {
    let e: PEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("до получки").font(.footnote).foregroundColor(Color(UIColor(rgb: 0xFBE3F0)))
            if let f = e.frame, let unit = f.payUnit {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    if let big = f.payBig, !big.isEmpty {
                        Text(big).font(.system(size: 52, weight: .bold)).monospacedDigit().foregroundColor(white)
                    }
                    Text(unit).font(.system(size: 20, weight: .bold)).foregroundColor(white)
                }
                .lineLimit(1).minimumScaleFactor(0.6)
                Text(f.payLine ?? "").font(.footnote.weight(.medium)).monospacedDigit().foregroundColor(white).lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                if let total = f.payTotal, let passed = f.payPassed {
                    Bars(total: total, passed: passed)
                }
            } else {
                Spacer(minLength: 0)
                Text(e.money == nil ? "Откройте Персоналку" : "Выплат впереди нет").font(.footnote.weight(.medium)).foregroundColor(white)
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetBg(gradient(0x7A0F4A, 0xB0186B))
        .open(nil)
    }
}

// MARK: - Описания

struct PoolWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.pool", provider: Provider()) { PoolView(e: $0) }
            .configurationDisplayName("Бассейн плана")
            .description("Заработок месяца — вода в бассейне, план — бортик")
            .supportedFamilies([.systemMedium])
    }
}

struct LaneWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.lane", provider: Provider()) { LaneView(e: $0) }
            .configurationDisplayName("Дорожка")
            .description("Идущая тренировка — Тюля плывёт к финишу")
            .supportedFamilies([.systemMedium])
    }
}

struct TyulyaWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.tyulya", provider: Provider()) { TyulyaView(e: $0) }
            .configurationDisplayName("Тюля")
            .description("Талисман: подсказывает, что сегодня")
            .supportedFamilies([.systemMedium])
    }
}

struct BonusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.bonus", provider: Provider()) { BonusView(e: $0) }
            .configurationDisplayName("Заряд бонуса")
            .description("Сколько ПТ до следующей ступени")
            .supportedFamilies([.systemSmall])
    }
}

struct DayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.day", provider: Provider()) { DayView(e: $0) }
            .configurationDisplayName("День")
            .description("Заработок за сегодня и проведённые тренировки")
            .supportedFamilies([.systemSmall])
    }
}

struct PaydayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "personalka.payday", provider: Provider()) { PaydayView(e: $0) }
            .configurationDisplayName("До получки")
            .description("Сколько дней до выплаты и сколько придёт")
            .supportedFamilies([.systemSmall])
    }
}
