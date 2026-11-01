#!/usr/bin/env bash
# Linux-only disposable local rehearsal. Refuses all pre-existing target resources.
set -euo pipefail
umask 077
FC_REPO="$(cd "$(dirname "$0")/../../.." && pwd -P)"
export FC_COMPOSE_PROJECT=interview-flashcards-p107-rehearsal
for FC_PROJECT in "$FC_COMPOSE_PROJECT" interview-flashcards-recovery-check; do
    for FC_KIND in container volume network; do
        FC_ARGS=(ls --quiet --filter "label=com.docker.compose.project=$FC_PROJECT")
        if [[ "$FC_KIND" == container ]]; then FC_ARGS+=(--all); fi
        test -z "$(docker "$FC_KIND" "${FC_ARGS[@]}" </dev/null)" || {
            echo "Refusing existing project $FC_PROJECT ($FC_KIND). Run on a disposable local Docker engine." >&2
            exit 1
        }
    done
done
mkdir -p "$FC_REPO/output"
FC_APP="$(mktemp -d "$FC_REPO/output/p1-07-live-XXXXXXXX")"
mkdir -p "$FC_APP/deploy" "$FC_APP/tools" "$FC_APP/tls"
cp "$FC_REPO"/tools/{automatic-backup.py,server-snapshot.sh,check-server-snapshot.sh,recovery-check-config.py,snapshot-fingerprints.sql} "$FC_APP/tools/"
cp "$FC_REPO"/deploy/{compose.production.yml,nginx.production.conf.template,nginx-application.conf} "$FC_APP/deploy/"
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
    -keyout "$FC_APP/tls/privkey.pem" -out "$FC_APP/tls/fullchain.pem" \
    -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1 2>/dev/null
export FC_HOST_NGINX_CONFIG="$FC_APP/host-nginx.conf"
export FC_RECOVERY_CA_FILE="$FC_APP/tls/fullchain.pem"
echo '# disposable host configuration fixture' > "$FC_HOST_NGINX_CONFIG"
cat > "$FC_APP/deploy/.env.production" <<EOF
POSTGRES_DB=p107_rehearsal
POSTGRES_USER=p107_rehearsal
POSTGRES_PASSWORD=p107-disposable-only
ACCOUNT_USERNAME=p107-local
ACCOUNT_PASSWORD_HASH='\$2b\$10\$bmZ1VX907I3mUazhP8TJbOdol3mq3kLib5HaahT4Wm8ElhKzQ35ri'
BACKEND_IMAGE=${FC_TEST_BACKEND_IMAGE:-interview-flashcards-backend:p013-local}
WEB_IMAGE=${FC_TEST_WEB_IMAGE:-interview-flashcards-web:p013-local}
PUBLIC_HOST=localhost
WEB_BIND_ADDRESS=127.0.0.1
HTTP_PORT=18087
HTTPS_PORT=18447
TLS_CERT_DIR=$FC_APP/tls
EOF
python3 - "$FC_APP" <<'PY'
import json
from pathlib import Path
import sys
app = Path(sys.argv[1])
policy = dict(app_dir=str(app), backup_dir=str(app / 'backups'), keep_count=2,
              max_bytes=1073741824, min_free_bytes=67108864, staging_reserve_bytes=67108864,
              backup_max_age_hours=36, verify_max_age_hours=192)
(app / 'deploy/backup-policy.json').write_text(json.dumps(policy))
PY
fc_test_compose() {
    docker compose -p "$FC_COMPOSE_PROJECT" --env-file "$FC_APP/deploy/.env.production" \
        -f "$FC_APP/deploy/compose.production.yml" "$@" </dev/null
}
fc_test_compose up -d --no-build --pull never --wait --wait-timeout 240
# Nonempty Unicode/history-like rows and the real source PDF exercise full DB/file restore.
fc_test_compose exec -T db psql -U p107_rehearsal -d p107_rehearsal -v ON_ERROR_STOP=1 -c \
    "CREATE TABLE p107_probe(id integer PRIMARY KEY, notes text, due_date date); INSERT INTO p107_probe VALUES (1, '笔记与恢复验证', '2026-10-01'), (2, 'preserve historical dates', '2026-12-01');"
fc_test_compose cp "$FC_REPO/docs/Java+agent八股(高频版).pdf" backend:/data/files/p107-source.pdf
fc_test_compose exec -T --user 0 backend sh -c 'chown app:app /data/files/p107-source.pdf; echo temporary-probe > /data/tmp/p107-probe.txt; chown app:app /data/tmp/p107-probe.txt'
fc_test_backup() { python3 "$FC_APP/tools/automatic-backup.py" "$1" --config "$FC_APP/deploy/backup-policy.json"; }
# First backup includes real bootstrap restore; subsequent backups exercise count retention.
fc_test_backup backup
fc_test_backup backup
fc_test_backup backup
fc_test_backup verify
fc_test_backup status > "$FC_APP/result.json"
python3 - "$FC_APP/result.json" <<'PY'
import json
import sys
result = json.load(open(sys.argv[1]))
assert result['healthy'] and result['current_storage']['count'] == 2
assert len(result['storage']['removed']) == 1
assert result['last_verified']['label'] == result['last_backup']['label']
print('P1-07: real snapshots, retention, full database/files match and restored HTTPS health PASS')
PY
fc_test_compose down --volumes --timeout 60
echo "REHEARSAL_EVIDENCE: $FC_APP"
