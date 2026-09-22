#!/usr/bin/env bash
# Восстановление дампа, снятого scripts/backup/dump.sh.
#   ./scripts/backup/restore.sh ./backups/b01-esuo-20260922-120000.sql.gz esuo-postgres-b01
# Внимание: база в контейнере пересоздаётся с нуля (DROP + CREATE), текущие данные в ней теряются.
set -uo pipefail
cd "$(dirname "$0")/../.."
[ -f .env ] && { set -a; . ./.env; set +a; }
DB_USER=${DB_USER:-esuo}

FILE=${1:-}
CONTAINER=${2:-}
if [ -z "$FILE" ] || [ -z "$CONTAINER" ]; then
  echo "usage: $0 <дамп.sql.gz> <контейнер БД, например esuo-postgres-b01>"
  exit 2
fi
[ -f "$FILE" ] || { echo "нет файла: $FILE"; exit 2; }
docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" || { echo "контейнер не поднят: $CONTAINER"; exit 2; }

DB=$(basename "$FILE" | cut -d- -f2)
[ -n "$DB" ] || { echo "не смог определить имя базы из имени файла: $FILE"; exit 2; }

read -r -p "Пересоздать базу '$DB' в $CONTAINER из $FILE? (yes/no) " CONFIRM
[ "$CONFIRM" = "yes" ] || { echo "отменено"; exit 1; }

docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE)" \
  && docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "CREATE DATABASE \"$DB\"" \
  && gunzip -c "$FILE" | docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB" \
  && echo "OK: $DB восстановлена из $FILE"
