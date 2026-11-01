"""Check isolation of the resolved Compose configuration without printing credentials."""
import json
from pathlib import PurePosixPath
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_configuration(config, project, snapshot_dir, app_dir):
    require(project == 'interview-flashcards-recovery-check', 'Unexpected recovery project')
    require(config.get('name') == project, 'Compose project name does not match recovery project')
    volumes = config.get('volumes', {})
    require(set(volumes) == {'database', 'files', 'temporary-files'}, 'Unexpected volumes')
    for name, volume in volumes.items():
        require(not volume.get('external'), 'External volumes are not allowed')
        require(volume.get('name') == project + '_' + name, 'Volume belongs to another project')

    networks = config.get('networks', {})
    require(set(networks) == {'default'}, 'Unexpected networks')
    require(not networks['default'].get('external'), 'External network is not allowed')
    require(networks['default'].get('name') == project + '_default', 'Network belongs to another project')

    services = config.get('services', {})
    require(set(services) == {'db', 'backend', 'web'}, 'Unexpected services')
    for name, service in services.items():
        require(bool(service.get('image')), 'Missing image')
        require(not service.get('container_name'), 'Explicit container names are not allowed')
        require(not service.get('network_mode'), 'Network mode override is not allowed')
        require(not service.get('privileged'), 'Privileged containers are not allowed')
        require(set(service.get('networks', {})) == {'default'}, 'Service joins an unexpected network')
        if name != 'web':
            require(not service.get('ports'), 'Database/backend ports must remain unpublished')

    expected_data = {
        'db': {('database', '/var/lib/postgresql')},
        'backend': {('files', '/data/files'), ('temporary-files', '/data/tmp')},
    }
    for name, expected in expected_data.items():
        mounts = services[name].get('volumes', [])
        require(all(mount.get('type') == 'volume' for mount in mounts), 'Data must use isolated named volumes')
        require(len(mounts) == len(expected), 'Unexpected data mounts')
        require({(mount.get('source'), mount.get('target')) for mount in mounts} == expected, 'Wrong data volume mounts')

    ports = services['web'].get('ports', [])
    require(len(ports) == 2, 'Unexpected Web ports')
    actual_ports = {(port.get('host_ip'), str(port.get('published')), int(port.get('target', 0)), port.get('protocol', 'tcp')) for port in ports}
    require(actual_ports == {('127.0.0.1', '18082', 80, 'tcp'), ('127.0.0.1', '18445', 443, 'tcp')}, 'Recovery ports must bind only to loopback')

    expected_binds = {
        str(PurePosixPath(snapshot_dir) / 'nginx.production.conf.template'): '/etc/nginx/templates/default.conf.template',
        str(PurePosixPath(snapshot_dir) / 'nginx-application.conf'): '/etc/nginx/snippets/application.conf',
        str(PurePosixPath(app_dir) / 'tls'): '/etc/nginx/tls',
    }
    binds = services['web'].get('volumes', [])
    require(len(binds) == len(expected_binds), 'Unexpected Web mounts')
    require(all(mount.get('type') == 'bind' and mount.get('read_only') for mount in binds), 'Web mounts must be read-only binds')
    require({mount.get('source'): mount.get('target') for mount in binds} == expected_binds, 'Unexpected Web bind sources')
    domain = services['web'].get('environment', {}).get('PUBLIC_HOST', '')
    require(isinstance(domain, str) and domain and all(char.isascii() and (char.isalnum() or char in '.-') for char in domain), 'Invalid certificate domain')
    return {'project': project, 'domain': domain, 'httpsPort': 18445,
            'images': {name: service['image'] for name, service in services.items()},
            'volumes': {name: volume['name'] for name, volume in volumes.items()}}


if __name__ == '__main__':
    try:
        result = validate_configuration(json.load(sys.stdin), *sys.argv[1:])
    except (ValueError, KeyError, TypeError) as error:
        print('Recovery isolation check failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
    print(json.dumps(result))
