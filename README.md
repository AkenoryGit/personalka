# Персоналка

![Иконка «Персоналки»](docs/mockups/icon/icon-preview.png)

Приложение тренера: календарь, клиенты, дневник занятий и зарплата в одном месте, плюс веб-кабинет для родителей. Android, затем iPhone; Kotlin Multiplatform, Compose, Ktor, PostgreSQL.

- [План работ](docs/plan.md) — 67 шагов, отмечены сделанные.
- [ТЗ](docs/tz/README.md) — по разделам, каждый подраздел отдельным файлом.
- [Макеты](docs/mockups/README.md) — экраны этапа 0 в светлой и тёмной теме.
- [Ход работ](PROGRESS.md) — где остановились и что дальше.

## Сборка

Каждый push собирает проект в GitHub Actions: тесты, проверки Android и APK. Свежий APK из ветки `main` — в релизе [«Последняя сборка»](https://github.com/AkenoryGit/personalka/releases/tag/latest-build); он ставится поверх прежней версии, данные сохраняются.

Локально: `./gradlew check :androidApp:assembleDebug` (нужна Java 21 и Android SDK).
