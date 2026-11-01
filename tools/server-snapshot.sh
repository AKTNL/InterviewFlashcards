#!/usr/bin/env bash
# Run on the Debian host: bash tools/server-snapshot.sh SNAPSHOT_LABEL
# The label names an empty directory inside the application's output/ folder.
set -euo pipefail
umask 077

FC_APP_DIR="${FC_APP_DIR:-/srv/interview-flashcards}"
FC_APP_DIR="$(cd "$FC_APP_DIR" && pwd -P)"
FC_LABEL="${1:-server-snapshot-$(date -u +%Y%m%d-%H%M%S)}"
FC_HOST_NGINX_CONFIG="${FC_HOST_NGINX_CONFIG:-/etc/nginx/sites-available/interview-flashcards}"

if [[ ! "$FC_LABEL" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}$ ]]; then
    echo "Invalid snapshot label." >&2
    exit 1
fi

FC_SNAPSHOT_DIR="$FC_APP_DIR/output/$FC_LABEL"
for FC_REQUIRED in deploy/.env.production deploy/compose.production.yml \
    deploy/nginx.production.conf.template deploy/nginx-application.conf \
    tools/snapshot-fingerprints.sql; do
    test -r "$FC_APP_DIR/$FC_REQUIRED" || { echo "Required file is not readable: $FC_REQUIRED" >&2; exit 1; }
done
test -r "$FC_HOST_NGINX_CONFIG" || { echo "Host Nginx configuration is not readable." >&2; exit 1; }
command -v docker >/dev/null

mkdir -p "$FC_APP_DIR/output"
if [[ -e "$FC_SNAPSHOT_DIR" ]]; then
    test -d "$FC_SNAPSHOT_DIR" || { echo "Snapshot destination is not a directory." >&2; exit 1; }
    if [[ -n "$(find "$FC_SNAPSHOT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
        echo "Snapshot directory is not empty; choose a new label." >&2
        exit 1
    fi
else
    mkdir "$FC_SNAPSHOT_DIR"
fi
chmod 700 "$FC_SNAPSHOT_DIR"

fcprod() {
    docker compose -p interview-flashcards \
        --env-file "$FC_APP_DIR/deploy/.env.production" \
        -f "$FC_APP_DIR/deploy/compose.production.yml" "$@" </dev/null
}

FC_STOP_ATTEMPTED=0
fc_finish() {
    local FC_RESULT=$?
    trap - EXIT
    if [[ "$FC_STOP_ATTEMPTED" == 1 ]]; then
        if ! fcprod up -d --no-build --pull never --wait --wait-timeout 240; then
            echo "Service restart failed; inspect production status before continuing." >&2
            FC_RESULT=1
        fi
    fi
    if [[ "$FC_RESULT" == 0 ]]; then
        printf 'SNAPSHOT_CREATED: %s\n' "$FC_SNAPSHOT_DIR"
    else
        echo "Snapshot was not completed successfully; preserve partial files for inspection." >&2
    fi
    exit "$FC_RESULT"
}
trap fc_finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

FC_STOP_ATTEMPTED=1
fcprod stop web backend

fcprod exec -T --interactive=false db sh -c \
    'umask 077; pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/p013-server-snapshot.dump'
fcprod exec -T --interactive=false db pg_restore --list /tmp/p013-server-snapshot.dump >/dev/null
fcprod cp db:/tmp/p013-server-snapshot.dump "$FC_SNAPSHOT_DIR/database.dump"

cp "$FC_APP_DIR/tools/snapshot-fingerprints.sql" "$FC_SNAPSHOT_DIR/table-fingerprints.sql"
fcprod cp "$FC_SNAPSHOT_DIR/table-fingerprints.sql" db:/tmp/p013-snapshot-fingerprints.sql
fcprod exec -T --interactive=false db sh -c \
    'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/p013-snapshot-fingerprints.sql' \
    > "$FC_SNAPSHOT_DIR/table-fingerprints.txt"

fcprod run --rm --no-deps --pull never --interactive=false \
    --user 0 --entrypoint sh \
    -e FC_BACKUP_UID="$(id -u)" -e FC_BACKUP_GID="$(id -g)" \
    -v "$FC_SNAPSHOT_DIR:/backup" backend -ec '
        umask 077
        tar czf /backup/files.tgz -C /data/files .
        tar czf /backup/temporary-files.tgz -C /data/tmp .
        (cd /data/files; find . -type f -exec sha256sum {} \; | LC_ALL=C sort) > /backup/files.sha256
        (cd /data/tmp; find . -type f -exec sha256sum {} \; | LC_ALL=C sort) > /backup/temporary-files.sha256
        chown "$FC_BACKUP_UID:$FC_BACKUP_GID" /backup/files.tgz /backup/temporary-files.tgz /backup/files.sha256 /backup/temporary-files.sha256
    '

cp -p "$FC_APP_DIR/deploy/.env.production" "$FC_SNAPSHOT_DIR/.env.production"
cp "$FC_APP_DIR/deploy/compose.production.yml" \
    "$FC_APP_DIR/deploy/nginx.production.conf.template" \
    "$FC_APP_DIR/deploy/nginx-application.conf" "$FC_SNAPSHOT_DIR/"
cp "$FC_HOST_NGINX_CONFIG" "$FC_SNAPSHOT_DIR/host-nginx.conf"
if [[ -r "$FC_APP_DIR/release-manifest.json" ]]; then
    cp "$FC_APP_DIR/release-manifest.json" "$FC_SNAPSHOT_DIR/"
fi
fcprod images > "$FC_SNAPSHOT_DIR/images.txt"

cd "$FC_SNAPSHOT_DIR"
tar tzf files.tgz >/dev/null
tar tzf temporary-files.tgz >/dev/null
find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
    | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS
sha256sum -c SHA256SUMS
