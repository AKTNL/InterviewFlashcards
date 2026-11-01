"""Exercise retention/data-loss boundaries with actual files and injected tool failures."""
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('automatic_backup', Path(__file__).with_name('automatic-backup.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = {'app_dir': self.root / 'app', 'backup_dir': self.root / 'backups',
                       'keep_count': 2, 'max_bytes': 1000000, 'min_free_bytes': 1,
                       'staging_reserve_bytes': 1, 'backup_max_age_hours': 36,
                       'verify_max_age_hours': 192}
        self.scheduler = MODULE.Scheduler(self.config)

    def snapshot(self, index):
        label = f'auto-202611{index:02d}T040000Z-1234abcd'
        path = self.scheduler.snapshots / label
        path.mkdir()
        for name in MODULE.REQUIRED:
            (path / name).write_text('test payload ' + name)
        self.rehash(path)
        (path / 'SNAPSHOT_COMPLETE').touch()
        return label, path

    def rehash(self, path):
        manifest = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ./' + p.name + '\n'
                           for p in sorted(path.iterdir()) if p.name not in {'SHA256SUMS', 'SNAPSHOT_COMPLETE'})
        (path / 'SHA256SUMS').write_text(manifest)

    def verify_point(self, label):
        self.scheduler.state['last_verified'] = {'label': label, 'at': MODULE.now()}

    def test_count_retention_preserves_latest_and_last_actually_restored(self):
        first, first_path = self.snapshot(1)
        second, second_path = self.snapshot(2)
        latest, latest_path = self.snapshot(3)
        self.verify_point(first)
        self.scheduler.prune(latest)
        self.assertTrue(first_path.exists())
        self.assertFalse(second_path.exists())
        self.assertTrue(latest_path.exists())

    def test_byte_retention_removes_only_eligible_old_snapshot(self):
        first, first_path = self.snapshot(1)
        second, _ = self.snapshot(2)
        latest, _ = self.snapshot(3)
        self.verify_point(second)
        self.config['keep_count'] = 9
        self.config['max_bytes'] = 2 * MODULE.tree_bytes(first_path)
        self.scheduler.prune(latest)
        self.assertFalse(first_path.exists())

    def test_unachievable_budget_reports_failure_and_preserves_both_recovery_points(self):
        first, first_path = self.snapshot(1)
        latest, latest_path = self.snapshot(2)
        self.verify_point(first)
        self.config['max_bytes'] = 1
        with self.assertRaisesRegex(RuntimeError, 'Retention limits'):
            self.scheduler.prune(latest)
        self.assertTrue(first_path.exists() and latest_path.exists())

    def test_incomplete_foreign_and_corrupt_snapshots_are_preserved_and_reported(self):
        latest, _ = self.snapshot(3)
        _, broken = self.snapshot(1)
        (broken / 'files.tgz').write_bytes(b'corrupted')
        partial = self.scheduler.snapshots / 'auto-20261102T040000Z-1234abcd'
        partial.mkdir()
        (partial / 'database.dump').write_bytes(b'partial')
        foreign = self.scheduler.snapshots / 'manual-snapshot'
        foreign.mkdir()
        self.verify_point(latest)
        with self.assertRaisesRegex(RuntimeError, 'Incomplete or modified'):
            self.scheduler.prune(latest)
        self.assertTrue(broken.exists() and partial.exists() and foreign.exists())
        self.assertEqual(len(self.scheduler.state['storage']['unmanaged_or_incomplete']), 3)

    def test_low_space_does_not_stop_services_or_delete_snapshots(self):
        label, path = self.snapshot(1)
        self.verify_point(label)
        self.config['min_free_bytes'] = 10**30
        with patch.object(self.scheduler, 'run_tool') as tool:
            with self.assertRaisesRegex(RuntimeError, 'Insufficient free space'):
                self.scheduler.execute('backup')
        tool.assert_not_called()
        self.assertTrue(path.exists())
        self.assertEqual(json.loads(self.scheduler.state_path.read_text())['backup']['status'], 'failed')

    def test_tool_failure_keeps_previous_success_and_skips_retention(self):
        label, path = self.snapshot(1)
        self.verify_point(label)
        self.scheduler.state['last_backup'] = {'label': label, 'at': MODULE.now()}
        with patch.object(self.scheduler, 'run_tool', side_effect=RuntimeError('dump failed')):
            with patch.object(self.scheduler, 'prune') as prune:
                with self.assertRaisesRegex(RuntimeError, 'dump failed'):
                    self.scheduler.execute('backup')
        prune.assert_not_called()
        self.assertTrue(path.exists())
        self.assertEqual(self.scheduler.state['last_backup']['label'], label)

    def test_first_backup_must_pass_actual_recovery_before_pruning(self):
        def tool(name, label, log):
            if name == 'server-snapshot.sh':
                _, fixture = self.snapshot(1)
                fixture.rename(self.scheduler.snapshots / label)
            else:
                raise RuntimeError('restore failed')
        with patch.object(self.scheduler, 'run_tool', side_effect=tool) as run:
            with patch.object(self.scheduler, 'prune') as prune:
                with self.assertRaisesRegex(RuntimeError, 'restore failed'):
                    self.scheduler.execute('backup')
        self.assertEqual(run.call_count, 2)
        prune.assert_not_called()
        self.assertNotIn('last_verified', self.scheduler.state)
        self.assertEqual(self.scheduler.state['backup']['status'], 'failed')

    def test_manifest_requires_full_coverage_and_forbids_traversal(self):
        _, path = self.snapshot(1)
        (path / 'untracked').write_text('not covered')
        with self.assertRaises(ValueError):
            MODULE.verify_snapshot(path)
        (path / 'untracked').unlink()
        with (path / 'SHA256SUMS').open('a') as stream:
            stream.write('0' * 64 + '  ./../outside\n')
        with self.assertRaises(ValueError):
            MODULE.verify_snapshot(path)

    def test_failed_periodic_drill_preserves_last_verified_point_and_records_failure(self):
        old, _ = self.snapshot(1)
        latest, _ = self.snapshot(2)
        self.verify_point(old)
        with patch.object(self.scheduler, 'run_tool', side_effect=RuntimeError('recovery unhealthy')):
            with self.assertRaisesRegex(RuntimeError, 'recovery unhealthy'):
                self.scheduler.execute('verify')
        self.assertEqual(self.scheduler.state['last_verified']['label'], old)
        self.assertEqual(self.scheduler.state['verify']['status'], 'failed')
        self.assertTrue((self.scheduler.snapshots / latest).exists())

    def test_status_detects_overdue_backup_and_restore_and_recent_failure(self):
        label, _ = self.snapshot(1)
        old = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
        self.scheduler.state.update(last_backup={'label': label, 'at': old},
                                    last_verified={'label': label, 'at': old},
                                    verify={'status': 'failed'})
        with patch('builtins.print') as output:
            self.assertEqual(self.scheduler.report(), 1)
        report = json.loads(output.call_args.args[0])
        self.assertIn('backup: overdue', report['problems'])
        self.assertIn('verify: overdue', report['problems'])

    @unittest.skipUnless(__import__('os').name == 'posix', 'Debian flock test')
    def test_concurrent_runner_is_rejected(self):
        with MODULE.maintenance_lock(self.scheduler.root):
            with self.assertRaises(BlockingIOError):
                with MODULE.maintenance_lock(self.scheduler.root):
                    self.fail('Concurrent lock unexpectedly acquired')

    @unittest.skipUnless(__import__('os').name == 'posix', 'Symlink permissions on Windows differ')
    def test_retention_never_follows_symlink_outside_backup_root(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'preserve').write_text('keep')
        (self.scheduler.snapshots / 'auto-20261101T040000Z-1234abcd').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.scheduler.prune('auto-20261102T040000Z-1234abcd')
        self.assertEqual((outside / 'preserve').read_text(), 'keep')

    def test_config_rejects_too_few_copies_and_dangerous_root(self):
        (self.config['app_dir'] / 'tools').mkdir(parents=True)
        (self.config['app_dir'] / 'tools/server-snapshot.sh').touch()
        config = dict(self.config, app_dir=str(self.config['app_dir']), backup_dir=str(self.config['backup_dir']))
        file = self.root / 'policy.json'
        for update in [{'keep_count': 1}, {'backup_dir': str(self.root)}, {'max_bytes': True}]:
            file.write_text(json.dumps(dict(config, **update)))
            with self.assertRaises(ValueError):
                MODULE.load_config(file)


if __name__ == '__main__':
    unittest.main()
