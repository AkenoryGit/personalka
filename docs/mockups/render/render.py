"""Перерисовывает картинки макетов docs/mockups/<страница>/*.png из исходников src/*.dc.html.

Запуск из папки docs/mockups/render (подготовка — в README.md рядом):
    python3 render.py            # все макеты
    python3 render.py Main Month # только эти
"""
import asyncio
import json
import os
import shutil
import sys
import tempfile

from playwright.async_api import async_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
MOCKUPS = os.path.dirname(HERE)
PAGES = {"kalendar": "calendar", "trenirovka": "training", "klienty": "clients",
         "glavnaya": "home-salary", "kabinet": "cabinet"}
VARIANTS = {
    "KbCode": [("KbCode-vvod.png", {"state": "Ввод"}), ("KbCode-blokirovka.png", {"state": "Блокировка"})],
    "KbConsent": [("KbConsent-bez-galochki.png", {"state": "Без галочки"}),
                  ("KbConsent-vzroslyj-otmecheno.png", {"who": "Взрослый", "state": "Отмечено"})],
}
NM = os.path.join(HERE, "node_modules")
FONTS = {"ms.woff2": os.path.join(NM, "material-symbols/material-symbols-outlined.woff2")}
for sub in ("cyrillic", "latin"):
    for wt in ("400", "500"):
        FONTS[f"roboto-{sub}-{wt}.woff2"] = os.path.join(NM, f"@fontsource/roboto/files/roboto-{sub}-{wt}-normal.woff2")
RANGES = {"cyrillic": "U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116",
          "latin": "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,"
                   "U+2000-206F,U+20AC,U+20BD,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"}
ROBOTO_CSS = "".join(
    f"@font-face{{font-family:'Roboto';font-weight:{wt};font-display:block;"
    f"src:url(https://fonts.local/roboto-{sub}-{wt}.woff2) format('woff2');unicode-range:{rng}}}"
    for sub, rng in RANGES.items() for wt in ("400", "500"))
MS_CSS = ("@font-face{font-family:'Material Symbols Outlined';font-weight:100 700;font-display:block;"
          "src:url(https://fonts.local/ms.woff2) format('woff2')}")


async def route(r):
    """Шрифты Google подменяются локальными: из песочницы и без сети картинки одинаковые."""
    u = r.request.url
    if "fonts.googleapis.com" in u:
        return await r.fulfill(status=200, content_type="text/css", body=MS_CSS if "Material" in u else ROBOTO_CSS)
    if "fonts.local/" in u:
        with open(FONTS[u.rsplit("/", 1)[1]], "rb") as f:
            return await r.fulfill(status=200, content_type="font/woff2", body=f.read(),
                                   headers={"access-control-allow-origin": "*"})
    if u.startswith("file://"):
        return await r.continue_()
    return await r.abort()


def default_dark(src):
    props = json.loads(src.split("data-props='", 1)[1].split("'", 1)[0])
    return bool(props.get("dark", {}).get("default", False))


async def main(only):
    canvas = json.load(open(os.path.join(MOCKUPS, "canvas.json"), encoding="utf-8"))
    work = tempfile.mkdtemp()
    shutil.copy(os.path.join(HERE, "dc-runtime.js"), work)
    with open(os.path.join(work, "support.js"), "w") as f:
        f.write('document.write("<script src=\\"dc-runtime.js\\"><\\/script>");')
    jobs = []
    for f in canvas["order"]:
        base = f.replace(".dc.html", "")
        if only and base not in only:
            continue
        b = canvas["boards"][f]
        folder = os.path.join(MOCKUPS, PAGES[b["page"]])
        shutil.copy(os.path.join(folder, "src", f), work)
        dark = default_dark(open(os.path.join(work, f), encoding="utf-8").read())
        other = "light" if dark else "dark"
        jobs.append((f, b["w"], b["h"], {"dark": dark}, os.path.join(folder, base + ".png")))
        jobs.append((f, b["w"], b["h"], {"dark": not dark}, os.path.join(folder, f"{base}-{other}.png")))
        for png, props in VARIANTS.get(base, []):
            jobs.append((f, b["w"], b["h"], dict(props, dark=dark), os.path.join(folder, png)))
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        for f, w, h, props, out in jobs:
            page = await browser.new_page(viewport={"width": w, "height": h})
            await page.route("**/*", route)
            await page.goto("file://" + os.path.join(work, f))
            await page.wait_for_function("document.querySelector('.scr') !== null", timeout=15000)
            await page.evaluate("p => window.__dcSetProps(window.__dcRootName(), p)", props)
            await page.evaluate("Promise.all([document.fonts.load(\"24px 'Material Symbols Outlined'\", 'home'),"
                                "document.fonts.load('400 15px Roboto', 'Яa'), document.fonts.load('500 15px Roboto', 'Яa')])")
            await page.wait_for_timeout(300)
            await page.screenshot(path=out, clip={"x": 0, "y": 0, "width": w, "height": h})
            print(os.path.relpath(out, MOCKUPS))
            await page.close()
        await browser.close()
    shutil.rmtree(work)


asyncio.run(main(set(sys.argv[1:])))
