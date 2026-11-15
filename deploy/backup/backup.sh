#!/bin/sh
# Nightly encrypted backup: pg_dump -> gzip -> openssl AES-256-CBC (PBKDF2).
# A backup counts only if every step succeeded AND the finished file was checked:
# it decrypts, passes gunzip -t and ends with pg_dump's completion footer. Only then is it
# renamed into place, STATUS updated and older backups pruned. A failed run never
# deletes anything. Keeps the newest $BACKUP_KEEP backups. Restore: see README "Backups".
#
# Written for busybox sh (no pipefail), so the dump goes to a temporary file first and
# each step's exit status is checked by hand. Note: `set -e` does not apply inside
# functions called as `backup || ...`, which is why nothing here relies on it.
set -eu
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE must be set}"
BACKUP_DIR=${BACKUP_DIR:-/backups}
BACKUP_HOUR=${BACKUP_HOUR:-3}
BACKUP_KEEP=${BACKUP_KEEP:-14}
# [configurable] Smallest plausible backup in bytes (encrypted file). A real dump of this
# database is megabytes; an empty or error-only dump is a few hundred bytes.
BACKUP_MIN_BYTES=${BACKUP_MIN_BYTES:-10240}
export PGPASSWORD="$POSTGRES_PASSWORD"

ENC="openssl enc -aes-256-cbc -pbkdf2 -iter 200000"

log_err() { echo "backup FAILED: $*" >&2; }

cleanup_temp() {
  # Only files this script creates: temporary dump and unfinished .part files.
  rm -f -- "$BACKUP_DIR"/.dump-*.sql "$BACKUP_DIR"/training-*.part
}

prune() {
  # Only ever touches files this script wrote: $BACKUP_DIR/training-*.sql.gz.enc
  n=0
  for f in $(ls -1t "$BACKUP_DIR" | grep -E '^training-[0-9TZ]+\.sql\.gz\.enc$' || true); do
    n=$((n + 1))
    if [ "$n" -gt "$BACKUP_KEEP" ]; then
      rm -f -- "$BACKUP_DIR/$f"
    fi
  done
}

backup() {
  cleanup_temp
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  out="$BACKUP_DIR/training-$ts.sql.gz.enc"
  part="$out.part"
  dump="$BACKUP_DIR/.dump-$ts.sql"

  pg_dump -h "${PGHOST:-db}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner >"$dump" \
    || { log_err "pg_dump failed (see above)"; cleanup_temp; return 1; }

  gzip -9 <"$dump" | $ENC -salt -pass env:BACKUP_PASSPHRASE -out "$part" \
    || { log_err "compress/encrypt failed"; cleanup_temp; return 1; }
  rm -f -- "$dump"

  size=$(wc -c <"$part" | tr -d ' ')
  if [ "$size" -lt "$BACKUP_MIN_BYTES" ]; then
    log_err "result is only $size bytes (floor $BACKUP_MIN_BYTES): refusing to keep it"
    cleanup_temp; return 1
  fi

  # Verify the finished file the way a restore would read it.
  $ENC -d -pass env:BACKUP_PASSPHRASE -in "$part" | gunzip -t \
    || { log_err "verification failed: file does not decrypt and gunzip cleanly"; cleanup_temp; return 1; }
  $ENC -d -pass env:BACKUP_PASSPHRASE -in "$part" | gunzip -c | tail -n 5 \
    | grep -q 'PostgreSQL database dump complete' \
    || { log_err "verification failed: dump is incomplete (no completion footer)"; cleanup_temp; return 1; }

  mv "$part" "$out" || { log_err "could not move the verified file into place"; cleanup_temp; return 1; }

  printf 'last_success=%s\nfile=%s\nbytes=%s\n' "$ts" "$(basename "$out")" "$size" >"$BACKUP_DIR/STATUS.tmp" \
    && mv "$BACKUP_DIR/STATUS.tmp" "$BACKUP_DIR/STATUS" \
    || echo "warning: could not write STATUS" >&2

  prune
  echo "backup written: $(basename "$out") ($size bytes, verified)"
}

mkdir -p "$BACKUP_DIR"
trap cleanup_temp EXIT
if [ "${1:-}" = "now" ]; then
  backup || exit 1
  exit 0
fi
while true; do
  if [ "$(date -u +%H)" -eq "$BACKUP_HOUR" ]; then
    backup || echo "backup FAILED (nightly run); older backups were kept" >&2
    sleep 3600
  fi
  sleep 300
done
