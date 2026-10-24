#!/bin/bash
# Ночная копия базы «Персоналки» (ТЗ 7.5): /opt/trener/bin/backup.sh, запускает trener-backup.timer в 03:30 по клубу.
#
# 1. Дамп базы trener (pg_dump, свой формат) и проверка, что он читается (pg_restore --list).
# 2. Шифрование age открытым ключом backup.pub; закрытый ключ — только у Дмитрия, на сервере его нет.
# 3. Письмо в ящик Planovo с темой «Персоналка: копия базы …» (backup_mail.py), большая копия — частями
#    под лимит вложения Mail.ru. На сервере — 7 последних зашифрованных копий.
# 4. Письма копий старше 30 дней удаляются из ящика (все папки, только письма с меткой копии).
# 5. По воскресеньям (и с --probe) — проба: дамп восстанавливается во временную базу trener_probe и число
#    записей в каждой таблице сверяется с дампом. Закрытого ключа нет, поэтому проба берёт дамп до шифрования —
#    те же байты, что зашифрованы (SHA-256 — в письме); расшифровку проверяет Дмитрий своим ключом (deploy/README.md).
# Любой сбой — письмо администратору и строка «ошибка» в /var/lib/trener/backup-status (для «Клуб → Сервер»).
#
# Для проверки в CI: TRENER_BACKUP_DIR, TRENER_BACKUP_KEY (файл открытого ключа), TRENER_BACKUP_PART (байт в части),
# TRENER_BACKUP_STATUS, TRENER_BACKUP_OUTBOX (письма файлами, см. backup_mail.py).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${TRENER_BACKUP_DIR:-/var/backups/trener}
KEY=${TRENER_BACKUP_KEY:-$HERE/backup.pub}
PART=${TRENER_BACKUP_PART:-15000000}   # 15 МБ — с base64 около 20 МБ, меньше лимита письма Mail.ru
STATUS=${TRENER_BACKUP_STATUS:-/var/lib/trener/backup-status}
KEEP=7
DAYS=30
PROBE=0
[ "${1:-}" = --probe ] && PROBE=1
[ "$(TZ=Asia/Yekaterinburg date +%u)" = 7 ] && PROBE=1
MAIL="python3 -I $HERE/backup_mail.py"

STAMP=$(TZ=Asia/Yekaterinburg date +%Y%m%d-%H%M)
DAY=$(TZ=Asia/Yekaterinburg date +%d.%m.%Y)
WORK=$(mktemp -d /tmp/trener-backup.XXXXXX)
PLAIN=$WORK/trener-$STAMP.dump
SEALED=$DIR/trener-$STAMP.dump.age
STAGE="подготовка"

status() {  # status ok|ошибка ТЕКСТ — одна строка: время, итог, подробности (без данных тренеров)
  install -d -m 755 "$(dirname "$STATUS")"
  printf '%s\t%s\t%s\n' "$(date -Iseconds)" "$1" "$2" > "$STATUS.new"
  chmod 644 "$STATUS.new"
  mv "$STATUS.new" "$STATUS"
}

drop_probe() { runuser -u postgres -- dropdb --if-exists trener_probe >/dev/null 2>&1 || true; }

finish() {
  local code=$?
  rm -rf "$WORK" "$SEALED.new"
  [ "$PROBE" = 1 ] && drop_probe
  if [ "$code" != 0 ]; then
    local text="Ночная копия базы «Персоналки» не сделана: этап «$STAGE», код $code. Журнал: journalctl -u trener-backup -n 100. Ручной запуск: systemctl start trener-backup."
    status ошибка "$STAGE"
    echo "СБОЙ: $text" >&2
    # Письмо администратору — своим ящиком; не вышло — через planovo-alert; иначе только журнал.
    $MAIL alert "Персоналка: копия базы не сделана" "$text" ||
      { [ -x /usr/local/bin/planovo-alert ] && /usr/local/bin/planovo-alert "Персоналка: копия базы не сделана" "$text"; } ||
      echo "письмо о сбое не отправлено" >&2
  fi
  exit "$code"
}
trap finish EXIT

