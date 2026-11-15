#!/bin/sh
# Tests for backup.sh, using a throwaway TimescaleDB container and synthetic data only.
#   sh deploy/backup/test-backup.sh            builds the backup image and runs backup.sh in it (what CI does)
#   TEST_HOST=1 sh deploy/backup/test-backup.sh  runs backup.sh with the host's sh/pg_dump/openssl instead
#                                                (for machines that cannot build the image)
# Cases: (1) unreachable DB fails loudly and prunes nothing, (2) a too-small result is rejected,
# (3) a good run writes a verified file, STATUS, and prunes, (4) the README restore steps work.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=timescale/timescaledb:2.17.2-pg16
DB=ti-backup-test-db
NET=ti-backup-test-net
BIMG=ti-backup-test-img
PORT=55432
PASS=throwaway-ci-passphrase # not a secret: only ever protects synthetic test data
WORK=$(mktemp -d)
BK="$WORK/backups"
mkdir -p "$BK"

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "TEST FAILED: $*" >&2; exit 1; }
sql() { docker exec -e PGPASSWORD=pw "$DB" psql -h 127.0.0.1 -U training -v ON_ERROR_STOP=1 -tA "$@"; }

# run_backup <PGHOST> [extra VAR=value ...]: runs `backup.sh now`, returns its exit status.
run_backup() {
  host=$1; shift
  if [ "${TEST_HOST:-}" = 1 ]; then
    [ "$host" = db ] && host=127.0.0.1
    env PGHOST="$host" PGPORT=$PORT POSTGRES_USER=training POSTGRES_PASSWORD=pw POSTGRES_DB=training \
      BACKUP_PASSPHRASE="$PASS" BACKUP_DIR="$BK" "$@" sh "$HERE/backup.sh" now
  else
    [ "$host" = db ] && host=$DB
    docker run --rm --network "$NET" -v "$BK:/backups" -e PGHOST="$host" -e POSTGRES_USER=training \
      -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=training -e BACKUP_PASSPHRASE="$PASS" "$@" "$BIMG" now
  fi
}
# Same, but with each extra argument turned into `-e` for docker mode.
backup_with() {
  host=$1; shift
  if [ "${TEST_HOST:-}" = 1 ]; then
    run_backup "$host" "$@"
  else
    set -- $(for a in "$@"; do printf -- '-e %s ' "$a"; done)
    run_backup "$host" "$@"
  fi
}

files() { ls -1 "$BK" | grep -E '^training-[0-9TZ]+\.sql\.gz\.enc$' || true; }
seed_old_files() {
  rm -f "$BK"/*
  : >"$BK/training-20200101T000000Z.sql.gz.enc"
  : >"$BK/training-20200102T000000Z.sql.gz.enc"
  touch -t 202001010000 "$BK/training-20200101T000000Z.sql.gz.enc"
  touch -t 202001020000 "$BK/training-20200102T000000Z.sql.gz.enc"
}

[ "${TEST_HOST:-}" = 1 ] || docker build -q -t "$BIMG" "$HERE" >/dev/null
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -p 127.0.0.1:$PORT:5432 \
  -e POSTGRES_DB=training -e POSTGRES_USER=training -e POSTGRES_PASSWORD=pw "$IMAGE" >/dev/null
i=0
until sql -d training -c 'select 1' >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -gt 60 ] && fail "database did not start"; sleep 2
done

# Synthetic data: a plain table and a hypertable, like the real schema uses.
sql -d training -c "
  CREATE TABLE athlete_test (id int PRIMARY KEY, note text);
  INSERT INTO athlete_test SELECT g, md5(g::text) FROM generate_series(1, 2000) g;
  CREATE TABLE sample_test (ts timestamptz NOT NULL, hr int);
  SELECT create_hypertable('sample_test', 'ts');
  INSERT INTO sample_test SELECT now() - (g || ' minutes')::interval, 60 + g % 100 FROM generate_series(1, 5000) g;" >/dev/null

echo "== 1. unreachable database: must fail, add no file, prune nothing"
seed_old_files
before=$(files)
if backup_with unreachable.invalid BACKUP_KEEP=1 BACKUP_MIN_BYTES=100 >"$WORK/out" 2>"$WORK/err"; then
  cat "$WORK/out" "$WORK/err" >&2; fail "backup exited 0 although the database is unreachable"
fi
[ "$(files)" = "$before" ] || fail "files changed after a failed run: $(files)"
[ "$(files | wc -l)" -eq 2 ] || fail "expected exactly the 2 old files"
ls "$BK" | grep -q '\.part$' && fail "leftover .part file"
[ -e "$BK/STATUS" ] && fail "STATUS written for a failed run"
grep -qi 'backup written' "$WORK/out" && fail "success message printed for a failed run"
[ -s "$WORK/err" ] || fail "nothing printed to stderr"
echo "ok"

echo "== 2. implausibly small result: must be rejected, prune nothing"
seed_old_files
if backup_with db BACKUP_KEEP=1 BACKUP_MIN_BYTES=999999999 >"$WORK/out" 2>"$WORK/err"; then
  fail "backup exited 0 although the result is below the size floor"
fi
[ "$(files | wc -l)" -eq 2 ] || fail "files changed after a rejected run: $(files)"
[ -s "$WORK/err" ] || fail "nothing printed to stderr"
echo "ok"

echo "== 3. good run: verified file, STATUS, prune after success"
seed_old_files
backup_with db BACKUP_KEEP=1 BACKUP_MIN_BYTES=500 >"$WORK/out" 2>"$WORK/err" || { cat "$WORK/err" >&2; fail "good run failed"; }
[ "$(files | wc -l)" -eq 1 ] || fail "expected only the newest file kept, got: $(files)"
new=$(files)
case "$new" in training-2020*) fail "kept an old file instead of the new one" ;; esac
[ -s "$BK/STATUS" ] || fail "STATUS missing"
grep -q "$new" "$BK/STATUS" || fail "STATUS does not name the new file"
cat "$BK/STATUS"
ls "$BK" | grep -q '\.part$' && fail "leftover .part file"
echo "ok"

echo "== 4. restore with the README steps into a second database; compare row counts"
sql -d postgres -c "CREATE DATABASE restore_check;" >/dev/null
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "pass:$PASS" -in "$BK/$new" -out "$WORK/restore.sql.gz"
sql -d restore_check -c "SELECT timescaledb_pre_restore();" >/dev/null
gunzip -c "$WORK/restore.sql.gz" | docker exec -i -e PGPASSWORD=pw "$DB" psql -h 127.0.0.1 -U training -d restore_check >/dev/null
sql -d restore_check -c "SELECT timescaledb_post_restore();" >/dev/null
for t in athlete_test sample_test; do
  a=$(sql -d training -c "SELECT count(*) FROM $t")
  b=$(sql -d restore_check -c "SELECT count(*) FROM $t")
  [ "$a" = "$b" ] && [ "$a" -gt 0 ] || fail "$t: original $a rows, restored $b"
  echo "$t: $a rows in both"
done
echo "ALL BACKUP TESTS PASSED"
