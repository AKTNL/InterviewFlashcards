"""Offline checks for snapshot orchestration; Docker is replaced by a stdin-reading stub."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BASH = (Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe'
        if os.name == 'nt' else Path(shutil.which('bash') or '/bin/bash'))

DOCKER_STUB = r'''#!/usr/bin/env bash
set -eu
# Reproduce the problematic CLI behavior: read all available standard input.
cat >/dev/null
if [[ "$1" == inspect ]]; then
    test "${FC_FAIL_STAGE:-}" != image || exit 53
    printf 'sha256:%064d\n' 1
    exit
fi
test "$1" = compose
FC_ACTION="${8}"
shift 8
echo "$FC_ACTION" >> "$FC_APP_DIR/docker-calls.txt"
case "$FC_ACTION" in
  stop) ;;
  up) test "${FC_FAIL_STAGE:-}" != restart ;;
  exec)
    case "$*" in
      *pg_dump*)
        test "${FC_FAIL_STAGE:-}" != dump || exit 51
        echo 'test database dump' > "$FC_APP_DIR/fake-database.dump"
        ;;
      *pg_restore*) ;;
      *psql*) echo 'app_user|1|test-fingerprint' ;;
      *) exit 90 ;;
    esac
    ;;
  cp)
    if [[ "$1" == db:* ]]; then
      cp "$FC_APP_DIR/fake-database.dump" "$2"
    else
      test -r "$1"
    fi
    ;;
  run)
    test "${FC_FAIL_STAGE:-}" != files || exit 52
    FC_BACKUP=''
    while (( $# )); do
      if [[ "$1" == -v ]]; then FC_BACKUP="${2%:/backup}"; shift 2; else shift; fi
    done
    test -n "$FC_BACKUP"
    tar czf "$FC_BACKUP/files.tgz" -C "$FC_APP_DIR/test-files" .
    tar czf "$FC_BACKUP/temporary-files.tgz" -C "$FC_APP_DIR/test-temporary-files" .
    (cd "$FC_APP_DIR/test-files"; find . -type f -exec sha256sum {} \; | sort) > "$FC_BACKUP/files.sha256"
    : > "$FC_BACKUP/temporary-files.sha256"
    ;;
  images) echo 'test-backend image-id' ;;
  ps) echo 'test-container-id' ;;
  *) exit 91 ;;
esac
'''


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        staging = ROOT / 'output/release'
        staging.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='snapshot-test-', dir=staging)
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name).resolve()
        assert self.app.is_relative_to(ROOT)
        for name in ['deploy', 'tools', 'bin', 'test-files', 'test-temporary-files']:
            (self.app / name).mkdir()
        for name in ['.env.production', 'compose.production.yml',
                     'nginx.production.conf.template', 'nginx-application.conf']:
            (self.app / 'deploy' / name).write_text('test fixture\n')
        (self.app / 'host-nginx.conf').write_text('test fixture\n')
        (self.app / 'test-files/source.pdf').write_bytes(b'test PDF bytes')
        shutil.copyfile(ROOT / 'tools/snapshot-fingerprints.sql', self.app / 'tools/snapshot-fingerprints.sql')
        stub = self.app / 'bin/docker'
        stub.write_text(DOCKER_STUB, encoding='utf-8', newline='\n')
        stub.chmod(0o755)
        self.env = dict(os.environ)
        self.env.pop('BASH_ENV', None)
        self.env['PATH'] = str(self.app / 'bin') + os.pathsep + self.env['PATH']
        self.env['FC_APP_DIR'] = self.shell_path(self.app)
        self.env['FC_HOST_NGINX_CONFIG'] = self.shell_path(self.app / 'host-nginx.conf')

    def shell_path(self, path):
        if os.name != 'nt':
            return str(path)
        return subprocess.check_output([str(BASH), '-c', 'cygpath -u "$1"', '_', str(path)], text=True).strip()

    def run_snapshot(self, stage=''):
        self.env['FC_FAIL_STAGE'] = stage
        script = (ROOT / 'tools/server-snapshot.sh').read_text(encoding='utf-8')
        # Execute from stdin, matching the original failure mode.
        return subprocess.run([str(BASH), '-s', '--', 'test-snapshot'], input=script,
                              env=self.env, text=True, capture_output=True, timeout=30)

    def calls(self):
        log = self.app / 'docker-calls.txt'
        return log.read_text().splitlines() if log.exists() else []

    def test_stdin_reading_docker_does_not_truncate_snapshot(self):
        result = self.run_snapshot()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('SNAPSHOT_CREATED:', result.stdout)
        snapshot = self.app / 'output/test-snapshot'
        self.assertTrue((snapshot / 'SHA256SUMS').is_file())
        self.assertTrue((snapshot / '.env.production').is_file())
        self.assertTrue((snapshot / 'SNAPSHOT_COMPLETE').is_file())
        self.assertTrue((snapshot / 'compose.images.json').is_file())
        self.assertEqual(self.calls().count('up'), 1)
        self.assertEqual(self.calls()[-1], 'up')

    def test_dump_failure_restarts_services_and_reports_failure(self):
        result = self.run_snapshot('dump')
        self.assertEqual(result.returncode, 51, result.stdout + result.stderr)
        self.assertEqual(self.calls()[-1], 'up')
        self.assertNotIn('SNAPSHOT_CREATED:', result.stdout)
        self.assertFalse((self.app / 'output/test-snapshot/SNAPSHOT_COMPLETE').exists())

    def test_file_archive_failure_restarts_services_and_reports_failure(self):
        result = self.run_snapshot('files')
        self.assertEqual(result.returncode, 52, result.stdout + result.stderr)
        self.assertEqual(self.calls()[-1], 'up')
        self.assertNotIn('SNAPSHOT_CREATED:', result.stdout)

    def test_nonempty_snapshot_is_preserved_without_stopping_services(self):
        snapshot = self.app / 'output/test-snapshot'
        snapshot.mkdir(parents=True)
        original = snapshot / 'database.dump'
        original.write_bytes(b'preserve me')
        result = self.run_snapshot()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(original.read_bytes(), b'preserve me')
        self.assertEqual(self.calls(), [])

    def test_restart_failure_is_not_reported_as_snapshot_success(self):
        result = self.run_snapshot('restart')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Service restart failed', result.stderr)
        self.assertNotIn('SNAPSHOT_CREATED:', result.stdout)

    def test_image_capture_failure_restarts_services_without_completion_marker(self):
        result = self.run_snapshot('image')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls()[-1], 'up')
        self.assertFalse((self.app / 'output/test-snapshot/SNAPSHOT_COMPLETE').exists())

    @unittest.skipUnless(os.name == 'posix', 'Debian flock test')
    def test_manual_maintenance_lock_rejects_snapshot_before_stopping_services(self):
        import fcntl
        (self.app / 'output').mkdir()
        with (self.app / 'output/server-maintenance.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_snapshot()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('maintenance is already running', result.stderr)
        self.assertEqual(self.calls(), [])


if __name__ == '__main__':
    unittest.main()
