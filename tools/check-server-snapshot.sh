#!/usr/bin/env bash
# Restore a snapshot into fresh loopback-only resources and remove those resources after success.
set -euo pipefail
umask 077

FC_APP_DIR="${FC_APP_DIR:-/srv/interview-flashcards}"
FC_APP_DIR="$(cd "$FC_APP_DIR" && pwd -P)"
FC_LABEL="${1:?Usage: bash tools/check-server-snapshot.sh SNAPSHOT_LABEL}"
[[ "$FC_LABEL" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}$ ]] || { echo 'Invalid snapshot label.' >&2; exit 1; }
FC_SNAPSHOT_ROOT="${FC_SNAPSHOT_ROOT:-$FC_APP_DIR/output}"
FC_SNAPSHOT_ROOT="$(cd "$FC_SNAPSHOT_ROOT" && pwd -P)"
FC_SNAPSHOT_DIR="$FC_SNAPSHOT_ROOT/$FC_LABEL"
test ! -L "$FC_SNAPSHOT_DIR" || { echo 'Snapshot symlinks are not allowed.' >&2; exit 1; }
mkdir -p "$FC_APP_DIR/output"
exec 9>"$FC_APP_DIR/output/server-maintenance.lock"
flock -n 9 || { echo 'Server maintenance is already running.' >&2; exit 1; }
FC_PROJECT=interview-flashcards-recovery-check

for FC_REQUIRED in SHA256SUMS database.dump files.tgz temporary-files.tgz \
    files.sha256 temporary-files.sha256 table-fingerprints.sql table-fingerprints.txt \
    .env.production compose.production.yml nginx.production.conf.template nginx-application.conf; do
    test -r "$FC_SNAPSHOT_DIR/$FC_REQUIRED" || { echo "Snapshot file is not readable: $FC_REQUIRED" >&2; exit 1; }
done
test -r "$FC_APP_DIR/tools/recovery-check-config.py"
(cd "$FC_SNAPSHOT_DIR" && sha256sum -c SHA256SUMS)

fc_docker() { docker "$@" </dev/null; }
for FC_RESOURCE in container volume network; do
    if [[ "$FC_RESOURCE" == container ]]; then
        FC_EXISTING="$(fc_docker container ls --all --quiet --filter "label=com.docker.compose.project=$FC_PROJECT")"
    else
        FC_EXISTING="$(fc_docker "$FC_RESOURCE" ls --quiet --filter "label=com.docker.compose.project=$FC_PROJECT")"
    fi
    test -z "$FC_EXISTING" || { echo "Recovery resources already exist; inspect project $FC_PROJECT before continuing." >&2; exit 1; }
done

python3 - <<'PY'
import socket
for port in (18082, 18445):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', port))
PY

FC_RUN_DIR="$(mktemp -d "$FC_APP_DIR/output/recovery-check-XXXXXXXX")"
FC_RECOVERY_ENV="$FC_RUN_DIR/.env.recovery"
python3 - "$FC_SNAPSHOT_DIR/.env.production" "$FC_RECOVERY_ENV" <<'PY'
from pathlib import Path
import re
import sys
overrides = {'WEB_BIND_ADDRESS': '127.0.0.1', 'HTTP_PORT': '18082', 'HTTPS_PORT': '18445'}
lines = Path(sys.argv[1]).read_text(encoding='utf-8').splitlines()
for key, value in overrides.items():
    pattern = re.compile(r'^\s*' + key + r'\s*=')
    lines = [line for line in lines if not pattern.match(line)]
    lines.append(key + '=' + value)
Path(sys.argv[2]).write_text('\n'.join(lines) + '\n', encoding='utf-8')
PY
chmod 600 "$FC_RECOVERY_ENV"

fcrecovery() {
    fc_docker compose -p "$FC_PROJECT" --env-file "$FC_RECOVERY_ENV" \
        -f "$FC_SNAPSHOT_DIR/compose.production.yml" "${FC_IMAGE_ARGS[@]}" "$@"
}
FC_IMAGE_ARGS=()
# Older P0-13 snapshots remain usable; new automatic snapshots require pins.
if [[ -f "$FC_SNAPSHOT_DIR/compose.images.json" ]]; then
    FC_IMAGE_ARGS=(-f "$FC_SNAPSHOT_DIR/compose.images.json")
