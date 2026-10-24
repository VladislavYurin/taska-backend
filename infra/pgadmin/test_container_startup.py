"""Regression: a new container must start against an existing desktop/server volume."""
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
import urllib.request


@unittest.skipUnless(os.environ.get('PGADMIN_CONTAINER_TEST') == '1', 'Enable the Docker startup regression explicitly')
class PgadminContainerStartupTest(unittest.TestCase):
    def test_recreated_container_bootstraps_before_config_distro_exists(self):
        prefix = 'taska-pgadmin-test-' + str(os.getpid())
        network, volume = prefix + '-net', prefix + '-data'
        containers = []
        root = Path(__file__).resolve().parent

        def docker(*arguments, check=True):
            return subprocess.run(['docker', *arguments], check=check, text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        def start(name, *arguments):
            containers.append(name)
            docker('run', '-d', '--name', name, *arguments)
            port = docker('port', name, '80/tcp').stdout.strip()
            return 'http://' + port

        def ready(url):
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                try:
                    with urllib.request.urlopen(url, timeout=3) as response:
                        if response.status == 200:
                            return
                except Exception:
                    pass
                time.sleep(0.5)
            self.fail('pgAdmin did not become ready in a newly created container')

        try:
            docker('network', 'create', network)
            docker('volume', 'create', volume)
            database = prefix + '-postgres'
            containers.append(database)
            aliases = [value for service in ('auth', 'project', 'workflow', 'issue', 'notification', 'admin')
                       for value in ('--network-alias', service + '-db')]
            docker('run', '-d', '--name', database, '--network', network, *aliases,
                   '-e', 'POSTGRES_USER=taska', '-e', 'POSTGRES_PASSWORD=local-test-only',
                   '-e', 'POSTGRES_DB=auth_db', 'postgres:16')
            common = ['--network', network, '-p', '127.0.0.1::80', '-v', volume + ':/var/lib/pgadmin',
                      '-e', 'PGADMIN_DEFAULT_EMAIL=admin@example.com', '-e', 'PGADMIN_DEFAULT_PASSWORD=local-test-only',
                      '-e', 'PGADMIN_DISABLE_POSTFIX=1']
            desktop = prefix + '-desktop'
            ready(start(desktop, *common, '-e', 'PGADMIN_CONFIG_SERVER_MODE=False', 'dpage/pgadmin4:9.3'))
            for _ in range(60):
                if docker('exec', database, 'pg_isready', '-U', 'taska', check=False).returncode == 0:
                    break
                time.sleep(0.5)
            for service in ('project', 'workflow', 'issue', 'notification', 'admin'):
                docker('exec', database, 'createdb', '-U', 'taska', service + '_db')
            docker('stop', desktop)
            mounts = [value for source, target in (
                ('entrypoint.sh', '/taska-pgadmin/entrypoint.sh'),
                ('prepare.py', '/taska-pgadmin/prepare.py'),
                ('config_local.py', '/pgadmin4/config_local.py'))
                for value in ('-v', str(root / source) + ':' + target + ':ro')]
            for number in range(2):
                server = prefix + '-server-' + str(number)
                url = start(server, *common, *mounts, '-e', 'PGADMIN_CONFIG_SERVER_MODE=True',
                            '-e', 'PGADMIN_SERVER_JSON_FILE=/tmp/taska-pgadmin-servers.json',
                            '-e', 'POSTGRES_USER=taska', '-e', 'POSTGRES_PASSWORD=local-test-only',
                            '--entrypoint', '/bin/sh', 'dpage/pgadmin4:9.3', '/taska-pgadmin/entrypoint.sh')
                ready(url)
                env = {**os.environ, 'PGADMIN_CHECK_URL': url, 'PGADMIN_DEFAULT_EMAIL': 'admin@example.com',
                       'PGADMIN_DEFAULT_PASSWORD': 'local-test-only'}
                subprocess.run([sys.executable, str(root / 'smoke.py')], env=env, check=True)
                self.assertEqual(urllib.request.urlopen(url + '/misc/ping', timeout=3).status, 200)
                docker('rm', '-f', server)
        finally:
            for name in reversed(containers):
                docker('rm', '-f', name, check=False)
            docker('volume', 'rm', volume, check=False)
            docker('network', 'rm', network, check=False)
