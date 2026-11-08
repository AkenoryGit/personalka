import SwiftUI
import UIKit
import WidgetKit

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }

    convenience init?(hex: String?) {
        guard let hex, hex.hasPrefix("#"), let v = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        self.init(rgb: v)
    }
}

extension Color {
    /// Цвет своей темы: светлая и тёмная — как в дизайн-системе «Персоналки».
    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
    }

    /// Полоска места: светлый и тёмный тон от приложения.
    static func stripe(_ light: String?, _ dark: String?) -> Color {
        let l = UIColor(hex: light) ?? UIColor(rgb: 0x738497)
        let d = UIColor(hex: dark) ?? l
        return Color(UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }
}

/// Роли дизайн-системы (шаг 0.2).
enum P {
    static let surface = Color.dyn(0xFBFAF7, 0x1E2124)
    static let alt = Color.dyn(0xEAE6DC, 0x2A2D31)
    static let divider = Color.dyn(0xDCD9D0, 0x34373B)
    static let outline = Color.dyn(0x86837B, 0x72767C)
    static let text = Color.dyn(0x15171A, 0xF3EFE6)
    static let muted = Color.dyn(0x69665E, 0x98958D)
    static let primary = Color.dyn(0x15171A, 0xF3EFE6)
    static let done = Color.dyn(0x007634, 0x64B376)
    static let charged = Color.dyn(0x8E5B00, 0xCF9037)
    static let accent = Color.dyn(0xF55111, 0xFF5A1F)
    static let yellow = Color(UIColor(rgb: 0xFFD54A))
}

extension View {
    /// Фон виджета: с iOS 17 — `containerBackground` (без него iOS 17 показывает заглушку), раньше — подложка.
    @ViewBuilder
    func widgetBg<B: View>(_ background: B, padded: Bool = true) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget) { background }
        } else if padded {
            frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(16)
                .background(background)
        } else {
            self
        }
    }

    /// Нажатие на виджет: карточка тренировки или «Главная».
    func open(_ id: String?) -> some View {
        widgetURL(URL(string: id.map { "personalka://card/\($0)" } ?? "personalka://home"))
    }
}

/// Нет кадров: приложение ещё не открывали после установки.
struct NoData: View {
    var color: Color = P.muted

    var body: some View {
        Text("Откройте Персоналку").font(.subheadline.weight(.medium)).foregroundColor(color)
    }
}

/// Точка оценки плана: зелёная — идём, янтарная — отстаём.
struct Dot: View {
    let behind: Bool

    var body: some View {
        Circle().fill(behind ? P.charged : P.done).frame(width: 8, height: 8)
    }
}

/// Полоса плана: проведено поверх прогноза, со ступени 2 — зелёным.
struct PlanBar: View {
    let done: Int
    let forecast: Int
    let tier: Int
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(P.alt)
                Capsule().fill(P.outline).frame(width: g.size.width * CGFloat(forecast) / 1000)
                Capsule().fill(tier > 1 ? P.done : P.primary).frame(width: g.size.width * CGFloat(done) / 1000)
            }
        }
        .frame(height: height)
    }
}

/// Тюля плывёт: вода, бассейн, дорожка.
struct Wave: Shape {
    let level: CGFloat

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let y = rect.height * level
        p.move(to: CGPoint(x: 0, y: y))
        var x: CGFloat = 0
        var up = true
        while x < rect.width {
            p.addQuadCurve(to: CGPoint(x: x + 13, y: y), control: CGPoint(x: x + 6.5, y: y + (up ? -3.5 : 3.5)))
            x += 13
            up.toggle()
        }
        p.addLine(to: CGPoint(x: rect.width, y: rect.height))
        p.addLine(to: CGPoint(x: 0, y: rect.height))
        p.closeSubpath()
        return p
    }
}

/// Молния «Заряда бонуса».
struct Bolt: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width / 22
        let h = rect.height / 42
        var p = Path()
        p.move(to: CGPoint(x: 15 * w, y: 0))
        p.addLine(to: CGPoint(x: 0, y: 25 * h))
        p.addLine(to: CGPoint(x: 10 * w, y: 25 * h))
        p.addLine(to: CGPoint(x: 6 * w, y: 42 * h))
        p.addLine(to: CGPoint(x: 22 * w, y: 16 * h))
        p.addLine(to: CGPoint(x: 12 * w, y: 16 * h))
        p.closeSubpath()
        return p
    }
}
