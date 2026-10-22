# Название и иконка: «Персоналка» (шаг 0.1)

Решено 18.10.2026.

- **Название:** «Персоналка», латиницей Personalka.
- **Имя пакета** (Android applicationId и iOS Bundle ID): `com.akenory.personalka` — в одном стиле с Planovo (`com.akenory.planovo`). От домена `planovo-app.ru` не строим: дефис в имени пакета запрещён. После первой установки и выпуска в RuStore имя пакета не меняется.
- **Иконка:** монограмма «П» с оранжевой точкой. Фон кремовый #F3EFE6, буква графит #15171A, точка оранжевая #FF5A1F.
- **Холст с вариантами:** https://claude.ai/artifact/6B3UWWURkd6vP1YFASySy8
- **Проверка:** иконка отрисована из Android-вектора на светлых и тёмных обоях в размерах 160, 56, 32 и 24 px и в тематических значках — читается. На светлых обоях кремовый фон почти сливается с обоями, буква читается чётко.
- **Куда положить:** на шаге 0.10 — файлы из [`res/`](res) в `androidApp/src/main/res/` по тем же путям; в манифесте `android:icon="@mipmap/ic_launcher"` и `android:roundIcon="@mipmap/ic_launcher_round"`.
- **PNG для магазинов** делаются из `icon-master.svg`: 512×512 для RuStore (шаг 5.6), 1024×1024 без прозрачности для iPhone (шаг 5.1). Квадрат без скруглений — углы скругляет система.
- **Палитра:** цвета иконки — фирменные. Оранжевый статус «поверх запрета» из ТЗ 10.5 на шаге 0.2 развести с фирменным оранжевым.

![Иконка](icon-preview.png)

## Файлы

| Файл | Что это |
| --- | --- |
| [`icon-master.svg`](icon-master.svg) | Мастер-файл 72 × 72, из него делаются PNG для магазинов |
| [`res/drawable/ic_launcher_foreground.xml`](res/drawable/ic_launcher_foreground.xml) | Передний слой адаптивной иконки, холст 108 dp |
| [`res/drawable/ic_launcher_monochrome.xml`](res/drawable/ic_launcher_monochrome.xml) | Монохромный слой для тематических значков Android 13+ |
| [`res/values/ic_launcher_background.xml`](res/values/ic_launcher_background.xml) | Цвет фона #F3EFE6 |
| [`res/mipmap-anydpi-v26/ic_launcher.xml`](res/mipmap-anydpi-v26/ic_launcher.xml), [`ic_launcher_round.xml`](res/mipmap-anydpi-v26/ic_launcher_round.xml) | Адаптивная иконка, обычная и круглая (одинаковые) |
| [`icon-preview.png`](icon-preview.png) | Картинка для этого файла, 192 × 192 |

Старые PNG-иконки (mipmap-mdpi…xxxhdpi) не нужны: приложение работает с Android 10 (ТЗ 2.3), а адаптивные иконки есть с Android 8.
