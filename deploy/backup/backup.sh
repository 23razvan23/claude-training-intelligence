#!/bin/sh
# Nightly encrypted backup: pg_dump | gzip | openssl AES-256-CBC (PBKDF2).
# Keeps the newest $BACKUP_KEEP backups. Restore: see README "Backups".
set -eu
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE must be set}"
BACKUP_DIR=${BACKUP_DIR:-/backups}
BACKUP_HOUR=${BACKUP_HOUR:-3}
BACKUP_KEEP=${BACKUP_KEEP:-14}
export PGPASSWORD="$POSTGRES_PASSWORD"

prune() {
  # Only ever touches files this script wrote: $BACKUP_DIR/training-*.sql.gz.enc
  n=0
  for f in $(ls -1t "$BACKUP_DIR" | grep -E '^training-[0-9TZ]+\.sql\.gz\.enc$'); do
    n=$((n + 1))
    if [ "$n" -gt "$BACKUP_KEEP" ]; then
      rm -f -- "$BACKUP_DIR/$f"
    fi
  done
}

backup() {
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  out="$BACKUP_DIR/training-$ts.sql.gz.enc"
  pg_dump -h "${PGHOST:-db}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner \
    | gzip -9 \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_PASSPHRASE -out "$out.part"
  mv "$out.part" "$out"
  prune
  echo "backup written: $(basename "$out")"
}

mkdir -p "$BACKUP_DIR"
if [ "${1:-}" = "now" ]; then
  backup
  exit 0
fi
while true; do
  if [ "$(date -u +%H)" -eq "$BACKUP_HOUR" ]; then
    backup || echo "backup FAILED" >&2
    sleep 3600
  fi
  sleep 300
done
