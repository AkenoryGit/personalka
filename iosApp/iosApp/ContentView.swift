import SwiftUI
import UIKit
import PersonalkaUI

/// Окно UIKit с экранами Compose Multiplatform на весь экран.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            // PDF-отчёт 1С, открытый в «Персоналке» из почты или «Файлов», — в «Сверки».
            .onOpenURL { url in MainViewControllerKt.openUrl(url: url) }
    }
}
