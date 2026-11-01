"""Check that recovery refuses configuration which could select production data."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location('recovery_config', Path(__file__).with_name('recovery-check-config.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
PROJECT = 'interview-flashcards-recovery-check'
APP = '/srv/interview-flashcards'
SNAPSHOT = APP + '/output/snapshot-test'


def fixture():
    def data_mount(name, target):
        return {'type': 'volume', 'source': name, 'target': target}

    config = {
        'name': PROJECT,
        'volumes': {name: {'name': PROJECT + '_' + name} for name in ['database', 'files', 'temporary-files']},
        'networks': {'default': {'name': PROJECT + '_default'}},
        'services': {
            'db': {'image': 'postgres:18.3-alpine', 'networks': {'default': None},
                   'environment': {'POSTGRES_PASSWORD': 'sensitive-should-not-print'},
                   'volumes': [data_mount('database', '/var/lib/postgresql')]},
            'backend': {'image': 'test-backend:release', 'networks': {'default': None},
                        'volumes': [data_mount('files', '/data/files'), data_mount('temporary-files', '/data/tmp')]},
            'web': {'image': 'test-web:release', 'networks': {'default': None},
                    'environment': {'PUBLIC_HOST': 'study.example.com'},
                    'ports': [{'host_ip': '127.0.0.1', 'published': '18082', 'target': 80, 'protocol': 'tcp'},
                              {'host_ip': '127.0.0.1', 'published': '18445', 'target': 443, 'protocol': 'tcp'}],
                    'volumes': [
                        {'type': 'bind', 'source': SNAPSHOT + '/nginx.production.conf.template', 'target': '/etc/nginx/templates/default.conf.template', 'read_only': True},
                        {'type': 'bind', 'source': SNAPSHOT + '/nginx-application.conf', 'target': '/etc/nginx/snippets/application.conf', 'read_only': True},
                        {'type': 'bind', 'source': APP + '/tls', 'target': '/etc/nginx/tls', 'read_only': True},
                    ]},
        },
    }
    return config


class RecoveryIsolationTest(unittest.TestCase):
    def check(self, config):
        return MODULE.validate_configuration(config, PROJECT, SNAPSHOT, APP)

    def test_expected_configuration_is_accepted_and_output_omits_secrets(self):
        result = self.check(fixture())
        self.assertEqual(result['domain'], 'study.example.com')
        self.assertNotIn('sensitive-should-not-print', str(result))

    def test_production_project_name_is_rejected(self):
        config = fixture()
        config['name'] = 'interview-flashcards'
        with self.assertRaises(ValueError):
            self.check(config)

    def test_production_or_external_volume_is_rejected(self):
        for change in [{'name': 'interview-flashcards_database'}, {'external': True}]:
            with self.subTest(change=change):
                config = fixture()
                config['volumes']['database'].update(change)
                with self.assertRaises(ValueError):
                    self.check(config)

    def test_bind_mount_for_database_is_rejected(self):
        config = fixture()
        config['services']['db']['volumes'] = [{'type': 'bind', 'source': '/production', 'target': '/var/lib/postgresql'}]
        with self.assertRaises(ValueError):
            self.check(config)

    def test_public_or_original_production_port_is_rejected(self):
        for change in [{'host_ip': '0.0.0.0'}, {'published': '18444'}]:
            with self.subTest(change=change):
                config = fixture()
                config['services']['web']['ports'][1].update(change)
                with self.assertRaises(ValueError):
                    self.check(config)

    def test_database_port_is_rejected(self):
        config = fixture()
        config['services']['db']['ports'] = [{'target': 5432, 'published': '5432'}]
        with self.assertRaises(ValueError):
            self.check(config)

    def test_shared_network_is_rejected(self):
        config = fixture()
        config['networks']['default']['name'] = 'interview-flashcards_default'
        with self.assertRaises(ValueError):
            self.check(config)

    def test_privileged_container_or_host_network_is_rejected(self):
        for change in [{'privileged': True}, {'network_mode': 'host'}, {'container_name': 'interview-flashcards-db-1'}]:
            with self.subTest(change=change):
                config = fixture()
                config['services']['db'].update(change)
                with self.assertRaises(ValueError):
                    self.check(config)

    def test_wrong_or_writable_web_bind_is_rejected(self):
        for change in [{'read_only': False}, {'source': '/etc'}, {'target': '/different'}]:
            with self.subTest(change=change):
                config = fixture()
                config['services']['web']['volumes'][0].update(change)
                with self.assertRaises(ValueError):
                    self.check(config)


if __name__ == '__main__':
    unittest.main()
