# Макеты клиентов (шаг 0.5)

Приняты 22.10.2026 целиком, без отдельного согласования. Отдельного документа решений у шага нет: правила — в ТЗ [4.15](../../tz/4.15.md), [4.16](../../tz/4.16.md), [4.17](../../tz/4.17.md), [5.8](../../tz/5.8.md), [5.9](../../tz/5.9.md), [5.10](../../tz/5.10.md), [5.16](../../tz/5.16.md), [5.18](../../tz/5.18.md). Нестыковки этой страницы исправлены на шаге 0.8 — [`docs/mockups/README.md`](../README.md).

**Холст «Персоналка — макеты»:** https://claude.ai/artifact/6PXCx6ETyZfXgxgxyEvqzQ — страница «Клиенты · 0.5». Время — вт 20.10, 15:40, как в макетах календаря. Клиенты придуманы.

## Что на холсте

| Ряд | Макеты |
| --- | --- |
| Список и «Без записи» · 5.8, 4.15, 4.16 | Список · активные; «Без записи» во всю прокрутку с разделом «Ждут время»; «+ В ожидание» (6 нажатий); запись на освободившееся время; новый клиент с подсказкой похожего |
| Карточка клиента · 5.9 | Тренировки, результаты, инфо после СПТ, расписание с меню «…», кабинет клиента; разложенный Fold — список и карточка рядом |
| Первый запуск · 5.18 | Приветствие, ФИО тренера, места и типы, календарь с подсказкой, пустой список клиентов |
| Конструктор типа · 3.3, 5.16 | Настройки · места и типы; новый тип — вид и цвет; новый тип — оплата и дневник |
| Родственники · 4.17 | Инфо с родственниками и нажатие на «Мать»; «+ Родственник» за 4 нажатия; карточка матери — связь видна со второй стороны |

## Что проверено

- 10.7: «Записать клиента из „Без записи“» — 2 нажатия, «Поставить в лист ожидания» — 6, «Записать ждущего на освободившееся» — 3 (таблица — в [`docs/mockups/README.md`](../README.md)).
- Первый запуск — меньше минуты, родственник добавляется за 4 нажатия (критерий шага 0.5 в плане).
- Шаг 0.8: в списке три фильтра «Активные · Без записи · Архив», остальное — под значком «Сортировка и фильтры»; Белова Е. — «Груд ДБ», Новикова П. на разложенном Fold — «без расписания»; форма записи из листа ожидания — в порядке обычной формы, повтор «бессрочно».
- Шаг 0.7: в карточке Ксении причина отмены 13.10 — «Не сможет, сообщили за 40 мин»; ссылка кабинета `…/c/<токен>` с токеном из 43 символов.

## Файлы

22 макетов страницы холста. Картинка — в теме, в которой макет стоит на холсте, рядом — в другой теме; размер в dp при плотности 1. Исходник — HTML-макет с холста: точные отступы, размеры и цвета, если на картинке их не разобрать. Как перерисовать картинки после правки холста — [`docs/mockups/render/`](../render/README.md).