fi
fcrecovery config --format json \
    | python3 "$FC_APP_DIR/tools/recovery-check-config.py" "$FC_PROJECT" "$FC_SNAPSHOT_DIR" "$FC_APP_DIR" \
    > "$FC_RUN_DIR/isolation.json"
echo 'ISOLATION_CHECK: PASS'

FC_RECOVERY_STARTED=0
fc_recovery_finish() {
    local FC_RESULT=$?
    trap - EXIT
    if [[ "$FC_RESULT" != 0 && "$FC_RECOVERY_STARTED" == 1 ]]; then
        printf 'Recovery check stopped. Inspect isolated project %s; report directory: %s\n' "$FC_PROJECT" "$FC_RUN_DIR" >&2
    fi
    exit "$FC_RESULT"
}
trap fc_recovery_finish EXIT

FC_RECOVERY_STARTED=1
fcrecovery up -d --no-build --pull never --wait --wait-timeout 120 db
fcrecovery cp "$FC_SNAPSHOT_DIR/database.dump" db:/tmp/p013-recovery.dump
fcrecovery exec -T --interactive=false db sh -c \
    'exec pg_restore --exit-on-error --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/p013-recovery.dump'
fcrecovery cp "$FC_SNAPSHOT_DIR/table-fingerprints.sql" db:/tmp/p013-snapshot-fingerprints.sql
fcrecovery exec -T --interactive=false db sh -c \
    'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/p013-snapshot-fingerprints.sql' \
    > "$FC_RUN_DIR/table-fingerprints-restored.txt"
cmp "$FC_SNAPSHOT_DIR/table-fingerprints.txt" "$FC_RUN_DIR/table-fingerprints-restored.txt"
echo 'DATABASE_MATCH: PASS'

fcrecovery run --rm --no-deps --pull never --interactive=false \
    --user 0 --entrypoint sh -v "$FC_SNAPSHOT_DIR:/backup:ro" backend -ec '
        test "$(find /data/files -mindepth 1 | wc -l)" -eq 0
        test "$(find /data/tmp -mindepth 1 | wc -l)" -eq 0
        tar xzf /backup/files.tgz -C /data/files
        tar xzf /backup/temporary-files.tgz -C /data/tmp
        (cd /data/files; find . -type f -exec sha256sum {} \; | LC_ALL=C sort) > /tmp/restored-files.sha256
        (cd /data/tmp; find . -type f -exec sha256sum {} \; | LC_ALL=C sort) > /tmp/restored-temporary.sha256
        cmp /backup/files.sha256 /tmp/restored-files.sha256
        cmp /backup/temporary-files.sha256 /tmp/restored-temporary.sha256
        cd /data/files
        if [ -s /backup/files.sha256 ]; then
            sha256sum -c /backup/files.sha256 >/dev/null
        else
            test "$(find . -type f | wc -l)" -eq 0
        fi
        cd /data/tmp
        if [ -s /backup/temporary-files.sha256 ]; then
            sha256sum -c /backup/temporary-files.sha256 >/dev/null
        else
            test "$(find . -type f | wc -l)" -eq 0
        fi
        chown -R app:app /data/files /data/tmp
    '
echo 'FILES_MATCH: PASS'

fcrecovery up -d --no-build --pull never --wait --wait-timeout 240
FC_DOMAIN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["domain"])' "$FC_RUN_DIR/isolation.json")"
FC_CA_ARGS=()
if [[ -n "${FC_RECOVERY_CA_FILE:-}" ]]; then FC_CA_ARGS=(--cacert "$FC_RECOVERY_CA_FILE"); fi
curl --noproxy '*' --fail --silent --show-error \
    --connect-timeout 10 --max-time 30 "${FC_CA_ARGS[@]}" \
    --resolve "$FC_DOMAIN:18445:127.0.0.1" \
    "https://$FC_DOMAIN:18445/api/health" > "$FC_RUN_DIR/health.json"
python3 - "$FC_RUN_DIR/health.json" <<'PY'
import json
import sys
with open(sys.argv[1]) as stream:
    health = json.load(stream)
assert health['data']['status'] == 'UP' and health['data']['database'] == 'UP', 'Restored application is unhealthy'
print('RESTORED_HEALTH: PASS')
PY

fcrecovery down --volumes --timeout 60
FC_RECOVERY_STARTED=0
rm -f -- "$FC_RECOVERY_ENV"
printf 'RESTORE_CHECK: PASS\nRESTORE_REPORT_DIR: %s\n' "$FC_RUN_DIR"
