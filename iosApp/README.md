# iosApp

Приложение для iPhone (iOS 16 и новее, ТЗ 2.3), шаг **5.1**. Всё, что на экране, — общие экраны Compose Multiplatform из `shared/ui` (фреймворк `PersonalkaUI`); здесь только оболочка на Swift.

| Файл | Что |
| --- | --- |
| `iosApp.xcodeproj` | Проект Xcode, одна цель `iosApp` (пакет `com.akenory.personalka`, имя на экране — «Персоналка») |
| `Configuration/Config.xcconfig` | Пакет, команда Apple (`TEAM_ID`), версия |
| `iosApp/iOSApp.swift` | Запуск (`startPersonalka`), возврат в приложение (`onAppActive`), нажатия на уведомления |
| `iosApp/ContentView.swift` | Экраны Compose на весь экран; PDF, открытый в приложении, — в «Сверки» |
| `iosApp/Info.plist`, `PrivacyInfo.xcprivacy` | Русский язык, открытие PDF, манифест приватности App Store |
| `iosApp/Assets.xcassets` | Значок (рисует `tools/icon.swift` из `docs/mockups/icon/icon-master.svg`) и цвет заставки |

Платформенные части на Kotlin — `iosMain` модулей:

- `shared/data/src/iosMain` — база в Application Support (`personalkaDatabase(folder)`), настройки телефона в `NSUserDefaults`, токены в Связке ключей, сеть Ktor на NSURLSession, копии: SHA-256 и AES из CommonCrypto, свой GCM (`Gcm.kt`) и zip (`Zip.kt`, сжатие zlib) — файл копии тот же, что на Android; папка копий — закладка на папку из «Файлов».
- `shared/ui/src/iosMain/.../ios` — `IosApp` (репозитории), `MainViewController` (входы для Swift), `IosFiles` (окно «Файлов», «Поделиться», буфер), `IosPhotos` (выбор фото), `IosNotifications` (уведомления наперёд — будильника, как на Android, у iPhone нет).

Отчёт 1С (PDF) разбирается общим кодом `shared/domain` (`report1c`) — PDFKit не нужен.

## Сборка на Mac

1. Нужны Xcode, JDK 21 (Gradle ищет его в `~/.gradle/gradle.properties`: `org.gradle.java.installations.paths=…`) и Android SDK в `local.properties` (`sdk.dir=…`) — Gradle настраивает все модули.
2. Первый раз Kotlin-часть лучше собрать в Терминале: `./gradlew :shared:ui:linkDebugFrameworkIosSimulatorArm64` (качает Kotlin/Native и библиотеки).
3. Открыть `iosApp/iosApp.xcodeproj`, выбрать симулятор iPhone, ▶︎. Фаза «Compile Kotlin Framework» сама зовёт `./gradlew :shared:ui:embedAndSignAppleFrameworkForXcode`.

Из Терминала: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build`.

## TestFlight

Обычно — само (шаг 5.1в): после push в `main` workflow «iPhone» (`.github/workflows/ios.yml`) собирает приложение на машине Mac GitHub и выгружает в TestFlight по ключу App Store Connect API (секреты `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`, ключ «App Manager»). Вручную с Mac (шаг 5.1б) — так:

Приложение в App Store Connect — «Персоналка», Bundle ID `com.akenory.personalka`, SKU `personalka`. Номер сборки — число коммитов, версия — `MARKETING_VERSION` в `Config.xcconfig`.

```
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' \
  -archivePath ~/tools/Personalka.xcarchive -allowProvisioningUpdates archive
xcodebuild -exportArchive -archivePath ~/tools/Personalka.xcarchive -exportOptionsPlist iosApp/Configuration/ExportOptions.plist \
  -exportPath ~/tools/export -allowProvisioningUpdates
```

Release-сборке Kotlin/Native мало 3 ГБ из `gradle.properties` — на Mac в `~/.gradle/gradle.properties` стоит `org.gradle.jvmargs=-Xmx6g`. Архив и выгрузка — 15–20 минут, обработка в App Store Connect — ещё 10–30.
