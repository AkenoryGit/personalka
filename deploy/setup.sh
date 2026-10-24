#!/bin/bash
# Сервер «Персоналки»: установка и обновление на VPS Planovo (Ubuntu 24.04, ТЗ 7.4) — по образцу deploy/setup.sh Planovo.
#
# Запускает выкладка (.github/workflows/deploy.yml) от root перед каждой новой версией; повторный запуск ничего не
# ломает. Ставит рядом с Planovo, но отдельно от него: пользователь trener, база trener в том же PostgreSQL 16,
# служба trener.service на 127.0.0.1:8090, поддомен в том же Caddy. Planovo не трогается: ни его служба и база,
# ни брандмауэр, ни его блок в Caddyfile — блок «Персоналки» лежит своим файлом /etc/caddy/sites/trener.caddy,
# в Caddyfile только строка import (её же пишет setup.sh Planovo, иначе его выкладка стирала бы блок).
# Пароль базы создаётся здесь и хранится только на сервере (/etc/trener/db-password).
#
# TRENER_TEST=1 — проверка в CI на чистой Ubuntu: свой сертификат Caddy вместо Let's Encrypt.
set -euo pipefail
DOMAIN=${TRENER_DOMAIN:-trener.planovo-app.ru}
TEST=${TRENER_TEST:-0}
PORT=8090   # Planovo — 8080
HERE=$(cd "$(dirname "$0")" && pwd)
export DEBIAN_FRONTEND=noninteractive
cd /tmp

step() { echo "== $*"; }

step "Пакеты"
# Planovo их уже поставил — тогда apt не трогаем. Выкладка Planovo или автообновление могут держать apt — ждём.
PACKAGES=(postgresql openjdk-21-jre-headless caddy curl openssl)
MISSING=()
for p in "${PACKAGES[@]}"; do dpkg -s "$p" >/dev/null 2>&1 || MISSING+=("$p"); done
if [ ${#MISSING[@]} -gt 0 ]; then
  echo "Ставлю: ${MISSING[*]}"
  apt-get -o DPkg::Lock::Timeout=600 update -qq
  apt-get -o DPkg::Lock::Timeout=600 install -y -qq --no-install-recommends "${MISSING[@]}" >/dev/null
fi
JAVA=$(ls -d /usr/lib/jvm/java-21-openjdk-*/bin/java 2>/dev/null | head -1)
[ -x "$JAVA" ] || { echo "Нет Java 21 в /usr/lib/jvm" >&2; exit 1; }

step "Пользователь и папки"
id -u trener >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /opt/trener --shell /usr/sbin/nologin trener
install -d -m 755 /opt/trener /opt/trener/releases /opt/trener/bin
install -d -m 750 -o root -g trener /etc/trener
# Служба запускает Java 21 по этой ссылке, а не /usr/bin/java: та может указывать на другую версию.
ln -sfn "$JAVA" /opt/trener/java

step "База PostgreSQL"
systemctl enable --now postgresql >/dev/null 2>&1
for _ in $(seq 1 30); do runuser -u postgres -- pg_isready -q && break; sleep 1; done
PASS_FILE=/etc/trener/db-password
if [ ! -s "$PASS_FILE" ]; then (umask 077; openssl rand -hex 24 > "$PASS_FILE"); fi
DB_PASSWORD=$(cat "$PASS_FILE")
runuser -u postgres -- psql -qtA -v ON_ERROR_STOP=1 -v pass="$DB_PASSWORD" >/dev/null <<'SQL'
SELECT 'CREATE ROLE trener LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'trener') \gexec
ALTER ROLE trener PASSWORD :'pass';
SELECT 'CREATE DATABASE trener OWNER trener ENCODING ''UTF8'' TEMPLATE template0' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'trener') \gexec
-- Только роль trener подключается к базе trener. В чужих базах прав у неё нет: таблицы Planovo ей не видны.
REVOKE ALL ON DATABASE trener FROM PUBLIC;
SQL

step "Настройки сервера"
# Строка для EnvironmentFile systemd: значение в кавычках, без кавычек, обратной косой черты и переводов строки внутри.
env_line() {
  if [[ "$2" == *[\"\\]* || "$2" == *$'\n'* ]]; then echo "Недопустимый символ в $1" >&2; exit 1; fi
  printf '%s="%s"\n' "$1" "$2"
}
{
  env_line TRENER_DB_URL "jdbc:postgresql://127.0.0.1:5432/trener"
  env_line TRENER_DB_USER trener
  env_line TRENER_DB_PASSWORD "$DB_PASSWORD"
  env_line TRENER_DOMAIN "$DOMAIN"
  env_line TRENER_HOST 127.0.0.1
  env_line TRENER_PORT "$PORT"
} > /etc/trener/trener.env.new
install -m 640 -o root -g trener /etc/trener/trener.env.new /etc/trener/trener.env
rm -f /etc/trener/trener.env.new

step "Скрипт выкладки и службы"
install -m 755 "$HERE/server.sh" /opt/trener/bin/server.sh
install -m 644 "$HERE/trener.service" "$HERE/trener-alert.service" /etc/systemd/system/
systemctl daemon-reload
# Первый запуск — из server.sh switch, когда jar уже на месте.
systemctl enable trener.service >/dev/null 2>&1

step "HTTPS (Caddy)"
install -d -m 755 /etc/caddy/sites
{
  echo "# Сервер «Персоналки» (кладёт deploy/setup.sh из репозитория personalka). Сертификат Caddy выпускает сам."
  echo "$DOMAIN {"
  [ "$TEST" = 1 ] && echo "	tls internal"
  cat <<EOF
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
}
EOF
} > /etc/caddy/sites/trener.caddy.new
mv /etc/caddy/sites/trener.caddy.new /etc/caddy/sites/trener.caddy
IMPORT='import /etc/caddy/sites/*.caddy'
CADDYFILE=/etc/caddy/Caddyfile
if ! grep -qxF "$IMPORT" "$CADDYFILE" 2>/dev/null; then
  printf '\n# Сайты других приложений на этом сервере — каждый своим файлом.\n%s\n' "$IMPORT" >> "$CADDYFILE"
fi
caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 || {
  caddy validate --config "$CADDYFILE" --adapter caddyfile
  exit 1
}
systemctl enable caddy >/dev/null 2>&1
systemctl reload caddy 2>/dev/null || systemctl restart caddy

step "Готово"
# Итог — одной строкой (выкладка показывает её в итогах сборки), без паролей.
SYSTEM=$(. /etc/os-release && echo "$PRETTY_NAME")
ALERT=$([ -x /usr/local/bin/planovo-alert ] && echo "через ящик Planovo" || echo "НЕТ planovo-alert — только журнал")
MEM=$(free -m | awk '/^Mem:/ { print $7 }')
DISK=$(df -BG --output=avail / | tail -1 | tr -d ' G')
echo "ИТОГ: $SYSTEM; $DOMAIN → 127.0.0.1:$PORT; $("$JAVA" -version 2>&1 | head -1); $(runuser -u postgres -- psql -tAc 'SHOW server_version' | cut -d' ' -f1 | sed 's/^/PostgreSQL /'); письма о падениях — $ALERT; свободно памяти $MEM МБ, диска $DISK ГБ"
