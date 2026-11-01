"""Debian host backup scheduler. Standard library only; never writes into live data volumes."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


LABEL = re.compile(r'auto-\d{8}T\d{6}Z-[a-f0-9]{8}')
REQUIRED = {'database.dump', 'files.tgz', 'temporary-files.tgz', 'files.sha256',
            'temporary-files.sha256', 'table-fingerprints.sql', 'table-fingerprints.txt',
            '.env.production', 'compose.production.yml', 'compose.images.json',
            'nginx.production.conf.template', 'nginx-application.conf', 'images.txt',
            'host-nginx.conf', 'image-db.txt', 'image-backend.txt', 'image-web.txt'}


def now():
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)


def load_config(path):
    config = json.loads(path.read_text(encoding='utf-8'))
    allowed = {'app_dir', 'backup_dir', 'keep_count', 'max_bytes', 'min_free_bytes',
               'staging_reserve_bytes', 'backup_max_age_hours', 'verify_max_age_hours'}
    if set(config) != allowed:
        raise ValueError('Configuration keys must match the example')
    for key in allowed - {'app_dir', 'backup_dir'}:
        if type(config[key]) is not int or config[key] <= 0:
            raise ValueError('Expected a positive integer: ' + key)
    if config['keep_count'] < 2:
        raise ValueError('keep_count must be at least two')
    for key in ('app_dir', 'backup_dir'):
        path_value = Path(config[key])
        if not path_value.is_absolute() or path_value.is_symlink():
            raise ValueError('Expected an absolute non-symlink directory: ' + key)
        config[key] = path_value.resolve()
    app, root = config['app_dir'], config['backup_dir']
    if root == app or root in app.parents or root.is_relative_to(app / 'deploy'):
        raise ValueError('Backup directory must be separate from application/configuration')
    if not (app / 'tools/server-snapshot.sh').is_file():
        raise ValueError('Application tools not found')
    return config


def tree_bytes(root):
    total = 0
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name
            if path.is_symlink():
                raise ValueError('Symlink inside managed backup storage')
        total += sum((Path(directory) / name).stat().st_size for name in files)
    return total


def verify_snapshot(path):
    """Rehash every saved file; only complete, entirely covered snapshots can be pruned."""
    if path.is_symlink() or not (path / 'SNAPSHOT_COMPLETE').is_file():
        raise ValueError('Snapshot is incomplete')
    entries = {}
    for line in (path / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  \./([^/\\]+)', line)
        if not match or match[2] in {'.', '..', 'SHA256SUMS', 'SNAPSHOT_COMPLETE'} or match[2] in entries:
            raise ValueError('Invalid checksum manifest')
        entries[match[2]] = match[1]
    actual = {entry.name for entry in path.iterdir()}
    if not REQUIRED <= entries.keys() or actual != set(entries) | {'SHA256SUMS', 'SNAPSHOT_COMPLETE'}:
        raise ValueError('Snapshot manifest does not cover all files')
    for name, expected in entries.items():
        source = path / name
        if source.is_symlink() or not source.is_file():
            raise ValueError('Snapshot member is not a regular file')
        digest = hashlib.sha256()
        with source.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise ValueError('Snapshot checksum mismatch')
    return tree_bytes(path)


@contextmanager
def maintenance_lock(root):
    # The runner serializes backup/retention/verification/status updates. The shell tools
    # also share server-maintenance.lock with their manual entry points.
    import fcntl
    with (root / 'scheduler.lock').open('a') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


class Scheduler:
    def __init__(self, config):
        self.config = config
        self.root = config['backup_dir']
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root.chmod(0o700)
        self.snapshots = self.root / 'snapshots'
        self.logs = self.root / 'logs'
        for path in (self.snapshots, self.logs):
            if path.is_symlink():
                raise ValueError('Managed directory cannot be a symlink')
            path.mkdir(exist_ok=True, mode=0o700)
        self.state_path = self.root / 'status.json'
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {}

    def save(self):
        atomic_json(self.state_path, self.state)

    def inventory(self):
        completed = []
        issues = []
        for path in sorted(self.snapshots.iterdir()):
            if not LABEL.fullmatch(path.name) or not path.is_dir() or path.is_symlink():
                issues.append(path.name)
                continue
            try:
                size = verify_snapshot(path)
                completed.append((path.name, size))
            except (ValueError, OSError):
                issues.append(path.name)
        return completed, issues

    def run_tool(self, name, label, log):
        env = dict(os.environ, FC_APP_DIR=str(self.config['app_dir']),
                   FC_SNAPSHOT_ROOT=str(self.snapshots))
        # No timeout here: killing pg_dump/tar alone could leave writes active. systemd
        # allows the script's EXIT trap to restart production on normal failure/TERM.
        with log.open('ab') as stream:
            result = subprocess.run(['bash', str(self.config['app_dir'] / 'tools' / name), label],
                                    env=env, stdin=subprocess.DEVNULL, stdout=stream, stderr=stream)
        if result.returncode:
            raise RuntimeError(name + ' failed; see restricted log')

    def check(self, label, log):
        verify_snapshot(self.snapshots / label)
        self.run_tool('check-server-snapshot.sh', label, log)
        self.state['last_verified'] = {'label': label, 'at': now()}
        self.save()

    def prune(self, newest):
        completed, issues = self.inventory()
        # Failure debris and modified/unrecognized directories are counted but never
        # deleted automatically. Only exact, validated auto-* children are eligible.
        protected = {newest, self.state.get('last_verified', {}).get('label')}
        used = tree_bytes(self.snapshots)
        survivors = list(completed)
        removed = []
        for label, size in completed:
            if len(survivors) <= self.config['keep_count'] and used <= self.config['max_bytes']:
                break
            if label in protected:
                continue
            target = self.snapshots / label
            if target.parent.resolve() != self.snapshots.resolve() or target.is_symlink():
                raise ValueError('Unsafe retention target')
            shutil.rmtree(target)
            survivors.remove((label, size))
            removed.append(label)
            used -= size
        self.state['storage'] = {'count': len(survivors), 'bytes': used,
                                 'free_bytes': shutil.disk_usage(self.root).free,
                                 'unmanaged_or_incomplete': issues, 'removed': removed}
        self.save()
        if used > self.config['max_bytes'] or len(survivors) > self.config['keep_count']:
            raise RuntimeError('Retention limits cannot be met while preserving newest and last verified snapshots')
        if issues:
            raise RuntimeError('Incomplete or modified snapshots require inspection; they were preserved')

    def execute(self, action):
        run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
        label = 'auto-' + run_id
        log = self.logs / (run_id + '.log')
        self.state[action] = {'status': 'running', 'started_at': now(), 'log': log.name}
        self.save()
        try:
            completed, _ = self.inventory()
            estimate = max(self.config['staging_reserve_bytes'],
                           int(completed[-1][1] * 1.2) if completed else 0)
            if shutil.disk_usage(self.root).free < self.config['min_free_bytes'] + estimate:
                raise RuntimeError('Insufficient free space; no snapshot was deleted and production was not stopped')
            if action == 'backup':
                self.run_tool('server-snapshot.sh', label, log)
                verify_snapshot(self.snapshots / label)
                self.state['last_backup'] = {'label': label, 'at': now()}
                self.save()
                # Bootstrap with an actual restoration, not just checksums. Recheck a
                # missing/corrupt previous recovery point before allowing retention.
                verified = self.state.get('last_verified', {}).get('label')
                if verified not in {item[0] for item in completed}:
                    self.check(label, log)
                self.prune(label)
            else:
                if not completed:
                    raise RuntimeError('No complete snapshot is available for a recovery drill')
                label = completed[-1][0]
                self.check(label, log)
            self.state[action].update(status='ok', label=label, finished_at=now())
            self.state[action].pop('error', None)
        except (OSError, ValueError, RuntimeError) as error:
            self.state[action].update(status='failed', finished_at=now(), error=str(error))
            raise
        finally:
            self.save()
            # Keep bounded run logs. Journald also records exit status and scheduling.
            for old in sorted(self.logs.glob('*.log'))[:-20]:
                if not old.is_symlink():
                    old.unlink()

    def report(self):
        completed, issues = self.inventory()
        problems = []
        for action, last, max_age in [('backup', 'last_backup', 'backup_max_age_hours'),
                                      ('verify', 'last_verified', 'verify_max_age_hours')]:
            value = self.state.get(last)
            if not value or value['label'] not in {item[0] for item in completed}:
                problems.append(action + ': no valid successful result')
            elif (datetime.now(timezone.utc) - datetime.fromisoformat(value['at'])).total_seconds() > self.config[max_age] * 3600:
                problems.append(action + ': overdue')
            if self.state.get(action, {}).get('status') in {'failed', 'running'}:
                problems.append(action + ': failed or interrupted/running; inspect status and journal')
        used, free = tree_bytes(self.snapshots), shutil.disk_usage(self.root).free
        if issues or used > self.config['max_bytes'] or len(completed) > self.config['keep_count'] or free < self.config['min_free_bytes']:
            problems.append('storage: needs attention')
        report = dict(self.state, healthy=not problems, problems=problems,
                      current_storage={'count': len(completed), 'bytes': used, 'free_bytes': free,
                                       'unmanaged_or_incomplete': issues})
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if not problems else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['backup', 'verify', 'status'])
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        config = load_config(args.config)
        root = config['backup_dir']
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with maintenance_lock(root):
            scheduler = Scheduler(config)
            if args.action == 'status':
                return scheduler.report()
            scheduler.execute(args.action)
        print('Automatic ' + args.action + ': OK')
        return 0
    except BlockingIOError:
        print('Another automatic backup operation is active', file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError) as error:
        print('Automatic backup: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
