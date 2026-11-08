import SwiftUI
import WidgetKit

/// Виджеты «Персоналки» (шаги 5.10, 5.10а): четыре обычных — с экраном блокировки — и шесть ярких.
@main
struct PersonalkaWidgets: WidgetBundle {
    var body: some Widget {
        LargeWidget()
        MoneyWidget()
        NowWidget()
        MonthWidget()
        PoolWidget()
        LaneWidget()
        TyulyaWidget()
        BonusWidget()
        DayWidget()
        PaydayWidget()
    }
}
