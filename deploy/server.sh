#!/usr/bin/env bash
# Переключение версии сервера на VPS и откат (ТЗ 7.4). Запускает выкладка из GitHub Actions по SSH:
#   server.sh switch <N>   — включить версию 0.1.N из /opt/trener/releases/<N>; /health не ответил — вернуть прежнюю
#   server.sh rollback     — вернуть ближайшую младшую версию, которая не падала (кнопка «Откат сервера»)
#   server.sh status       — какая версия включена и что отвечает /health
# Версии лежат в /opt/trener/releases/<N>/personalka-server.jar, включённая — ссылка /opt/trener/current.
# Пользователю выкладки нужно право `sudo systemctl restart trener` без пароля (deploy/README.md).
set -euo pipefail

ROOT=${TRENER_ROOT:-/opt/trener}
PORT=${TRENER_PORT:-8090}
KEEP=5          # сколько версий хранить для отката
WAIT=${TRENER_WAIT:-60}  # сколько секунд ждать ответа /health

current() { if [ -L "$ROOT/current" ]; then basename "$(readlink "$ROOT/current")"; fi; }

# Ответ /health с нужной версией: старая версия, ещё не остановившаяся, не считается.
healthy() {
    local version=$1 body
    for _ in $(seq "$WAIT"); do
        if body=$(curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" 2>/dev/null) && grep -qx "версия 0.1.$version" <<<"$body"; then
            echo "$body"
            return 0
        fi
        sleep 1
    done
    return 1
}

link() {
    ln -sfn "releases/$1" "$ROOT/current.new"
    mv -T "$ROOT/current.new" "$ROOT/current"
    sudo systemctl restart trener
}

enable() {
    local target=$1 previous
    previous=$(current)
    [ -f "$ROOT/releases/$target/personalka-server.jar" ] || { echo "::error title=Сервер::Нет версии 0.1.$target в $ROOT/releases"; exit 1; }
    echo "Включаю 0.1.$target (было: ${previous:+0.1.}${previous:-ничего})"
    link "$target"
    if healthy "$target"; then
        echo "::notice title=Сервер::Включена версия 0.1.$target, /health отвечает"
        return 0
    fi
    echo "::error title=Сервер::Версия 0.1.$target не ответила на /health за $WAIT с"
    touch "$ROOT/releases/$target/failed"   # «Откат сервера» на неё не вернёт
    sudo journalctl -u trener -n 30 --no-pager || true
    if [ -n "$previous" ] && [ "$previous" != "$target" ]; then
        link "$previous"
        if healthy "$previous"; then
            echo "::warning title=Сервер::Автоматический откат: снова работает 0.1.$previous"
        else
            echo "::error title=Сервер::Откат на 0.1.$previous тоже не ответил — нужна проверка на сервере"
        fi
    fi
    return 1
}

cleanup() {
    local now
    now=$(current)
    ls -1 "$ROOT/releases" | sort -n | head -n -"$KEEP" | while read -r old; do
        [ "$old" = "$now" ] || rm -rf "${ROOT:?}/releases/$old"
    done
}

case "${1:-}" in
    switch)
        [[ "${2:-}" =~ ^[0-9]+$ ]] || { echo "Нужен номер сборки: server.sh switch <N>"; exit 2; }
        enable "$2"
        cleanup
        ;;
    rollback)
        now=$(current)
        before=$(ls -1 "$ROOT/releases" | sort -n | awk -v now="$now" '$1 < now' | while read -r n; do
            [ -f "$ROOT/releases/$n/failed" ] || echo "$n"
        done | tail -1)
        [ -n "$before" ] || { echo "::error title=Откат сервера::Нет версии старше 0.1.$now"; exit 1; }
        enable "$before"
        ;;
    status)
        echo "Включена 0.1.$(current)"
        curl -fsS --max-time 2 "http://127.0.0.1:$PORT/health" || echo "/health не отвечает"
        ;;
    *)
        echo "server.sh switch <N> | rollback | status"
        exit 2
        ;;
esac
