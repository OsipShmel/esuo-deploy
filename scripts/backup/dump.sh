#!/usr/bin/env bash
# Простой бэкап баз ЕСУО: pg_dump из работающих postgres-контейнеров в .sql.gz на хосте
# (вне docker-томов), чтобы потерянный volume не значил потерянную демку.
#   ./scripts/backup/dump.sh              — дамп всех поднятых баз (center, b01, b02)
#   ./scripts/backup/dump.sh b01          — дамп только одной
# Восстановление: ./scripts/backup/restore.sh <файл.sql.gz> <контейнер>
set -uo pipefail
cd "$(dirname "$0")/../.."
[ -f .env ] && { set -a; . ./.env; set +a; }
DB_USER=${DB_USER:-esuo}
OUT_DIR=${BACKUP_DIR:-./backups}
mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)

# label:container:db
TARGETS=(
  "center:esuo-postgres-center:esuo_center"
  "b01:esuo-postgres-b01:esuo"
  "b02:esuo-postgres-b02:esuo"
)

dump_one() {
  local label=$1 container=$2 db=$3
  if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
    echo "  пропуск $label: контейнер $container не поднят"
    return 0
  fi
  local file="$OUT_DIR/${label}-${db}-${STAMP}.sql.gz"
  if docker exec "$container" pg_dump -U "$DB_USER" "$db" | gzip > "$file"; then
    echo "  OK $label -> $file ($(du -h "$file" | cut -f1))"
  else
    echo "  FAIL $label: pg_dump не сработал" >&2
    rm -f "$file"
    return 1
  fi
}

FAIL=0
if [ -n "${1:-}" ]; then
  match=0
  for t in "${TARGETS[@]}"; do
    IFS=: read -r label container db <<< "$t"
    [ "$label" = "$1" ] || continue
    match=1
    dump_one "$label" "$container" "$db" || FAIL=1
  done
  [ "$match" = 1 ] || { echo "неизвестная база: $1 (доступны: center, b01, b02)"; exit 2; }
else
  for t in "${TARGETS[@]}"; do
    IFS=: read -r label container db <<< "$t"
    dump_one "$label" "$container" "$db" || FAIL=1
  done
fi
exit $FAIL