# Число записей по таблицам: «таблица|число», по имени.
dump_counts() {
  pg_restore --data-only --file=- "$1" | awk '
    /^COPY / { split($2, n, "."); t = n[2]; c = 0; inside = 1; next }
    inside && /^\\\.$/ { print t "|" c; inside = 0; next }
    inside { c++ }' | sort
}
db_counts() {
  runuser -u postgres -- psql -d "$1" -qtA -v ON_ERROR_STOP=1 -c "
    SELECT table_name || '|' || (xpath('/row/c/text()', query_to_xml(
      format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text
    FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'" | sort
}

STAGE="дамп"
install -d -m 700 "$DIR"
runuser -u postgres -- pg_dump --format=custom --compress=9 trener > "$PLAIN"
pg_restore --list "$PLAIN" > /dev/null
COUNTS=$(dump_counts "$PLAIN")
TABLES=$(grep -c . <<<"$COUNTS" || true)
ROWS=$(awk -F'|' '{ s += $2 } END { print s + 0 }' <<<"$COUNTS")
[ "$TABLES" -gt 0 ] || { echo "в дампе нет таблиц" >&2; exit 1; }
SHA=$(sha256sum "$PLAIN" | cut -d' ' -f1)

STAGE="проба восстановления"
PROBE_TEXT="не в этот раз, она по воскресеньям"
if [ "$PROBE" = 1 ]; then
  drop_probe
  runuser -u postgres -- createdb --template=template0 --encoding=UTF8 trener_probe
  runuser -u postgres -- pg_restore --no-owner --no-acl --exit-on-error --dbname=trener_probe < "$PLAIN"
  if ! diff <(echo "$COUNTS") <(db_counts trener_probe) >&2; then
    echo "число записей во временной базе не совпало с дампом" >&2
    exit 1
  fi
  drop_probe
  PROBE_TEXT="прошла, во временной базе столько же записей, сколько в дампе (таблиц: $TABLES, записей: $ROWS)"
fi

STAGE="шифрование"
[ -s "$KEY" ] || { echo "нет открытого ключа $KEY" >&2; exit 1; }
(umask 077; age --encrypt --recipients-file "$KEY" --output "$SEALED.new" "$PLAIN")
mv "$SEALED.new" "$SEALED"
rm -f "$PLAIN"
SIZE=$(stat -c %s "$SEALED")

STAGE="письмо"
split --bytes="$PART" --numeric-suffixes=1 --suffix-length=2 "$SEALED" "$WORK/$(basename "$SEALED")."
PARTS=("$WORK"/*.age.*)
if [ ${#PARTS[@]} = 1 ]; then mv "${PARTS[0]}" "$WORK/$(basename "$SEALED")"; PARTS=("$WORK/$(basename "$SEALED")"); fi
cat > "$WORK/body.txt" <<EOF
Ночная копия базы сервера «Персоналки» за $DAY (ТЗ 7.5).

Файл: $(basename "$SEALED"), $SIZE байт, частей: ${#PARTS[@]}.
Зашифрован age открытым ключом $(head -c 16 "$KEY")…; открывается только закрытым ключом personalka-backup.key.
В копии таблиц: $TABLES, записей: $ROWS. SHA-256 дампа до шифрования: $SHA
Проба восстановления — $PROBE_TEXT.

Как восстановить — deploy/README.md в репозитории personalka, раздел «Копии базы».
$( [ ${#PARTS[@]} -gt 1 ] && echo "Части склеить по порядку: cat $(basename "$SEALED").* > $(basename "$SEALED")" )
EOF
$MAIL send "Персоналка: копия базы $DAY" "$WORK/body.txt" "${PARTS[@]}"

STAGE="чистка"
# На сервере — 7 последних копий.
ls -1t "$DIR"/trener-*.dump.age 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
$MAIL clean "$DAYS"

STAGE="готово"
status ok "$DAY: $(basename "$SEALED"), $SIZE байт, частей ${#PARTS[@]}, таблиц $TABLES, записей $ROWS; проба — $PROBE_TEXT"
echo "КОПИЯ: $(basename "$SEALED") отправлена ($SIZE байт, частей ${#PARTS[@]}); таблиц $TABLES, записей $ROWS; проба — $PROBE_TEXT"
