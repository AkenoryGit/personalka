import Foundation
import SwiftUI
import WidgetKit

/// Общая папка приложения и виджетов (App Group): приложение кладёт сюда кадры (Kotlin, `IosWidgets`).
enum Shared {
    static let group = "group.com.akenory.personalka"
    static let key = "widget.timeline"
}

/// «Деньги» — готовые строки, теми же функциями, что «Главная» (шаг 5.10).
struct WMoney: Decodable {
    let title: String
    let monthName: String
    let tierChip: String?
    let tier: Int
    let today: String
    let todayWill: String
    let todayPt: String
    let todayCancelled: String?
    let month: String
    let monthWill: String
    let monthPt: String
    let monthCancelled: String?
    let done: Int?
    let forecast: Int?
    let pace: String?
    let paceShort: String?
    let behind: Bool
    let ptTier: String
    let todaySigned: String
    let planPercent: String?
    let poolNote: String?
    let planDone: Bool
    let bonusPart: Int?
    let bonusBig: String?
    let bonusSmall: String?
}

/// Строка расписания под «Сейчас».
struct WRow: Decodable, Identifiable {
    let id: String
    let time: String
    let name: String
    let typePlace: String
    let light: String?
    let dark: String?
}

/// Талисман «Тюля»: спит или машет, крупная фраза, строка и плашка.
struct WMascot: Decodable {
    let sleep: Bool
    let title: String
    let line: String
    let pill: String?
}

/// Кадр: что виджет показывает с минуты `t` (секунды от 1970).
struct WFrame: Decodable {
    let t: Double
    let phase: String
    let header: String
    let right: String?
    let label: String
    let id: String?
    let name: String?
    let typePlace: String?
    let light: String?
    let dark: String?
    let whenText: String?
    let progress: Int?
    let start: Double?
    let end: Double?
    let next: [WRow]
    let more: String?
    let count: String?
    let todayTotal: Int
    let todayDone: Int
    let laneLabel: String
    let lane: Int
    let laneSub: String?
    let dayLine: String?
    let moneyToday: Bool
    let mascot: WMascot?
    let payBig: String?
    let payUnit: String?
    let payLine: String?
    let payTotal: Int?
    let payPassed: Int?

    var running: Bool { phase == "NOW" }
    var startDate: Date? { start.map { Date(timeIntervalSince1970: $0) } }
    var endDate: Date? { end.map { Date(timeIntervalSince1970: $0) } }
}

struct WFile: Decodable {
    let money: WMoney?
    let entries: [WFrame]
}

struct PEntry: TimelineEntry {
    let date: Date
    let frame: WFrame?
    let money: WMoney?
}

/// Кадры из общей папки: текущий — с этой минуты, дальше — каждый в своё время.
struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> PEntry { Sample.entry }

    func getSnapshot(in context: Context, completion: @escaping (PEntry) -> Void) {
        completion(context.isPreview ? Sample.entry : (Self.load(from: Date()).first ?? Sample.empty))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PEntry>) -> Void) {
        let now = Date()
        let entries = Self.load(from: now)
        if entries.count <= 1 {
            // Кадров впереди нет — приложение давно не открывали; заглянуть снова через час.
            completion(Timeline(entries: entries.isEmpty ? [Sample.empty] : entries, policy: .after(now.addingTimeInterval(3600))))
        } else {
            completion(Timeline(entries: entries, policy: .atEnd))
        }
    }

    static func load(from now: Date) -> [PEntry] {
        guard let text = UserDefaults(suiteName: Shared.group)?.string(forKey: Shared.key),
              let data = text.data(using: .utf8),
              let file = try? JSONDecoder().decode(WFile.self, from: data),
              !file.entries.isEmpty else { return [] }
        let t = now.timeIntervalSince1970
        let current = file.entries.lastIndex { $0.t <= t } ?? 0
        var out: [PEntry] = []
        for (i, f) in file.entries.enumerated() where i >= current {
            let date = i == current ? now : Date(timeIntervalSince1970: f.t)
            out.append(PEntry(date: date, frame: f, money: f.moneyToday ? file.money : nil))
        }
        return out
    }
}

/// Образец для списка виджетов: придуманные имена и цифры, как на макетах.
enum Sample {
    static let entry: PEntry = {
        let json = """
        {"money":{"title":"Деньги · ноябрь","monthName":"Ноябрь","tierChip":"Ступень 1 · до бонуса 63 ПТ","tier":1,
        "today":"2 760 ₽","todayWill":"будет ≈ 13 880 ₽","todayPt":"3 ПТ · будет 12","todayCancelled":"2 отмены",
        "month":"49 840 ₽","monthWill":"будет ≈ 184 980 ₽","monthPt":"38 ПТ · будет 139","monthCancelled":"11 отмен",
        "done":310,"forecast":1000,"pace":"Хорошо идёте: прогноз 115 % плана","paceShort":"прогноз 115 % плана","behind":false,
        "ptTier":"38 ПТ · ступень 1","todaySigned":"+2 760 ₽","planPercent":"31 % плана",
        "poolNote":"Прогноз 115 % — план переплывёте","planDone":false,"bonusPart":376,"bonusBig":"63 ПТ","bonusSmall":"до бонуса"},
        "entries":[{"t":0,"phase":"NEXT","header":"Следующая · 12:00–12:50","right":"в 12:00","label":"Следующая",
        "id":"sample","name":"Никитин А.","typePlace":"ПТ · ББ","light":"#2D84E0","dark":"#57A1F6","whenText":"в 12:00",
        "next":[{"id":"s2","time":"13:00","name":"Морозов Д.","typePlace":"ПТ · ББ","light":"#2D84E0","dark":"#57A1F6"},
        {"id":"s3","time":"14:00","name":"Белова Е., Носова Е.","typePlace":"Сплит · ББ","light":"#7377E1","dark":"#8C93F4"}],
        "more":"ещё 7 · последняя в 20:00","count":"сегодня 12","todayTotal":12,"todayDone":3,"laneLabel":"СЛЕДУЮЩАЯ","lane":0,
        "laneSub":"ПТ · ББ · ещё 9 сегодня","dayLine":"3 из 12 · так держать","moneyToday":true,
        "mascot":{"sleep":false,"title":"Отлично!","line":"Дальше в 12:00 · ещё 9","pill":"Никитин А. · ПТ · ББ"},
        "payBig":"2","payUnit":"дня","payLine":"10.11 · ≈ 103 980 ₽","payTotal":16,"payPassed":14}]}
        """
        let file = try? JSONDecoder().decode(WFile.self, from: Data(json.utf8))
        return PEntry(date: Date(), frame: file?.entries.first, money: file?.money)
    }()

    static let empty = PEntry(date: Date(), frame: nil, money: nil)
}
