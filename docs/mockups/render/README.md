# Перерисовка картинок макетов

Нужна, только если макет поменяли на холсте [«Персоналка — макеты»](https://claude.ai/artifact/6PXCx6ETyZfXgxgxyEvqzQ). Порядок:

1. Скачать с холста изменённые исходники `project/<Макет>.dc.html` в `docs/mockups/<страница>/src/`, при новых макетах — и `project/canvas.json` в `docs/mockups/`.
2. Один раз подготовить эту папку:
    - скачать с холста `artifact-type/dc-runtime.js` сюда, рядом с `render.py` (в репозиторий не кладётся);
    - `npm i material-symbols @fontsource/roboto` здесь — локальные шрифты вместо Google Fonts;
    - Python с пакетом `playwright` и Chromium (`python3 -m playwright install chromium`).
3. `python3 render.py` — все макеты, или `python3 render.py Main Month` — только названные.

Каждый макет рисуется в теме, в которой стоит на холсте (`<Макет>.png`), и в другой (`-dark.png` или `-light.png`); у кода и согласия кабинета — ещё варианты состояний. Размер — в dp макета при плотности 1. После перерисовки картинки стоит открыть и посмотреть, что значки и русский текст на месте.