| Макет | Картинка | Другая тема | Исходник |
| --- | --- | --- | --- |
| Список · активные · 412 × 960 | [ClList.png](ClList.png) | [тёмная](ClList-dark.png) | [src/ClList.dc.html](src/ClList.dc.html) |
| Без записи · вся прокрутка · 412 × 1300 | [ClNoBooking.png](ClNoBooking.png) | [тёмная](ClNoBooking-dark.png) | [src/ClNoBooking.dc.html](src/ClNoBooking.dc.html) |
| + В ожидание · 6 нажатий · 412 × 960 | [ClWaitAdd.png](ClWaitAdd.png) | [тёмная](ClWaitAdd-dark.png) | [src/ClWaitAdd.dc.html](src/ClWaitAdd.dc.html) |
| Записать на освободившееся · 412 × 960 | [ClBook.png](ClBook.png) | [тёмная](ClBook-dark.png) | [src/ClBook.dc.html](src/ClBook.dc.html) |
| Новый клиент · похожий · 412 × 960 | [ClNew.png](ClNew.png) | [тёмная](ClNew-dark.png) | [src/ClNew.dc.html](src/ClNew.dc.html) |
| Карточка · тренировки · 412 × 960 | [ClCard.png](ClCard.png) | [тёмная](ClCard-dark.png) | [src/ClCard.dc.html](src/ClCard.dc.html) |
| Карточка · результаты · 412 × 960 | [ClCardResults.png](ClCardResults.png) | [тёмная](ClCardResults-dark.png) | [src/ClCardResults.dc.html](src/ClCardResults.dc.html) |
| Карточка · инфо, после СПТ · 412 × 960 | [ClCardInfo.png](ClCardInfo.png) | [тёмная](ClCardInfo-dark.png) | [src/ClCardInfo.dc.html](src/ClCardInfo.dc.html) |
| Карточка · расписание, меню «…» · 412 × 960 | [ClCardSchedule.png](ClCardSchedule.png) | [тёмная](ClCardSchedule-dark.png) | [src/ClCardSchedule.dc.html](src/ClCardSchedule.dc.html) |
| Кабинет клиента · 412 × 960 | [ClCabinet.png](ClCabinet.png) | [тёмная](ClCabinet-dark.png) | [src/ClCabinet.dc.html](src/ClCabinet.dc.html) |
| Разложенный Fold · список и карточка · 760 × 840 | [ClUnfolded.png](ClUnfolded.png) | [тёмная](ClUnfolded-dark.png) | [src/ClUnfolded.dc.html](src/ClUnfolded.dc.html) |
| Приветствие · 412 × 960 | [FrWelcome.png](FrWelcome.png) | [тёмная](FrWelcome-dark.png) | [src/FrWelcome.dc.html](src/FrWelcome.dc.html) |
| ФИО тренера · 412 × 960 | [FrName.png](FrName.png) | [тёмная](FrName-dark.png) | [src/FrName.dc.html](src/FrName.dc.html) |
| Места и типы · 412 × 960 | [FrSetup.png](FrSetup.png) | [тёмная](FrSetup-dark.png) | [src/FrSetup.dc.html](src/FrSetup.dc.html) |
| Календарь с подсказкой · 412 × 960 | [FrCalendar.png](FrCalendar.png) | [тёмная](FrCalendar-dark.png) | [src/FrCalendar.dc.html](src/FrCalendar.dc.html) |
| Клиенты · пусто · 412 × 960 | [ClEmpty.png](ClEmpty.png) | [тёмная](ClEmpty-dark.png) | [src/ClEmpty.dc.html](src/ClEmpty.dc.html) |
| Настройки · места и типы · 412 × 960 | [TypesList.png](TypesList.png) | [тёмная](TypesList-dark.png) | [src/TypesList.dc.html](src/TypesList.dc.html) |
| Новый тип · вид и цвет · 412 × 960 | [TypeNew.png](TypeNew.png) | [тёмная](TypeNew-dark.png) | [src/TypeNew.dc.html](src/TypeNew.dc.html) |
| Новый тип · оплата и дневник · 412 × 960 | [TypeNew2.png](TypeNew2.png) | [тёмная](TypeNew2-dark.png) | [src/TypeNew2.dc.html](src/TypeNew2.dc.html) |
| Инфо · родственники, нажатие на «Мать» · 412 × 960 | [ClRelInfo.png](ClRelInfo.png) | [тёмная](ClRelInfo-dark.png) | [src/ClRelInfo.dc.html](src/ClRelInfo.dc.html) |
| + Родственник · 4 нажатия · 412 × 960 | [ClRelAdd.png](ClRelAdd.png) | [тёмная](ClRelAdd-dark.png) | [src/ClRelAdd.dc.html](src/ClRelAdd.dc.html) |
| Карточка матери · связь со второй стороны · 412 × 960 | [ClRelBack.png](ClRelBack.png) | [тёмная](ClRelBack-dark.png) | [src/ClRelBack.dc.html](src/ClRelBack.dc.html) |
