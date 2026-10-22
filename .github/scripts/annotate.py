"""Итоги сборки — отметками GitHub Actions (::notice / ::error).

Отметки видны на странице сборки и читаются через API (`gh api .../check-runs/<job>/annotations`),
даже когда полный лог недоступен. Запуск: python3 .github/scripts/annotate.py build-log.txt [apk-info.txt]
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(kind: str, title: str, message: str) -> None:
    print(f"::{kind} title={esc(title)}::{esc(message)}")


def module_of(path: str) -> str:
    return path.split("/build/")[0].lstrip("./") or "root"


def tests() -> int:
    modules: dict[str, list[int]] = {}
    failed: list[str] = []
    for path in sorted(glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True)):
        root = ET.parse(path).getroot()
        counts = modules.setdefault(module_of(path), [0, 0, 0])
        counts[0] += int(root.get("tests", 0))
        counts[1] += int(root.get("failures", 0)) + int(root.get("errors", 0))
        counts[2] += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            for bad in list(case.iter("failure")) + list(case.iter("error")):
                text = (bad.get("message") or bad.text or "").strip()
                failed.append(f"{case.get('classname')}.{case.get('name')}: {text[:400]}")
    if modules:
        lines = [f"{m}: тестов {c[0]}, упало {c[1]}, пропущено {c[2]}" for m, c in modules.items()]
        emit("notice", "Тесты", "\n".join(lines))
    else:
        emit("warning", "Тесты", "Результатов тестов нет")
    for item in failed[:8]:
        emit("error", "Тест упал", item)
    return len(failed)


def lint() -> None:
    for path in glob.glob("**/build/reports/lint-results*.xml", recursive=True):
        root = ET.parse(path).getroot()
        by_level: dict[str, int] = {}
        ids: dict[str, int] = {}
        for issue in root.iter("issue"):
            level = issue.get("severity", "?")
            by_level[level] = by_level.get(level, 0) + 1
            ids[issue.get("id", "?")] = ids.get(issue.get("id", "?"), 0) + 1
        summary = ", ".join(f"{k}: {v}" for k, v in sorted(by_level.items())) or "замечаний нет"
        top = ", ".join(f"{k} ×{v}" for k, v in sorted(ids.items(), key=lambda x: -x[1])[:8])
        emit("notice", f"Lint {module_of(path)}", summary + (f"\n{top}" if top else ""))


def build_log(path: str) -> None:
    if not os.path.exists(path):
        return
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    errors = [l for l in lines if re.match(r"^(e: |ERROR:|Error:|> Task .* FAILED)", l)]
    for line in errors[:14]:
        emit("error", "Сборка", line[:600])
    ksp = [l for l in lines if l.startswith("w: [ksp]")]
    if ksp:
        emit("warning", "KSP", "\n".join(dict.fromkeys(ksp))[:1500])
    for i, line in enumerate(lines):
        if line.startswith("* What went wrong:"):
            block = [l for l in lines[i + 1 : i + 14] if l.strip() and not l.startswith("* Try:")]
            emit("error", "Что пошло не так", "\n".join(block)[:1500])
            break


def apk_info(path: str) -> None:
    if os.path.exists(path):
        emit("notice", "APK", open(path, encoding="utf-8", errors="replace").read().strip()[:1500])


if __name__ == "__main__":
    tests()
    lint()
    build_log(sys.argv[1] if len(sys.argv) > 1 else "build-log.txt")
    apk_info(sys.argv[2] if len(sys.argv) > 2 else "apk-info.txt")
